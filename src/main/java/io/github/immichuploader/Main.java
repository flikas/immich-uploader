package io.github.immichuploader;

import io.github.immichuploader.cli.RootCommand;
import io.github.immichuploader.immich.AuthException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.util.concurrent.ExecutionException;

public final class Main {
    private static final Logger LOG = LogManager.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    public static int execute(String[] args) {
        RootCommand root = new RootCommand();
        CommandLine commandLine = new CommandLine(root);
        commandLine.setOut(new PrintWriter(System.out, true));
        commandLine.setErr(new PrintWriter(System.err, true));
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.setUsageHelpAutoWidth(true);
        commandLine.setExecutionStrategy(parseResult -> {
            if (root.isVerbose()) {
                Configurator.setRootLevel(Level.DEBUG);
            }
            LOG.info("命令启动: {}", parseResult.commandSpec().qualifiedName());
            return new CommandLine.RunLast().execute(parseResult);
        });
        commandLine.setExecutionExceptionHandler((ex, cmd, parseResult) -> {
            Throwable cause = unwrap(ex);
            LOG.error("命令执行失败: {}", message(cause), cause);
            cmd.getErr().println("错误: " + message(cause));
            if (root.isVerbose()) {
                cause.printStackTrace(cmd.getErr());
            }
            if (cause instanceof AuthException) {
                return 1;
            }
            return cause instanceof UserError ? 1 : 2;
        });
        commandLine.setParameterExceptionHandler((ex, args1) -> {
            LOG.error("参数错误: {}", ex.getMessage());
            ex.getCommandLine().getErr().println("参数错误: " + ex.getMessage());
            ex.getCommandLine().usage(ex.getCommandLine().getErr());
            return 2;
        });
        int exitCode = commandLine.execute(args);
        LOG.info("命令完成: exitCode={}", exitCode);
        return exitCode;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof ExecutionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    private static String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
