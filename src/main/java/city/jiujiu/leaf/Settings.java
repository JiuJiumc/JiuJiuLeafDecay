package city.jiujiu.leaf;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record Settings(boolean enabled, boolean onBreak, boolean onPhysics, int delay,
                       int radius, int budget, int maxChunks, int maxJobs, int timeoutSeconds,
                       Set<String> allowedWorlds, Set<String> deniedWorlds,
                       Set<Material> excluded, String permission, Map<String, String> messages) {
    public static Settings read(YamlConfiguration c) {
        for (String key : java.util.List.of("enabled", "trigger-on-break", "trigger-on-physics")) {
            if (c.contains(key) && !(c.get(key) instanceof Boolean))
                throw new IllegalArgumentException(key + " 必须是 true 或 false");
        }
        for (String key : java.util.List.of("enabled-worlds", "disabled-worlds", "excluded-leaves")) {
            if (c.contains(key) && (!(c.get(key) instanceof java.util.List<?> list)
                    || list.stream().anyMatch(value -> !(value instanceof String))))
                throw new IllegalArgumentException(key + " 必须是字符串列表，例如 [] 或 [world]");
        }
        Set<Material> excluded = c.getStringList("excluded-leaves").stream().map(name -> {
            Material m = Material.matchMaterial(name);
            if (m == null || !m.name().endsWith("_LEAVES"))
                throw new IllegalArgumentException("excluded-leaves 中不是树叶材质：" + name);
            return m;
        }).collect(Collectors.toUnmodifiableSet());
        Map<String, String> messages = new HashMap<>();
        var section = c.getConfigurationSection("messages");
        if (section != null) section.getKeys(false).forEach(key -> messages.put(key, section.getString(key, "")));
        String permission = c.getString("admin-permission", "jiujiuleafdecay.admin").trim();
        if (permission.isEmpty()) throw new IllegalArgumentException("admin-permission 不能为空");
        return new Settings(c.getBoolean("enabled", true), c.getBoolean("trigger-on-break", true),
                c.getBoolean("trigger-on-physics", true), number(c, "delay-ticks", 10, 8, 200),
                number(c, "scan-radius", 7, 1, 12), number(c, "checks-per-chunk-per-tick", 64, 8, 256),
                number(c, "max-active-chunks", 32, 1, 128), number(c, "max-jobs-per-chunk", 32, 4, 128),
                number(c, "queue-timeout-seconds", 30, 5, 120),
                Set.copyOf(c.getStringList("enabled-worlds")), Set.copyOf(c.getStringList("disabled-worlds")),
                excluded, permission, Map.copyOf(messages));
    }
    private static int number(YamlConfiguration c, String key, int fallback, int min, int max) {
        Object raw = c.get(key, fallback);
        if (!(raw instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < min || n.intValue() > max)
            throw new IllegalArgumentException(key + " 必须是 " + min + "～" + max + " 的整数");
        return n.intValue();
    }
    public boolean accepts(String world) {
        return enabled && !deniedWorlds.contains(world) && (allowedWorlds.isEmpty() || allowedWorlds.contains(world));
    }
}
