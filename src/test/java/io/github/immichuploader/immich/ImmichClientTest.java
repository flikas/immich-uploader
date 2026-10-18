package io.github.immichuploader.immich;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmichClientTest {
    @TempDir
    Path temp;

    private MockWebServer server;
    private ImmichClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new ImmichClient(server.url("/").toString(), "secret-key", 5, 10, 1, false);
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        server.shutdown();
    }

    @Test
    void bulkCheckParsesResultsAndSendsChecksums() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {"results":[
                  {"id":"0","action":"reject","reason":"duplicate","assetId":"asset-0","isTrashed":false},
                  {"id":"1","action":"accept"}
                ]}
                """));

        List<BulkCheckResult> results = client.bulkUploadCheck(List.of(
                new BulkCheckItem("0", "checksum-0"),
                new BulkCheckItem("1", "checksum-1")
        ));

        assertEquals(2, results.size());
        assertTrue(results.get(0).duplicate());
        assertEquals("asset-0", results.get(0).assetId());
        assertTrue(results.get(1).accepted());

        RecordedRequest request = server.takeRequest();
        assertEquals("/api/assets/bulk-upload-check", request.getPath());
        assertEquals("secret-key", request.getHeader("x-api-key"));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"checksum\":\"checksum-0\""));
    }

    @Test
    void uploadSendsLivePhotoFieldsAndChecksumHeader() throws Exception {
        Path file = temp.resolve("photo.jpg");
        Files.write(file, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 'd', 'a', 't', 'a'});
        server.enqueue(new MockResponse().setResponseCode(201)
                .setBody("{\"id\":\"asset-1\",\"status\":\"created\"}"));

        AssetUploadResult result = client.uploadAsset(new AssetUploadRequest(
                file,
                "photo.jpg",
                "sha1-base64",
                "device-asset-1",
                "device-1",
                Instant.parse("2024-01-02T03:04:05Z"),
                Instant.parse("2024-01-02T03:04:06Z"),
                "video-1",
                "hidden"
        ));

        assertEquals("asset-1", result.assetId());
        assertEquals("created", result.status());

        RecordedRequest request = server.takeRequest();
        assertEquals("/api/assets", request.getPath());
        assertEquals("sha1-base64", request.getHeader("x-immich-checksum"));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("name=\"assetData\""));
        assertTrue(body.contains("name=\"deviceAssetId\""));
        assertTrue(body.contains("name=\"livePhotoVideoId\""));
        assertTrue(body.contains("video-1"));
        assertTrue(body.contains("name=\"visibility\""));
        assertTrue(body.contains("hidden"));
    }

    @Test
    void reportsUploadProgressBytes() throws Exception {
        Path file = temp.resolve("photo.jpg");
        byte[] data = new byte[32 * 1024];
        Files.write(file, data);
        server.enqueue(new MockResponse().setResponseCode(201)
                .setBody("{\"id\":\"asset-progress\",\"status\":\"created\"}"));

        java.util.concurrent.atomic.AtomicLong written = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong total = new java.util.concurrent.atomic.AtomicLong();
        client.uploadAsset(new AssetUploadRequest(
                file, "photo.jpg", "sha1", "device-asset", "device",
                Instant.now(), Instant.now(), "", "", null,
                (bytes, all) -> {
                    written.set(bytes);
                    total.set(all);
                }));

        assertTrue(written.get() > 0);
        assertEquals(data.length, total.get());
    }

    @Test
    void readsAssetAndLinksLivePhoto() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {"id":"image-1","type":"IMAGE","livePhotoVideoId":"video-1",
                 "isTrashed":false,"visibility":"timeline"}
                """));
        AssetInfo info = client.getAsset("image-1");
        assertEquals("image-1", info.id());
        assertEquals("IMAGE", info.type());
        assertEquals("video-1", info.livePhotoVideoId());

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        client.linkLivePhoto("image-1", "video-2");

        RecordedRequest get = server.takeRequest();
        assertEquals("/api/assets/image-1", get.getPath());
        assertEquals("GET", get.getMethod());

        RecordedRequest put = server.takeRequest();
        assertEquals("/api/assets/image-1", put.getPath());
        assertEquals("PUT", put.getMethod());
        assertTrue(put.getBody().readUtf8().contains("\"livePhotoVideoId\":\"video-2\""));
    }

    @Test
    void mapsUnauthorizedToAuthException() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"message\":\"unauthorized\"}"));
        assertThrows(AuthException.class,
                () -> client.bulkUploadCheck(List.of(new BulkCheckItem("0", "x"))));
    }

    @Test
    void mapsForbiddenToPermissionError() {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"message\":\"forbidden\"}"));
        ImmichApiException error = assertThrows(ImmichApiException.class,
                () -> client.bulkUploadCheck(List.of(new BulkCheckItem("0", "x"))));
        assertEquals(403, error.statusCode());
    }

    @Test
    void retriesTooManyRequests() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "0"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"results\":[{\"id\":\"0\",\"action\":\"accept\"}]}"));

        List<BulkCheckResult> results = client.bulkUploadCheck(List.of(new BulkCheckItem("0", "x")));
        assertEquals(1, results.size());
        assertTrue(results.get(0).accepted());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void fallsBackToLegacyUploadEndpoint() throws Exception {
        Path file = temp.resolve("photo.jpg");
        Files.write(file, new byte[]{1, 2, 3});
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"message\":\"not found\"}"));
        server.enqueue(new MockResponse().setResponseCode(201)
                .setBody("{\"id\":\"asset-legacy\",\"status\":\"created\"}"));

        AssetUploadResult result = client.uploadAsset(new AssetUploadRequest(
                file, "photo.jpg", "sha1", "device-asset", "device",
                Instant.now(), Instant.now(), "", ""
        ));

        assertEquals("asset-legacy", result.assetId());
        assertEquals("/api/assets", server.takeRequest().getPath());
        assertEquals("/api/asset/upload", server.takeRequest().getPath());
    }
}
