package io.github.immichuploader.cli;

import io.github.immichuploader.Main;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadCommandSmokeTest {
    @TempDir
    Path temp;

    @Test
    void uploadsAndWritesRecordAndHtmlReportThenResumes() throws Exception {
        Path mediaDir = temp.resolve("media");
        Files.createDirectories(mediaDir);
        Path media = mediaDir.resolve("photo.jpg");
        Files.write(media, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 'd', 'a', 't', 'a'});
        Path state = temp.resolve("state");

        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(version());
            server.enqueue(bulk("0", "accept", ""));
            server.enqueue(new MockResponse().setResponseCode(201)
                    .setBody("{\"id\":\"asset-1\",\"status\":\"created\"}"));
            server.enqueue(bulk("r0", "reject", "asset-1"));

            int exit = Main.execute(new String[]{
                    "upload",
                    "--server", server.url("/").toString(),
                    "--api-key", "test-key",
                    "--dir", media.getParent().toString(),
                    "--state-dir", state.toString(),
                    "--sender-threads", "1",
                    "--batch-size", "10",
                    "--no-verify-links"
            });
            assertEquals(0, exit);
            assertEquals(4, server.getRequestCount());

            Path record = state.resolve("upload-record.tsv");
            assertTrue(Files.exists(record));
            String recordText = Files.readString(record);
            assertTrue(recordText.contains("asset-1"));
            assertTrue(recordText.contains("UPLOADED"));

            List<Path> reports;
            try (var stream = Files.list(state.resolve("reports"))) {
                reports = stream.toList();
            }
            assertEquals(1, reports.size());
            String html = Files.readString(reports.get(0));
            assertTrue(html.contains("成功清单"));
            assertTrue(html.contains("photo.jpg"));
            assertTrue(html.contains("核对报告"));
            assertTrue(html.contains("匹配"));

            server.enqueue(version());
            int exitAgain = Main.execute(new String[]{
                    "upload",
                    "--server", server.url("/").toString(),
                    "--api-key", "test-key",
                    "--dir", media.getParent().toString(),
                    "--state-dir", state.toString(),
                    "--sender-threads", "1",
                    "--batch-size", "10"
            });
            assertEquals(0, exitAgain);
            assertEquals(5, server.getRequestCount());
        }
    }

    private static MockResponse version() {
        return new MockResponse().setResponseCode(200)
                .setBody("{\"major\":1,\"minor\":136,\"patch\":0}");
    }

    private static MockResponse bulk(String id, String action, String assetId) {
        String result = "{\"id\":\"" + id + "\",\"action\":\"" + action + "\""
                + (assetId.isBlank() ? "" : ",\"reason\":\"duplicate\",\"assetId\":\"" + assetId + "\"")
                + "}";
        return new MockResponse().setResponseCode(200)
                .setBody("{\"results\":[" + result + "]}");
    }
}
