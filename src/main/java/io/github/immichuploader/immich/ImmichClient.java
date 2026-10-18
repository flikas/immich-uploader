package io.github.immichuploader.immich;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.immichuploader.util.ServerUrl;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Sink;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class ImmichClient implements ImmichApi, AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(ImmichClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_ERROR_BODY = 64 * 1024;

    private final String baseUrl;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxRetries;

    private volatile boolean legacyUpload;
    private volatile boolean legacyBulk;

    public ImmichClient(
            String baseUrl,
            String apiKey,
            int connectTimeoutSeconds,
            int requestTimeoutSeconds,
            int maxRetries,
            boolean insecure
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        this.baseUrl = ServerUrl.normalize(baseUrl);
        this.maxRetries = Math.max(0, maxRetries);

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)))
                .readTimeout(Duration.ofSeconds(Math.max(1, requestTimeoutSeconds)))
                .writeTimeout(Duration.ofSeconds(Math.max(1, requestTimeoutSeconds)))
                .retryOnConnectionFailure(true)
                .addInterceptor(chain -> {
                    Request request = chain.request().newBuilder()
                            .header("x-api-key", apiKey)
                            .header("Accept", "application/json")
                            .header("User-Agent", "immich-uploader/1.0")
                            .build();
                    return chain.proceed(request);
                });

        if (insecure) {
            configureInsecureTls(builder);
            LOG.warn("已启用 --insecure：TLS 证书校验被关闭，仅建议在测试环境使用");
        }
        this.http = builder.build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public ServerVersion serverVersion() {
        try {
            JsonNode version = getJson("/server/version");
            ServerVersion parsed = parseVersion(version);
            if (parsed != null) {
                return parsed;
            }
        } catch (RuntimeException e) {
            LOG.debug("无法读取 /server/version: {}", e.getMessage());
        }

        try {
            JsonNode about = getJson("/server/about");
            if (about != null) {
                ServerVersion parsed = parseVersion(about);
                if (parsed != null) {
                    return parsed;
                }
                JsonNode version = about.get("version");
                if (version != null && version.isTextual()) {
                    return new ServerVersion(null, null, null, version.asText());
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("无法读取 /server/about: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public List<BulkCheckResult> bulkUploadCheck(List<BulkCheckItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        ObjectNode root = mapper.createObjectNode();
        ArrayNode assets = root.putArray("assets");
        for (BulkCheckItem item : items) {
            assets.addObject()
                    .put("id", item.id())
                    .put("checksum", item.checksum());
        }
        String json;
        try {
            json = mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new ImmichApiException("无法生成 bulk-upload-check 请求", -1, "", e);
        }
        RequestBody body = RequestBody.create(json, JSON);

        while (true) {
            String endpoint = legacyBulk ? "/asset/bulk-upload-check" : "/assets/bulk-upload-check";
            Request request = new Request.Builder()
                    .url(url(endpoint))
                    .post(body)
                    .build();
            try (Response response = execute(request, "bulk-upload-check")) {
                String responseBody = bodyString(response);
                return parseBulkResults(responseBody, items);
            } catch (ImmichApiException e) {
                if (!legacyBulk && e.endpointMissing()) {
                    legacyBulk = true;
                    LOG.debug("bulk-upload-check 新接口不可用，回退到旧接口");
                    continue;
                }
                throw e;
            }
        }
    }

    @Override
    public AssetUploadResult uploadAsset(AssetUploadRequest asset) {
        while (true) {
            String endpoint = legacyUpload ? "/asset/upload" : "/assets";
            Request request = buildUploadRequest(endpoint, asset);
            try (Response response = execute(request, "upload")) {
                String responseBody = bodyString(response);
                JsonNode node = parseJson(responseBody, "upload");
                String id = text(node, "id");
                String status = text(node, "status");
                if (id.isBlank()) {
                    throw new ImmichApiException(
                            "Immich 上传响应缺少 asset id: " + truncate(responseBody, 500),
                            response.code(),
                            responseBody
                    );
                }
                return new AssetUploadResult(id, status);
            } catch (ImmichApiException e) {
                if (!legacyUpload && e.endpointMissing()) {
                    legacyUpload = true;
                    LOG.debug("上传新接口不可用，回退到旧接口");
                    continue;
                }
                throw e;
            }
        }
    }

    @Override
    public AssetInfo getAsset(String assetId) {
        if (assetId == null || assetId.isBlank()) {
            throw new ImmichApiException("assetId 不能为空", -1, "");
        }
        Request request = new Request.Builder()
                .url(url("/assets/" + assetId))
                .get()
                .build();
        try (Response response = execute(request, "get-asset")) {
            String body = bodyString(response);
            JsonNode node = parseJson(body, "get-asset");
            return new AssetInfo(
                    text(node, "id"),
                    text(node, "type"),
                    text(node, "livePhotoVideoId"),
                    node.path("isTrashed").asBoolean(false),
                    text(node, "visibility")
            );
        }
    }

    @Override
    public void linkLivePhoto(String imageAssetId, String videoAssetId) {
        if (imageAssetId == null || imageAssetId.isBlank() || videoAssetId == null || videoAssetId.isBlank()) {
            throw new ImmichApiException("Live Photo 关联 ID 不能为空", -1, "");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("livePhotoVideoId", videoAssetId);
        Request request = new Request.Builder()
                .url(url("/assets/" + imageAssetId))
                .put(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response ignored = execute(request, "link-live-photo")) {
            // Success is enough; the asset details are not needed here.
        }
    }

    private Request buildUploadRequest(String endpoint, AssetUploadRequest asset) {
        String filename = asset.filename();
        if (filename == null || filename.isBlank()) {
            filename = asset.file().getFileName().toString();
        }
        MediaType mediaType = mediaType(asset);
        RequestBody fileBody = asset.data() == null
                ? RequestBody.create(asset.file().toFile(), mediaType)
                : RequestBody.create(asset.data(), mediaType);
        if (asset.progressListener() != null) {
            fileBody = progressBody(fileBody, asset.progressListener());
        }

        MultipartBody.Builder multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("assetData", filename, fileBody)
                .addFormDataPart("deviceAssetId", asset.deviceAssetId())
                .addFormDataPart("deviceId", asset.deviceId())
                .addFormDataPart("fileCreatedAt", asset.createdAt().toString())
                .addFormDataPart("fileModifiedAt", asset.modifiedAt().toString())
                .addFormDataPart("filename", filename)
                .addFormDataPart("isFavorite", "false");

        if (!asset.livePhotoVideoId().isBlank()) {
            multipart.addFormDataPart("livePhotoVideoId", asset.livePhotoVideoId());
        }
        if (!asset.visibility().isBlank()) {
            multipart.addFormDataPart("visibility", asset.visibility());
        }

        return new Request.Builder()
                .url(url(endpoint))
                .header("x-immich-checksum", asset.checksumBase64())
                .post(multipart.build())
                .build();
    }

    private RequestBody progressBody(RequestBody delegate, UploadProgressListener listener) {
        long length = -1;
        try {
            length = delegate.contentLength();
        } catch (IOException ignored) {
            // Unknown length is fine; progress still advances in bytes.
        }
        final long total = length;
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return delegate.contentType();
            }

            @Override
            public long contentLength() {
                return total;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                ForwardingSink forwarding = new ForwardingSink(sink) {
                    private long written;

                    @Override
                    public void write(Buffer source, long byteCount) throws IOException {
                        super.write(source, byteCount);
                        written += byteCount;
                        try {
                            listener.onProgress(written, total);
                        } catch (RuntimeException ignored) {
                            // A progress consumer must never fail an upload.
                        }
                    }
                };
                BufferedSink progressSink = okio.Okio.buffer(forwarding);
                delegate.writeTo(progressSink);
                progressSink.flush();
            }
        };
    }

    private MediaType mediaType(AssetUploadRequest asset) {
        String mime = null;
        try {
            mime = Files.probeContentType(asset.file());
        } catch (IOException ignored) {
            // Fall through to the generic binary type.
        }
        MediaType parsed = mime == null ? null : MediaType.parse(mime);
        return parsed == null ? MediaType.get("application/octet-stream") : parsed;
    }

    private List<BulkCheckResult> parseBulkResults(String responseBody, List<BulkCheckItem> items) {
        JsonNode root = parseJson(responseBody, "bulk-upload-check");
        JsonNode results = root.get("results");
        if (results == null || !results.isArray()) {
            throw new ImmichApiException(
                    "bulk-upload-check 响应缺少 results: " + truncate(responseBody, 500),
                    200,
                    responseBody
            );
        }

        Map<String, BulkCheckResult> byId = new HashMap<>();
        for (JsonNode node : results) {
            String id = text(node, "id");
            if (id.isBlank()) {
                continue;
            }
            byId.put(id, new BulkCheckResult(
                    id,
                    text(node, "action"),
                    text(node, "reason"),
                    text(node, "assetId"),
                    node.path("isTrashed").asBoolean(false)
            ));
        }

        List<BulkCheckResult> ordered = new ArrayList<>(items.size());
        for (BulkCheckItem item : items) {
            BulkCheckResult result = byId.get(item.id());
            if (result == null) {
                throw new ImmichApiException(
                        "bulk-upload-check 响应缺少 id=" + item.id(),
                        200,
                        responseBody
                );
            }
            ordered.add(result);
        }
        return List.copyOf(ordered);
    }

    private JsonNode getJson(String path) {
        Request request = new Request.Builder()
                .url(url(path))
                .get()
                .build();
        try (Response response = execute(request, "GET " + path)) {
            String body = bodyString(response);
            if (body.isBlank()) {
                return null;
            }
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new ImmichApiException("无法读取 " + path + ": " + e.getMessage(), -1, "", e);
        }
    }

    private Response execute(Request request, String operation) {
        IOException lastIo = null;
        for (int attempt = 0; ; attempt++) {
            try {
                Response response = http.newCall(request).execute();
                if (response.isSuccessful()) {
                    return response;
                }

                int code = response.code();
                String body = errorBody(response);
                String retryAfter = response.header("Retry-After");
                response.close();

                if (code == 401) {
                    throw new AuthException(code);
                }
                if (code == 403) {
                    throw new ImmichApiException(
                            "Immich " + operation + " 权限不足 (HTTP 403)。请检查 API Key 权限。"
                                    + (body.isBlank() ? "" : ": " + truncate(body, 500)),
                            code,
                            body
                    );
                }
                if ((code == 429 || code >= 500) && attempt < maxRetries) {
                    sleepBackoff(attempt, retryAfter);
                    continue;
                }

                String message = "Immich " + operation + " 失败 (HTTP " + code + ")"
                        + (body.isBlank() ? "" : ": " + truncate(body, 1000));
                throw new ImmichApiException(message, code, body);
            } catch (IOException e) {
                lastIo = e;
                if (attempt < maxRetries) {
                    LOG.debug("{} 网络错误，准备重试: {}", operation, e.getMessage());
                    sleepBackoff(attempt, null);
                    continue;
                }
                throw new ImmichApiException(
                        "Immich " + operation + " 网络错误: " + e.getMessage(),
                        -1,
                        "",
                        lastIo
                );
            }
        }
    }

    private void sleepBackoff(int attempt, String retryAfter) {
        long backoff = Math.min(30_000L, 500L << Math.min(attempt, 6));
        backoff += ThreadLocalRandom.current().nextLong(0, 250);
        if (retryAfter != null && !retryAfter.isBlank()) {
            backoff = Math.max(backoff, parseRetryAfter(retryAfter));
        }
        try {
            TimeUnit.MILLISECONDS.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImmichApiException("操作已中断", -1, "", e);
        }
    }

    private long parseRetryAfter(String value) {
        try {
            return TimeUnit.SECONDS.toMillis(Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            try {
                ZonedDateTime date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
                return Math.max(0, date.toInstant().toEpochMilli() - System.currentTimeMillis());
            } catch (RuntimeException ignoredDate) {
                return 0;
            }
        }
    }

    private ServerVersion parseVersion(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode major = node.get("major");
        JsonNode minor = node.get("minor");
        JsonNode patch = node.get("patch");
        if (major != null && major.isNumber() && minor != null && minor.isNumber() && patch != null && patch.isNumber()) {
            return new ServerVersion(major.asInt(), minor.asInt(), patch.asInt(), null);
        }
        JsonNode version = node.get("version");
        if (version != null && version.isTextual()) {
            return new ServerVersion(null, null, null, version.asText());
        }
        return null;
    }

    private JsonNode parseJson(String body, String operation) {
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null || node.isNull()) {
                throw new ImmichApiException("Immich " + operation + " 返回空响应", 200, body);
            }
            return node;
        } catch (ImmichApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ImmichApiException(
                    "Immich " + operation + " 返回了无法解析的 JSON: " + truncate(body, 500),
                    200,
                    body,
                    e
            );
        }
    }

    private String bodyString(Response response) {
        try {
            ResponseBody body = response.body();
            return body == null ? "" : body.string();
        } catch (IOException e) {
            throw new ImmichApiException("读取 Immich 响应失败: " + e.getMessage(), -1, "", e);
        }
    }

    private String errorBody(Response response) {
        try {
            ResponseBody body = response.peekBody(MAX_ERROR_BODY);
            return body == null ? "" : body.string();
        } catch (IOException e) {
            return "";
        }
    }

    private HttpUrl url(String path) {
        return HttpUrl.get(baseUrl + path);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }

    private static void configureInsecureTls(OkHttpClient.Builder builder) {
        try {
            X509TrustManager trustManager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trustManager}, new SecureRandom());
            builder.sslSocketFactory(context.getSocketFactory(), trustManager);
            builder.hostnameVerifier((hostname, session) -> true);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法初始化 --insecure TLS 上下文", e);
        }
    }

    @Override
    public void close() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
