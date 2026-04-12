package com.dashie.caine.game;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class GameStateProvider {

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
}
