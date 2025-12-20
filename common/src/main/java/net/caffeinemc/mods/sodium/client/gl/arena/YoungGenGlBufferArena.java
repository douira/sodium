package net.caffeinemc.mods.sodium.client.gl.arena;

import it.unimi.dsi.fastutil.longs.Long2ReferenceAVLTreeMap;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlMutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;

import java.util.ArrayList;
import java.util.List;

public class YoungGenGlBufferArena extends GlBufferArena {
    Long2ReferenceAVLTreeMap<RegionOwnedAllocator> ownersByUsed = new Long2ReferenceAVLTreeMap<>();

    YoungGenGlBufferArena(ArenaAggregator allocator, GlMutableBuffer initialBuffer, long capacity, int stride) {
        super(allocator, initialBuffer, capacity, stride);
    }

    @Override
    public void deleteSingleOwner(CommandList commands) {
        // don't delete on single-owner deletion
    }

    public void deleteShared(CommandList commands) {
        super.deleteSingleOwner(commands);
    }

    @Override
    void updateUsed(long deltaUsed, RegionOwnedAllocator owner) {
        super.updateUsed(deltaUsed, owner);
        this.ownersByUsed.remove(owner.used);
        owner.used += deltaUsed;
        owner.segmentCount += Long.signum(deltaUsed);
        this.ownersByUsed.put(owner.used, owner);
    }

    @Override
    void handleResizeUploads(CommandList commandList, RegionOwnedAllocator uploadingOwner, List<PendingUpload> queue, long totalOwnerUsageAfterUploads) {
        boolean relocatedUploadingOwner = false;

        // this needs to be a loop because the young gen buffer isn't guaranteed to be defragmented so we may need to evict more than one owner
        do {
            long biggestUsage = Long.MAX_VALUE; // max value to make sure the head map lookup works
            int biggestUsageSegmentCount = 0;
            RegionOwnedAllocator biggestUsageOwner = null;

            if (!relocatedUploadingOwner) {
                biggestUsage = totalOwnerUsageAfterUploads;
                biggestUsageSegmentCount = uploadingOwner.segmentCount + queue.size();
                biggestUsageOwner = uploadingOwner;
            }

            // get the biggest owner
            var entry = this.ownersByUsed.lastEntry();
            if (entry != null) {
                biggestUsageOwner = entry.getValue();
                biggestUsage = biggestUsageOwner.used;
                biggestUsageSegmentCount = biggestUsageOwner.segmentCount;
            }

            if (biggestUsageOwner == uploadingOwner) {
                relocatedUploadingOwner = true;
            }

            if (biggestUsageOwner == null) {
                throw new IllegalStateException("Could not find any owner to relocate for young gen arena resize");
            }

            // by construction, either the owner is the biggest one and is getting moved to its own arena, or another owner is bigger and this one will fit into this young gen arena

            // TODO: when estimating new capacity, take into account how full the section already is since a full section will not grow much anymore
            var newCapacity = GlBufferArena.estimateNewCapacity(biggestUsageSegmentCount, biggestUsageOwner.getFillFractionInv(), biggestUsage);
            transferToNewArena(commandList, biggestUsageOwner, newCapacity, biggestUsage);

            // try uploading again
            uploadingOwner.getBackingArena().tryUploads(commandList, uploadingOwner, queue);
        } while (!queue.isEmpty());
    }

    private void transferToNewArena(CommandList commandList, RegionOwnedAllocator owner, long newCapacity, long usage) {
        var newArena = this.parent.createArenaOfSizeAtLeast(commandList, newCapacity * this.stride, this.stride);
        owner.setBackingArena(newArena);
        this.ownersByUsed.remove(owner.used);

        // extract all segments owned by this owner, and reassign them to the new arena.
        // we also need to patch up the preceding and following segments to remove references to the extracted segments.
        var extractedSegments = this.extractAllSegmentsOwnedBy(owner, newArena);
        this.checkAssertions();

        // copy the extracted segments into the new arena
        newArena.receiveSegmentsFrom(commandList, extractedSegments, this.arenaBuffer, usage);

        // notify the owner that has been moved of the buffer change
        owner.notifyBufferChanged(commandList);
    }

    private List<GlBufferSegment> extractAllSegmentsOwnedBy(RegionOwnedAllocator owner, AllocatorBase newAllocator) {
        ArrayList<GlBufferSegment> extractedSegments = new ArrayList<>();
        long extractedTotalSize = 0;
        GlBufferSegment previousExtracted = null;
        GlBufferSegment current = this.head;

        // extract segments owned by the specified owner, patching links of the segments that are not extracted, and correcting the links on the extracted segments to point to each other
        while (current != null) {
            GlBufferSegment next = current.getNext();

            if (current.getOwner() == owner) {
                // patch links, offsets, and lengths of surrounding segments
                extractSegment(current, next);

                extractedSegments.add(current);
                current.setAllocator(newAllocator);
                extractedTotalSize += current.getLength();

                // link extracted segments together
                if (previousExtracted != null) {
                    previousExtracted.setNext(current);
                    current.setPrev(previousExtracted);
                } else {
                    current.setPrev(null);
                }

                previousExtracted = current;
            }
            current = next;
        }

        this.used -= extractedTotalSize;
        return extractedSegments;
    }

    private void extractSegment(GlBufferSegment current, GlBufferSegment next) {
        GlBufferSegment prev = current.getPrev();

        // current is head
        if (current == this.head) {
            // next is null
            if (next == null) {
                // new free head
                this.head = GlBufferSegment.createFreeSegment(this, 0, current.getLength());
            }
            // next is free, expand it
            else if (next.isFree()) {
                this.head = next;
                next.setOffset(0);
                next.setLength(next.getLength() + current.getLength());
            }
            // next is not free, create new free segment as head
            else {
                this.head = GlBufferSegment.createFreeSegment(this, 0, current.getLength());
                this.head.setNext(next);
                next.setPrev(this.head);
            }
        }
        // current is tail
        else if (next == null) {
            // current cannot be head since the other case handles that

            // prev is free, expand it
            if (prev.isFree()) {
                prev.setLength(prev.getLength() + current.getLength());
                prev.setNext(null);
            }
            // prev is not free, create new free segment as tail
            else {
                GlBufferSegment newFreeTail = GlBufferSegment.createFreeSegment(this, current.getOffset(), current.getLength());
                prev.setNext(newFreeTail);
                newFreeTail.setPrev(prev);
            }
        }
        // current is in the middle
        else {
            // both prev and next are free, expand prev
            if (prev.isFree() && next.isFree()) {
                prev.setLength(prev.getLength() + current.getLength() + next.getLength());
                prev.setNext(next.getNext());
                if (next.getNext() != null) {
                    next.getNext().setPrev(prev);
                }
            }
            // prev is free, expand it
            else if (prev.isFree()) {
                prev.setLength(prev.getLength() + current.getLength());
                prev.setNext(next);
                next.setPrev(prev);
            }
            // next is free, expand it
            else if (next.isFree()) {
                next.setOffset(current.getOffset());
                next.setLength(next.getLength() + current.getLength());
                next.setPrev(prev);
                prev.setNext(next);
            }
            // neither is free, create new free segment between them
            else {
                GlBufferSegment newFreeSegment = GlBufferSegment.createFreeSegment(this, current.getOffset(), current.getLength());
                prev.setNext(newFreeSegment);
                newFreeSegment.setPrev(prev);
                newFreeSegment.setNext(next);
                next.setPrev(newFreeSegment);
            }
        }
    }
}