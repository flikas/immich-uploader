package io.github.immichuploader.cli;

import io.github.immichuploader.Main;
import io.github.immichuploader.core.Checksum;
import io.github.immichuploader.core.MediaKind;
import io.github.immichuploader.core.MediaRole;
import io.github.immichuploader.state.RecordEntry;
import io.github.immichuploader.state.RecordStatus;
import io.github.immichuploader.state.RecordStore;
import io.github.immichuploader.util.ServerUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyCommandSmokeTest {
    @TempDir
    Path temp;

    @Test
    void verifiesRecordAndWritesHtmlReport() throws Exception {
        Path media = temp.resolve("photo.jpg");
        Files.write(media, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 'd', 'a', 't', 'a'});
        BasicFileAttributes attrs = Files.readAttributes(media, BasicFileAttributes.class);
        Path recordFile = temp.resolve("record.tsv");

        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String serverUrl = ServerUrl.normalize(server.url("/").toString());
            try (RecordStore store = RecordStore.open(recordFile, serverUrl)) {
                store.append(new RecordEntry(
                        System.currentTimeMillis(),
                        RecordStatus.UPLOADED,
                        MediaKind.IMAGE,
                        MediaRole.NORMAL,
                        "",
                        media.toRealPath(),
                        attrs.size(),
                        attrs.lastModifiedTime().toMillis(),
                        Checksum.sha1Base64(media),
                        "asset-1",
                        "",
                        serverUrl,
                        "uploaded",
                        ""
                ));
            }

            server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("{\"major\":1,\"minor\":136,\"patch\":0}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                    {"results":[{"id":"r0","action":"reject","reason":"duplicate","assetId":"asset-1"}]}
                    """));

            Path reports = temp.resolve("reports");
            int exit = Main.execute(new String[]{
                    "verify",
                    "--server", server.url("/").toString(),
                    "--api-key", "test-key",
                    "--record", recordFile.toString(),
                    "--report-dir", reports.toString(),
                    "--concurrency", "1"
            });
            assertEquals(0, exit);
            assertEquals(2, server.getRequestCount());

            try (var stream = Files.list(reports)) {
                Path report = stream.findFirst().orElseThrow();
                String html = Files.readString(report);
                assertTrue(html.contains("核对报告"));
                assertTrue(html.contains("匹配"));
                assertTrue(html.contains("photo.jpg"));
            }
        }
    }
}
