package com.dashie.caine.action;

import com.dashie.caine.CaineModClient;
import com.dashie.caine.game.BaritoneWrapper;
import com.dashie.caine.game.GameStateProvider;
import com.dashie.caine.memory.MemoryManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class ActionExecutor {
    private static final int MAX_CHAT_LENGTH = 200;
    private static final int MAX_FOLLOWUP_ROUNDS = 3;

    private final GameStateProvider gameState;
    private final BaritoneWrapper baritone;
    private final MemoryManager memoryManager;
    // Tracks the last player targeted by tp/look actions so we can auto-look before chatting
    private volatile String lastTargetPlayer = null;
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

    public ActionExecutor(GameStateProvider gameState, BaritoneWrapper baritone, MemoryManager memoryManager) {
        this.gameState = gameState;
        this.baritone = baritone;
        this.memoryManager = memoryManager;
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
        }
    }

    private void executeChat(String message) throws InterruptedException {
        MinecraftClient client = MinecraftClient.getInstance();
        List<String> parts = splitMessage(message);

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
            if (client.player != null && client.player.networkHandler != null) {
                client.player.networkHandler.sendCommand(cmd);
            }
        });
    }

    /**
     * Smart teleport: only TP if player is >20 blocks away or not in line of sight.
     * Otherwise just looks at them.
     */
    private void executeTpToPlayer(String playerName) throws InterruptedException {
        if (playerName.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();

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
        if (baritone.isAvailable()) {
            MinecraftClient client = MinecraftClient.getInstance();
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
