package com.dashie.caine;

import com.dashie.caine.action.Action;
import com.dashie.caine.action.ActionExecutor;
import com.dashie.caine.ai.ActionParser;
import com.dashie.caine.ai.GeminiRunner;
import com.dashie.caine.ai.PromptBuilder;
import com.dashie.caine.build.BuildHistory;
import com.dashie.caine.build.LitematicaWrapper;
import com.dashie.caine.build.SchematicManager;
import com.dashie.caine.chat.ChatManager;
import com.dashie.caine.game.GameStateProvider;
import com.dashie.caine.memory.MemoryManager;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.Random;

/**
 * Coordinates AI trigger timing: queued mentions, periodic check-ins, and cooldowns.
 * Ticked every client tick (20 ticks/second).
 */
public class CaineScheduler {
    // Timing in client ticks (20 ticks = 1 second)
    private static final int DEBOUNCE_TICKS = 60;        // 3 seconds — wait for follow-up messages after mention
    private static final int COOLDOWN_TICKS = 200;       // 10 seconds — minimum gap between AI calls
    private static final int MIN_PERIODIC = 12000;       // 10 minutes
    private static final int MAX_PERIODIC = 36000;       // 30 minutes

    private final ChatManager chatManager;
    private final GameStateProvider gameState;
    private final GeminiRunner gemini;
    private final ActionExecutor executor;
    private final MemoryManager memoryManager;
    private final BuildHistory buildHistory;
    private final SchematicManager schematicManager;
    private final LitematicaWrapper litematica;
    private final Random random = new Random();

    private int debounceTicks = 0;
    private int cooldownTicks = 0;
    private int periodicTicks = 0;
    private int nextPeriodicTrigger;

    // Debounce state: accumulate a brief window after first mention, then fire
    private boolean debouncing = false;
    private String debounceSender = null;
    private boolean debounceIsPro = false;

    public CaineScheduler(ChatManager chatManager, GameStateProvider gameState,
                          GeminiRunner gemini, ActionExecutor executor, MemoryManager memoryManager,
                          BuildHistory buildHistory, SchematicManager schematicManager,
                          LitematicaWrapper litematica) {
        this.chatManager = chatManager;
        this.gameState = gameState;
        this.gemini = gemini;
        this.executor = executor;
        this.memoryManager = memoryManager;
        this.buildHistory = buildHistory;
        this.schematicManager = schematicManager;
        this.litematica = litematica;
        this.nextPeriodicTrigger = randomInterval();

        // Wire the observe-think-act followup handler (pro model persists through followups)
        executor.setFollowupHandler((waitSeconds, roundNumber) -> {
            CaineModClient.LOGGER.info("Followup round {} — building prompt after {}s observe", roundNumber, waitSeconds);
            String prompt = PromptBuilder.buildFollowup(chatManager, gameState, memoryManager, roundNumber);
            String response = gemini.sendPromptSync(prompt, false);
            List<Action> actions = ActionParser.parse(response);
            CaineModClient.LOGGER.info("Followup round {} parsed {} actions", roundNumber, actions.size());
            return actions;
        });
    }

    /**
     * Called every client tick. Manages queued triggers, debouncing, cooldowns, and periodic checks.
     */
    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        // Tick cooldown
        if (cooldownTicks > 0) cooldownTicks--;

        // If not currently debouncing or processing, pick up the next queued trigger
        if (!debouncing && !gemini.isProcessing() && cooldownTicks <= 0 && chatManager.hasPendingTrigger()) {
            debounceSender = chatManager.consumeTrigger();
            debounceIsPro = chatManager.consumeProFlag();
            debouncing = true;
            debounceTicks = DEBOUNCE_TICKS;
            CaineModClient.LOGGER.debug("Debounce started for mention by: {} (pro: {})", debounceSender, debounceIsPro);

            // Send admin pass check command (response arrives during debounce window)
            if (debounceSender != null) {
                chatManager.startAdminCheck();
                if (client.player != null && client.player.networkHandler != null) {
                    client.player.networkHandler.sendCommand(
                            "data get entity " + debounceSender + " Inventory");
                }
            }
        }

        // Debounce countdown — while debouncing, absorb any additional triggers from the same window
        if (debouncing) {
            // If more mentions arrived during debounce, update to the latest sender
            if (chatManager.hasPendingTrigger()) {
                String next = chatManager.consumeTrigger();
                if (next != null) debounceSender = next;
                // Don't reset timer — just absorb
            }

            debounceTicks--;
            if (debounceTicks <= 0) {
                debouncing = false;
                triggerAI("mention", debounceSender, debounceIsPro);
                debounceSender = null;
                debounceIsPro = false;
            }
        }

        // Periodic random check-in (only if auto-messages are enabled)
        periodicTicks++;
        if (periodicTicks >= nextPeriodicTrigger) {
            periodicTicks = 0;
            nextPeriodicTrigger = randomInterval();

            if (chatManager.isAutoMessagesEnabled()
                    && !gemini.isProcessing()
                    && cooldownTicks <= 0
                    && chatManager.hasRecentActivity()) {
                CaineModClient.LOGGER.info("Periodic check-in triggered");
                triggerAI("periodic", null, false);
            }
        }
    }

    private void triggerAI(String reason, String sender, boolean usePro) {
        if (gemini.isProcessing()) {
            // Re-queue the trigger so it's not lost
            if (sender != null) {
                CaineModClient.LOGGER.debug("Gemini busy, re-queuing trigger from: {}", sender);
            }
            return;
        }

        CaineModClient.LOGGER.info("Triggering AI (reason: {}, sender: {}, pro: {})", reason, sender, usePro);

        // Consume admin pass check result (was sent during debounce)
        boolean hasAdminPass = chatManager.consumeAdminCheck();
        if (hasAdminPass) {
            CaineModClient.LOGGER.info("Admin pass detected for player: {}", sender);
        }

        String prompt = PromptBuilder.build(chatManager, gameState, memoryManager, reason, sender, hasAdminPass, buildHistory, schematicManager, litematica);

        gemini.sendPrompt(prompt, usePro,
                response -> {
                    List<Action> actions = ActionParser.parse(response);
                    CaineModClient.LOGGER.info("Parsed {} actions from AI response", actions.size());

                    executor.execute(actions).thenRun(() -> {
                        chatManager.markResponded();
                        cooldownTicks = COOLDOWN_TICKS;
                        CaineModClient.LOGGER.info("Actions complete, cooldown started");
                    });
                },
                error -> {
                    CaineModClient.LOGGER.error("AI request failed: {}", error.getMessage());
                    cooldownTicks = COOLDOWN_TICKS;
                }
        );
    }

    private int randomInterval() {
        return MIN_PERIODIC + random.nextInt(MAX_PERIODIC - MIN_PERIODIC);
    }
}
