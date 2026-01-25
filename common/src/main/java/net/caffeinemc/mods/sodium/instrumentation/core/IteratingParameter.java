package net.caffeinemc.mods.sodium.instrumentation.core;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;

import java.util.List;
import java.util.function.Supplier;

public abstract class IteratingParameter<V> extends Parameter<V> implements Iterable<V> {
    public IteratingParameter(Context context, String name) {
        super(context, name);
    }

    @Override
    List<Scene> generateValuations(Supplier<List<Scene>> sceneSupplier) {
        var scenes = sceneSupplier.get();
        var outputScenes = new ReferenceArrayList<Scene>(scenes.size());
        for (V value : this) {
            for (Scene scene : scenes) {
                outputScenes.add(scene.withValuation(this.createValuation(value)));
            }
        }
        return outputScenes;
    }
}
