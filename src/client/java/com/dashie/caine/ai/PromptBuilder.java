package com.dashie.caine.ai;

import com.dashie.caine.chat.ChatManager;
import com.dashie.caine.game.GameStateProvider;
import com.dashie.caine.memory.MemoryManager;

import java.util.List;

public class PromptBuilder {

    public static String build(ChatManager chatManager, GameStateProvider gameState,
                               MemoryManager memoryManager, String triggerType, String triggerSender) {
        StringBuilder sb = new StringBuilder();

        // Game state context (now includes surroundings/vision)
        sb.append(gameState.getGameStateString());
        sb.append("\n");

        // Memories — relevant context from CAINE's long-term memory
        String memories = buildMemorySection(memoryManager, chatManager, triggerSender);
        if (!memories.isEmpty()) {
            sb.append("=== YOUR MEMORIES ===\n");
            sb.append("These are things you previously remembered. Use them for context.\n");
            sb.append(memories);
            sb.append("\n");
        }

        // Recent chat history — messages tagged [NEW] are ones you haven't responded to yet
        sb.append("=== RECENT CHAT ===\n");
        sb.append("Messages marked [NEW] arrived since your last response. Unmarked = already seen/responded to.\n");
        String history = chatManager.getFormattedHistory(20);
        if (history.isEmpty()) {
            sb.append("(no recent messages)\n");
        } else {
            sb.append(history).append("\n");
        }
        sb.append("New messages since last response: ").append(chatManager.getNewMessageCount()).append("\n");
        sb.append("\n");

        // Trigger context
        sb.append("=== TRIGGER ===\n");
        switch (triggerType) {
            case "mention" -> {
                sb.append("Player '").append(triggerSender).append("' mentioned your name (CAINE) in chat.\n");
                sb.append("Use tp_to_player to go to them (it auto-decides: TP if far, look if nearby).\n");
                sb.append("Read the [NEW] messages to understand what they want.\n");
                sb.append("IMPORTANT: Do NOT re-greet or repeat yourself for messages you already responded to (unmarked ones).\n");
                sb.append("Only respond to what's NEW. If the player is continuing a conversation, continue it naturally.\n");
            }
            case "periodic" -> {
                sb.append("This is a periodic check-in. No one specifically called you.\n");
                sb.append("Look at the [NEW] messages and optionally make a fun or relevant comment.\n");
                sb.append("You may also choose to do nothing if chat is boring or empty.\n");
                sb.append("Keep it natural - don't force conversation. Don't repeat things you already said.\n");
            }
            default -> {
                sb.append("Respond naturally based on the context.\n");
            }
        }

        sb.append("\nRespond with ONLY a JSON object: {\"thought\": \"...\", \"actions\": [...]}");
        sb.append("\nNo markdown. No code blocks. No explanation. Just the raw JSON.");

        return sb.toString();
    }

    /**
     * Builds a followup prompt for the observe-think-act loop.
     */
    public static String buildFollowup(ChatManager chatManager, GameStateProvider gameState,
                                       MemoryManager memoryManager, int roundNumber) {
        StringBuilder sb = new StringBuilder();

        sb.append(gameState.getGameStateString());
        sb.append("\n");

        sb.append("=== RECENT CHAT (after your last actions) ===\n");
        String history = chatManager.getFormattedHistory(20);
        if (history.isEmpty()) {
            sb.append("(no new messages appeared)\n");
        } else {
            sb.append(history).append("\n");
        }
        sb.append("\n");

        sb.append("=== FOLLOWUP ROUND ").append(roundNumber).append(" ===\n");
        sb.append("You just executed some actions and used 'observe' to wait and watch for results.\n");
        sb.append("The chat above shows what happened since then (command output, player responses, etc).\n");
        sb.append("Now decide your next actions based on what you observed.\n");
        sb.append("You can use 'observe' again if you need to wait for more output (max 3 rounds total).\n");
        sb.append("You can also save_memory to remember important findings.\n");
        sb.append("\nRespond with ONLY a JSON object: {\"thought\": \"...\", \"actions\": [...]}");
        sb.append("\nNo markdown. No code blocks. No explanation. Just the raw JSON.");

        return sb.toString();
    }

    /**
     * Builds the memory section for the prompt by gathering relevant memories
     * based on the trigger sender and recent chat participants.
     */
    private static String buildMemorySection(MemoryManager memoryManager,
                                             ChatManager chatManager, String triggerSender) {
        // Gather player names to look up memories for
        List<String> relevantPlayers = new java.util.ArrayList<>();
        if (triggerSender != null && !triggerSender.isEmpty()) {
            relevantPlayers.add(triggerSender);
        }
        // Add recent chat participants
        for (String name : chatManager.getRecentSenders(5)) {
            if (!relevantPlayers.contains(name)) {
                relevantPlayers.add(name);
            }
        }

        return memoryManager.getMemoriesForPrompt(relevantPlayers, 15);
    }
}
