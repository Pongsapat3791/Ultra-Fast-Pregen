package org.slf4j;

/** Stand-in, see build.gradle. */
public final class LoggerFactory {
    private LoggerFactory() {}

    public static Logger getLogger(String name) {
        throw new UnsupportedOperationException();
    }
}
