package com.dashie.caine.game;

import com.dashie.caine.CaineModClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

/**
 * Wraps Baritone integration via chat commands.
 * Baritone intercepts messages starting with '#' on the client side.
 * If Baritone is not installed, all methods are no-ops.
 */
public class BaritoneWrapper {
    private final boolean available;

    public BaritoneWrapper() {
        this.available = FabricLoader.getInstance().isModLoaded("baritone");
        if (available) {
            CaineModClient.LOGGER.info("Baritone detected! Pathfinding, following, and mining enabled.");
        } else {
            CaineModClient.LOGGER.info("Baritone not found. Pathfinding will fall back to /tp commands.");
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public void pathfindTo(int x, int y, int z) {
        if (!available) return;
        sendBaritoneCommand("goto " + x + " " + y + " " + z);
    }

    public void followPlayer(String name) {
        if (!available) return;
        sendBaritoneCommand("follow player " + name);
    }

    public void mine(String block, int quantity) {
        if (!available) return;
        // Baritone's mine command mines all instances of the block type
        sendBaritoneCommand("mine " + block);
    }

    public void stop() {
        if (!available) return;
        sendBaritoneCommand("stop");
    }

    private void sendBaritoneCommand(String cmd) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.player.networkHandler != null) {
            CaineModClient.LOGGER.info("Baritone command: #{}", cmd);
            // Baritone intercepts chat messages starting with '#' before they reach the server
            client.player.networkHandler.sendChatMessage("#" + cmd);
        }
    }
}
