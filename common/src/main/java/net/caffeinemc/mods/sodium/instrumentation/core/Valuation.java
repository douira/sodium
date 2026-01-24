package net.caffeinemc.mods.sodium.instrumentation.core;

public interface Valuation<V> {
    Aspect getAspect();

    V getValue();

    default void addToReportHeader(StringBuilder sb) {
        sb.append(this.getAspect().getName());
    }

    default void addToReport(StringBuilder sb) {
        sb.append(this.getValue());
    }

    default void applyToAspect(Valuation<?> previousValuation) {
        // Default implementation does nothing
    }

    default void startMeasurement(long now) {
        // Default implementation does nothing
    }

    default void endMeasurement(long now) {
        // Default implementation does nothing
    }

    boolean isMeasurementComplete();

    default boolean isMeasurementAllowed() {
        return this.getAspect().isMeasurementAllowed();
    }
}
