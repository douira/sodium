package net.caffeinemc.mods.sodium.instrumentation.core;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class Context {
    private final String name;
    private final Collection<Aspect> aspects = new ReferenceArrayList<>();
    private final List<Scene> scenes = new ReferenceArrayList<>();
    private int skippedScenes = 0;
    private int currentSceneIndex = 0;
    private Scene lastScene;
    private Scene currentScene;
    private long contextStart;
    private long measurementStart;

    public Context(String name) {
        this.name = name;
    }

    void register(Aspect aspect) {
        this.aspects.add(aspect);
    }

    public void generateScenes() {
        this.contextStart = System.nanoTime();

        Scene initialScene = new Scene();
        this.generateScenes(initialScene, 0, this.scenes::add);
    }

    private void generateScenes(Scene scene, int aspectIndex, Consumer<Scene> sceneWriter) {
        if (aspectIndex >= this.aspects.size()) {
            sceneWriter.accept(scene);
            return;
        }

        Aspect aspect = ((List<Aspect>) this.aspects).get(aspectIndex);
        aspect.generateValuations(scene, (newScene, sceneWriterLocal) ->
                        this.generateScenes(newScene, aspectIndex + 1, sceneWriterLocal),
                sceneWriter
        );
    }

    public boolean prepareScene(Function<Supplier<Boolean>, Boolean> measurementWrapper) {
        while (this.currentSceneIndex < this.scenes.size()) {
            this.currentScene = this.scenes.get(this.currentSceneIndex++);
            this.currentScene.prepareScene(this.lastScene);
            if (measurementWrapper.apply(this.currentScene::isMeasurementAllowed)) {
                return true;
            } else {
                this.skippedScenes++;
            }
        }
        return false;
    }

    public void startSceneMeasurement() {
        if (this.measurementStart == 0) {
            this.measurementStart = System.nanoTime();
        }

        this.currentScene.startMeasurement();
    }

    public boolean isMeasurementComplete() {
        return this.currentScene.isMeasurementComplete();
    }

    public void endSceneMeasurement() {
        this.currentScene.endMeasurement();
        this.lastScene = this.currentScene;
    }

    public String getCurrentProgress(long now) {
        // current scene of total, progress percentage, skipped, elapsed time, expected remaining time
        var contextElapsed = now - this.contextStart;
        var measurementElapsed = now - this.measurementStart;
        var totalScenes = this.scenes.size();
        var completedScenes = this.currentSceneIndex - this.skippedScenes;
        var progressPercentage = (completedScenes * 100.0) / totalScenes;
        var averageTimePerScene = completedScenes == 0 ? 0 : measurementElapsed / completedScenes;
        var expectedRemainingNanos = averageTimePerScene * (totalScenes - completedScenes);
        return String.format("Scenes: %d/%d (%.2f%%, %d skipped), Elapsed: %s, Remaining: %s",
                this.currentSceneIndex,
                totalScenes,
                progressPercentage,
                this.skippedScenes,
                formatDuration(contextElapsed),
                formatDuration(expectedRemainingNanos)
        );
    }

    public static String formatDuration(long nanos) {
        long totalMillis = nanos / 1_000_000;
        long hours = totalMillis / 3_600_000;
        long minutes = (totalMillis % 3_600_000) / 60_000;
        long seconds = (totalMillis % 60_000) / 1_000;

        if (hours == 0) {
            return String.format("%02dm %02ds", minutes, seconds);
        }
        return String.format("%02dh %02dm %02ds", hours, minutes, seconds);
    }

    public String generateReport() {
        var endTime = System.nanoTime();
        var totalTime = endTime - this.contextStart;

        StringBuilder sb = new StringBuilder();

        // non-csv header
        sb.append(String.format("Report for Context: %s\n", this.name));
        sb.append(String.format("Total Scenes: %d\n", this.scenes.size()));
        sb.append(String.format("Skipped Scenes: %d\n", this.skippedScenes));
        sb.append(String.format("Total Elapsed Time: %s\n", formatDuration(totalTime)));

        this.generateReportCSV(sb);

        return sb.toString();
    }

    private void generateReportCSV(StringBuilder sb) {
        // column headers
        for (var aspect : this.aspects) {
            sb.append(aspect.getName()).append(",");
        }

        // data rows
        for (var scene : this.scenes) {
            scene.generateReportRow(sb);
        }
    }

    public String getName() {
        return this.name;
    }
}
