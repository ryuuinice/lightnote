package com.lightnote.client.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class AppLogger {

    private static final Logger ROOT_LOGGER = Logger.getLogger("com.lightnote.client");
    private static boolean configured;

    private AppLogger() {
    }

    public static synchronized void configure(Path dataDirectory) {
        if (configured || dataDirectory == null) {
            return;
        }
        try {
            Path logDirectory = dataDirectory.resolve("logs");
            Files.createDirectories(logDirectory);
            Path logFile = logDirectory.resolve("lightnote-client.log");
            FileHandler fileHandler = new FileHandler(logFile.toString(), 512 * 1024, 3, true);
            fileHandler.setEncoding("UTF-8");
            fileHandler.setFormatter(new PlainLogFormatter());
            ROOT_LOGGER.setUseParentHandlers(false);
            ROOT_LOGGER.setLevel(Level.INFO);
            ROOT_LOGGER.addHandler(fileHandler);
            configured = true;
            logger(AppLogger.class).info("日志已初始化: " + logFile);
        } catch (IOException ex) {
            System.err.println("[LightNote] Failed to initialize log file: " + ex.getMessage());
        }
    }

    public static Logger logger(Class<?> type) {
        return Logger.getLogger(type.getName());
    }

    private static final class PlainLogFormatter extends Formatter {

        @Override
        public String format(LogRecord record) {
            String throwable = "";
            if (record.getThrown() != null) {
                throwable = "\n" + stackTrace(record.getThrown());
            }
            return "%1$tF %1$tT [%2$s] %3$s - %4$s%5$s%n".formatted(
                    record.getMillis(),
                    record.getLevel().getName(),
                    record.getLoggerName(),
                    formatMessage(record),
                    throwable
            );
        }

        private String stackTrace(Throwable throwable) {
            StringBuilder builder = new StringBuilder();
            builder.append(throwable).append('\n');
            for (StackTraceElement element : throwable.getStackTrace()) {
                builder.append("\tat ").append(element).append('\n');
            }
            Throwable cause = throwable.getCause();
            if (cause != null) {
                builder.append("Caused by: ").append(stackTrace(cause));
            }
            return builder.toString().stripTrailing();
        }
    }
}
