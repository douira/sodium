package net.caffeinemc.mods.sodium.instrumentation.aspects;

import net.caffeinemc.mods.sodium.instrumentation.core.Context;
import net.caffeinemc.mods.sodium.instrumentation.core.MeasuredAspect;

import java.util.Collection;

public abstract class ValueCollectingResult<V, R> extends MeasuredAspect<R> {
    private final Collection<V> valueCollector;
    private final int minSamples;

    protected ValueCollectingResult(Context context, String name, Collection<V> valueCollector, int minSamples) {
        super(context, name);
        this.valueCollector = valueCollector;
        this.minSamples = minSamples;
    }

    protected abstract R calculateValue(Collection<V> collectedValues);

    private class ValueCollectingValuation extends MeasuredAspect<R>.MeasurementValuation {
        @Override
        public void startMeasurement(long now) {
            ValueCollectingResult.this.valueCollector.clear();
        }

        @Override
        public void endMeasurement(long now) {
            R finalValue = ValueCollectingResult.this.calculateValue(ValueCollectingResult.this.valueCollector);
//            System.out.println(finalValue);
            this.recordMeasurement(finalValue);
        }

        @Override
        public boolean isMeasurementComplete() {
            return ValueCollectingResult.this.valueCollector.size() >= ValueCollectingResult.this.minSamples;
        }
    }

    @Override
    protected MeasuredAspect<R>.MeasurementValuation createValuation() {
        return new ValueCollectingValuation();
    }
}
