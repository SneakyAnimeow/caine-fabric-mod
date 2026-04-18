package com.dashie.caine.build;

import com.dashie.caine.CaineModClient;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Tracks build operations so CAINE can undo what it just built.
 * Stores snapshots of the area before building and generates undo commands.
 * <p>
 * Each build operation captures the "before" state of every block position
 * that will be modified. Undo restores those original blocks.
 */
public class BuildHistory {

    /**
     * A snapshot of blocks before a build operation.
     */
    public record BuildSnapshot(
            String description,
            long timestamp,
            List<BlockRecord> originalBlocks,
            BlockPos origin
    ) {
        public String summary() {
            long ageSeconds = (System.currentTimeMillis() - timestamp) / 1000;
            String age = ageSeconds < 60 ? ageSeconds + "s ago"
                    : ageSeconds < 3600 ? (ageSeconds / 60) + "m ago"
                    : (ageSeconds / 3600) + "h ago";
            return String.format("'%s' (%d blocks, %s) at (%d, %d, %d)",
                    description, originalBlocks.size(), age,
                    origin.getX(), origin.getY(), origin.getZ());
        }
    }

    /**
     * A single block's original state before modification.
     */
    public record BlockRecord(int x, int y, int z, String blockId) {}

    private static final int MAX_HISTORY = 10;
    private static final int MAX_BLOCKS_PER_SNAPSHOT = 50_000;

    private final ConcurrentLinkedDeque<BuildSnapshot> history = new ConcurrentLinkedDeque<>();

    /**
     * Captures the current state of blocks in a region before building.
     * Call this BEFORE executing any build commands.
     *
     * @param description What's being built (for display)
     * @param minX        Min X coordinate of the affected area
     * @param minY        Min Y coordinate
     * @param minZ        Min Z coordinate
     * @param maxX        Max X coordinate
     * @param maxY        Max Y coordinate
     * @param maxZ        Max Z coordinate
     * @return The snapshot, or null if capture failed
     */
    public BuildSnapshot captureArea(String description, int minX, int minY, int minZ,
                                      int maxX, int maxY, int maxZ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return null;
        ClientWorld world = client.world;

        // Ensure min < max
        int x1 = Math.min(minX, maxX), x2 = Math.max(minX, maxX);
        int y1 = Math.min(minY, maxY), y2 = Math.max(minY, maxY);
        int z1 = Math.min(minZ, maxZ), z2 = Math.max(minZ, maxZ);

        long volume = (long)(x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        if (volume > MAX_BLOCKS_PER_SNAPSHOT) {
            CaineModClient.LOGGER.warn("Build area too large for snapshot: {} blocks (max {})",
                    volume, MAX_BLOCKS_PER_SNAPSHOT);
            return null;
        }

        List<BlockRecord> blocks = new ArrayList<>();
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
                    blocks.add(new BlockRecord(x, y, z, blockId));
                }
            }
        }

        BlockPos origin = new BlockPos(x1, y1, z1);
        BuildSnapshot snapshot = new BuildSnapshot(description, System.currentTimeMillis(), blocks, origin);

        history.addFirst(snapshot);
        // Trim history
        while (history.size() > MAX_HISTORY) {
            history.removeLast();
        }

        CaineModClient.LOGGER.info("Captured build snapshot '{}': {} blocks at ({},{},{}) to ({},{},{})",
                description, blocks.size(), x1, y1, z1, x2, y2, z2);
        return snapshot;
    }

    /**
     * Captures a region around the player's current position.
     * Uses the given width/height/depth to determine the area starting at player pos + offset.
     */
    public BuildSnapshot captureAroundPlayer(String description, int width, int height, int depth) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return null;

        int px = (int) client.player.getX();
        int py = (int) client.player.getY();
        int pz = (int) client.player.getZ();

        // Build area starts slightly offset from player (matching build_structure pattern: ~1 ~0 ~1)
        return captureArea(description,
                px + 1, py, pz + 1,
                px + Math.max(width, 1), py + Math.max(height, 10), pz + Math.max(depth, 1));
    }

    /**
     * Generates undo commands for the most recent build.
     * Returns setblock commands that restore the original blocks.
     */
    public List<String> generateUndoCommands() {
        BuildSnapshot snapshot = history.peekFirst();
        if (snapshot == null) {
            CaineModClient.LOGGER.info("No builds to undo");
            return List.of();
        }
        return generateUndoCommands(snapshot);
    }

    /**
     * Generates undo commands for a specific snapshot.
     */
    private List<String> generateUndoCommands(BuildSnapshot snapshot) {
        List<String> commands = new ArrayList<>();

        // Group air blocks — they're most common in undo (clearing the structure)
        // Use /fill for large contiguous air regions, /setblock for individual blocks
        Map<String, List<BlockRecord>> byBlock = new HashMap<>();
        for (BlockRecord record : snapshot.originalBlocks) {
            byBlock.computeIfAbsent(record.blockId(), k -> new ArrayList<>()).add(record);
        }

        // First pass: handle air blocks with fill commands where possible
        List<BlockRecord> airBlocks = byBlock.getOrDefault("minecraft:air", List.of());
        if (airBlocks.size() > 10) {
            // For large air regions, use a single fill of the entire area then place non-air blocks
            BlockPos origin = snapshot.origin;
            int maxX = origin.getX(), maxY = origin.getY(), maxZ = origin.getZ();
            for (BlockRecord r : snapshot.originalBlocks) {
                maxX = Math.max(maxX, r.x());
                maxY = Math.max(maxY, r.y());
                maxZ = Math.max(maxZ, r.z());
            }
            commands.add(String.format("fill %d %d %d %d %d %d air",
                    origin.getX(), origin.getY(), origin.getZ(), maxX, maxY, maxZ));

            // Then place all non-air blocks back
            for (Map.Entry<String, List<BlockRecord>> entry : byBlock.entrySet()) {
                if (entry.getKey().equals("minecraft:air")) continue;
                for (BlockRecord r : entry.getValue()) {
                    commands.add(String.format("setblock %d %d %d %s", r.x(), r.y(), r.z(), entry.getKey()));
                }
            }
        } else {
            // Small build — just setblock each position
            for (BlockRecord r : snapshot.originalBlocks) {
                commands.add(String.format("setblock %d %d %d %s replace", r.x(), r.y(), r.z(), r.blockId()));
            }
        }

        return commands;
    }

    /**
     * Removes the most recent snapshot from history (after undoing it).
     */
    public BuildSnapshot popLatest() {
        return history.pollFirst();
    }

    /**
     * Gets the most recent build snapshot without removing it.
     */
    public BuildSnapshot peekLatest() {
        return history.peekFirst();
    }

    /**
     * Gets summaries of all stored builds for the prompt.
     */
    public List<String> getHistorySummaries() {
        List<String> summaries = new ArrayList<>();
        for (BuildSnapshot snapshot : history) {
            summaries.add(snapshot.summary());
        }
        return summaries;
    }

    /**
     * Returns the number of stored build snapshots.
     */
    public int size() {
        return history.size();
    }

    /**
     * Clears all build history.
     */
    public void clear() {
        history.clear();
    }
}
