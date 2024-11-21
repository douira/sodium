package net.caffeinemc.mods.sodium.client.model.quad.properties;

public enum MeshQuadCategory {
    // the members of this enum other than LOCAL must have the same layout as those in ModelQuadFacing
    POS_X,
    POS_Y,
    POS_Z,
    NEG_X,
    NEG_Y,
    NEG_Z,
    UNASSIGNED,

    // it's important that local has the highest ordinal so that detecting whether the camera is within a section is easy with bitwise math
    LOCAL;

    public static final MeshQuadCategory[] VALUES = MeshQuadCategory.values();

    public static final int COUNT = VALUES.length;
    public static final int ALL = (1 << COUNT) - 1;
}
