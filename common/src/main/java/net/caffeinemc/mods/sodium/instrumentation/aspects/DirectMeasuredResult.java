package net.caffeinemc.mods.sodium.instrumentation.aspects;

import net.caffeinemc.mods.sodium.instrumentation.core.Context;
import net.caffeinemc.mods.sodium.instrumentation.core.MeasuredAspect;

import java.util.function.Supplier;

public class DirectMeasuredResult<V> extends MeasuredAspect<V> {
    private final Supplier<V> measurement;

    public DirectMeasuredResult(Context context, String name, Supplier<V> measurement) {
        super(context, name);
        this.measurement = measurement;
    }

    protected void validateValue(V value) {
        // Default implementation does nothing
    }

    private class DirectMeasurementValuation extends MeasuredAspect<V>.MeasurementValuation {
        @Override
        public void endMeasurement(long now) {
            var value = DirectMeasuredResult.this.measurement.get();
            DirectMeasuredResult.this.validateValue(value);
            this.recordMeasurement(value);
        }

        @Override
        public boolean isMeasurementComplete() {
            return true;
        }
    }

    @Override
    protected MeasuredAspect<V>.MeasurementValuation createValuation() {
        return new DirectMeasurementValuation();
    }
}
