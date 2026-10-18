package io.github.immichuploader.report;

import io.github.immichuploader.core.MediaExtensions;
import io.github.immichuploader.core.MediaKind;
import io.github.immichuploader.core.MediaRole;
import io.github.immichuploader.core.RunSummary;
import io.github.immichuploader.core.SkippedFile;
import io.github.immichuploader.core.VerificationSummary;
import io.github.immichuploader.state.RecordEntry;
import io.github.immichuploader.state.RecordStatus;
import io.github.immichuploader.util.Formats;
import io.github.immichuploader.util.Html;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HtmlReportWriter {
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private HtmlReportWriter() {
    }

    public static Path write(
            Path reportDir,
            ReportContext context,
            RunSummary summary,
            VerificationSummary verification,
            boolean verificationOnly
    ) throws IOException {
        Files.createDirectories(reportDir);
        Path file = uniqueFile(reportDir);
        Files.writeString(file, render(context, summary, verification, verificationOnly), StandardCharsets.UTF_8);
        return file;
    }

    private static Path uniqueFile(Path reportDir) {
        String stamp = FILE_STAMP.format(LocalDateTime.now());
        Path file = reportDir.resolve("report-" + stamp + ".html");
        int suffix = 2;
        while (Files.exists(file)) {
            file = reportDir.resolve("report-" + stamp + "-" + suffix + ".html");
            suffix++;
        }
        return file;
    }

    private static String render(
            ReportContext context,
            RunSummary summary,
            VerificationSummary verification,
            boolean verificationOnly
    ) {
        int scanned = summary == null ? 0 : summary.scanned();
        int images = summary == null ? 0 : summary.imageCount();
        int videos = summary == null ? 0 : summary.videoCount();
        int uploaded = summary == null ? 0 : summary.uploaded();
        int duplicates = summary == null ? 0 : summary.duplicates();
        int failed = summary == null ? 0 : summary.failed();
        int resumeSkipped = summary == null ? 0 : summary.resumeSkipped();
        int skippedFiles = summary == null ? 0 : summary.skipped();
        int pairs = summary == null ? 0 : summary.pairsDetected();
        int linked = summary == null ? 0 : summary.pairsLinked();
        int alreadyLinked = summary == null ? 0 : summary.pairsAlreadyLinked();
        int conflicts = summary == null ? 0 : summary.pairsConflict();
        long bytes = summary == null ? 0 : summary.bytesUploaded();
        long duration = summary == null ? 0 : summary.durationMillis();

        StringBuilder h = new StringBuilder(64 * 1024);
        h.append("""
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Immich 批量上传报告</title>
                <style>
                :root{--bg:#f5f7fb;--panel:#fff;--text:#172033;--muted:#667085;--line:#e3e8f0;--brand:#2563eb;--ok:#067647;--warn:#b54708;--bad:#b42318;--chip:#eef4ff}
                *{box-sizing:border-box}
                body{margin:0;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif;font-size:14px;line-height:1.55}
                .wrap{max-width:1500px;margin:0 auto;padding:28px 22px 48px}
                h1{font-size:24px;margin:0 0 4px}
                h2{font-size:18px;margin:24px 0 10px}
                h3{font-size:15px;margin:18px 0 8px}
                .sub{color:var(--muted);margin-bottom:20px}
                .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px}
                .card{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:12px 14px}
                .card .k{font-size:12px;color:var(--muted)}
                .card .v{font-size:22px;font-weight:700;margin-top:2px}
                .card .v.bad{color:var(--bad)} .card .v.warn{color:var(--warn)} .card .v.ok{color:var(--ok)}
                .meta{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:14px 16px;margin-top:14px}
                .meta dl{display:grid;grid-template-columns:180px 1fr;gap:6px 16px;margin:0}
                .meta dt{color:var(--muted)} .meta dd{margin:0;word-break:break-all}
                .tabs{display:flex;gap:6px;flex-wrap:wrap;margin:24px 0 12px;border-bottom:1px solid var(--line);padding-bottom:10px}
                .tab{border:1px solid var(--line);background:#fff;color:var(--text);border-radius:8px;padding:8px 13px;cursor:pointer;font-size:14px}
                .tab.active{background:var(--brand);border-color:var(--brand);color:#fff}
                .badge{display:inline-block;background:var(--chip);color:var(--brand);border-radius:999px;padding:0 7px;margin-left:5px;font-size:12px}
                .tab.active .badge{background:rgba(255,255,255,.22);color:#fff}
                .panel{display:none;background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:14px}
                .panel.active{display:block}
                .filters{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:10px}
                .filters input,.filters select{height:34px;border:1px solid var(--line);border-radius:7px;padding:0 10px;background:#fff;color:var(--text);min-width:150px}
                .filters input[type=search]{min-width:300px;flex:1}
                .filters button{height:34px;border:1px solid var(--line);border-radius:7px;background:#fff;color:var(--text);padding:0 12px;cursor:pointer}
                .count{color:var(--muted);font-size:12px;margin-left:auto}
                table{width:100%;border-collapse:collapse;table-layout:auto}
                th,td{text-align:left;vertical-align:top;padding:8px 9px;border-bottom:1px solid var(--line)}
                th{font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:.03em;background:#fafbff;position:sticky;top:0}
                tr:hover td{background:#f8faff}
                .path{max-width:440px;word-break:break-all;font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px}
                .hash{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:11px;word-break:break-all;max-width:220px}
                .muted{color:var(--muted)} .nowrap{white-space:nowrap}
                .pill{display:inline-block;border-radius:999px;padding:1px 8px;font-size:12px;white-space:nowrap}
                .pill.ok{background:#ecfdf3;color:var(--ok)} .pill.warn{background:#fffaeb;color:var(--warn)} .pill.bad{background:#fef3f2;color:var(--bad)} .pill.neutral{background:#f2f4f7;color:#344054}
                .empty{padding:22px;color:var(--muted);text-align:center}
                .note{color:var(--muted);font-size:12px;margin:10px 0}
                ul.warnings{margin:8px 0 0 18px;padding:0} ul.warnings li{margin:3px 0;color:var(--warn)}
                footer{color:var(--muted);font-size:12px;margin-top:22px}
                @media print{body{background:#fff}.tabs,.filters{display:none}.panel{display:block!important;page-break-inside:auto} .wrap{max-width:none;padding:0} .tab{display:none}}
                </style>
                </head>
                <body>
                <div class="wrap">
                """);

        h.append("<h1>Immich 批量上传报告</h1>");
        h.append("<div class=\"sub\">生成时间 ")
                .append(Html.escape(DISPLAY_TIME.format(LocalDateTime.now())))
                .append(" · 工具版本 ").append(Html.escape(context.toolVersion()))
                .append("</div>");

        h.append("<div class=\"cards\">");
        card(h, "遍历文件", Integer.toString(summary == null ? 0 : summary.visitedFiles()), "");
        card(h, "扫描媒体", Integer.toString(scanned), "");
        card(h, "图片", Integer.toString(images), "");
        card(h, "视频", Integer.toString(videos), "");
        card(h, "断点跳过", Integer.toString(resumeSkipped), "");
        card(h, "格式/扩展名跳过", Integer.toString(skippedFiles), skippedFiles > 0 ? "warn" : "");
        card(h, "上传成功", Integer.toString(uploaded), "ok");
        card(h, "服务端已存在", Integer.toString(duplicates), "ok");
        card(h, "失败", Integer.toString(failed), failed > 0 ? "bad" : "");
        card(h, "Live Photo 对", Integer.toString(pairs), "");
        card(h, "已关联", Integer.toString(linked + alreadyLinked + (summary == null ? 0 : summary.pairsSkipped())), "ok");
        card(h, "关联冲突", Integer.toString(conflicts), conflicts > 0 ? "bad" : "");
        card(h, "上传字节", Formats.bytes(bytes), "");
        h.append("</div>");

        h.append("<div class=\"meta\"><dl>");
        meta(h, "服务器", context.server());
        meta(h, "服务器版本", context.serverVersion().isBlank() ? "未知" : context.serverVersion());
        meta(h, "记录文件", context.recordFile() == null ? "" : context.recordFile().toString());
        meta(h, "设备 ID", context.deviceId());
        meta(h, "递归扫描", context.recursive() ? "是" : "否");
        meta(h, "全文件模式", context.allFiles() ? "是" : "否");
        meta(h, "文件头识别", context.magicDetection() ? "启用" : "关闭");
        meta(h, "Live Photo 模式", context.livePhotoMode().name());
        meta(h, "上传后核对", context.verify() ? "是" : "否");
        meta(h, "关联核对", context.verifyLinks() ? "是" : "否");
        meta(h, "耗时", Formats.duration(duration));
        meta(h, "扩展名", context.extensions().isEmpty() ? "(默认)"
                : context.extensions().stream().sorted().collect(java.util.stream.Collectors.joining(", ")));
        h.append("</dl>");
        if (!context.directories().isEmpty()) {
            h.append("<div class=\"note\"><strong>扫描目录</strong><br>");
            for (Path directory : context.directories()) {
                h.append("<span class=\"path\">").append(Html.escape(directory.toString())).append("</span><br>");
            }
            h.append("</div>");
        }
        if (summary != null && !summary.warnings().isEmpty()) {
            h.append("<div class=\"note\"><strong>警告</strong><ul class=\"warnings\">");
            for (String warning : summary.warnings()) {
                h.append("<li>").append(Html.escape(warning)).append("</li>");
            }
            h.append("</ul></div>");
        }
        if (summary != null && summary.fatal) {
            h.append("<p class=\"pill bad\">致命错误: ")
                    .append(Html.escape(summary.fatalMessage == null ? "" : summary.fatalMessage))
                    .append("</p>");
        }
        h.append("</div>");

        List<RecordEntry> successes = summary == null ? List.of() : summary.successes();
        List<RunSummary.Failure> failures = summary == null ? List.of() : summary.failures();
        List<SkippedFile> skippedItems = summary == null ? List.of() : summary.skippedFiles();
        List<VerificationSummary.Item> verificationItems = verification == null ? List.of() : verification.items();

        h.append("<nav class=\"tabs\">");
        tab(h, "overview", "概览", false, verificationOnly ? "" : "active");
        tab(h, "success", "成功清单", successes.size(), verificationOnly ? "" : "");
        tab(h, "failure", "失败清单", failures.size(), "");
        tab(h, "skipped", "跳过清单", skippedItems.size(), "");
        tab(h, "verify", "核对报告", verificationItems.size(), verificationOnly ? "active" : "");
        h.append("</nav>");

        h.append("<section id=\"tab-overview\" class=\"panel ").append(verificationOnly ? "" : "active").append("\">");
        h.append("<h2>运行概览</h2>");
        h.append("<p class=\"note\">成功清单包含新上传和服务端已存在的资产；失败清单包含扫描、哈希、预检、上传和 Live Photo 关联错误；跳过清单包含扩展名不匹配、媒体过滤、无法识别以及 Immich 不支持的格式。</p>");
        if (summary != null) {
            h.append("<div class=\"cards\">");
            card(h, "哈希文件", Integer.toString(summary.hashed()), "");
            card(h, "预检已存在", Integer.toString(summary.precheckDuplicates()), "");
            card(h, "预检错误", Integer.toString(summary.precheckErrors()), summary.precheckErrors() > 0 ? "warn" : "");
            card(h, "Live Photo 跳过", Integer.toString(summary.pairsSkipped()), "");
            card(h, "补链成功", Integer.toString(summary.pairsLinked()), "ok");
            card(h, "原本已关联", Integer.toString(summary.pairsAlreadyLinked()), "ok");
            h.append("</div>");
        }
        h.append("</section>");

        h.append("<section id=\"tab-success\" class=\"panel\">");
        h.append("<div class=\"filters\">");
        filter(h, "text", "success-text", "搜索路径、SHA1、Asset ID、说明...");
        selectKind(h, "全部类型", new String[][]{{"IMAGE", "图片"}, {"VIDEO", "视频"}, {"OTHER", "其他"}});
        selectStatus(h, "全部状态", new String[][]{{"UPLOADED", "上传成功"}, {"DUPLICATE", "服务端已存在"}});
        h.append("<button type=\"button\" data-clear>清除筛选</button>");
        h.append("<span class=\"count\" data-visible-count></span></div>");
        h.append("<table data-filter-table><thead><tr>")
                .append("<th>文件</th><th>路径</th><th>类型</th><th>状态</th><th>Live Photo</th><th>配对状态</th>")
                .append("<th>大小</th><th>SHA1</th><th>Asset ID</th><th>关联视频</th><th>时间</th><th>说明</th>")
                .append("</tr></thead><tbody>");
        for (RecordEntry entry : successes) {
            successRow(h, entry);
        }
        h.append("</tbody></table>");
        if (successes.isEmpty()) {
            h.append("<div class=\"empty\">本次运行没有成功记录。</div>");
        }
        h.append("</section>");

        h.append("<section id=\"tab-failure\" class=\"panel\">");
        h.append("<div class=\"filters\">");
        filter(h, "text", "failure-text", "搜索路径或错误信息...");
        selectKind(h, "全部类型", new String[][]{{"IMAGE", "图片"}, {"VIDEO", "视频"}, {"OTHER", "其他"}});
        selectStatus(h, "全部阶段", new String[][]{
                {"SCAN", "扫描"}, {"HASH", "哈希"}, {"CHECK", "预检"},
                {"UPLOAD", "上传"}, {"LIVE_PHOTO", "Live Photo"}, {"RECORD", "记录"}, {"VERIFY", "核对"}
        });
        h.append("<button type=\"button\" data-clear>清除筛选</button>");
        h.append("<span class=\"count\" data-visible-count></span></div>");
        h.append("<table data-filter-table><thead><tr><th>路径</th><th>类型</th><th>阶段</th><th>错误</th></tr></thead><tbody>");
        for (RunSummary.Failure failure : failures) {
            failureRow(h, failure);
        }
        h.append("</tbody></table>");
        if (failures.isEmpty()) {
            h.append("<div class=\"empty\">没有失败记录。</div>");
        }
        h.append("</section>");

        h.append("<section id=\"tab-skipped\" class=\"panel\">");
        h.append("<div class=\"filters\">");
        filter(h, "text", "skipped-text", "搜索路径、扩展名或跳过原因...");
        selectStatus(h, "全部阶段", new String[][]{
                {"SCAN", "扫描/扩展名"}, {"CHECK", "预检/格式"}, {"UPLOAD", "上传/格式"}, {"PAIR", "Live Photo 配对"}
        });
        h.append("<button type=\"button\" data-clear>清除筛选</button>");
        h.append("<span class=\"count\" data-visible-count></span></div>");
        h.append("<table data-filter-table><thead><tr><th>路径</th><th>扩展名</th><th>阶段</th><th>跳过原因</th></tr></thead><tbody>");
        for (SkippedFile skipped : skippedItems) {
            skippedRow(h, skipped);
        }
        h.append("</tbody></table>");
        if (skippedItems.isEmpty()) {
            h.append("<div class=\"empty\">没有跳过记录。</div>");
        }
        h.append("</section>");

        h.append("<section id=\"tab-verify\" class=\"panel ").append(verificationOnly ? "active" : "").append("\">");
        if (verification == null) {
            h.append("<div class=\"empty\">本次未执行核对。可使用 <code>verify</code> 子命令，或去掉 <code>--no-verify</code>。</div>");
        } else {
            h.append("<div class=\"cards\">");
            card(h, "核对总数", Integer.toString(verification.total), "");
            card(h, "匹配", Integer.toString(verification.matched), "ok");
            card(h, "回收站", Integer.toString(verification.trashed), "warn");
            card(h, "缺失", Integer.toString(verification.missing), verification.missing > 0 ? "bad" : "");
            card(h, "本地已变化", Integer.toString(verification.localChangedServerOld
                    + verification.localChangedNewExists
                    + verification.localChangedMissing), verification.localChangedMissing > 0 ? "bad" : "warn");
            card(h, "文件丢失", Integer.toString(verification.fileMissing), verification.fileMissing > 0 ? "bad" : "");
            card(h, "哈希错误", Integer.toString(verification.hashErrors), verification.hashErrors > 0 ? "bad" : "");
            card(h, "API 错误", Integer.toString(verification.apiErrors), verification.apiErrors > 0 ? "bad" : "");
            card(h, "关联缺失", Integer.toString(verification.linkMissing), verification.linkMissing > 0 ? "bad" : "");
            card(h, "关联不一致", Integer.toString(verification.linkMismatch), verification.linkMismatch > 0 ? "bad" : "");
            h.append("</div>");
            h.append("<p class=\"note\">SHA1 使用 Immich bulk-upload-check 比对；Live Photo 图片额外检查服务端 livePhotoVideoId。</p>");
            h.append("<div class=\"filters\">");
            filter(h, "text", "verify-text", "搜索路径、SHA1 或说明...");
            selectKind(h, "全部类型", new String[][]{{"IMAGE", "图片"}, {"VIDEO", "视频"}, {"OTHER", "其他"}});
            selectStatus(h, "全部结果", new String[][]{
                    {"MATCHED", "匹配"}, {"MATCHED_DIFFERENT_ASSET", "匹配但 assetId 不同"},
                    {"TRASHED", "回收站"}, {"MISSING", "缺失"},
                    {"LOCAL_CHANGED_SERVER_OLD", "本地变化/服务端有旧版"},
                    {"LOCAL_CHANGED_NEW_EXISTS", "本地变化/服务端有新版本"},
                    {"LOCAL_CHANGED_MISSING", "本地变化/服务端均无"},
                    {"LINK_MISSING", "关联缺失"}, {"LINK_MISMATCH", "关联不一致"},
                    {"VIDEO_MISSING", "配对视频缺失"},
                    {"FILE_MISSING", "文件丢失"}, {"HASH_ERROR", "哈希错误"}, {"API_ERROR", "API 错误"}
            });
            h.append("<button type=\"button\" data-clear>清除筛选</button>");
            h.append("<span class=\"count\" data-visible-count></span></div>");
            h.append("<table data-filter-table><thead><tr><th>路径</th><th>类型</th><th>结果</th><th>记录 SHA1</th><th>实际 SHA1</th><th>Asset ID</th><th>说明</th></tr></thead><tbody>");
            for (VerificationSummary.Item item : verificationItems) {
                verificationRow(h, item);
            }
            h.append("</tbody></table>");
            if (verificationItems.isEmpty()) {
                h.append("<div class=\"empty\">没有可核对的记录。</div>");
            }
        }
        h.append("</section>");

        h.append("<footer>Immich Batch Uploader · 报告为静态 HTML，可离线保存和筛选。</footer>");
        h.append("</div>");
        h.append("<script>");
        h.append("""
                (function(){
                  const tabs=[...document.querySelectorAll('.tab')];
                  const panels=[...document.querySelectorAll('.panel')];
                  function activate(name){
                    tabs.forEach(t=>t.classList.toggle('active',t.dataset.tab===name));
                    panels.forEach(p=>p.classList.toggle('active',p.id==='tab-'+name));
                  }
                  tabs.forEach(t=>t.addEventListener('click',()=>activate(t.dataset.tab)));
                  document.querySelectorAll('.panel').forEach(panel=>{
                    const table=panel.querySelector('table[data-filter-table]');
                    if(!table) return;
                    const text=panel.querySelector('[data-filter-text]');
                    const kind=panel.querySelector('[data-filter-kind]');
                    const status=panel.querySelector('[data-filter-status]');
                    const count=panel.querySelector('[data-visible-count]');
                    const rows=[...table.querySelectorAll('tbody tr')];
                    function apply(){
                      const q=(text&&text.value||'').trim().toLowerCase();
                      const k=kind&&kind.value||'';
                      const s=status&&status.value||'';
                      let visible=0;
                      rows.forEach(row=>{
                        const hay=(row.dataset.search||'').toLowerCase();
                        const ok=(!q||hay.includes(q))&&(!k||row.dataset.kind===k)&&(!s||row.dataset.status===s);
                        row.hidden=!ok;
                        if(ok) visible++;
                      });
                      if(count) count.textContent='显示 '+visible+' / '+rows.length;
                    }
                    [text,kind,status].forEach(el=>{ if(el){ el.addEventListener('input',apply); el.addEventListener('change',apply); }});
                    const clear=panel.querySelector('[data-clear]');
                    if(clear) clear.addEventListener('click',()=>{ if(text) text.value=''; if(kind) kind.value=''; if(status) status.value=''; apply(); });
                    apply();
                  });
                })();
                """);
        h.append("</script></body></html>");
        return h.toString();
    }

    private static void card(StringBuilder h, String key, String value, String style) {
        h.append("<div class=\"card\"><div class=\"k\">").append(Html.escape(key))
                .append("</div><div class=\"v ").append(style).append("\">")
                .append(Html.escape(value)).append("</div></div>");
    }

    private static void meta(StringBuilder h, String key, String value) {
        h.append("<dt>").append(Html.escape(key)).append("</dt><dd>")
                .append(Html.escape(value == null ? "" : value)).append("</dd>");
    }

    private static void tab(StringBuilder h, String id, String label, int count, String extraClass) {
        h.append("<button type=\"button\" class=\"tab ").append(extraClass)
                .append("\" data-tab=\"").append(id).append("\">")
                .append(Html.escape(label));
        if (count > 0) {
            h.append("<span class=\"badge\">").append(count).append("</span>");
        }
        h.append("</button>");
    }

    private static void tab(StringBuilder h, String id, String label, boolean ignored, String extraClass) {
        tab(h, id, label, -1, extraClass);
    }

    private static void filter(StringBuilder h, String type, String name, String placeholder) {
        h.append("<input type=\"").append(type).append("\" data-filter-text placeholder=\"")
                .append(Html.attr(placeholder)).append("\">");
    }

    private static void selectKind(StringBuilder h, String allLabel, String[][] options) {
        select(h, "data-filter-kind", allLabel, options);
    }

    private static void selectStatus(StringBuilder h, String allLabel, String[][] options) {
        select(h, "data-filter-status", allLabel, options);
    }

    private static void select(StringBuilder h, String attribute, String allLabel, String[][] options) {
        h.append("<select ").append(attribute).append("><option value=\"\">")
                .append(Html.escape(allLabel)).append("</option>");
        for (String[] option : options) {
            h.append("<option value=\"").append(Html.attr(option[0])).append("\">")
                    .append(Html.escape(option[1])).append("</option>");
        }
        h.append("</select>");
    }

    private static void successRow(StringBuilder h, RecordEntry entry) {
        String kind = entry.kind().name();
        String status = entry.status().name();
        String pairState = switch (entry.role()) {
            case LIVE_IMAGE -> entry.livePhotoVideoId().isBlank() ? "待补链" : "已关联";
            case LIVE_VIDEO -> "配对视频";
            case NORMAL -> "";
        };
        String search = entry.path() + " " + entry.assetId() + " " + entry.sha1Base64() + " "
                + entry.livePhotoVideoId() + " " + entry.message() + " " + entry.action();
        h.append("<tr data-kind=\"").append(kind).append("\" data-status=\"").append(status)
                .append("\" data-search=\"").append(Html.lowerAttr(search)).append("\">");
        h.append("<td>").append(Html.escape(entry.path().getFileName().toString())).append("</td>");
        h.append("<td class=\"path\">").append(Html.escape(entry.path().toString())).append("</td>");
        h.append("<td>").append(Html.escape(entry.kind().label())).append("</td>");
        h.append("<td>").append(pill(entry.status() == RecordStatus.UPLOADED ? "上传成功" : "服务端已存在",
                entry.status() == RecordStatus.UPLOADED ? "ok" : "neutral")).append("</td>");
        h.append("<td>").append(Html.escape(entry.role().label())).append("</td>");
        h.append("<td>").append(Html.escape(pairState)).append("</td>");
        h.append("<td class=\"nowrap\">").append(Html.escape(Formats.bytes(entry.size()))).append("</td>");
        h.append("<td class=\"hash\">").append(Html.escape(entry.sha1Base64())).append("</td>");
        h.append("<td class=\"hash\">").append(Html.escape(entry.assetId())).append("</td>");
        h.append("<td class=\"hash\">").append(Html.escape(entry.livePhotoVideoId())).append("</td>");
        h.append("<td class=\"nowrap\">").append(Html.escape(Formats.dateTime(entry.uploadedAtMillis()))).append("</td>");
        h.append("<td>").append(Html.escape(entry.message())).append("</td>");
        h.append("</tr>");
    }

    private static void failureRow(StringBuilder h, RunSummary.Failure failure) {
        String path = failure.path() == null ? "" : failure.path().toString();
        MediaKind kind = MediaKind.OTHER;
        if (failure.path() != null) {
            MediaKind detected = MediaExtensions.kindOf(
                    MediaExtensions.extensionOf(failure.path().getFileName().toString()));
            if (detected != null) {
                kind = detected;
            }
        }
        String search = path + " " + failure.stage() + " " + failure.message();
        h.append("<tr data-kind=\"").append(kind.name()).append("\" data-status=\"")
                .append(Html.attr(failure.stage())).append("\" data-search=\"")
                .append(Html.lowerAttr(search)).append("\">");
        h.append("<td class=\"path\">").append(Html.escape(path)).append("</td>");
        h.append("<td>").append(Html.escape(kind.label())).append("</td>");
        h.append("<td>").append(pill(failure.stage(), "bad")).append("</td>");
        h.append("<td>").append(Html.escape(failure.message())).append("</td>");
        h.append("</tr>");
    }

    private static void skippedRow(StringBuilder h, SkippedFile skipped) {
        String path = skipped.path() == null ? "" : skipped.path().toString();
        String search = path + " " + skipped.stage() + " " + skipped.reason();
        h.append("<tr data-kind=\"").append(skipped.kind().name()).append("\" data-status=\"")
                .append(Html.attr(skipped.stage())).append("\" data-search=\"")
                .append(Html.lowerAttr(search)).append("\">");
        h.append("<td class=\"path\">").append(Html.escape(path)).append("</td>");
        h.append("<td>").append(Html.escape(extensionOf(path))).append("</td>");
        h.append("<td>").append(pill(skipped.stage(), "warn")).append("</td>");
        h.append("<td>").append(Html.escape(skipped.reason())).append("</td>");
        h.append("</tr>");
    }

    private static String extensionOf(String path) {
        if (path == null || path.isBlank()) {
            return "—";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) {
            return "—";
        }
        return path.substring(dot);
    }

    private static void verificationRow(StringBuilder h, VerificationSummary.Item item) {
        String path = item.record().path().toString();
        String search = path + " " + item.record().sha1Base64() + " " + item.actualSha1()
                + " " + item.serverAssetId() + " " + item.note();
        h.append("<tr data-kind=\"").append(item.record().kind().name()).append("\" data-status=\"")
                .append(item.status().name()).append("\" data-search=\"")
                .append(Html.lowerAttr(search)).append("\">");
        h.append("<td class=\"path\">").append(Html.escape(path)).append("</td>");
        h.append("<td>").append(Html.escape(item.record().kind().label())).append("</td>");
        h.append("<td>").append(pill(verificationLabel(item.status()), verificationStyle(item.status()))).append("</td>");
        h.append("<td class=\"hash\">").append(Html.escape(item.record().sha1Base64())).append("</td>");
        h.append("<td class=\"hash\">").append(Html.escape(item.actualSha1())).append("</td>");
        h.append("<td class=\"hash\">").append(Html.escape(item.serverAssetId())).append("</td>");
        h.append("<td>").append(Html.escape(item.note())).append("</td>");
        h.append("</tr>");
    }

    private static String pill(String text, String style) {
        return "<span class=\"pill " + style + "\">" + Html.escape(text) + "</span>";
    }

    public static String verificationLabel(VerificationSummary.Status status) {
        return switch (status) {
            case MATCHED -> "匹配";
            case MATCHED_DIFFERENT_ASSET -> "匹配但 assetId 不同";
            case TRASHED -> "回收站";
            case MISSING -> "缺失";
            case LOCAL_CHANGED_SERVER_OLD -> "本地变化/服务端有旧版";
            case LOCAL_CHANGED_NEW_EXISTS -> "本地变化/服务端有新版本";
            case LOCAL_CHANGED_MISSING -> "本地变化/服务端均无";
            case LINK_MISSING -> "关联缺失";
            case LINK_MISMATCH -> "关联不一致";
            case VIDEO_MISSING -> "配对视频缺失";
            case FILE_MISSING -> "文件丢失";
            case HASH_ERROR -> "哈希错误";
            case API_ERROR -> "API 错误";
        };
    }

    private static String verificationStyle(VerificationSummary.Status status) {
        return switch (status) {
            case MATCHED -> "ok";
            case MATCHED_DIFFERENT_ASSET, TRASHED, LOCAL_CHANGED_SERVER_OLD, LOCAL_CHANGED_NEW_EXISTS -> "warn";
            default -> "bad";
        };
    }
}
