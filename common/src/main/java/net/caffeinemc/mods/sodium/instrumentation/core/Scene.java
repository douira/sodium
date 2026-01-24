package net.caffeinemc.mods.sodium.instrumentation.core;

import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;

import java.util.SortedMap;
import java.util.function.Consumer;

public class Scene {
    private final SortedMap<Aspect, Valuation<?>> valuations;

    private Scene(SortedMap<Aspect, Valuation<?>> valuations) {
        this.valuations = valuations;
    }

    public Scene() {
        this.valuations = new Reference2ObjectLinkedOpenHashMap<>();
    }

    void prepareScene(Scene previousScene) {
        for (var valuation : this.valuations.values()) {
            valuation.applyToAspect(previousScene == null ?
                    null : previousScene.valuations.get(valuation.getAspect()));
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

    void addValuation(Valuation<?> valuation, Consumer<Scene> sceneConsumer) {
        // create new scene if the valuation already exists
        if (this.valuations.containsKey(valuation.getAspect())) {
            var newValuations = new Reference2ObjectLinkedOpenHashMap<>(this.valuations);
            newValuations.put(valuation.getAspect(), valuation);
            sceneConsumer.accept(new Scene(newValuations));
        } else {
            this.valuations.put(valuation.getAspect(), valuation);
            sceneConsumer.accept(this);
        }
    }

    public void generateReportRow(StringBuilder sb) {
        for (var valuation : this.valuations.values()) {
            if (valuation.getAspect().showOnReport()) {
                valuation.addToReport(sb);
                sb.append(",");
            }
        }
        sb.append("\n");
    }
}
