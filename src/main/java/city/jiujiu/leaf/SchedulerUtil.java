package city.jiujiu.leaf;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.concurrent.TimeUnit;

final class SchedulerUtil implements RegionTasks {
    private static volatile Boolean detectedFolia;
    private final Plugin plugin;
    private final boolean folia;
    SchedulerUtil(Plugin plugin) {
        this.plugin = plugin;
        if (detectedFolia == null) {
            try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); detectedFolia = true; }
            catch (ClassNotFoundException ignored) { detectedFolia = false; }
        }
        this.folia = detectedFolia;
    }
    @Override public Runnable later(World world, int cx, int cz, Runnable action) {
        if (folia) {
            var task = Bukkit.getRegionScheduler().runDelayed(plugin, world, cx, cz, t -> action.run(), 1L);
            return task::cancel;
        }
        var task = Bukkit.getScheduler().runTaskLater(plugin, action, 1L);
        return task::cancel;
    }
    @Override public boolean ownsNeighborhood(World world, int cx, int cz) {
        return folia ? Bukkit.isOwnedByCurrentRegion(world, cx, cz, 1) : Bukkit.isPrimaryThread();
    }
    void async(Runnable action) {
        if (folia) Bukkit.getAsyncScheduler().runNow(plugin, t -> action.run());
        else Bukkit.getScheduler().runTaskAsynchronously(plugin, action);
    }
    void global(Runnable action) {
        if (folia) Bukkit.getGlobalRegionScheduler().run(plugin, t -> action.run());
        else Bukkit.getScheduler().runTask(plugin, action);
    }
    void reply(Player player, Runnable action) {
        if (folia) player.getScheduler().runDelayed(plugin, t -> action.run(), null, 1L);
        else Bukkit.getScheduler().runTaskLater(plugin, action, 1L);
    }
    void maintenance(Runnable action) {
        if (folia) Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> action.run(), 5, 5, TimeUnit.SECONDS);
        else Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, action, 100L, 100L);
    }
    void cancelAll() {
        if (!folia) Bukkit.getScheduler().cancelTasks(plugin);
        if (folia) {
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        }
    }
}
