package net.caffeinemc.mods.sodium.client.render.chunk.async;

public enum AsyncTaskType {
    GLOBAL_TASK_COLLECTION("C"),
    FRUSTUM_TASK_COLLECTION("T");

    public static final AsyncTaskType[] VALUES = values();

    public final String abbreviation;

    AsyncTaskType(String abbreviation) {
        this.abbreviation = abbreviation;
    }
}
