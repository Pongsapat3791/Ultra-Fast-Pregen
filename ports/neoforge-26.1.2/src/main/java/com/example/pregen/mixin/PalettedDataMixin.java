package com.example.pregen.mixin;

import com.example.pregen.WorldgenCompat;
import com.example.pregen.terrain.BlockBits;
import com.example.pregen.terrain.PaletteGrowth;
import java.util.Arrays;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.SingleValuePalette;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A container's growth from one value without copying that value 4,096 times (see PaletteGrowth), and its
 * storage and palette for BlockBits.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.PalettedContainer$Data")
public abstract class PalettedDataMixin<T> implements BlockBits.Data {
    @Shadow @Final private BitStorage storage;
    @Shadow @Final private Palette<T> palette;

    @Override
    public BitStorage pregen$storage() {
        return storage;
    }

    @Override
    public Palette<?> pregen$palette() {
        return palette;
    }

    // Only a shortcut: a mod that replaces copyFrom copies as it likes.
    @Inject(method = "copyFrom", require = 0, at = @At("HEAD"), cancellable = true)
    private void pregen$copyOneValue(Palette<T> oldPalette, BitStorage oldStorage, CallbackInfo ci) {
        if (!PaletteGrowth.enabled || oldStorage.getClass() != ZeroBitStorage.class
                || oldPalette.getClass() != SingleValuePalette.class || storage.getClass() != SimpleBitStorage.class
                || oldStorage.getSize() != storage.getSize() || !WorldgenCompat.paletteGrowthAllowed()) {
            return;
        }
        // Minecraft's loop gives every entry this id; asking again in the loop gives the same id.
        if (palette.idFor(oldPalette.valueFor(0), PaletteResize.noResizeExpected()) != 0) return;
        Arrays.fill(storage.getRaw(), 0L);
        PaletteGrowth.CLEARED.increment();
        ci.cancel();
    }
}
