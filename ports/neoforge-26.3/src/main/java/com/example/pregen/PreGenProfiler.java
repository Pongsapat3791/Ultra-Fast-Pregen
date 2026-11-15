package com.example.pregen;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/**
 * Optional Java Flight Recorder capture for one pre-generation run, for hosts where JVM flags
 * cannot be edited. All jdk.jfr references live here so a runtime without the module only
 * loses profiling. Uses the JDK's "profile" settings (method sampling plus allocations).
 */
final class PreGenProfiler {
    private final Recording recording;
    private final Path output;

    private PreGenProfiler(Recording recording, Path output) {
        this.recording = recording;
        this.output = output;
    }

    static PreGenProfiler start(Path directory, Consumer<String> log) {
        try {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path output = directory.resolve("pregen-profile-" + stamp + ".jfr").toAbsolutePath();
            Recording recording = new Recording(Configuration.getConfiguration("profile"));
            recording.setName("PreGen");
            recording.setToDisk(true);
            recording.start();
            log.accept("[PreGen] Profiling enabled (JFR). Output: " + output);
            return new PreGenProfiler(recording, output);
        } catch (Throwable failure) {
            log.accept("[PreGen] Profiling unavailable on this Java runtime: " + failure);
            return null;
        }
    }

    void finish(Consumer<String> log) {
        try {
            recording.stop();
            recording.dump(output);
            log.accept("[PreGen] Profile saved: " + output);
        } catch (Throwable failure) {
            log.accept("[PreGen] Failed to save profile: " + failure);
        } finally {
            recording.close();
        }
    }
}
