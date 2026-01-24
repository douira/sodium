package net.caffeinemc.mods.sodium.instrumentation.aspects;

import net.caffeinemc.mods.sodium.instrumentation.core.Context;
import net.caffeinemc.mods.sodium.instrumentation.core.MeasuredAspect;

public class MeasurementDurationResult extends MeasuredAspect<Long> {
    private final long minMeasurementDuration;

    public MeasurementDurationResult(Context context, long minMeasurementDuration) {
        super(context, "measurement duration (ns)");
        this.minMeasurementDuration = minMeasurementDuration;
    }

    private class DurationMeasurementValuation extends MeasuredAspect<Long>.MeasurementValuation {
        private long startTime;

        @Override
        public void startMeasurement(long now) {
            this.startTime = now;
        }

        @Override
        public void endMeasurement(long now) {
            this.recordMeasurement(now - this.startTime);
        }

        @Override
        public boolean isMeasurementComplete() {
            return (System.nanoTime() - this.startTime) >= MeasurementDurationResult.this.minMeasurementDuration;
        }
    }

    @Override
    protected MeasuredAspect<Long>.MeasurementValuation createValuation() {
        return new DurationMeasurementValuation();
    }
}
