package net.caffeinemc.mods.sodium.instrumentation.core;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;

import java.util.List;
import java.util.function.Supplier;

public abstract class MeasuredAspect<V> extends Aspect {
    protected MeasuredAspect(Context context, String name) {
        super(context, name);
    }

    @Override
    List<Scene> generateValuations(Supplier<List<Scene>> sceneSupplier) {
        var scenes = sceneSupplier.get();
        var outputScenes = new ReferenceArrayList<Scene>(scenes.size());
        for (Scene scene : scenes) {
            outputScenes.add(scene.withValuation(this.createValuation()));
        }
        return outputScenes;
    }

    protected abstract class MeasurementValuation implements Valuation<V> {
        private V value;

        @Override
        public Aspect getAspect() {
            return MeasuredAspect.this;
        }

        @Override
        public V getValue() {
            return this.value;
        }

        protected void recordMeasurement(V value) {
            this.value = value;
        }
    }

    protected abstract MeasurementValuation createValuation();
}
