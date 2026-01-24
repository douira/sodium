package net.caffeinemc.mods.sodium.instrumentation.core;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public abstract class MeasuredAspect<V> extends Aspect {
    protected MeasuredAspect(Context context, String name) {
        super(context, name);
    }

    @Override
    void generateValuations(Scene scene, BiConsumer<Scene, Consumer<Scene>> sceneConsumer, Consumer<Scene> sceneWriter) {
        scene.addValuation(this.createValuation(), localScene -> sceneConsumer.accept(localScene, sceneWriter));
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
