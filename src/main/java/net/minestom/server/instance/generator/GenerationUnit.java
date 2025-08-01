package net.minestom.server.instance.generator;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Represents an area that can be generated.
 * <p>
 * The size is guaranteed to be a multiple of 16 (section).
 */
public interface GenerationUnit {
    /**
     * This unit's modifier, used to place blocks and biomes within this unit.
     *
     * @return the modifier
     */
    @NotNull UnitModifier modifier();

    /**
     * The size of this unit in blocks.
     * <p>
     * Guaranteed to be a multiple of 16.
     *
     * @return the size of this unit
     */
    @NotNull Point size();

    /**
     * The absolute start (min x, y, z) of this unit.
     *
     * @return the absolute start
     */
    @NotNull Point absoluteStart();

    /**
     * The absolute end (max x, y, z) of this unit.
     *
     * @return the absolute end
     */
    @NotNull Point absoluteEnd();

    /**
     * Creates a fork of this unit, which will be applied to the instance whenever possible.
     *
     * @param start the start of the fork
     * @param end   the end of the fork
     * @return the fork
     */
    @NotNull GenerationUnit fork(@NotNull Point start, @NotNull Point end);

    /**
     * Creates a fork of this unit depending on the blocks placed within the consumer.
     *
     * @param consumer the consumer
     */
    void fork(@NotNull Consumer<Block.@NotNull Setter> consumer);

    /**
     * Divides this unit into the smallest independent units.
     *
     * @return an immutable list of independent units
     */
    default @NotNull List<GenerationUnit> subdivide() {
        return List.of(this);
    }

    /**
     * Returns the sections that this unit contains. Coordinates are in section coordinates.
     *
     * @return the contained sections
     */
    default @NotNull Set<Vec> sections() {
        final Point start = absoluteStart(), end = absoluteEnd();
        final int minX = start.sectionX(), minY = start.sectionY(), minZ = start.sectionZ();
        final int maxX = end.sectionX(), maxY = end.sectionY(), maxZ = end.sectionZ();
        final int count = (maxX - minX) * (maxY - minY) * (maxZ - minZ);
        Vec[] sections = new Vec[count];
        int index = 0;
        for (int sectionX = minX; sectionX < maxX; sectionX++) {
            for (int sectionY = minY; sectionY < maxY; sectionY++) {
                for (int sectionZ = minZ; sectionZ < maxZ; sectionZ++) {
                    sections[index++] = new Vec(sectionX, sectionY, sectionZ);
                }
            }
        }
        return Set.of(sections);
    }
}
