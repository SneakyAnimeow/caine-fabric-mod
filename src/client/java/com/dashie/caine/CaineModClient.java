package com.dashie.caine;

import com.dashie.caine.action.ActionExecutor;
import com.dashie.caine.ai.GeminiRunner;
import com.dashie.caine.ai.StructureGenerator;
import com.dashie.caine.build.BuildHistory;
import com.dashie.caine.build.LitematicaWrapper;
import com.dashie.caine.build.SchematicManager;
import com.dashie.caine.chat.ChatManager;
import com.dashie.caine.game.BaritoneWrapper;
import com.dashie.caine.game.GameStateProvider;
import com.dashie.caine.memory.MemoryManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.packet.c2s.play.UpdatePlayerAbilitiesC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Random;

public class CaineModClient implements ClientModInitializer {
    public static final String MOD_ID = "caine";
    public static final Logger LOGGER = LoggerFactory.getLogger("CAINE");

    // Anti-AFK movement
    private final Random afkRandom = new Random();
    private int afkTickCounter = 0;
    private int nextAfkMoveTick = 80; // ~4 seconds initially
    // WATUT idle reset — every ~2 minutes
    private int watutResetCounter = 0;
    private static final int WATUT_RESET_INTERVAL = 2400; // 2 minutes in ticks
    // Cached WATUT reflection (null = not yet tried, empty method array = WATUT not found)
    private Method watutOnAction = null;
    private Object watutManagerInstance = null;
    private boolean watutReflectionAttempted = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("CAINE is initializing! Welcome to the Digital Circus!");

        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("caine");
        Path systemPromptPath = extractSystemPrompt();

        // Initialize memory database
        MemoryManager memoryManager;
        try {
            Files.createDirectories(configDir);
            memoryManager = new MemoryManager(configDir.resolve("caine-memories.db"));
        } catch (IOException e) {
            LOGGER.error("Failed to create config directory for memory DB", e);
            memoryManager = new MemoryManager(configDir.resolve("caine-memories.db"));
        }

        ChatManager chatManager = new ChatManager();
        GameStateProvider gameState = new GameStateProvider();
        BaritoneWrapper baritone = new BaritoneWrapper();
        GeminiRunner gemini = new GeminiRunner(systemPromptPath);
        StructureGenerator structureGenerator = new StructureGenerator(gemini);
        BuildHistory buildHistory = new BuildHistory();
        SchematicManager schematicManager = new SchematicManager(configDir);
        LitematicaWrapper litematica = new LitematicaWrapper();
        ActionExecutor executor = new ActionExecutor(gameState, baritone, memoryManager, chatManager);
        executor.setStructureGenerator(structureGenerator);
        executor.setBuildHistory(buildHistory);
        executor.setSchematicManager(schematicManager);
        executor.setLitematicaWrapper(litematica);
        CaineScheduler scheduler = new CaineScheduler(chatManager, gameState, gemini, executor, memoryManager, buildHistory, schematicManager, litematica);

        // Register chat receive events
        ClientReceiveMessageEvents.CHAT.register(chatManager::onChatMessage);
        ClientReceiveMessageEvents.GAME.register(chatManager::onGameMessage);

        // Tick the scheduler every client tick + keep CAINE levitating + anti-AFK
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            scheduler.tick(client);

            if (client.player == null) return;

            // Keep CAINE flying in creative mode — the host doesn't walk!
            if (client.player.getAbilities().allowFlying
                    && !client.player.getAbilities().flying) {
                client.player.getAbilities().flying = true;
                if (client.player.networkHandler != null) {
                    client.player.networkHandler.sendPacket(
                            new UpdatePlayerAbilitiesC2SPacket(client.player.getAbilities()));
                }
            }

            // Anti-AFK: subtle head movements and small body sway to look alive
            afkTickCounter++;
            if (afkTickCounter >= nextAfkMoveTick) {
                afkTickCounter = 0;
                nextAfkMoveTick = 60 + afkRandom.nextInt(120); // 3-9 seconds

                float yawDelta = (afkRandom.nextFloat() - 0.5f) * 8.0f;  // ±4 degrees
                float pitchDelta = (afkRandom.nextFloat() - 0.5f) * 4.0f; // ±2 degrees

                float newYaw = MathHelper.wrapDegrees(client.player.getYaw() + yawDelta);
                float newPitch = MathHelper.clamp(client.player.getPitch() + pitchDelta, -30.0f, 10.0f);

                client.player.setYaw(newYaw);
                client.player.setPitch(newPitch);

                // Occasionally swing arm for liveliness (every ~30 seconds on average)
                if (afkRandom.nextInt(10) == 0) {
                    client.player.swingHand(Hand.MAIN_HAND);
                }
            }

            // WATUT anti-idle: call onAction() via reflection to reset idle timer
            watutResetCounter++;
            if (watutResetCounter >= WATUT_RESET_INTERVAL) {
                watutResetCounter = 0;
                resetWatutIdle();
            }
        });

        LOGGER.info("CAINE is ready! The show must go on!");
    }

    /**
     * Resets WATUT's idle timer via reflection.
     * Calls WatutMod.getPlayerStatusManagerClient().onAction()
     */
    private void resetWatutIdle() {
        if (!watutReflectionAttempted) {
            watutReflectionAttempted = true;
            try {
                Class<?> watutModClass = Class.forName("com.corosus.watut.WatutMod");
                Method getManager = watutModClass.getMethod("getPlayerStatusManagerClient");
                watutManagerInstance = getManager.invoke(null);
                watutOnAction = watutManagerInstance.getClass().getMethod("onAction");
                LOGGER.info("WATUT detected! Anti-idle integration enabled.");
            } catch (ClassNotFoundException e) {
                LOGGER.info("WATUT not detected, skipping anti-idle integration.");
            } catch (Exception e) {
                LOGGER.warn("Failed to set up WATUT anti-idle integration", e);
            }
        }

        if (watutOnAction != null && watutManagerInstance != null) {
            try {
                watutOnAction.invoke(watutManagerInstance);
            } catch (Exception e) {
                LOGGER.debug("Failed to reset WATUT idle timer", e);
            }
        }
    }

    private Path extractSystemPrompt() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("caine");
        Path promptPath = configDir.resolve("system-prompt.md");

        try {
            Files.createDirectories(configDir);
            try (InputStream in = getClass().getResourceAsStream("/assets/caine/system-prompt.md")) {
                if (in != null) {
                    Files.copy(in, promptPath, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("System prompt extracted to: {}", promptPath);
                } else {
                    LOGGER.error("System prompt resource not found in mod jar!");
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to extract system prompt", e);
        }

        return promptPath;
    }
}
