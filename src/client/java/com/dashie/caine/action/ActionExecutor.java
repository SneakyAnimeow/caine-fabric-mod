package com.dashie.caine.action;

import com.dashie.caine.CaineModClient;
import com.dashie.caine.chat.ChatManager;
import com.dashie.caine.game.BaritoneWrapper;
import com.dashie.caine.game.GameStateProvider;
import com.dashie.caine.memory.MemoryManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class ActionExecutor {
    private static final int MAX_CHAT_LENGTH = 200;
    private static final int MAX_FOLLOWUP_ROUNDS = 5;

    private final GameStateProvider gameState;
    private final BaritoneWrapper baritone;
    private final MemoryManager memoryManager;
    private final ChatManager chatManager;
    // Tracks the last player targeted by tp/look actions so we can auto-look before chatting
    private volatile String lastTargetPlayer = null;
    // Skip auto-backup during restore to avoid overwriting the backup we're restoring from
    private volatile boolean skipAutoBackup = false;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CAINE-Actions");
        t.setDaemon(true);
        return t;
    });

    /**
     * Called when an Observe action is hit. Given the wait seconds, it should
     * collect new messages, build a followup prompt, call Gemini, and return new actions.
     */
    @FunctionalInterface
    public interface FollowupHandler {
        List<Action> handleFollowup(int waitSeconds, int roundNumber) throws Exception;
    }

    private FollowupHandler followupHandler;

    public ActionExecutor(GameStateProvider gameState, BaritoneWrapper baritone, MemoryManager memoryManager, ChatManager chatManager) {
        this.gameState = gameState;
        this.baritone = baritone;
        this.memoryManager = memoryManager;
        this.chatManager = chatManager;
    }

    public void setFollowupHandler(FollowupHandler handler) {
        this.followupHandler = handler;
    }

    /**
     * Executes a list of actions sequentially with small delays between them.
     * Runs on its own thread; game-thread operations are dispatched via MinecraftClient.execute().
     */
    public CompletableFuture<Void> execute(List<Action> actions) {
        return CompletableFuture.runAsync(() -> {
            lastTargetPlayer = null;
            executeActionList(actions, 0);
        }, scheduler);
    }

    private void executeActionList(List<Action> actions, int followupRound) {
        for (Action action : actions) {
            try {
                if (action instanceof Action.Observe obs) {
                    handleObserve(obs.seconds(), followupRound);
                    return; // Observe consumes the rest — followup actions replace remaining
                }
                executeOne(action);
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                CaineModClient.LOGGER.error("Error executing action: {}", action, e);
            }
        }
    }

    private void handleObserve(int waitSeconds, int currentRound) throws InterruptedException {
        if (followupHandler == null) {
            CaineModClient.LOGGER.warn("Observe action used but no followup handler set");
            return;
        }
        if (currentRound >= MAX_FOLLOWUP_ROUNDS) {
            CaineModClient.LOGGER.info("Max followup rounds ({}) reached, stopping observe chain", MAX_FOLLOWUP_ROUNDS);
            return;
        }

        int safeWait = Math.max(1, Math.min(waitSeconds, 15));
        CaineModClient.LOGGER.info("Observing for {} seconds (round {}/{})...", safeWait, currentRound + 1, MAX_FOLLOWUP_ROUNDS);
        Thread.sleep(safeWait * 1000L);

        try {
            List<Action> newActions = followupHandler.handleFollowup(safeWait, currentRound + 1);
            if (newActions != null && !newActions.isEmpty()) {
                CaineModClient.LOGGER.info("Followup round {} produced {} actions", currentRound + 1, newActions.size());
                executeActionList(newActions, currentRound + 1);
            }
        } catch (Exception e) {
            CaineModClient.LOGGER.error("Followup handler failed at round {}", currentRound + 1, e);
        }
    }

    private void executeOne(Action action) throws InterruptedException {
        CaineModClient.LOGGER.info("Executing: {}", action);

        switch (action) {
            case Action.Chat chat -> {
                // Auto-look at the last target player before speaking
                if (lastTargetPlayer != null) {
                    doLookAt(lastTargetPlayer);
                    Thread.sleep(150);
                }
                executeChat(chat.message());
            }
            case Action.Command cmd -> executeCommand(cmd.command());
            case Action.TpToPlayer tp -> executeTpToPlayer(tp.player());
            case Action.LookAtPlayer look -> executeLookAtPlayer(look.player());
            case Action.Pathfind pf -> executePathfind(pf.x(), pf.y(), pf.z());
            case Action.FollowPlayer fp -> executeFollowPlayer(fp.player());
            case Action.Mine mine -> executeMine(mine.block(), mine.quantity());
            case Action.GiveItem give -> executeGiveItem(give.player(), give.item(), give.count(), give.drop());
            case Action.SaveMemory mem -> executeSaveMemory(mem.category(), mem.subject(), mem.content(), mem.importance());
            case Action.ForgetMemory fm -> executeForgetMemory(fm.subject(), fm.category());
            case Action.RecallMemory rm -> executeRecallMemory(rm.query());
            case Action.StopTask ignored -> executeStopTask();
            case Action.Observe ignored -> {} // Handled in executeActionList before reaching here
            case Action.Delay delay -> {
                CaineModClient.LOGGER.info("Delaying {} seconds between commands...", delay.seconds());
                Thread.sleep(delay.seconds() * 1000L);
            }
            case Action.Wait wait -> {
                CaineModClient.LOGGER.info("Waiting {} seconds...", wait.seconds());
                Thread.sleep(wait.seconds() * 1000L);
            }
            case Action.Nothing ignored -> CaineModClient.LOGGER.info("Doing nothing (intentional)");
            case Action.UseItemOnBlock uib -> executeUseItemOnBlock(uib.x(), uib.y(), uib.z());
            case Action.UseItemOnEntity uie -> executeUseItemOnEntity(uie.target());
            case Action.SelectSlot ss -> executeSelectSlot(ss.slot());
            case Action.Attack atk -> executeAttack(atk.target());
            case Action.BackupInventory bi -> executeBackupInventory(bi.player());
            case Action.RestoreInventory ri -> executeRestoreInventory(ri.player());
            case Action.RunScript rs -> executeRunScript(rs.commands(), rs.delayTicks(), rs.repeat(), rs.stopCondition());
        }
    }

    private void executeChat(String message) throws InterruptedException {
        MinecraftClient client = MinecraftClient.getInstance();
        // Strip characters that Minecraft considers illegal in chat
        String sanitized = message.replaceAll("[^\\x20-\\x7E\\xA0-\\xFF]", "").trim();
        if (sanitized.isEmpty()) return;
        List<String> parts = splitMessage(sanitized);

        for (String part : parts) {
            client.execute(() -> {
                if (client.player != null && client.player.networkHandler != null) {
                    client.player.networkHandler.sendChatMessage(part);
                }
            });
            // Small delay between message parts
            if (parts.size() > 1) {
                Thread.sleep(800);
            }
        }
    }

    private void executeCommand(String command) {
        // Strip leading / if present
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player == null || client.player.networkHandler == null) return;

            // Auto-backup inventory before any /clear <player> command
            if (!skipAutoBackup && cmd.startsWith("clear ")) {
                String target = cmd.substring(6).split(" ")[0].trim();
                if (!target.isEmpty() && !target.startsWith("@")) {
                    CaineModClient.LOGGER.info("Auto-backing up {}'s inventory before clear", target);
                    client.player.networkHandler.sendCommand(
                            "data modify storage caine:backups " + target +
                                    " set from entity " + target + " Inventory");
                }
            }

            client.player.networkHandler.sendCommand(cmd);
        });
    }

    /**
     * Smart teleport: only TP if player is >20 blocks away or not in line of sight.
     * Otherwise just looks at them.
     */
    private void executeTpToPlayer(String playerName) throws InterruptedException {
        if (playerName.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();

        // Save current position before teleporting
        savePositionBeforeTP(client);

        // Check distance and visibility on game thread, then decide
        client.execute(() -> {
            if (client.player == null || client.world == null) {
                // Can't check — just tp
                if (client.player != null && client.player.networkHandler != null)
                    client.player.networkHandler.sendCommand("tp @s " + playerName);
                return;
            }

            Optional<AbstractClientPlayerEntity> target = gameState.findPlayer(playerName);
            if (target.isEmpty()) {
                // Player not in render distance — definitely tp
                CaineModClient.LOGGER.info("Player '{}' not in render distance, teleporting", playerName);
                client.player.networkHandler.sendCommand("tp @s " + playerName);
                return;
            }

            AbstractClientPlayerEntity targetPlayer = target.get();
            double dist = client.player.distanceTo(targetPlayer);

            if (dist > 20.0) {
                CaineModClient.LOGGER.info("Player '{}' is {} blocks away (>20), teleporting", playerName, (int) dist);
                client.player.networkHandler.sendCommand("tp @s " + playerName);
            } else {
                // Close enough — just look at them
                CaineModClient.LOGGER.info("Player '{}' is nearby ({} blocks), just looking", playerName, (int) dist);
                lookAt(client.player, targetPlayer.getX(), targetPlayer.getEyeY(), targetPlayer.getZ());
            }
        });

        // After TP, wait for server to update position, then look at player
        lastTargetPlayer = playerName;
        Thread.sleep(500);
        doLookAt(playerName);
    }

    private void executeLookAtPlayer(String playerName) {
        if (playerName.isEmpty()) return;
        lastTargetPlayer = playerName;
        doLookAt(playerName);
    }

    /**
     * Rotates the player to face the named player. Safe to call from any thread.
     */
    private void doLookAt(String playerName) {
        if (playerName == null || playerName.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player == null || client.world == null) return;

            Optional<AbstractClientPlayerEntity> target = gameState.findPlayer(playerName);
            if (target.isPresent()) {
                AbstractClientPlayerEntity targetPlayer = target.get();
                lookAt(client.player,
                        targetPlayer.getX(),
                        targetPlayer.getEyeY(),
                        targetPlayer.getZ());
            }
        });
    }

    private void lookAt(ClientPlayerEntity player, double targetX, double targetY, double targetZ) {
        double dx = targetX - player.getX();
        double dy = targetY - player.getEyeY();
        double dz = targetZ - player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        // Minecraft yaw: atan2(dz, dx) converted to degrees, offset by -90
        // 0° = south, 90° = west, 180° = north, 270° = east
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDist)));

        player.setYaw(MathHelper.wrapDegrees(yaw));
        player.setPitch(MathHelper.clamp(pitch, -90.0f, 90.0f));
    }

    private void executePathfind(int x, int y, int z) {
        MinecraftClient client = MinecraftClient.getInstance();
        savePositionBeforeTP(client);
        if (baritone.isAvailable()) {
            client.execute(() -> baritone.pathfindTo(x, y, z));
        } else {
            // Fallback: instant teleport via command
            executeCommand("tp @s " + x + " " + y + " " + z);
        }
    }

    private void executeFollowPlayer(String playerName) throws InterruptedException {
        if (playerName.isEmpty()) return;
        if (baritone.isAvailable()) {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> baritone.followPlayer(playerName));
        } else {
            // Fallback: one-time teleport
            executeTpToPlayer(playerName);
        }
    }

    private void executeMine(String block, int quantity) {
        if (baritone.isAvailable()) {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> baritone.mine(block, quantity));
        } else {
            CaineModClient.LOGGER.warn("Mine action ignored — Baritone not available");
        }
    }

    /**
     * Give items to a player. Two modes:
     * - drop=false: /give command (instant, items appear in their inventory)
     * - drop=true:  give to self then drop (immersive, CAINE physically throws items)
     */
    private void executeGiveItem(String player, String item, int count, boolean drop) throws InterruptedException {
        if (player.isEmpty() || item.isEmpty()) return;
        int safeCount = Math.max(1, Math.min(count, 64 * 27)); // cap at 27 stacks

        if (!drop) {
            // Direct /give
            executeCommand("give " + player + " " + item + " " + safeCount);
        } else {
            // Give to self, then drop towards the player
            MinecraftClient client = MinecraftClient.getInstance();

            // Look at the player first
            if (lastTargetPlayer == null) lastTargetPlayer = player;
            doLookAt(player);
            Thread.sleep(300);

            // Give items to ourselves
            executeCommand("give @s " + item + " " + safeCount);
            Thread.sleep(400);

            // Drop all items (Q key spam) — use /give + /clear combo as more reliable
            // Actually, use the throw command approach: select hotbar slot and drop
            // Simplest reliable approach: give to self, then use a single command to teleport the items
            client.execute(() -> {
                if (client.player == null || client.player.networkHandler == null) return;
                // Drop items by pressing Q repeatedly via command workaround:
                // We'll use /drop simulation — but MC has no /drop command.
                // Instead: give to self, immediately clear from self, then give to player
                // OR: just use the throw key input
            });

            // Use key press simulation to drop items
            // For reliability, press drop key (Q) for each stack
            int stacks = (int) Math.ceil(safeCount / 64.0);
            for (int i = 0; i < stacks && i < 9; i++) {
                client.execute(() -> {
                    if (client.options != null && client.player != null) {
                        // Simulate pressing Q (drop item key)
                        client.player.dropSelectedItem(true); // true = drop entire stack
                    }
                });
                Thread.sleep(150);
            }
            CaineModClient.LOGGER.info("Dropped {} of {} towards {}", safeCount, item, player);
        }
    }

    private void executeStopTask() {
        if (baritone.isAvailable()) {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(baritone::stop);
        }
    }

    private void executeSaveMemory(String category, String subject, String content, int importance) {
        if (content.isEmpty()) return;
        memoryManager.saveMemory(category, subject, content, importance);
    }

    private void executeForgetMemory(String subject, String category) {
        if (subject.isEmpty()) return;
        int forgotten = memoryManager.forgetBySubject(subject, category);
        CaineModClient.LOGGER.info("Forgot {} memories about '{}' (category: {})", forgotten, subject,
                category.isEmpty() ? "all" : category);
    }

    private void executeRecallMemory(String query) {
        if (query.isEmpty()) return;
        var results = memoryManager.searchMemories(query, 10);
        if (results.isEmpty()) {
            CaineModClient.LOGGER.info("Recall '{}': no memories found", query);
        } else {
            CaineModClient.LOGGER.info("Recall '{}': found {} memories", query, results.size());
            for (var m : results) {
                CaineModClient.LOGGER.info("  Memory: [{}] {} — {}", m.category(), m.subject(), m.content());
            }
        }
    }

    private void savePositionBeforeTP(MinecraftClient client) {
        if (client.player != null && client.world != null) {
            String dim = client.world.getRegistryKey().getValue().toString();
            gameState.setLastTeleportPosition(
                    client.player.getX(), client.player.getY(), client.player.getZ(), dim);
            CaineModClient.LOGGER.info("Saved pre-TP position: ({}, {}, {}) in {}",
                    (int) client.player.getX(), (int) client.player.getY(),
                    (int) client.player.getZ(), dim);
        }
    }

    private void executeUseItemOnBlock(int x, int y, int z) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player == null || client.interactionManager == null) return;
            BlockPos blockPos = new BlockPos(x, y, z);
            BlockHitResult hitResult = new BlockHitResult(
                    Vec3d.ofCenter(blockPos), Direction.UP, blockPos, false);
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
            CaineModClient.LOGGER.info("Used item on block at ({}, {}, {})", x, y, z);
        });
    }

    private void executeUseItemOnEntity(String targetName) {
        if (targetName.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player == null || client.world == null || client.interactionManager == null) return;
            Entity target = findNearestEntityByName(client, targetName);
            if (target != null) {
                client.interactionManager.interactEntity(client.player, target, Hand.MAIN_HAND);
                CaineModClient.LOGGER.info("Used item on entity: {}", targetName);
            } else {
                CaineModClient.LOGGER.warn("Entity not found for interaction: {}", targetName);
            }
        });
    }

    private void executeSelectSlot(int slot) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.getInventory().selectedSlot = Math.max(0, Math.min(slot, 8));
                CaineModClient.LOGGER.info("Selected hotbar slot {}", slot);
            }
        });
    }

    private void executeAttack(String targetName) {
        if (targetName.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player == null || client.world == null || client.interactionManager == null) return;
            Entity target = findNearestEntityByName(client, targetName);
            if (target != null) {
                client.interactionManager.attackEntity(client.player, target);
                client.player.swingHand(Hand.MAIN_HAND);
                CaineModClient.LOGGER.info("Attacked entity: {}", targetName);
            } else {
                CaineModClient.LOGGER.warn("Entity not found for attack: {}", targetName);
            }
        });
    }

    /**
     * Finds the nearest entity matching the given name (player name or entity type).
     */
    private Entity findNearestEntityByName(MinecraftClient client, String name) {
        if (client.player == null || client.world == null) return null;

        // First try as a player name
        Optional<AbstractClientPlayerEntity> player = gameState.findPlayer(name);
        if (player.isPresent()) return player.get();

        // Search nearby entities by display name
        Box area = client.player.getBoundingBox().expand(10.0);
        Entity closest = null;
        double closestDist = Double.MAX_VALUE;
        for (Entity entity : client.world.getOtherEntities(client.player, area)) {
            if (entity.getName().getString().equalsIgnoreCase(name)
                    || entity.getType().getName().getString().equalsIgnoreCase(name)) {
                double dist = client.player.distanceTo(entity);
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = entity;
                }
            }
        }
        return closest;
    }

    private void executeBackupInventory(String player) {
        if (player.isEmpty()) return;
        CaineModClient.LOGGER.info("Backing up {}'s inventory to server storage", player);
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null && client.player.networkHandler != null) {
                client.player.networkHandler.sendCommand(
                        "data modify storage caine:backups " + player +
                                " set from entity " + player + " Inventory");
            }
        });
    }

    /**
     * Executes a batch of commands in rapid succession.
     * Each command is fired on the game thread with a configurable tick delay between them.
     * This enables complex multi-step operations without needing server-side datapacks.
     */
    private void executeRunScript(List<String> commands, int delayTicks, int repeat, String stopCondition) throws InterruptedException {
        if (commands.isEmpty()) return;
        long delayMs = delayTicks * 50L; // 1 tick = 50ms
        boolean hasCondition = stopCondition != null && !stopCondition.isBlank();
        int totalCommands = commands.size() * repeat;
        CaineModClient.LOGGER.info("Running script: {} commands x {} repeats = {} total, {}ms between each{}",
                commands.size(), repeat, totalCommands, delayMs,
                hasCondition ? ", stop_condition: " + stopCondition : "");

        for (int r = 0; r < repeat; r++) {
            // Check stop condition before each repeat (after the first)
            if (r > 0 && hasCondition) {
                if (!checkStopCondition(stopCondition)) {
                    CaineModClient.LOGGER.info("Stop condition failed at repeat {}/{}, stopping early", r + 1, repeat);
                    break;
                }
            }
            for (int i = 0; i < commands.size(); i++) {
                String cmd = commands.get(i);
                if (cmd == null || cmd.isBlank()) continue;
                executeCommand(cmd);
                if (r < repeat - 1 || i < commands.size() - 1) {
                    Thread.sleep(delayMs);
                }
            }
        }
        CaineModClient.LOGGER.info("Script execution complete ({} commands x {} repeats)", commands.size(), repeat);
    }

    /**
     * Runs an /execute if ... condition command and checks server feedback.
     * Returns true if "Test passed" (condition met, keep going), false if "Test failed" (stop).
     */
    private boolean checkStopCondition(String condition) throws InterruptedException {
        chatManager.startConditionCapture();
        executeCommand(condition);
        // Wait for the server to send back "Test passed" or "Test failed"
        Thread.sleep(200);
        return chatManager.consumeConditionResult();
    }

    /**
     * Restores a player's inventory from server-side storage backup.
     * Uses item entities spawned from storage data — items may end up in different slots.
     */
    private void executeRestoreInventory(String player) throws InterruptedException {
        if (player.isEmpty()) return;
        CaineModClient.LOGGER.info("Restoring {}'s inventory from backup", player);

        // Clean up any leftover restore entities
        executeCommand("kill @e[tag=caine_r]");
        Thread.sleep(300);

        // Clear current inventory — skip auto-backup since we're restoring
        skipAutoBackup = true;
        executeCommand("clear " + player);
        Thread.sleep(300);
        skipAutoBackup = false;

        // Iterate through all possible inventory indices (player can have up to 41 items)
        // For each stored item: summon a temp item entity, copy data from storage, TP to player for pickup
        for (int i = 0; i < 41; i++) {
            // Only summon if this index exists in the backup
            executeCommand(String.format(
                    "execute if data storage caine:backups %s[%d] run summon item ~ 320 ~ " +
                            "{Tags:[\"caine_r\"],Item:{id:\"minecraft:stone\",count:1}," +
                            "PickupDelay:32767,Age:-32768,NoGravity:1b}",
                    player, i));
            Thread.sleep(50);

            // Copy actual item data from backup storage to the entity
            executeCommand(String.format(
                    "data modify entity @e[tag=caine_r,limit=1] Item set from storage caine:backups %s[%d]",
                    player, i));

            // Remove the Slot tag (not valid for item entities)
            executeCommand("data remove entity @e[tag=caine_r,limit=1] Item.Slot");

            // Enable pickup and teleport to player
            executeCommand("data modify entity @e[tag=caine_r,limit=1] PickupDelay set value 0");
            executeCommand(String.format("tp @e[tag=caine_r,limit=1] %s", player));
            Thread.sleep(100);

            // Remove tag for next iteration
            executeCommand("tag @e[tag=caine_r] remove caine_r");
        }

        // Final cleanup
        Thread.sleep(500);
        executeCommand("kill @e[tag=caine_r]");
        CaineModClient.LOGGER.info("Inventory restore complete for {}", player);
    }

    /**
     * Splits a long message into chunks that fit within Minecraft's chat limit.
     * Splits at word boundaries when possible.
     */
    private List<String> splitMessage(String message) {
        if (message.length() <= MAX_CHAT_LENGTH) {
            return List.of(message);
        }

        List<String> parts = new ArrayList<>();
        String remaining = message;

        while (remaining.length() > MAX_CHAT_LENGTH) {
            int splitAt = remaining.lastIndexOf(' ', MAX_CHAT_LENGTH);
            if (splitAt <= 0) splitAt = MAX_CHAT_LENGTH;
            parts.add(remaining.substring(0, splitAt).trim());
            remaining = remaining.substring(splitAt).trim();
        }

        if (!remaining.isEmpty()) {
            parts.add(remaining);
        }

        return parts;
    }
}
