package net.caffeinemc.mods.sodium.instrumentation.core;

import java.util.function.Consumer;

public abstract class Parameter<V> extends Aspect {
    private Consumer<V> applier;

    protected Parameter(Context context, String name) {
        super(context, name);
    }

    private void setCurrentValue(V value) {
        if (this.applier == null) {
            throw new IllegalStateException("Parameter applier not set for parameter: " + this.getName());
        }

        this.applier.accept(value);
    }

    public void setApplier(Consumer<V> applier) {
        this.applier = applier;
    }

    @Override
    public boolean isMeasurementAllowed() {
        return super.isMeasurementAllowed();
    }

    public boolean isMeasurementAllowed(V value) {
        return this.isMeasurementAllowed();
    }

    private class ParameterValuation implements Valuation<V> {
        private final V value;

        public ParameterValuation(V value) {
            this.value = value;
        }

        @Override
        public Aspect getAspect() {
            return Parameter.this;
        }

        @Override
        public V getValue() {
            return this.value;
        }

        @Override
        public void applyToAspect(Valuation<?> previousValuation) {
            if (previousValuation != null && previousValuation.getValue().equals(this.value)) {
                return;
            }
            Parameter.this.setCurrentValue(this.value);
        }

        @Override
        public boolean isMeasurementComplete() {
            return true;
        }

        @Override
        public boolean isMeasurementAllowed() {
            return Parameter.this.isMeasurementAllowed(this.value);
        }
    }

    protected Valuation<V> createValuation(V value) {
        return new ParameterValuation(value);
    }
}
