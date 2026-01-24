package net.caffeinemc.mods.sodium.instrumentation.core;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public abstract class IteratingParameter<V> extends Parameter<V> implements Iterable<V> {
    public IteratingParameter(Context context, String name) {
        super(context, name);
    }

    @Override
    public void generateValuations(Scene scene, BiConsumer<Scene, Consumer<Scene>> sceneConsumer, Consumer<Scene> sceneWriter) {
        for (V value : this) {
            scene.addValuation(this.createValuation(value), localScene -> sceneConsumer.accept(localScene, sceneWriter));
        }
    }
}
