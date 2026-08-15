package it.belloworld.mercurygram;

import android.os.SystemClock;

import org.telegram.messenger.DispatchQueue2;
import org.telegram.messenger.FileLoadOperation;
import org.telegram.messenger.FileLoaderPriorityQueue;

import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * Lets low priority downloads start while a higher priority one is stalled.
 *
 * FileLoaderPriorityQueue holds back every priority 0 operation queued after
 * one with a higher priority until that one finishes. When the server keeps
 * answering a higher priority download with FLOOD_WAIT (seen for a few chat
 * avatars on a large account), or its connection dies, it never finishes, and
 * the low priority downloads behind it (thumbnails, the profile media grid)
 * never send a single request.
 *
 * A higher priority operation that has run for STALL_MS without receiving a
 * chunk stops holding them back. It keeps running; a slow but live download
 * that trips this only lets the low priority ones start sooner.
 *
 * One instance per queue, only used on that queue's worker thread.
 */
public final class MgDownloadStall {

    static final long STALL_MS = 10_000;

    private final WeakHashMap<FileLoadOperation, Long> firstSeenRunning = new WeakHashMap<>();
    private final Runnable recheck;

    public MgDownloadStall(Runnable recheck) {
        this.recheck = recheck;
    }

    /** False when the operation is a running, higher priority one that is stalled. */
    public boolean blocksLowPriority(FileLoadOperation op) {
        if (!isRunningAboveLow(op)) {
            firstSeenRunning.remove(op);
            return true;
        }
        long now = SystemClock.elapsedRealtime();
        return msUntilStall(firstSeen(op, now), op.mgLastChunkAt, now) >= 0;
    }

    /**
     * The queue only re-evaluates on add, remove or finish, so without a
     * timer a stall would not release the waiting low priority operations
     * until some unrelated event. Run this after the queue started its
     * operations, so the ones it just started are tracked too.
     */
    public void scheduleRecheck(DispatchQueue2 queue, ArrayList<FileLoadOperation> ops) {
        long now = SystemClock.elapsedRealtime();
        long delay = -1;
        boolean lowWaiting = false;
        for (int i = 0; i < ops.size(); i++) {
            FileLoadOperation op = ops.get(i);
            if (op.getPriority() <= FileLoaderPriorityQueue.PRIORITY_VALUE_LOW) {
                lowWaiting |= !op.wasStarted();
            } else if (isRunningAboveLow(op)) {
                long left = msUntilStall(firstSeen(op, now), op.mgLastChunkAt, now);
                if (left >= 0 && (delay < 0 || left < delay)) {
                    delay = left;
                }
            }
        }
        queue.cancelRunnable(recheck);
        if (lowWaiting && delay >= 0) {
            queue.postRunnable(recheck, delay + 1);
        }
    }

    /** Time left before the operation counts as stalled, negative once it does. */
    static long msUntilStall(long firstSeenRunning, long lastChunkAt, long now) {
        return STALL_MS - (now - Math.max(firstSeenRunning, lastChunkAt));
    }

    private static boolean isRunningAboveLow(FileLoadOperation op) {
        return op.wasStarted() && op.getPriority() > FileLoaderPriorityQueue.PRIORITY_VALUE_LOW;
    }

    private long firstSeen(FileLoadOperation op, long now) {
        Long first = firstSeenRunning.get(op);
        if (first == null) {
            firstSeenRunning.put(op, now);
            return now;
        }
        return first;
    }
}
