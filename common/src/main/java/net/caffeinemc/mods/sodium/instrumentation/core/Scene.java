package net.caffeinemc.mods.sodium.instrumentation.core;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import java.util.Map;

public class Scene {
    private final Map<Aspect, Valuation<?>> valuations;
    private final Context context;

    private Scene(Context context, Map<Aspect, Valuation<?>> valuations) {
        this.context = context;
        this.valuations = valuations;
    }

    public Scene(Context context) {
        this(context, new Reference2ObjectOpenHashMap<>());
    }

    void prepareScene(Scene previousScene) {
        // important to iterate aspects in the right order
        for (var aspect : this.context.getAspects()) {
            var valuation = this.valuations.get(aspect);
            if (valuation != null) {
                valuation.applyToAspect(previousScene == null ?
                        null : previousScene.valuations.get(valuation.getAspect()));
            }
        }
    }

    boolean isMeasurementAllowed() {
        for (var valuation : this.valuations.values()) {
            if (!valuation.isMeasurementAllowed()) {
                return false;
            }
        }
        return true;
    }

    void startMeasurement() {
        var now = System.nanoTime();
        for (var valuation : this.valuations.values()) {
            valuation.startMeasurement(now);
        }
    }

    public boolean isMeasurementComplete() {
        for (var valuation : this.valuations.values()) {
            if (!valuation.isMeasurementComplete()) {
                return false;
            }
        }
        return true;
    }

    void endMeasurement() {
        var now = System.nanoTime();
        for (var valuation : this.valuations.values()) {
            valuation.endMeasurement(now);
        }
    }

    Scene withValuation(Valuation<?> valuation) {
        // create new scene if the valuation already exists
        if (this.valuations.containsKey(valuation.getAspect())) {
            var newValuations = new Reference2ObjectOpenHashMap<>(this.valuations);
            newValuations.put(valuation.getAspect(), valuation);
            return new Scene(this.context, newValuations);
        } else {
            this.valuations.put(valuation.getAspect(), valuation);
            return this;
        }
    }

    public void generateReportRow(StringBuilder sb) {
        boolean hasPrevious = false;
        for (var aspect : this.context.getAspects()) {
            if (aspect.showOnReport()) {
                if (hasPrevious) {
                    sb.append(",");
                }
                var valuation = this.valuations.get(aspect);
                if (valuation == null) {
                    throw new IllegalStateException("No valuation for reportable aspect " + aspect.getName() + " in scene");
                }
                valuation.addToReport(sb);
                hasPrevious = true;
            }
        }
    }
}
