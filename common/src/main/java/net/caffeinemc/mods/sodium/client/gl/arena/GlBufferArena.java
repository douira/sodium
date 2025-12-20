package net.caffeinemc.mods.sodium.client.gl.arena;

import net.caffeinemc.mods.sodium.client.gl.arena.staging.StagingBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlMutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

public class GlBufferArena implements AllocatorBase {
    static final boolean CHECK_ASSERTIONS = false;

    // how many segments we require to be present before we calculate an average size
    public static final int MIN_SEGMENTS_FOR_AVG = 16;
    // growth factor to use when we have too few segments present
    public static final float FEW_SEGMENTS_GROWTH_FACTOR = 1.5f;
    // factor to use when we are allocating with an expected size
    public static final float EXPECTED_SIZE_TARGET_FACTOR = 1.5f;

    final ArenaAggregator parent;
    final StagingBuffer stagingBuffer;
    GlMutableBuffer arenaBuffer;

    GlBufferSegment head;

    long capacity;
    long used;
    private int segmentCount;

    final int stride;

    GlBufferArena(ArenaAggregator parent, GlMutableBuffer initialBuffer, long capacity, int stride) {
        this.parent = parent;
        this.stagingBuffer = parent.stagingBuffer;
        this.arenaBuffer = initialBuffer;
        this.capacity = capacity;
        this.stride = stride;

        this.head = GlBufferSegment.createFreeSegment(this, 0, capacity);
    }

    private void resize(CommandList commandList, long newCapacity) {
        if (this.used > newCapacity) {
            throw new UnsupportedOperationException("New capacity must be larger than used size");
        }

        this.checkAssertions();

        long tail = newCapacity - this.used;

        List<GlBufferSegment> usedSegments = this.getUsedSegments();
        List<PendingBufferCopyCommand> pendingCopies = this.buildTransferList(usedSegments, tail);

        this.transferSegments(commandList, pendingCopies, newCapacity);

        finalizedCompactedSegments(tail, usedSegments);
    }

    private void finalizedCompactedSegments(long tail, List<GlBufferSegment> usedSegments) {
        this.head = GlBufferSegment.createFreeSegment(this, 0, tail);

        if (usedSegments.isEmpty()) {
            this.head.setNext(null);
        } else {
            this.head.setNext(usedSegments.getFirst());
            this.head.getNext().setPrev(this.head);
        }

        this.checkAssertions();
    }

    void receiveSegmentsFrom(CommandList commandList, List<GlBufferSegment> segments, GlMutableBuffer srcBufferObj, long requiredCapacity) {
        this.used = requiredCapacity;
        if (this.used > this.capacity) {
            throw new UnsupportedOperationException("New capacity must be larger than used size");
        }

        long tail = this.capacity - this.used;
        List<PendingBufferCopyCommand> pendingCopies = this.buildTransferList(segments, tail);

        long bufferSize = this.capacity * this.stride;
        if (bufferSize >= (1L << 32)) {
            throw new IllegalArgumentException("Maximum arena buffer size is 4 GiB");
        }

        executeCopyCommands(commandList, pendingCopies, srcBufferObj, this.arenaBuffer);

        finalizedCompactedSegments(tail, segments);
    }

    private List<PendingBufferCopyCommand> buildTransferList(List<GlBufferSegment> usedSegments, long base) {
        List<PendingBufferCopyCommand> pendingCopies = new ArrayList<>();
        PendingBufferCopyCommand currentCopyCommand = null;

        long writeOffset = base;

        for (int i = 0; i < usedSegments.size(); i++) {
            GlBufferSegment s = usedSegments.get(i);

            if (currentCopyCommand == null || currentCopyCommand.getReadOffset() + currentCopyCommand.getLength() != s.getOffset()) {
                if (currentCopyCommand != null) {
                    pendingCopies.add(currentCopyCommand);
                }

                currentCopyCommand = new PendingBufferCopyCommand(s.getOffset(), writeOffset, s.getLength());
            } else {
                currentCopyCommand.setLength(currentCopyCommand.getLength() + s.getLength());
            }

            s.setOffset(writeOffset);

            if (i + 1 < usedSegments.size()) {
                s.setNext(usedSegments.get(i + 1));
            } else {
                s.setNext(null);
            }

            if (i - 1 < 0) {
                s.setPrev(null);
            } else {
                s.setPrev(usedSegments.get(i - 1));
            }

            writeOffset += s.getLength();
        }

        if (currentCopyCommand != null) {
            pendingCopies.add(currentCopyCommand);
        }

        return pendingCopies;
    }

    private void transferSegments(CommandList commandList, Collection<PendingBufferCopyCommand> list, long capacity) {
        long bufferSize = capacity * this.stride;
        if (bufferSize >= (1L << 32)) {
            throw new IllegalArgumentException("Maximum arena buffer size is 4 GiB");
        }

        GlMutableBuffer srcBufferObj = this.arenaBuffer;
        GlMutableBuffer dstBufferObj = this.parent.getBufferOfSizeAtLeast(commandList, bufferSize);

        executeCopyCommands(commandList, list, srcBufferObj, dstBufferObj);

        this.parent.releaseBufferForReuse(commandList, srcBufferObj);

        this.arenaBuffer = dstBufferObj;

        // set the capacity using the size of the buffer since it may be larger than the expected capacity due to buffer reuse
        this.capacity = this.arenaBuffer.getSize() / this.stride;
    }

    private void executeCopyCommands(CommandList commandList, Collection<PendingBufferCopyCommand> list, GlMutableBuffer srcBufferObj, GlMutableBuffer dstBufferObj) {
        for (PendingBufferCopyCommand cmd : list) {
            commandList.copyBufferSubData(srcBufferObj, dstBufferObj,
                    cmd.getReadOffset() * this.stride,
                    cmd.getWriteOffset() * this.stride,
                    cmd.getLength() * this.stride);
        }
    }

    private ArrayList<GlBufferSegment> getUsedSegments() {
        ArrayList<GlBufferSegment> used = new ArrayList<>();
        GlBufferSegment seg = this.head;

        while (seg != null) {
            GlBufferSegment next = seg.getNext();

            if (!seg.isFree()) {
                used.add(seg);
            }

            seg = next;
        }

        return used;
    }

    @Override
    public long getDeviceUsedMemory() {
        return this.used * this.stride;
    }

    @Override
    public long getDeviceAllocatedMemory() {
        return this.capacity * this.stride;
    }

    void updateUsed(long deltaUsed, RegionOwnedAllocator owner) {
        this.used += deltaUsed;
        this.segmentCount += Long.signum(deltaUsed);
    }

    private GlBufferSegment alloc(int size, RegionOwnedAllocator owner) {
        GlBufferSegment a = this.findFree(size);

        if (a == null) {
            return null;
        }

        GlBufferSegment result;

        if (a.getLength() == size) {
            a.setOwner(owner);

            result = a;
        } else {
            GlBufferSegment b = new GlBufferSegment(this, owner, a.getEnd() - size, size);
            b.setNext(a.getNext());
            b.setPrev(a);

            if (b.getNext() != null) {
                b.getNext().setPrev(b);
            }

            a.setLength(a.getLength() - size);
            a.setNext(b);

            result = b;
        }

        this.updateUsed(result.getLength(), owner);
        this.checkAssertions();

        return result;
    }

    private GlBufferSegment findFree(int size) {
        GlBufferSegment entry = this.head;
        GlBufferSegment best = null;

        while (entry != null) {
            if (entry.isFree()) {
                if (entry.getLength() == size) {
                    return entry;
                } else if (entry.getLength() >= size) {
                    if (best == null || best.getLength() > entry.getLength()) {
                        best = entry;
                    }
                }
            }

            entry = entry.getNext();
        }

        return best;
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

        if (next != null && next.isFree()) {
            entry.mergeInto(next);
        }

        GlBufferSegment prev = entry.getPrev();

        if (prev != null && prev.isFree()) {
            prev.mergeInto(entry);
        }

        this.checkAssertions();
    }

    @Override
    public void deleteSingleOwner(CommandList commands) {
        commands.deleteBuffer(this.arenaBuffer);
    }

    @Override
    public boolean isEmpty() {
        return this.used <= 0;
    }

    @Override
    public GlBuffer getBufferObject() {
        return this.arenaBuffer;
    }

    public boolean upload(CommandList commandList, RegionOwnedAllocator owner, Stream<PendingUpload> stream) {
        // Record the buffer object before we start any work
        // If the arena needs to re-allocate a buffer, this will allow us to check and return an appropriate flag
        GlBuffer prevBuffer = this.arenaBuffer;

        // A linked list is used as we'll be randomly removing elements and want O(1) performance
        long totalUploadBytes = 0;
        List<PendingUpload> queue = new LinkedList<>();
        for (var upload : (Iterable<PendingUpload>) stream::iterator) {
            totalUploadBytes += upload.getDataBuffer().getLength();
            queue.add(upload);
        }

        // we need to calculate total owner usage here because uploads will change the owner usage and this way we can avoid recalculating the size of the queue
        var totalOwnerUsageAfterUploads = totalUploadBytes / this.stride + owner.used;

        // Try to upload all the data into free segments first,
        // but only attempt this if there is enough free space assuming no fragmentation
        if (totalUploadBytes < (this.capacity - this.used) * this.stride) {
            this.tryUploads(commandList, owner, queue);
        }

        // If we weren't able to upload some buffers, they will have been left behind in the queue
        if (!queue.isEmpty()) {
            handleResizeUploads(commandList, owner, queue, totalOwnerUsageAfterUploads);
        }

        return this.arenaBuffer != prevBuffer;
    }

    void handleResizeUploads(CommandList commandList, RegionOwnedAllocator owner, List<PendingUpload> queue, long totalUploadBytes) {
        // resize to the new estimated capacity
        this.resize(commandList, estimateNewCapacityAfterUpload(owner.getFillFractionInv(), queue));

        // Try again to upload any buffers that failed last time
        this.tryUploads(commandList, owner, queue);

        // If we still had failures, something has gone wrong
        if (!queue.isEmpty()) {
            throw new RuntimeException("Failed to upload all buffers");
        }
    }

    static long estimateNewCapacity(int newSegmentCount, float regionFillFractionInv, long requiredNewSize) {
        // the base estimation is to use a growth factor applied to the new required size
        long newCapacity;

        // use average segment size if we have enough segments to make it an accurate value
        if (newSegmentCount >= MIN_SEGMENTS_FOR_AVG) {
            newCapacity = (long) (estimateTotalSize(newSegmentCount, regionFillFractionInv, requiredNewSize) * EXPECTED_SIZE_TARGET_FACTOR);
        } else {
            newCapacity = (long) (requiredNewSize * FEW_SEGMENTS_GROWTH_FACTOR);
        }
        return newCapacity;
    }

    long estimateNewCapacityAfterUpload(float regionFillFractionInv, List<PendingUpload> queue) {
        // Calculate the amount of memory needed for the remaining uploads
        long requiredNewSize = getNewRequiredSize(queue);

        int newSegmentCount = this.segmentCount + queue.size();

        return estimateNewCapacity(newSegmentCount, regionFillFractionInv, requiredNewSize);
    }

    static float estimateTotalSize(int newSegmentCount, float regionFillFractionInv, long requiredTotalSize) {
        // find the average segment size after the remaining uploads are allocated
        long averageNewSegmentSize = (requiredTotalSize / newSegmentCount) + 1; // +1 to round up

        // use the average segment size to determine a new capacity, with some overshoot applied for safety
        var expectedSegmentCount = newSegmentCount * regionFillFractionInv;
        return averageNewSegmentSize * expectedSegmentCount;
    }

    long getNewRequiredSize(List<PendingUpload> queue) {
        long remainingUploadSize = 0;
        for (var upload : queue) {
            remainingUploadSize += upload.getDataBuffer().getLength();
        }

        // Convert size to elements by dividing by the stride.
        // This doesn't need a ceil since the upload buffers will be at least as big as required and have the same stride.
        long remainingElements = remainingUploadSize / this.stride;

        // Ask the arena to grow to accommodate the remaining uploads
        // This will force a re-allocation and compaction, which will leave us a continuous free segment
        // for the remaining uploads

        // Re-sizing the arena results in a compaction, so any free space in the arena will be
        // made into one contiguous segment, joined with the new segment of free space we're asking for
        return remainingElements + this.used;
    }

    void tryUploads(CommandList commandList, RegionOwnedAllocator owner, List<PendingUpload> queue) {
        queue.removeIf(upload -> this.tryUpload(commandList, owner, upload));
        this.stagingBuffer.flush(commandList);
    }

    private boolean tryUpload(CommandList commandList, RegionOwnedAllocator owner, PendingUpload upload) {
        ByteBuffer data = upload.getDataBuffer()
                .getDirectBuffer();

        int elementCount = data.remaining() / this.stride;

        GlBufferSegment dst = this.alloc(elementCount, owner);

        if (dst == null) {
            return false;
        }

        // Copy the data into our staging buffer, then copy it into the arena's buffer
        this.stagingBuffer.enqueueCopy(commandList, data, this.arenaBuffer, dst.getOffset() * this.stride);

        upload.setResult(dst);

        return true;
    }

    void checkAssertions() {
        if (CHECK_ASSERTIONS) {
            this.checkAssertions0();
        }
    }

    private void checkAssertions0() {
        GlBufferSegment seg = this.head;
        long used = 0;

        while (seg != null) {
            if (seg.getOffset() < 0) {
                throw new IllegalStateException("segment.start < 0: out of bounds");
            } else if (seg.getEnd() > this.capacity) {
                throw new IllegalStateException("segment.end > arena.capacity: out of bounds");
            }

            if (!seg.isFree()) {
                used += seg.getLength();
            }

            GlBufferSegment next = seg.getNext();

            if (next != null) {
                if (next.getOffset() < seg.getEnd()) {
                    throw new IllegalStateException("segment.next.start < segment.end: overlapping segments (corrupted)");
                } else if (next.getOffset() > seg.getEnd()) {
                    throw new IllegalStateException("segment.next.start > segment.end: not truly connected (sparsity error)");
                }

                if (next.isFree() && next.getNext() != null) {
                    if (next.getNext().isFree()) {
                        throw new IllegalStateException("segment.free && segment.next.free: not merged consecutive segments");
                    }
                }
            }

            GlBufferSegment prev = seg.getPrev();

            if (prev != null) {
                if (prev.getEnd() > seg.getOffset()) {
                    throw new IllegalStateException("segment.prev.end > segment.start: overlapping segments (corrupted)");
                } else if (prev.getEnd() < seg.getOffset()) {
                    throw new IllegalStateException("segment.prev.end < segment.start: not truly connected (sparsity error)");
                }

                if (prev.isFree() && prev.getPrev() != null) {
                    if (prev.getPrev().isFree()) {
                        throw new IllegalStateException("segment.free && segment.prev.free: not merged consecutive segments");
                    }
                }
            }

            seg = next;
        }

        if (this.used < 0) {
            throw new IllegalStateException("arena.used < 0: failure to track");
        } else if (this.used > this.capacity) {
            throw new IllegalStateException("arena.used > arena.capacity: failure to track");
        }

        if (this.used != used) {
            throw new IllegalStateException("arena.used is invalid");
        }
    }
}
