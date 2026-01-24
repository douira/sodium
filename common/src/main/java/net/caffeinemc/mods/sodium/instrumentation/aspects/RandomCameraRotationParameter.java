package net.caffeinemc.mods.sodium.instrumentation.aspects;

import net.caffeinemc.mods.sodium.instrumentation.core.Context;
import net.caffeinemc.mods.sodium.instrumentation.core.IteratingParameter;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector2f;

import java.util.Iterator;
import java.util.Random;

public class RandomCameraRotationParameter extends IteratingParameter<Vector2f> {
    private final int randomSamples;
    private final float minPitch;
    private final float maxPitch;
    private final int seed;

    public RandomCameraRotationParameter(Context context, String name, int randomSamples, float minPitch, float maxPitch, int seed) {
        super(context, name);
        this.randomSamples = randomSamples;
        this.minPitch = minPitch;
        this.maxPitch = maxPitch;
        this.seed = seed;
    }

    public RandomCameraRotationParameter(Context context, String name) {
        this(context, name, 10, -10, 60, 1235);
    }

    @Override
    public @NotNull Iterator<Vector2f> iterator() {
        return new Iterator<>() {
            private int index = 0;
            private final Random random = new Random(RandomCameraRotationParameter.this.seed);

            @Override
            public boolean hasNext() {
                return this.index < RandomCameraRotationParameter.this.randomSamples;
            }

            @Override
            public Vector2f next() {
                this.index++;

                // uniform sphere sampling with constraints
                double u = this.random.nextDouble();
                double v = this.random.nextDouble();
                double phi = Math.acos(2 * v - 1);
                double pitch = Mth.map(phi, 0, Math.PI, RandomCameraRotationParameter.this.minPitch, RandomCameraRotationParameter.this.maxPitch); // Convert to pitch
                double yaw = Mth.map(u, 0, 1, -180, 180); // Convert to yaw

                return new Vector2f((float) yaw, (float) pitch);
            }
        };
    }
}
