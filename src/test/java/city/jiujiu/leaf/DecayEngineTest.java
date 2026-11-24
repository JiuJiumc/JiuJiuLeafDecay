package city.jiujiu.leaf;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class DecayEngineTest {
    record Pos(int x, int y, int z) {}
    static class FakeTasks implements RegionTasks {
        record Ticket(Runnable action, AtomicBoolean cancelled) {}
        final ConcurrentLinkedQueue<Ticket> queue = new ConcurrentLinkedQueue<>();
        boolean owned = true;
        @Override public Runnable later(World world, int cx, int cz, Runnable action) {
            Ticket t = new Ticket(action, new AtomicBoolean()); queue.add(t);
            return () -> t.cancelled.set(true);
        }
        @Override public boolean ownsNeighborhood(World world, int cx, int cz) { return owned; }
        void tick() {
            int count = queue.size();
            for (int i = 0; i < count; i++) {
                Ticket t = queue.poll();
                if (t != null && !t.cancelled.get()) t.action.run();
            }
        }
        void drain(int max) {
            for (int i = 0; i < max && !queue.isEmpty(); i++) tick();
            assertTrue(queue.isEmpty(), "任务必须在有界时间完成");
        }
    }
    World world;
    Block air;
    Map<Pos, Block> blocks;
    FakeTasks tasks;
    List<DecayEngine> engines;

    @BeforeEach void prepare() {
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        air = mock(Block.class);
        when(air.getBlockData()).thenReturn(mock(BlockData.class));
        blocks = new ConcurrentHashMap<>();
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(a ->
                blocks.getOrDefault(new Pos(a.getArgument(0), a.getArgument(1), a.getArgument(2)), air));
        tasks = new FakeTasks(); engines = new ArrayList<>();
    }
    private DecayEngine engine(String yaml) throws Exception {
        YamlConfiguration c = new YamlConfiguration(); c.loadFromString(yaml);
        DecayEngine e = new DecayEngine(Settings.read(c), tasks); engines.add(e); return e;
    }
    private Block block(int x, int y, int z, boolean persistent, int distance, boolean cancelled) {
        Block b = mock(Block.class); Leaves leaf = mock(Leaves.class);
        AtomicBoolean removed = new AtomicBoolean();
        when(b.getWorld()).thenReturn(world);
        when(b.getX()).thenReturn(x); when(b.getY()).thenReturn(y); when(b.getZ()).thenReturn(z);
        when(b.getType()).thenReturn(Material.OAK_LEAVES);
        when(leaf.isPersistent()).thenReturn(persistent);
        when(leaf.getDistance()).thenReturn(distance); when(leaf.getMaximumDistance()).thenReturn(7);
        when(b.getBlockData()).thenAnswer(a -> removed.get() ? air.getBlockData() : leaf);
        doAnswer(a -> { if (!cancelled) removed.set(true); return null; }).when(b).randomTick();
        blocks.put(new Pos(x, y, z), b); return b;
    }
    @Test void permanentDecorativeLeavesAreAlwaysRetained() throws Exception {
        DecayEngine e = engine(""); Block b = block(8, 80, 8, true, 7, false);
        e.submit(b, 0); tasks.drain(100);
        verify(b, never()).randomTick(); assertEquals(0, e.stats().decayed());
    }
    @Test void supportedNaturalLeavesAreRetained() throws Exception {
        DecayEngine e = engine(""); Block b = block(8, 80, 8, false, 6, false);
        e.submit(b, 0); tasks.drain(100); verify(b, never()).randomTick();
    }
    @Test void unsupportedNaturalLeavesUseNativeTickAfterDelay() throws Exception {
        DecayEngine e = engine(""); Block b = block(8, 80, 8, false, 7, false);
        e.submit(b, 0);
        for (int i = 0; i < 9; i++) tasks.tick();
        verify(b, never()).randomTick(); tasks.tick();
        verify(b, times(1)).randomTick(); assertEquals(1, e.stats().decayed()); assertEquals(0, e.stats().chunks());
    }
    @Test void nativeCancellationIsNotOverridden() throws Exception {
        DecayEngine e = engine(""); Block b = block(8, 80, 8, false, 7, true);
        e.submit(b, 0); tasks.drain(100);
        verify(b).randomTick(); verify(b, never()).setType(any()); assertEquals(0, e.stats().decayed());
    }
    @Test void replacementWithPersistentLeafDuringDelayIsRetained() throws Exception {
        DecayEngine e = engine(""); Block b = block(8, 80, 8, false, 7, false);
        e.submit(b, 0); Leaves replacement = mock(Leaves.class);
        when(replacement.isPersistent()).thenReturn(true); when(b.getBlockData()).thenReturn(replacement);
        tasks.drain(100); verify(b, never()).randomTick();
    }
    @Test void excludedLeafTypeIsRetained() throws Exception {
        DecayEngine e = engine("excluded-leaves: [OAK_LEAVES]"); Block b = block(8, 80, 8, false, 7, false);
        e.submit(b, 0); tasks.drain(100); verify(b, never()).randomTick();
    }
    @Test void duplicateSubmissionsDoNotDuplicateNativeDrops() throws Exception {
        DecayEngine e = engine(""); Block b = block(8, 80, 8, false, 7, false);
        for (int i = 0; i < 2000; i++) e.submit(b, 0);
        assertEquals(1, e.stats().jobs()); assertEquals(1, tasks.queue.size());
        tasks.drain(100); verify(b, times(1)).randomTick();
    }
    @Test void scanAndPhysicsOverlapCannotDuplicateDrops() throws Exception {
        DecayEngine e = engine(""); Block b = block(8, 80, 8, false, 7, false);
        e.submit(b, 1); e.submit(b, 0); tasks.drain(100); verify(b, times(1)).randomTick();
    }
    @Test void scanCrossesChunkAndNegativeCoordinateBoundaries() throws Exception {
        DecayEngine e = engine(""); Block origin = block(-1, 80, -1, true, 7, false);
        Block a = block(0, 80, -1, false, 7, false), b = block(-1, 80, 0, false, 7, false);
        e.submit(origin, 1); assertEquals(4, e.stats().chunks());
        tasks.drain(100); verify(a).randomTick(); verify(b).randomTick(); verify(origin, never()).randomTick();
    }
    @Test void scansRespectManhattanRangeAndBuildHeight() throws Exception {
        DecayEngine e = engine(""); Block origin = block(8, -64, 8, true, 7, false);
        Block outside = block(9, -63, 9, false, 7, false);
        e.submit(origin, 1); tasks.drain(100); verify(outside, never()).randomTick();
        verify(world, never()).getBlockAt(anyInt(), intThat(y -> y < -64 || y >= 320), anyInt());
    }
    @Test void workPerChunkPerTickIsBounded() throws Exception {
        DecayEngine e = engine("checks-per-chunk-per-tick: 8"); Block b = block(8, 80, 8, false, 7, true);
        e.submit(b, 3); long before = 0;
        for (int i = 0; i < 80; i++) {
            tasks.tick(); long after = e.stats().checks(); assertTrue(after - before <= 8); before = after;
        }
        tasks.drain(200); assertEquals(0, e.stats().jobs());
    }
    @Test void maxChunksAndJobsRemainBounded() throws Exception {
        DecayEngine e = engine("max-active-chunks: 1\nmax-jobs-per-chunk: 4");
        for (int i = 0; i < 30; i++) e.submit(block(i, 80, 8, false, 7, false), 0);
        assertEquals(1, e.stats().chunks()); assertEquals(4, e.stats().jobs()); assertTrue(e.stats().overflow() > 0);
        tasks.drain(100); assertEquals(0, e.stats().chunks());
    }
    @Test void disabledPluginAndWorldFiltersScheduleNothing() throws Exception {
        Block b = block(8, 80, 8, false, 7, false);
        for (String yaml : List.of("enabled: false", "enabled-worlds: [other]", "disabled-worlds: [world]",
                "enabled-worlds: [world]\ndisabled-worlds: [world]")) {
            DecayEngine e = engine(yaml); e.submit(b, 0); assertEquals(0, e.stats().chunks());
        }
        assertTrue(tasks.queue.isEmpty());
    }
    @Test void unloadedChunkIsNotReadOrLoaded() throws Exception {
        DecayEngine e = engine(""); Block b = block(8, 80, 8, false, 7, false);
        when(world.isChunkLoaded(0, 0)).thenReturn(false);
        e.submit(b, 0); tasks.drain(100);
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt()); verify(world, never()).getChunkAt(anyInt(), anyInt());
        assertEquals(0, e.stats().chunks());
    }
    @Test void foreignRegionIsNeverRead() throws Exception {
        DecayEngine e = engine(""); Block b = block(8, 80, 8, false, 7, false);
        tasks.owned = false; e.submit(b, 0); tasks.drain(100);
        verify(world, never()).isChunkLoaded(anyInt(), anyInt()); verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }
    @Test void unloadEventCancelsAndReleasesQueuedChunk() throws Exception {
        DecayEngine e = engine(""); Block b = block(8, 80, 8, false, 7, false);
        e.submit(b, 0); e.unload(world.getUID(), 0, 0);
        assertEquals(0, e.stats().chunks()); tasks.drain(100); verify(b, never()).randomTick();
    }
    @Test void stalledUnloadedRegionIsExpiredWithoutLiveWorldAccess() throws Exception {
        DecayEngine e = engine("queue-timeout-seconds: 5"); Block b = block(8, 80, 8, false, 7, false);
        e.submit(b, 0); clearInvocations(world);
        e.expire(System.nanoTime() + 6_000_000_000L);
        assertEquals(0, e.stats().chunks()); assertEquals(1, e.stats().overflow());
        verifyNoInteractions(world); tasks.drain(100); verify(b, never()).randomTick();
    }
    @Test void closeIsIdempotentAndPreventsOldCallbacksAndNewSubmissions() throws Exception {
        DecayEngine e = engine(""); Block b = block(8, 80, 8, false, 7, false);
        e.submit(b, 0); e.close(); e.close(); e.submit(b, 0);
        tasks.drain(100); assertEquals(0, e.stats().chunks()); verify(b, never()).randomTick();
    }
    @Test void simultaneousRegionSubmissionsDeduplicateWithoutLeaking() throws Exception {
        DecayEngine e = engine(""); Block b = block(8, 80, 8, false, 7, false);
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 1000; i++) pool.submit(() -> e.submit(b, 0));
        }
        assertEquals(1, e.stats().chunks()); assertEquals(1, e.stats().jobs()); assertEquals(1, tasks.queue.size());
        tasks.drain(100); verify(b, times(1)).randomTick(); assertEquals(0, e.stats().chunks());
    }
    @Test void reentrantNativeDecaySubmissionsRemainBoundedAndComplete() throws Exception {
        DecayEngine e = engine(""); Block b = block(8, 80, 8, false, 7, false);
        AtomicInteger hits = new AtomicInteger();
        BlockData removedData = air.getBlockData();
        doAnswer(a -> {
            hits.incrementAndGet(); e.submit(b, 1);
            when(b.getBlockData()).thenReturn(removedData); return null;
        }).when(b).randomTick();
        e.submit(b, 0); tasks.drain(100);
        assertEquals(1, hits.get()); assertEquals(0, e.stats().jobs());
    }
    @Test void rejectedSchedulingDoesNotLeakBucket() throws Exception {
        RegionTasks failing = new RegionTasks() {
            public Runnable later(World w, int x, int z, Runnable r) { throw new IllegalStateException("disabled"); }
            public boolean ownsNeighborhood(World w, int x, int z) { return true; }
        };
        DecayEngine e = new DecayEngine(Settings.read(new YamlConfiguration()), failing);
        assertThrows(IllegalStateException.class, () -> e.submit(block(8, 80, 8, false, 7, false), 0));
        assertEquals(0, e.stats().chunks());
    }
    @Test void invalidNumericConfigurationIsRejected() throws Exception {
        for (String yaml : List.of("delay-ticks: 0", "scan-radius: 13", "max-active-chunks: 999999", "checks-per-chunk-per-tick: 1",
                "max-jobs-per-chunk: 0", "queue-timeout-seconds: 0", "scan-radius: 2.5", "scan-radius: wrong"))
            assertThrows(IllegalArgumentException.class, () -> engine(yaml), yaml);
    }
    @Test void invalidMaterialAndEmptyPermissionAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> engine("excluded-leaves: [NOT_A_LEAF]"));
        assertThrows(IllegalArgumentException.class, () -> engine("admin-permission: ''"));
    }
    @Test void invalidSwitchesAndWorldListsCannotAccidentallyEnableAllWorlds() {
        for (String yaml : List.of("enabled: yesplease", "trigger-on-break: 1", "trigger-on-physics: wrong",
                "enabled-worlds: world", "disabled-worlds: world", "excluded-leaves: OAK_LEAVES", "enabled-worlds: [123]"))
            assertThrows(IllegalArgumentException.class, () -> engine(yaml));
    }
    @Test void topBuildBoundaryAndNegativeChunksCompleteSafely() throws Exception {
        DecayEngine e = engine(""); Block b = block(-16,319,-16,false,7,false);
        e.submit(b, 7); tasks.drain(1000); verify(b, times(1)).randomTick();
        verify(world, never()).getBlockAt(anyInt(), intThat(y -> y < -64 || y >= 320), anyInt());
        assertEquals(0,e.stats().chunks());
    }
    @Test void shippedConfigParsesWithRealMultilineStatusAndPermanentProtection() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            var yaml = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
            Settings settings = Settings.read(yaml);
            assertTrue(settings.messages().get("status").contains("\n"));
            assertEquals(7, settings.radius()); assertEquals(10, settings.delay());
        }
    }
}
