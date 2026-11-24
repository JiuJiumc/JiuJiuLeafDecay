package city.jiujiu.leaf;

import org.bukkit.ChatColor;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class JiuJiuLeafDecay extends JavaPlugin implements Listener {
    private record RuntimeState(Settings settings, DecayEngine engine) {}
    private volatile RuntimeState state;
    private volatile boolean running;
    private final AtomicBoolean reloading = new AtomicBoolean();
    private SchedulerUtil tasks;

    @Override public void onEnable() {
        tasks = new SchedulerUtil(this);
        saveDefaultConfig();
        try {
            Settings settings = readSettings();
            state = new RuntimeState(settings, new DecayEngine(settings, tasks));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "配置无效，快速落叶未启用。", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        running = true;
        tasks.maintenance(() -> {
            RuntimeState current = state;
            if (running && current != null) current.engine.expire(System.nanoTime());
        });
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("已启用分区限速落叶；永久树叶保护始终开启。目标：Mizuki/Folia 26.2，Java 25。");
    }
    private Settings readSettings() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(new File(getDataFolder(), "config.yml"));
        try (var input = getResource("config.yml")) {
            if (input == null) throw new IllegalStateException("缺少内置配置");
            config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8)));
        }
        return Settings.read(config);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        RuntimeState current = state;
        if (!running || current == null || !current.settings.onBreak()) return;
        Block block = event.getBlock();
        if (Tag.LOGS.isTagged(block.getType()) || block.getBlockData() instanceof Leaves)
            current.engine.submit(block, current.settings.radius());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        RuntimeState current = state;
        if (!running || current == null || !current.settings.onPhysics()) return;
        Block block = event.getBlock();
        if (block.getBlockData() instanceof Leaves leaves && !leaves.isPersistent())
            current.engine.submit(block, 0);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent event) {
        RuntimeState current = state;
        if (!running || current == null || !current.settings.onPhysics()) return;
        current.engine.submit(event.getBlock(), 1);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        RuntimeState current = state;
        if (!running || current == null) return;
        var chunk = event.getChunk();
        current.engine.unload(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        RuntimeState current = state;
        if (current == null || !running) return true;
        if (!sender.hasPermission(current.settings.permission())) { message(sender, current, "no-permission", Map.of()); return true; }
        if (args.length == 0 || args.length == 1 && args[0].equalsIgnoreCase("status")) {
            DecayEngine.Stats s = current.engine.stats();
            message(sender, current, "status", Map.of("enabled", String.valueOf(current.settings.enabled()),
                    "chunks", String.valueOf(s.chunks()), "jobs", String.valueOf(s.jobs()),
                    "checks", String.valueOf(s.checks()), "decayed", String.valueOf(s.decayed()),
                    "overflow", String.valueOf(s.overflow())));
        } else if (args.length == 1 && args[0].equalsIgnoreCase("reload")) reload(sender, current);
        else message(sender, current, "usage", Map.of());
        return true;
    }
    private void reload(CommandSender sender, RuntimeState previous) {
        if (!reloading.compareAndSet(false, true)) { message(sender, previous, "busy", Map.of()); return; }
        message(sender, previous, "reloading", Map.of());
        tasks.async(() -> {
            try {
                Settings replacement = readSettings();
                if (!running) { reloading.set(false); return; }
                tasks.global(() -> {
                    try {
                        if (!running) return;
                        previous.engine.close();
                        RuntimeState next = new RuntimeState(replacement, new DecayEngine(replacement, tasks));
                        state = next;
                        reply(sender, next, "reloaded");
                    } finally { reloading.set(false); }
                });
            } catch (Exception e) {
                reloading.set(false);
                if (running) {
                    getLogger().log(Level.WARNING, "配置重载失败，原配置保持有效。", e);
                    reply(sender, previous, "reload-failed");
                }
            }
        });
    }
    private void reply(CommandSender sender, RuntimeState current, String key) {
        if (!running) return;
        if (sender instanceof Player player) tasks.reply(player, () -> { if (running) message(sender, current, key, Map.of()); });
        else tasks.global(() -> { if (running) message(sender, current, key, Map.of()); });
    }
    private void message(CommandSender sender, RuntimeState current, String key, Map<String, String> replacements) {
        String text = current.settings.messages().getOrDefault(key, "");
        if (text.isEmpty()) return;
        for (var entry : replacements.entrySet()) text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        String prefix = current.settings.messages().getOrDefault("prefix", "");
        for (String line : text.split("\n")) sender.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + line));
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        RuntimeState current = state;
        if (current == null || !sender.hasPermission(current.settings.permission()) || args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("status", "reload").stream().filter(s -> s.startsWith(prefix)).toList();
    }
    @Override public void onDisable() {
        running = false;
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
        RuntimeState previous = state;
        if (previous != null) previous.engine.close();
        if (tasks != null) tasks.cancelAll();
        state = null;
        reloading.set(false);
    }
}
