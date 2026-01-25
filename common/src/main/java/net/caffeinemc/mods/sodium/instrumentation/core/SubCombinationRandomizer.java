package net.caffeinemc.mods.sodium.instrumentation.core;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

public class SubCombinationRandomizer extends Aspect {
    private final Random random;

    public SubCombinationRandomizer(Context context, int seed) {
        super(context, null);
        this.random = new Random(seed);
    }

    @Override
    List<Scene> generateValuations(Supplier<List<Scene>> sceneSupplier) {
        var scenes = sceneSupplier.get();
        var outputScenes = new ReferenceArrayList<>(scenes);
        Collections.shuffle(outputScenes, this.random);
        return outputScenes;
    }

    @Override
    public boolean showOnReport() {
        return false;
    }
}
