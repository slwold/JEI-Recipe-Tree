package com.slwold.jeirecipetree.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JERTLog {

    private static final Logger LOG = LoggerFactory.getLogger("JERT");

    private JERTLog() {
    }

    public static void debug(String message, Object... args) {
        try {
            if (SafeConfig.developerMode()) {
                LOG.info(message, args);
            }
        } catch (RuntimeException ignored) {

        }
    }

    public static void info(String message, Object... args) {
        LOG.info(message, args);
    }

    public static void warn(String message, Object... args) {
        LOG.warn(message, args);
    }

    public static void warn(String message, Throwable error) {
        LOG.warn(message, error);
    }

    public static void error(String message, Object... args) {
        LOG.error(message, args);
    }

    public static void error(String message, Throwable error) {
        LOG.error(message, error);
    }

    public static void summary(String message, Object... args) {
        LOG.info(message, args);
    }
}
