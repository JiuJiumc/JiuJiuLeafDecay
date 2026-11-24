package city.jiujiu.leaf;

import org.bukkit.World;

interface RegionTasks {
    Runnable later(World world, int chunkX, int chunkZ, Runnable action);
    boolean ownsNeighborhood(World world, int chunkX, int chunkZ);
}
