package net.caffeinemc.mods.sodium.client.gl.arena;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlMutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.Map;
import java.util.Set;

public class DefragmentingGlBufferArena extends GlBufferArena {
    private static final float DEFRAG_MIN_SEEN_FREE_FRACTION = 0.95f;
    public static final int MAX_DEFRAG_STEPS = 5;

    // profiling has shown that Long2ReferenceRBTreeMap is 58% slower than TreeMap here
    private final SizedTreeMap<GlBufferSegment> freeSegmentsByLength = new SizedTreeMap<>();

    // direction to move the biggest free segment in during defragmentation
    private boolean defragmentRight = true;

    protected DefragmentingGlBufferArena(ArenaAggregator parent, GlMutableBuffer initialBuffer, long capacity, int stride) {
        super(parent, initialBuffer, capacity, stride);
        this.addFreeSegment(this.head);
    }

    protected void addFreeSegment(GlBufferSegment segment) {
        this.freeSegmentsByLength.addSized(segment);
        this.checkSegmentAssertions(segment);
    }

    protected void removeFreeSegment(GlBufferSegment segment) {
        this.freeSegmentsByLength.removeSized(segment);

        // don't check segment assertions on remove because what we're removing is invalid
    }

    public long getBiggestFreeSegmentSize() {
        return this.freeSegmentsByLength.getHighestSize();
    }

    private float calculateFragmentationDegree(Set<Map.Entry<Long, GlBufferSegment>> givenEntries) {
        // take the top 3 biggest free segments and sum their sizes
        long biggestFreeTotalSize = 0;
        int count = 0;
        if (givenEntries == null) {
            givenEntries = this.freeSegmentsByLength.descendingMap().entrySet();
        }
        for (var entry : givenEntries) {
            biggestFreeTotalSize += entry.getValue().getLength();
            count++;
            if (count >= 3) {
                break;
            }
        }
        long totalFreeSize = this.capacity - this.used;
        if (totalFreeSize == 0) {
            return 0.0f;
        }
        return (float) (totalFreeSize - biggestFreeTotalSize) / (float) this.capacity;
    }

    protected void defragmentIncremental(CommandList commands, ArenaAggregator.DefragBudget budget) {
        // don't defragment if there's only one free segment
        if (this.freeSegmentsByLength.size() <= 1) {
            return;
        }

        var descendingFreeSegments = this.freeSegmentsByLength.descendingMap().entrySet();
        long requiredSeenFreeSize = (long) ((this.capacity - this.used) * DEFRAG_MIN_SEEN_FREE_FRACTION);

        // calculate number of defragmentation steps to perform based on fragmentation degree
        float fragmentationDegree = this.calculateFragmentationDegree(descendingFreeSegments);
        int defragmentationSteps = calculateDefragmentationSteps(fragmentationDegree);

        for (int i = 0; i < defragmentationSteps; i++) {
            defragmentationStep(commands, descendingFreeSegments, requiredSeenFreeSize, budget);
            if (budget.isElementBudgetEmpty()) {
                break;
            }
        }
    }

    private static int calculateDefragmentationSteps(float fragmentationDegree) {
        return Mth.lerpInt(fragmentationDegree, 1, MAX_DEFRAG_STEPS + 1);
    }

    private void defragmentationStep(CommandList commands, Set<Map.Entry<Long, GlBufferSegment>> descendingFreeSegments, long requiredSeenFreeSize, ArenaAggregator.DefragBudget budget) {
        // find the biggest free segment that can receive defragmentation
        long seenFreeSize = 0;
        var it = descendingFreeSegments.iterator();
        Map.Entry<Long, GlBufferSegment> biggestEntry = null;
        var secondBiggestEntry = it.next();
        while (it.hasNext() || biggestEntry != null) {
            biggestEntry = secondBiggestEntry;
            secondBiggestEntry = it.hasNext() ? it.next() : null;

            var biggestFree = biggestEntry.getValue();
            seenFreeSize += biggestFree.getLength();

            // stop if we've already seen enough free and defragmentation must be low
            if (seenFreeSize >= requiredSeenFreeSize) {
                break;
            }

            // determine the direction we want to move it
            var next = biggestFree.getNext();
            var prev = biggestFree.getPrev();
            if (next == null && prev == this.head) {
                // violated invariant, only one free segment
                throw new IllegalStateException("There cannot be multiple free segments if there's no next and the previous is the head");
            }

            // find as many segments as will fit into the free segment in the chosen direction to move in the opposite direction, which causes the free segment to move in the chosen direction
            // TODO: this is causing likely the cause of a java.lang.IllegalStateException: segment.prev.end > segment.start: overlapping segments (corrupted) within the segment extraction code
            // TODO: more smartly determine whether moving the free space in any particular direction would actually gain us anything, i.e. if there's no significant amount of free segments to be combined with in this direction, don't even try. maybe just get the top N biggest free segments and move them towards each other preferentially? -> use while loops and collect the biggest and second biggest and try to move the biggest towards the second biggest, and if that doesn't work, in the other direction, and if that doesn't work, try the second and third biggest, etc.
            // TODO: byte and copy count budgeting, integrate with time estimation?
            var secondBiggestFree = secondBiggestEntry != null ? secondBiggestEntry.getValue() : null;
            var defragmentRightLocal = this.defragmentRight;
            if (secondBiggestFree != null) {
                defragmentRightLocal = biggestFree.getOffset() < secondBiggestFree.getOffset();
            }
            if (defragmentRightLocal) {
                if (next != null && defragmentRightwards(commands, biggestFree, budget)) {
                    this.checkAssertions();
                    return;
                }
            } else {
                if (prev != this.head && biggestFree != this.head && defragmentLeftwards(commands, biggestFree, budget)) {
                    this.checkAssertions();
                    return;
                }
            }
        }

        // no success, go the other way next time
        this.defragmentRight = !this.defragmentRight;
    }

    private boolean defragmentRightwards(CommandList commands, GlBufferSegment biggestFree, ArenaAggregator.DefragBudget budget) {
        long freeLength = biggestFree.getLength();
        long freeEnd = biggestFree.getEnd();
        long freeOffset = biggestFree.getOffset();

        long totalMoveLength = 0;
        var toMove = biggestFree.getNext();
        var destinationPrev = biggestFree.getPrev();
        var ownersToNotify = new ReferenceOpenHashSet<RegionAllocatorHandle>();
        while (toMove != null && !toMove.isFree()) {
            var newTotalMoveLength = totalMoveLength + toMove.getLength();
            if (newTotalMoveLength > freeLength || totalMoveLength != 0 && budget.elementCopyExceedsBudget(newTotalMoveLength)) {
                break;
            }

            // this segment does still fit, add it
            toMove.setOffset(freeOffset + totalMoveLength);
            totalMoveLength = newTotalMoveLength;
            ownersToNotify.add(toMove.getOwner());

            // perform linkages with prev
            if (destinationPrev == null) {
                // moving to head
                this.head = toMove;
            } else {
                destinationPrev.setNext(toMove);
            }
            toMove.setPrev(destinationPrev);

            // get the next segment to check
            destinationPrev = toMove;
            toMove = toMove.getNext();
        }
        // toMove is now the first segment that doesn't fit, or null, or free

        // if there's anything small enough to move
        if (totalMoveLength > 0) {
            // execute the copy of the continuous segments
            long bytes = totalMoveLength * this.stride;
            commands.copyBufferSubData(this.arenaBuffer, this.arenaBuffer,
                    freeEnd * this.stride,
                    freeOffset * this.stride,
                    bytes
            );
            budget.consumeElementCopy(totalMoveLength, bytes);

            // fix linkages of the last moved segment to the free segment
            destinationPrev.setNext(biggestFree);
            biggestFree.setPrev(destinationPrev);

            // adjust the free segment
            this.removeFreeSegment(biggestFree);
            biggestFree.setOffset(freeOffset + totalMoveLength);

            // check for merging with next free segment
            if (toMove != null && toMove.isFree()) {
                this.removeFreeSegment(toMove);
                biggestFree.setLength(biggestFree.getLength() + toMove.getLength());
                biggestFree.setNext(toMove.getNext());
                if (toMove.getNext() != null) {
                    toMove.getNext().setPrev(biggestFree);
                }
            } else {
                biggestFree.setNext(toMove);
                if (toMove != null) {
                    toMove.setPrev(biggestFree);
                }
            }

            this.addFreeSegment(biggestFree);

            for (var owner : ownersToNotify) {
                owner.notifyBufferChanged(commands);
            }

            return true;
        }

        return false;
    }

    // note that there is no this.tail
    private boolean defragmentLeftwards(CommandList commands, GlBufferSegment biggestFree, ArenaAggregator.DefragBudget budget) {
        long freeLength = biggestFree.getLength();
        long freeEnd = biggestFree.getEnd();
        long freeOffset = biggestFree.getOffset();

        long totalMoveLength = 0;
        var toMove = biggestFree.getPrev();
        var destinationNext = biggestFree.getNext();
        var ownersToNotify = new ReferenceOpenHashSet<RegionAllocatorHandle>();
        while (toMove != this.head && !toMove.isFree()) {
            var newTotalMoveLength = totalMoveLength + toMove.getLength();
            if (newTotalMoveLength > freeLength || totalMoveLength != 0 && budget.elementCopyExceedsBudget(newTotalMoveLength)) {
                break;
            }

            // this segment does still fit, add it
            totalMoveLength = newTotalMoveLength;
            toMove.setOffset(freeEnd - totalMoveLength);
            ownersToNotify.add(toMove.getOwner());

            // perform linkages with next
            if (destinationNext != null) {
                destinationNext.setPrev(toMove);
            }
            toMove.setNext(destinationNext);

            // get the next segment to check
            destinationNext = toMove;
            toMove = toMove.getPrev();
        }
        // toMove is now the first segment that doesn't fit, or null, or free

        // if there's anything small enough to move
        if (totalMoveLength > 0) {
            // execute the copy of the continuous segments
            long bytes = totalMoveLength * this.stride;
            commands.copyBufferSubData(this.arenaBuffer, this.arenaBuffer,
                    (freeOffset - totalMoveLength) * this.stride,
                    (freeEnd - totalMoveLength) * this.stride,
                    bytes
            );
            budget.consumeElementCopy(totalMoveLength, bytes);

            // fix linkages of the last moved segment to the free segment
            destinationNext.setPrev(biggestFree);
            biggestFree.setNext(destinationNext);

            // adjust the free segment
            this.removeFreeSegment(biggestFree);

            // TODO: in weird rare cases this results in a negative offset, why?
            // run with asserts enabled in mangrove forest: the overlapping segments are probably the cause
            if (freeOffset < totalMoveLength) {
                throw new IllegalStateException("Invalid segments resulted in negative offset during defragmentation");
            }
            biggestFree.setOffset(freeOffset - totalMoveLength);

            // check for merging with prev free segment
            if (toMove != null && toMove.isFree()) {
                this.removeFreeSegment(toMove);
                biggestFree.setOffset(toMove.getOffset());
                biggestFree.setLength(freeLength + toMove.getLength());
                biggestFree.setPrev(toMove.getPrev());
                if (toMove.getPrev() != null) {
                    toMove.getPrev().setNext(biggestFree);
                } else {
                    this.head = biggestFree;
                }
            } else {
                biggestFree.setPrev(toMove);
                if (toMove != null) {
                    toMove.setNext(biggestFree);
                } else {
                    this.head = biggestFree;
                }
            }

            this.addFreeSegment(biggestFree);

            for (var owner : ownersToNotify) {
                owner.notifyBufferChanged(commands);
            }

            return true;
        }

        return false;
    }

    @Override
    GlBufferSegment takeFree(long size) {
        return this.freeSegmentsByLength.removeFirstFitting(size);
    }

    GlBufferSegment alloc(long size, RegionAllocatorHandle owner) {
        this.checkAssertions();

        GlBufferSegment free = this.takeFree(size);

        if (free == null) {
            return null;
        }

        GlBufferSegment result;

        // exact fit
        if (free.getLength() == size) {
            free.setOwner(owner);

            result = free;
        }
        // free space is larger than requested, return new segment at end of free space
        else {
            result = new GlBufferSegment(this, owner, free.getEnd() - size, size);
            result.setNext(free.getNext());
            result.setPrev(free);

            if (result.getNext() != null) {
                result.getNext().setPrev(result);
            }

            free.setLength(free.getLength() - size);
            free.setNext(result);

            this.addFreeSegment(free);
        }

        this.updateUsed(result.getLength(), owner);
        this.checkAssertions();

        return result;
    }

    @Override
    public void free(GlBufferSegment entry) {
        if (entry.isFree()) {
            throw new IllegalStateException("Already freed");
        }

        var owner = entry.getOwner();
        entry.setFree();

        this.updateUsed(-entry.getLength(), owner);

        GlBufferSegment next = entry.getNext();
        boolean nextFree = next != null && next.isFree();
        GlBufferSegment prev = entry.getPrev();
        boolean prevFree = prev != null && prev.isFree();

        // both free, merge with both
        if (nextFree && prevFree) {
            this.removeFreeSegment(prev);
            this.removeFreeSegment(next);
            prev.setLength(prev.getLength() + entry.getLength() + next.getLength());
            prev.setNext(next.getNext());
            if (next.getNext() != null) {
                next.getNext().setPrev(prev);
            }
            this.addFreeSegment(prev);
        } else if (nextFree) {
            this.removeFreeSegment(next);
            entry.setLength(entry.getLength() + next.getLength());
            entry.setNext(next.getNext());
            if (next.getNext() != null) {
                next.getNext().setPrev(entry);
            }
            this.addFreeSegment(entry);
        } else if (prevFree) {
            this.removeFreeSegment(prev);
            prev.setLength(prev.getLength() + entry.getLength());
            prev.setNext(entry.getNext());
            if (entry.getNext() != null) {
                entry.getNext().setPrev(prev);
            }
            this.addFreeSegment(prev);
        } else {
            this.addFreeSegment(entry);
        }

        this.checkAssertions();
    }

    @Override
    public void renderDebugMap(GuiGraphics graphics, int x, int y, int drawWidth, int drawHeight) {
        super.renderDebugMap(graphics, x, y, drawWidth, drawHeight);

        // render measure of fragmentation degree and copies performed per frame
        float fragmentationDegree = this.calculateFragmentationDegree(null);
        int defragmentationSteps = calculateDefragmentationSteps(fragmentationDegree);
        int barLength = (int) (drawHeight * fragmentationDegree);
        var thickness = 3;
        graphics.fill(x, y, x + thickness, y + barLength, 0xCFFFFFFF);
        graphics.drawString(Minecraft.getInstance().font, Integer.toString(defragmentationSteps), x, y, 0xFFFFFFFF);
    }
}
