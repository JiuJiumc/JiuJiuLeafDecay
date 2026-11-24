package city.jiujiu.leaf;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;

/** Only immutable coordinates cross regions. No scan ever loads a chunk. */
final class DecayEngine implements AutoCloseable {
    private record ChunkKey(UUID world, int x, int z) {}
    private record Origin(int x, int y, int z, int radius) {}
    private record Point(int x, int y, int z) {}
    record Stats(int chunks, int jobs, long checks, long decayed, long overflow) {}
    private static final class Scan {
        final Origin origin;
        final long due;
        final int minX, maxX, minY, maxY, minZ, maxZ;
        int x, y, z;
        boolean done;
        Scan(Origin o, long due, int cx, int cz, int bottom, int top) {
            this.origin = o; this.due = due;
            minX = Math.max(o.x - o.radius, cx << 4); maxX = Math.min(o.x + o.radius, (cx << 4) + 15);
            minZ = Math.max(o.z - o.radius, cz << 4); maxZ = Math.min(o.z + o.radius, (cz << 4) + 15);
            minY = Math.max(o.y - o.radius, bottom); maxY = Math.min(o.y + o.radius, top - 1);
            x = minX; y = minY; z = minZ;
            done = minY > maxY;
        }
        Point next() {
            Point p = new Point(x, y, z);
            if (++x > maxX) { x = minX; if (++z > maxZ) { z = minZ; if (++y > maxY) done = true; } }
            return p;
        }
        boolean contains(Point p) {
            return Math.abs(p.x - origin.x) + Math.abs(p.y - origin.y) + Math.abs(p.z - origin.z) <= origin.radius;
        }
    }
    private static final class Bucket {
        final World world;
        final ChunkKey key;
        final ConcurrentLinkedDeque<Scan> scans = new ConcurrentLinkedDeque<>();
        final Set<Origin> origins = ConcurrentHashMap.newKeySet();
        final long created = System.nanoTime();
        Runnable cancel = () -> {};
        long tick;
        Bucket(World world, ChunkKey key) { this.world = world; this.key = key; }
    }
    private final Settings settings;
    private final RegionTasks tasks;
    private final Object queueLock = new Object();
    private final ConcurrentHashMap<ChunkKey, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongAdder checks = new LongAdder(), decayed = new LongAdder(), overflow = new LongAdder();
    private volatile boolean closed;

    DecayEngine(Settings settings, RegionTasks tasks) { this.settings = settings; this.tasks = tasks; }

    // Called from an event in the origin region. World metadata is read here, not in another region.
    void submit(Block origin, int radius) {
        if (closed || !settings.accepts(origin.getWorld().getName())) return;
        World world = origin.getWorld();
        int x = origin.getX(), y = origin.getY(), z = origin.getZ();
        UUID id = world.getUID();
        int bottom = world.getMinHeight(), top = world.getMaxHeight();
        Origin job = new Origin(x, y, z, radius);
        synchronized (queueLock) {
            if (closed) return;
            for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
                for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) {
                    ChunkKey key = new ChunkKey(id, cx, cz);
                    Bucket bucket = buckets.get(key);
                    boolean fresh = bucket == null;
                    if (fresh) {
                        if (buckets.size() >= settings.maxChunks()) { overflow.increment(); continue; }
                        bucket = new Bucket(world, key);
                        buckets.put(key, bucket);
                    }
                    if (bucket.origins.contains(job)) continue;
                    if (bucket.origins.size() >= settings.maxJobs()) { overflow.increment(); continue; }
                    bucket.origins.add(job);
                    bucket.scans.addLast(new Scan(job, bucket.tick + settings.delay(), cx, cz, bottom, top));
                    if (fresh) schedule(bucket);
                }
            }
        }
    }

    // Called under queueLock; dispatch only schedules and must never execute synchronously.
    private void schedule(Bucket bucket) {
        try { bucket.cancel = tasks.later(bucket.world, bucket.key.x, bucket.key.z, () -> pulse(bucket)); }
        catch (RuntimeException e) { buckets.remove(bucket.key, bucket); bucket.scans.clear(); bucket.origins.clear(); throw e; }
    }

    private void pulse(Bucket bucket) {
        if (closed) return;
        // Buffer ownership is checked before native randomTick, which may update neighboring blocks.
        if (!tasks.ownsNeighborhood(bucket.world, bucket.key.x, bucket.key.z)
                || !bucket.world.isChunkLoaded(bucket.key.x, bucket.key.z)) {
            discard(bucket); return;
        }
        List<Point> batch = new ArrayList<>(settings.budget());
        synchronized (queueLock) {
            if (closed || buckets.get(bucket.key) != bucket) return;
            bucket.tick++;
            int budget = settings.budget();
            while (budget > 0 && !bucket.scans.isEmpty()) {
                Scan scan = bucket.scans.peekFirst();
                if (scan.due > bucket.tick) break;
                if (scan.done) { bucket.scans.removeFirst(); bucket.origins.remove(scan.origin); continue; }
                Point p = scan.next();
                budget--; // Even positions outside the sphere consume budget: CPU work is bounded.
                if (scan.contains(p)) batch.add(p);
                if (scan.done) { bucket.scans.removeFirst(); bucket.origins.remove(scan.origin); }
            }
        }
        try {
            for (Point p : batch) {
                if (closed) return;
                Block block = bucket.world.getBlockAt(p.x, p.y, p.z);
                checks.increment();
                if (!(block.getBlockData() instanceof Leaves leaves) || leaves.isPersistent()
                        || leaves.getDistance() != leaves.getMaximumDistance()
                        || settings.excluded().contains(block.getType())) continue;
                // Native random tick fires LeavesDecayEvent and uses the server's loot/game rules.
                block.randomTick();
                if (!(block.getBlockData() instanceof Leaves)) decayed.increment();
            }
        } finally {
            synchronized (queueLock) {
                if (!closed && buckets.get(bucket.key) == bucket) {
                    if (bucket.scans.isEmpty()) buckets.remove(bucket.key, bucket);
                    else schedule(bucket);
                }
            }
        }
    }
    private void discard(Bucket bucket) {
        synchronized (queueLock) {
            buckets.remove(bucket.key, bucket); bucket.cancel.run(); bucket.scans.clear(); bucket.origins.clear();
        }
    }
    void unload(UUID world, int cx, int cz) {
        synchronized (queueLock) {
            Bucket bucket = buckets.get(new ChunkKey(world, cx, cz));
            if (bucket != null) discard(bucket);
        }
    }
    // Async maintenance touches only queue metadata and scheduler cancellation handles, never World state.
    void expire(long now) {
        synchronized (queueLock) {
            long maxAge = settings.timeoutSeconds() * 1_000_000_000L;
            for (Bucket bucket : buckets.values()) {
                if (now - bucket.created >= maxAge) {
                    overflow.add(bucket.origins.size());
                    discard(bucket);
                }
            }
        }
    }
    Stats stats() {
        synchronized (queueLock) {
            return new Stats(buckets.size(), buckets.values().stream().mapToInt(b -> b.origins.size()).sum(),
                    checks.sum(), decayed.sum(), overflow.sum());
        }
    }
    @Override public void close() {
        synchronized (queueLock) {
            closed = true;
            buckets.values().forEach(b -> { b.cancel.run(); b.scans.clear(); b.origins.clear(); });
            buckets.clear();
        }
    }
}
