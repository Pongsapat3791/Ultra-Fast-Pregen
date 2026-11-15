package com.seibel.distanthorizons.api.objects;

/** Test stand-in for DH's call result. */
public class DhApiResult<T> {
    public final boolean success;
    public final String message;
    public final T payload;

    private DhApiResult(boolean success, String message, T payload) {
        this.success = success;
        this.message = message;
        this.payload = payload;
    }

    public static <Pt> DhApiResult<Pt> createSuccess() {
        return new DhApiResult<>(true, "", null);
    }

    public static <Pt> DhApiResult<Pt> createFail(String message) {
        return new DhApiResult<>(false, message, null);
    }
}
