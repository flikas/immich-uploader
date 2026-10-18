package io.github.immichuploader.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "immich-uploader",
        mixinStandardHelpOptions = true,
        version = "immich-uploader 1.0.0",
        description = "批量上传图片和视频到 Immich，支持 Live Photo 配对、断点续传、SHA1 核对和 HTML 报告。",
        subcommands = {UploadCommand.class, VerifyCommand.class, ScanCommand.class}
)
public final class RootCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Option(
            names = "--verbose",
            description = "输出 DEBUG 日志和异常堆栈",
            scope = ScopeType.INHERIT
    )
    private boolean verbose;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    public boolean isVerbose() {
        return verbose;
    }
}
