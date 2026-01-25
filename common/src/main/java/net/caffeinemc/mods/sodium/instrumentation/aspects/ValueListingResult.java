package net.caffeinemc.mods.sodium.instrumentation.aspects;

import net.caffeinemc.mods.sodium.instrumentation.core.Context;

import java.util.Collection;

public class ValueListingResult<V> extends ValueCollectingResult<V, String> {
    public ValueListingResult(Context context, String name, Collection<V> valueCollector, int minSamples) {
        super(context, name, valueCollector, minSamples);
    }

    @Override
    protected String calculateValue(Collection<V> collectedValues) {
        var sb = new StringBuilder();
        Context.joinIterable(sb, collectedValues, ":");
        return sb.toString();
    }
}
