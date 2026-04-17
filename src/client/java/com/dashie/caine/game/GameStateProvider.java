package com.dashie.caine.game;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class GameStateProvider {

    // Last position before teleporting — set by ActionExecutor
    private volatile String lastTeleportPosition = null;

    public void setLastTeleportPosition(double x, double y, double z, String dimension) {
        this.lastTeleportPosition = String.format("(%.0f, %.0f, %.0f) in %s", x, y, z, dimension);
    }

    public String getLastTeleportPosition() {
        return lastTeleportPosition;
    }

    public record PlayerInfo(String name, double x, double y, double z, float health) {
        public String formatted() {
            return String.format("%s at (%.0f, %.0f, %.0f) HP:%.0f", name, x, y, z, health);
        }
    }

    public Optional<ClientPlayerEntity> getPlayer() {
        return Optional.ofNullable(MinecraftClient.getInstance().player);
    }

    public Optional<ClientWorld> getWorld() {
        return Optional.ofNullable(MinecraftClient.getInstance().world);
    }

    public String getPlayerPosition() {
        return getPlayer()
                .map(p -> String.format("(%.0f, %.0f, %.0f)", p.getX(), p.getY(), p.getZ()))
                .orElse("unknown");
    }

    /**
     * Returns players currently in render distance with position info.
     */
    public List<PlayerInfo> getNearbyPlayers() {
        List<PlayerInfo> players = new ArrayList<>();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return players;

        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (player == client.player) continue;
            players.add(new PlayerInfo(
                    player.getName().getString(),
                    player.getX(), player.getY(), player.getZ(),
                    player.getHealth()));
        }
        return players;
    }

    /**
     * Returns all online player names from the tab list (includes those out of render distance).
     */
    public List<String> getOnlinePlayerNames() {
        List<String> names = new ArrayList<>();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) return names;

        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile() != null) {
                String name = entry.getProfile().getName();
                if (client.player != null && !name.equals(client.player.getName().getString())) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Finds a player entity by name in the current world (must be in render distance).
     */
    public Optional<AbstractClientPlayerEntity> findPlayer(String name) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return Optional.empty();

        return client.world.getPlayers().stream()
                .filter(p -> p.getName().getString().equalsIgnoreCase(name))
                .findFirst();
    }

    public String getHealth() {
        return getPlayer()
                .map(p -> String.format("%.0f/20", p.getHealth()))
                .orElse("?");
    }

    public String getHunger() {
        return getPlayer()
                .map(p -> String.valueOf(p.getHungerManager().getFoodLevel()))
                .orElse("?");
    }

    public String getDimension() {
        return getWorld()
                .map(w -> w.getRegistryKey().getValue().toString())
                .orElse("unknown");
    }

    public String getTimeOfDay() {
        return getWorld().map(w -> {
            long time = w.getTimeOfDay() % 24000;
            if (time < 6000) return "morning (" + time + ")";
            if (time < 12000) return "afternoon (" + time + ")";
            if (time < 18000) return "evening (" + time + ")";
            return "night (" + time + ")";
        }).orElse("unknown");
    }

    public String getHeldItem() {
        return getPlayer()
                .map(p -> p.getMainHandStack().isEmpty()
                        ? "empty hand"
                        : p.getMainHandStack().getItem().toString())
                .orElse("unknown");
    }

    public String getSelectedSlot() {
        return getPlayer()
                .map(p -> String.valueOf(p.getInventory().selectedSlot))
                .orElse("0");
    }

    /**
     * Returns a compact summary of the player's inventory (hotbar + notable items).
     */
    public String getInventoryString() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("=== YOUR INVENTORY ===\n");

        // Hotbar (slots 0-8) with selected indicator
        int selected = client.player.getInventory().selectedSlot;
        sb.append("Hotbar:\n");
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            String marker = (i == selected) ? " [SELECTED]" : "";
            if (stack.isEmpty()) {
                sb.append("  Slot ").append(i).append(": (empty)").append(marker).append("\n");
            } else {
                sb.append("  Slot ").append(i).append(": ").append(stack.getCount()).append("x ")
                        .append(stack.getName().getString()).append(marker).append("\n");
            }
        }

        // Main inventory (slots 9-35) - compact summary
        List<String> mainItems = new ArrayList<>();
        for (int i = 9; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                mainItems.add(stack.getCount() + "x " + stack.getName().getString());
            }
        }
        if (!mainItems.isEmpty()) {
            sb.append("Main inventory: ").append(String.join(", ", mainItems)).append("\n");
        } else {
            sb.append("Main inventory: (empty)\n");
        }

        // Offhand
        ItemStack offhand = client.player.getOffHandStack();
        if (!offhand.isEmpty()) {
            sb.append("Offhand: ").append(offhand.getCount()).append("x ").append(offhand.getName().getString()).append("\n");
        }

        // Armor
        List<String> armor = new ArrayList<>();
        for (ItemStack armorStack : client.player.getInventory().armor) {
            if (!armorStack.isEmpty()) {
                armor.add(armorStack.getName().getString());
            }
        }
        if (!armor.isEmpty()) {
            sb.append("Armor: ").append(String.join(", ", armor)).append("\n");
        }

        return sb.toString();
    }

    public String getBiome() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return "unknown";
        BlockPos pos = client.player.getBlockPos();
        return client.world.getBiome(pos).getKey()
                .map(k -> k.getValue().toString())
                .orElse("unknown");
    }

    /**
     * Assembles a complete game state summary for the AI prompt.
     */
    public String getGameStateString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== GAME STATE ===\n");
        sb.append("Your name: ").append(
                getPlayer().map(p -> p.getName().getString()).orElse("unknown")).append("\n");
        sb.append("Your position: ").append(getPlayerPosition()).append("\n");
        sb.append("Health: ").append(getHealth()).append("\n");
        sb.append("Hunger: ").append(getHunger()).append("\n");
        sb.append("Held item: ").append(getHeldItem()).append("\n");
        sb.append("Selected slot: ").append(getSelectedSlot()).append("\n");
        sb.append("Dimension: ").append(getDimension()).append("\n");
        sb.append("Time: ").append(getTimeOfDay()).append("\n");
        sb.append("Biome: ").append(getBiome()).append("\n");

        // Nearby players (in render distance with positions)
        List<PlayerInfo> nearby = getNearbyPlayers();
        if (!nearby.isEmpty()) {
            sb.append("Nearby players:\n");
            for (PlayerInfo p : nearby) {
                sb.append("  - ").append(p.formatted()).append("\n");
            }
        }

        // All online players from tab list
        List<String> allOnline = getOnlinePlayerNames();
        if (!allOnline.isEmpty()) {
            sb.append("All online players: ").append(String.join(", ", allOnline)).append("\n");
        } else {
            sb.append("No other players online\n");
        }

        // Inventory
        String inventory = getInventoryString();
        if (!inventory.isEmpty()) {
            sb.append("\n").append(inventory);
        }

        // Last position before teleporting
        if (lastTeleportPosition != null) {
            sb.append("Previous position (before last TP): ").append(lastTeleportPosition).append("\n");
        }

        // Surroundings — what CAINE can see
        sb.append("\n").append(getSurroundingsString());

        return sb.toString();
    }

    /**
     * Scans the environment around the player: block being looked at,
     * nearby entities (mobs, animals, items), and notable blocks in view.
     */
    public String getSurroundingsString() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("=== WHAT YOU SEE ===\n");

        // What block/entity the player is looking at (crosshair target)
        String lookingAt = getLookingAt(client);
        if (lookingAt != null) {
            sb.append("Looking at: ").append(lookingAt).append("\n");
        }

        // Nearby entities (mobs, animals, items) within 20 blocks
        List<String> entities = getNearbyEntities(client, 20.0);
        if (!entities.isEmpty()) {
            sb.append("Nearby entities:\n");
            for (String e : entities) {
                sb.append("  - ").append(e).append("\n");
            }
        }

        // Notable blocks in a small radius (chests, signs, spawners, etc.)
        List<String> blocks = getNotableBlocks(client, 8);
        if (!blocks.isEmpty()) {
            sb.append("Notable blocks nearby:\n");
            for (String b : blocks) {
                sb.append("  - ").append(b).append("\n");
            }
        }

        // Ground/surface info
        sb.append("Standing above: ").append(getBlockBelow(client)).append("\n");

        return sb.toString();
    }

    private String getLookingAt(MinecraftClient client) {
        if (client.player == null || client.world == null) return null;
        ClientPlayerEntity player = client.player;

        // Block raycast (5 block reach)
        Vec3d eyePos = player.getCameraPosVec(1.0f);
        Vec3d lookVec = player.getRotationVec(1.0f);
        Vec3d endPos = eyePos.add(lookVec.multiply(5.0));

        BlockHitResult blockHit = client.world.raycast(new RaycastContext(
                eyePos, endPos, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player));

        // Entity raycast
        Box searchBox = player.getBoundingBox().stretch(lookVec.multiply(5.0)).expand(1.0);
        EntityHitResult entityHit = ProjectileUtil.raycast(player, eyePos, endPos,
                searchBox, e -> !e.isSpectator() && e != player, 25.0);

        if (entityHit != null) {
            Entity entity = entityHit.getEntity();
            double dist = player.distanceTo(entity);
            return String.format("%s (%.0f blocks away)", entityName(entity), dist);
        }

        if (blockHit.getType() == HitResult.Type.BLOCK) {
            BlockState state = client.world.getBlockState(blockHit.getBlockPos());
            String blockName = state.getBlock().getName().getString();
            BlockPos pos = blockHit.getBlockPos();
            return String.format("%s at (%d, %d, %d)", blockName, pos.getX(), pos.getY(), pos.getZ());
        }

        return "nothing (looking at sky/far away)";
    }

    private List<String> getNearbyEntities(MinecraftClient client, double radius) {
        List<String> descriptions = new ArrayList<>();
        if (client.player == null || client.world == null) return descriptions;

        Box area = client.player.getBoundingBox().expand(radius);
        Map<String, Integer> entityCounts = new HashMap<>();
        List<String> uniqueEntities = new ArrayList<>();

        for (Entity entity : client.world.getOtherEntities(client.player, area)) {
            if (entity instanceof AbstractClientPlayerEntity) continue; // players handled separately

            String name = entityName(entity);
            entityCounts.merge(name, 1, Integer::sum);
        }

        for (Map.Entry<String, Integer> entry : entityCounts.entrySet()) {
            if (entry.getValue() > 1) {
                uniqueEntities.add(entry.getValue() + "x " + entry.getKey());
            } else {
                uniqueEntities.add(entry.getKey());
            }
        }

        // Limit to 10 most relevant entries
        return uniqueEntities.size() > 10 ? uniqueEntities.subList(0, 10) : uniqueEntities;
    }

    private String entityName(Entity entity) {
        String name = entity.getName().getString();
        if (entity instanceof MobEntity) {
            double dist = MinecraftClient.getInstance().player.distanceTo(entity);
            return String.format("%s (mob, %.0fb)", name, dist);
        }
        if (entity instanceof AnimalEntity) {
            return name + " (animal)";
        }
        if (entity instanceof ItemEntity item) {
            return item.getStack().getCount() + "x " + item.getStack().getName().getString() + " (item on ground)";
        }
        return name;
    }

    private List<String> getNotableBlocks(MinecraftClient client, int radius) {
        List<String> notable = new ArrayList<>();
        if (client.player == null || client.world == null) return notable;

        BlockPos center = client.player.getBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.add(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    String blockId = state.getBlock().toString();

                    // Check for interesting blocks
                    if (isNotableBlock(blockId)) {
                        String name = state.getBlock().getName().getString();
                        notable.add(String.format("%s at (%d, %d, %d)", name, pos.getX(), pos.getY(), pos.getZ()));
                        if (notable.size() >= 8) return notable;
                    }
                }
            }
        }
        return notable;
    }

    private boolean isNotableBlock(String blockId) {
        return blockId.contains("chest") || blockId.contains("sign") || blockId.contains("spawner")
                || blockId.contains("enchanting") || blockId.contains("anvil") || blockId.contains("beacon")
                || blockId.contains("brewing") || blockId.contains("furnace") || blockId.contains("crafting")
                || blockId.contains("bed") || blockId.contains("portal") || blockId.contains("end_portal")
                || blockId.contains("dragon_egg") || blockId.contains("jukebox") || blockId.contains("bell")
                || blockId.contains("campfire") || blockId.contains("lectern") || blockId.contains("barrel");
    }

    private String getBlockBelow(MinecraftClient client) {
        if (client.player == null || client.world == null) return "unknown";
        BlockPos below = client.player.getBlockPos().down();
        return client.world.getBlockState(below).getBlock().getName().getString();
    }

    // ======================== TERRAIN AWARENESS ========================

    /**
     * Generates a detailed terrain scan of the area around the player.
     * This gives CAINE a "picture" of the landscape: height map, block composition,
     * open spaces, and terrain features.
     *
     * @param radius Horizontal radius to scan (max 16)
     * @return Human-readable terrain description
     */
    public String getTerrainScan(int radius) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return "Cannot scan — not in world";
        radius = Math.min(radius, 16);

        ClientWorld world = client.world;
        BlockPos playerPos = client.player.getBlockPos();
        int px = playerPos.getX(), py = playerPos.getY(), pz = playerPos.getZ();

        StringBuilder sb = new StringBuilder();
        sb.append("=== TERRAIN SCAN (").append(radius * 2 + 1).append("x").append(radius * 2 + 1).append(" area) ===\n");
        sb.append("Center: (").append(px).append(", ").append(py).append(", ").append(pz).append(")\n");

        // 1. Height map — surface elevation relative to player
        int[][] heightMap = new int[radius * 2 + 1][radius * 2 + 1];
        Map<String, Integer> surfaceBlocks = new LinkedHashMap<>();
        int airCount = 0;
        int solidCount = 0;
        int waterCount = 0;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // Find surface: scan down from player + 20 to player - 20
                int surfaceY = py;
                boolean found = false;
                for (int dy = 20; dy >= -20; dy--) {
                    BlockPos checkPos = new BlockPos(px + dx, py + dy, pz + dz);
                    BlockState state = world.getBlockState(checkPos);
                    if (!state.isAir()) {
                        // Check if block above is air/fluid (this is the surface)
                        BlockState above = world.getBlockState(checkPos.up());
                        if (above.isAir() || !above.getFluidState().isEmpty()) {
                            surfaceY = py + dy;
                            found = true;
                            String blockName = state.getBlock().getName().getString();
                            surfaceBlocks.merge(blockName, 1, Integer::sum);
                            if (!state.getFluidState().isEmpty()) waterCount++;
                            break;
                        }
                    }
                }
                if (!found) {
                    surfaceY = py - 20;
                    airCount++;
                }

                heightMap[dx + radius][dz + radius] = surfaceY - py;
                minY = Math.min(minY, surfaceY);
                maxY = Math.max(maxY, surfaceY);
                solidCount++;
            }
        }

        // 2. Terrain shape summary
        int heightRange = maxY - minY;
        if (heightRange <= 2) {
            sb.append("Terrain: FLAT (").append(heightRange).append(" block variation)\n");
        } else if (heightRange <= 6) {
            sb.append("Terrain: GENTLE HILLS (").append(heightRange).append(" block variation)\n");
        } else if (heightRange <= 15) {
            sb.append("Terrain: HILLY (").append(heightRange).append(" block variation)\n");
        } else {
            sb.append("Terrain: MOUNTAINOUS/CLIFF (").append(heightRange).append(" block variation)\n");
        }

        // 3. Surface composition — top 5 blocks
        sb.append("Surface composition:\n");
        surfaceBlocks.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append(" blocks\n"));

        if (waterCount > 0) {
            sb.append("  Water: ").append(waterCount).append(" surface blocks\n");
        }

        // 4. Compact height map (North-South cross section and East-West)
        sb.append("Height profile (N→S through center, relative to you):\n  ");
        for (int dz = -radius; dz <= radius; dz += Math.max(1, radius / 4)) {
            int h = heightMap[radius][dz + radius]; // center column, varying z
            sb.append(formatHeight(h)).append(" ");
        }
        sb.append("\n");

        sb.append("Height profile (W→E through center, relative to you):\n  ");
        for (int dx = -radius; dx <= radius; dx += Math.max(1, radius / 4)) {
            int h = heightMap[dx + radius][radius]; // varying x, center z
            sb.append(formatHeight(h)).append(" ");
        }
        sb.append("\n");

        // 5. Open space assessment — good for building?
        int flatBlocks = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.abs(heightMap[dx + radius][dz + radius]) <= 1) {
                    flatBlocks++;
                }
            }
        }
        int totalBlocks = (radius * 2 + 1) * (radius * 2 + 1);
        int flatPercent = (flatBlocks * 100) / totalBlocks;
        sb.append("Flat area (±1 block): ").append(flatPercent).append("% (").append(flatBlocks).append("/").append(totalBlocks).append(" blocks)\n");

        // 6. Check for open air above player (vertical clearance)
        int clearance = 0;
        for (int dy = 1; dy <= 30; dy++) {
            if (world.getBlockState(playerPos.up(dy)).isAir()) {
                clearance++;
            } else {
                break;
            }
        }
        sb.append("Vertical clearance above: ").append(clearance).append(" blocks\n");

        // 7. Nearby structures / man-made blocks detection
        int manMadeCount = 0;
        List<String> structureBlocks = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -5; dy <= 10; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = new BlockPos(px + dx, py + dy, pz + dz);
                    BlockState state = world.getBlockState(pos);
                    String id = state.getBlock().toString();
                    if (isManMadeBlock(id)) {
                        manMadeCount++;
                        if (structureBlocks.size() < 5) {
                            String name = state.getBlock().getName().getString();
                            if (!structureBlocks.contains(name)) {
                                structureBlocks.add(name);
                            }
                        }
                    }
                }
            }
        }
        if (manMadeCount > 0) {
            sb.append("Man-made blocks detected: ").append(manMadeCount)
                    .append(" (types: ").append(String.join(", ", structureBlocks)).append(")\n");
        }

        return sb.toString();
    }

    /**
     * Generates a focused scan of a specific area (for build site assessment).
     */
    public String scanBuildSite(int x1, int y1, int z1, int x2, int y2, int z2) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return "Cannot scan — not in world";
        ClientWorld world = client.world;

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        Map<String, Integer> blockCounts = new LinkedHashMap<>();
        int total = 0, airCount = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockState state = world.getBlockState(new BlockPos(x, y, z));
                    total++;
                    if (state.isAir()) {
                        airCount++;
                    } else {
                        String name = state.getBlock().getName().getString();
                        blockCounts.merge(name, 1, Integer::sum);
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Build site scan (").append(minX).append(",").append(minY).append(",").append(minZ)
                .append(" to ").append(maxX).append(",").append(maxY).append(",").append(maxZ).append("):\n");
        sb.append("  Total blocks: ").append(total).append(", Air: ").append(airCount)
                .append(" (").append(airCount * 100 / Math.max(total, 1)).append("%)\n");

        if (!blockCounts.isEmpty()) {
            sb.append("  Solid blocks:\n");
            blockCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(8)
                    .forEach(e -> sb.append("    ").append(e.getKey()).append(": ").append(e.getValue()).append("\n"));
        }

        return sb.toString();
    }

    private String formatHeight(int h) {
        if (h > 0) return "+" + h;
        if (h < 0) return String.valueOf(h);
        return "=";
    }

    private boolean isManMadeBlock(String blockId) {
        return blockId.contains("plank") || blockId.contains("brick") || blockId.contains("stairs")
                || blockId.contains("slab") || blockId.contains("fence") || blockId.contains("wall")
                || blockId.contains("glass") || blockId.contains("door") || blockId.contains("wool")
                || blockId.contains("concrete") || blockId.contains("terracotta") || blockId.contains("quartz")
                || blockId.contains("smooth_stone") || blockId.contains("polished") || blockId.contains("cut_")
                || blockId.contains("chiseled") || blockId.contains("pillar") || blockId.contains("lantern")
                || blockId.contains("torch") || blockId.contains("carpet") || blockId.contains("banner");
    }
}
