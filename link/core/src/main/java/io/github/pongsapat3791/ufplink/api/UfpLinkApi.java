package io.github.pongsapat3791.ufplink.api;

/** The entry point of Ultra Fast Pregen Link's API. */
public final class UfpLinkApi {
    /** Only grows; methods are added within it, never changed or removed. */
    public static final int VERSION = 1;

    private static volatile LinkApi api;

    private UfpLinkApi() {}

    /** The API; throws {@link IllegalStateException} before the mod has initialized. */
    public static LinkApi get() {
        LinkApi current = api;
        if (current == null) throw new IllegalStateException("Ultra Fast Pregen Link has not initialized yet");
        return current;
    }

    /** For the mod itself, while it initializes. */
    public static void install(LinkApi implementation) {
        api = implementation;
    }
}
