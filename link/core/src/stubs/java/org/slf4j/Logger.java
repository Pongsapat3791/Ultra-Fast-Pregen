package org.slf4j;

/** Stand-in, see build.gradle. */
public interface Logger {
    void info(String format, Object... arguments);

    void warn(String format, Object... arguments);

    void error(String format, Object... arguments);
}
