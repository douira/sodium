package net.caffeinemc.mods.sodium.instrumentation.core;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public abstract class Aspect {
    private final String name;

    protected Aspect(Context context, String name) {
        this.name = name;
        context.register(this);
    }

    public String getName() {
        return this.name;
    }

    abstract void generateValuations(Scene scene, BiConsumer<Scene, Consumer<Scene>> sceneConsumer, Consumer<Scene> sceneWriter);

    public boolean isMeasurementAllowed() {
        return true;
    }

    public boolean showOnReport() {
        return true;
    }
}
