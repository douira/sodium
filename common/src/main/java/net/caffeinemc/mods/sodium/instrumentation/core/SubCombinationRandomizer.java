package net.caffeinemc.mods.sodium.instrumentation.core;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;

import java.util.Collections;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class SubCombinationRandomizer extends Aspect {
    private final Random random;

    public SubCombinationRandomizer(Context context, int seed) {
        super(context, null);
        this.random = new Random(seed);
    }

    @Override
    void generateValuations(Scene scene, BiConsumer<Scene, Consumer<Scene>> sceneConsumer, Consumer<Scene> sceneWriter) {
        var combinations = new ReferenceArrayList<Scene>();
        sceneConsumer.accept(scene, combinations::add);
        Collections.shuffle(combinations, this.random);
        combinations.forEach(sceneWriter);
    }

    @Override
    public boolean showOnReport() {
        return false;
    }
}
