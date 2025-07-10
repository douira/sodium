package net.caffeinemc.mods.sodium.client.render.chunk.lists;

@FunctionalInterface
public interface CoordinateSectionVisitor {
    void visit(long sectionPos);
}
