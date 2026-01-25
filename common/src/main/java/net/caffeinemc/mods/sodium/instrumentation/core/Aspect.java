package net.caffeinemc.mods.sodium.instrumentation.core;

import java.util.List;
import java.util.function.Supplier;

public abstract class Aspect {
    private final String name;

    protected Aspect(Context context, String name) {
        this.name = name;
        context.register(this);
    }

    public String getName() {
        return this.name;
    }

    abstract List<Scene> generateValuations(Supplier<List<Scene>> sceneSupplier);

    public boolean isMeasurementAllowed() {
        return true;
    }

    public boolean showOnReport() {
        return true;
    }
}
