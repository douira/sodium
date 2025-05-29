package net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting;

import net.caffeinemc.mods.sodium.client.gui.options.TextProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public enum QuadSplittingMode implements TextProvider {
    OFF("/", 1.0f, "options.off"),
    SAFE("S", 2.0f, "sodium.options.quad_splitting.safe"),
    UNLIMITED("U", Float.POSITIVE_INFINITY, "sodium.options.quad_splitting.unlimited");

    private final String shortName;

    // how much bigger the final geometry is allowed to be compared to the input geometry when performing quad splitting.
    private final float maxAmplificationFactor;
    private final Component name;

    QuadSplittingMode(String shortName, float maxAmplificationFactor, String name) {
        this.shortName = shortName;
        this.maxAmplificationFactor = maxAmplificationFactor;
        this.name = Component.translatable(name);
    }

    @Override
    public Component getLocalizedName() {
        return this.name;
    }

    public String getShortName() {
        return this.shortName;
    }

    public boolean allowsSplitting() {
        return this != OFF;
    }

    public int getMaxTotalQuads(int baseQuadCount) {
        if (Float.isInfinite(this.maxAmplificationFactor)) {
            return Integer.MAX_VALUE;
        }
        return Mth.ceil(baseQuadCount * this.maxAmplificationFactor);
    }
}
