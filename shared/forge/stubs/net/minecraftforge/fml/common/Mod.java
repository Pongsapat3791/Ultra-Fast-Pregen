package net.minecraftforge.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Only to compile Ultra Fast Pregen's Forge entry point: the shape of Forge's own annotation of this name (the
 * same on every Forge version), so that the build needs no Forge files. Never packaged; Forge's own class is the
 * one that exists when the mod runs.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value();
}
