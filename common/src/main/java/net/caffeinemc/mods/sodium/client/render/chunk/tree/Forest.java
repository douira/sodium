package net.caffeinemc.mods.sodium.client.render.chunk.tree;

public interface Forest {
    void add(int x, int y, int z);

    int getPresence(int x, int y, int z);
}
