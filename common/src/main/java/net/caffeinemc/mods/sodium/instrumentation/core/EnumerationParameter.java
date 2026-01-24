package net.caffeinemc.mods.sodium.instrumentation.core;

import org.jetbrains.annotations.NotNull;

import java.util.Iterator;

public class EnumerationParameter<V> extends IteratingParameter<V> {
    private final Iterable<V> values;

    public EnumerationParameter(Context context, String name, Iterable<V> values) {
        super(context, name);
        this.values = values;
    }

    @Override
    public @NotNull Iterator<V> iterator() {
        return this.values.iterator();
    }
}
