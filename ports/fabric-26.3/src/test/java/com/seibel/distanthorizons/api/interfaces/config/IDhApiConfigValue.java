package com.seibel.distanthorizons.api.interfaces.config;

public interface IDhApiConfigValue<T> {
    boolean setValue(T value, String source);

    boolean clearValue();
}
