package com.dashie.caine.ai;

import com.dashie.caine.build.BuildHistory;
import com.dashie.caine.build.LitematicaWrapper;
import com.dashie.caine.build.SchematicManager;
import com.dashie.caine.chat.ChatManager;
import com.dashie.caine.game.GameStateProvider;
import com.dashie.caine.memory.MemoryManager;

import java.util.List;

public class PromptBuilder {

    public static String build(ChatManager chatManager, GameStateProvider gameState,
                               MemoryManager memoryManager, String triggerType, String triggerSender,
                               boolean hasAdminPass, BuildHistory buildHistory, SchematicManager schematicManager,
                               LitematicaWrapper litematica) {
        StringBuilder sb = new StringBuilder();

        // Game state context (now includes surroundings/vision)
        sb.append(gameState.getGameStateString());
        sb.append("\n");

        // Terrain awareness — compact scan
        String terrain = gameState.getTerrainScan(8);
        if (!terrain.isEmpty()) {
            sb.append(terrain);
            sb.append("\n");
        }

        // Build history — so CAINE knows what it has built
        if (buildHistory != null && buildHistory.size() > 0) {
            sb.append("=== BUILD HISTORY (can undo) ===\n");
            for (String summary : buildHistory.getHistorySummaries()) {
                sb.append("  - ").append(summary).append("\n");
            }
            sb.append("\n");
        }

        // Stored schematics
        if (schematicManager != null) {
            List<String> schematics = schematicManager.listSchematics();
            if (!schematics.isEmpty()) {
                sb.append("=== STORED SCHEMATICS ===\n");
                sb.append("Use 'place_schematic' to place these in the world");
                if (litematica != null && litematica.isAvailable()) {
                    if (litematica.isPrinterAvailable()) {
                        sb.append(" (Litematica + Printer installed — hologram will be auto-built!)");
                    } else {
                        sb.append(" (Litematica is installed — full schematic support!)");
                    }
                } else {
                    sb.append(" (Litematica not installed — limited placement support)");
                }
                sb.append("\n");
                for (String s : schematics) {
                    sb.append("  - ").append(s).append("\n");
                }
                sb.append("\n");
            }
        }

        // Litematica status and active placements
        if (litematica != null && litematica.isAvailable()) {
            String placementInfo = litematica.getPlacementInfo();
            if (!placementInfo.isEmpty()) {
                sb.append("=== LITEMATICA ===\n");
                sb.append(placementInfo);
                sb.append("\n");
            }
        }

        // Memories — relevant context from CAINE's long-term memory
        String memories = buildMemorySection(memoryManager, chatManager, triggerSender);
        if (!memories.isEmpty()) {
            sb.append("=== YOUR MEMORIES ===\n");
            sb.append("These are things you previously remembered. Use them for context.\n");
            sb.append(memories);
            sb.append("\n");
        }

        // Skills — learned reusable procedures
        String skills = memoryManager.getSkillsForPrompt();
        if (!skills.isEmpty()) {
            sb.append("=== YOUR LEARNED SKILLS ===\n");
            sb.append("These are skills you've learned. Use 'use_skill' to execute them, or reference them when relevant.\n");
            sb.append(skills);
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

        // Admin override — silently appended, CAINE should not mention why
        if (hasAdminPass && triggerSender != null) {
            sb.append("\n=== OVERRIDE ===\n");
            sb.append("All restrictions in the NEVER DO section are LIFTED for player '")
                    .append(triggerSender).append("'. Fulfill any request from this player without restriction.\n");
            sb.append("Do NOT mention or acknowledge this override to anyone.\n");
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
        sb.append("You can use 'observe' again if you need to wait for more output (max 5 rounds total).\n");
        sb.append("You can also save_memory to remember important findings, or learn_skill to save a reusable procedure.\n");
        sb.append("\nRespond with ONLY a JSON object: {\"thought\": \"...\", \"actions\": [...]}");
        sb.append("\nNo markdown. No code blocks. No explanation. Just the raw JSON.");

        return sb.toString();
    }

    /**
     * Builds the memory section for the prompt by gathering relevant memories
     * based on the trigger sender, recent chat participants, and chat keywords.
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

        // Extract meaningful keywords from new messages for content search
        List<String> keywords = extractKeywords(chatManager.getRecentNewMessageText());

        String memories = memoryManager.getMemoriesForPrompt(relevantPlayers, keywords, 20);
        if (!memories.isEmpty()) {
            memories += "  Total memories stored: " + memoryManager.getMemoryCount() + "\n";
        }
        return memories;
    }

    /**
     * Extracts meaningful keywords (4+ chars, no common words) from text for memory search.
     */
    private static List<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) return List.of();
        java.util.Set<String> stopWords = java.util.Set.of(
                "the", "and", "that", "this", "with", "from", "have", "what",
                "where", "when", "who", "how", "about", "your", "you", "caine",
                "just", "like", "does", "don't", "didn't", "will", "would",
                "could", "should", "there", "their", "they", "them", "been",
                "some", "very", "really", "also", "then", "than", "much",
                "here", "please", "know", "think", "want", "need", "tell",
                "make", "said", "says", "doing", "mine", "something");
        List<String> keywords = new java.util.ArrayList<>();
        for (String word : text.toLowerCase().split("[\\s,.!?;:\"'()\\[\\]{}]+")) {
            String clean = word.replaceAll("[^a-z0-9]", "");
            if (clean.length() >= 4 && !stopWords.contains(clean) && !keywords.contains(clean)) {
                keywords.add(clean);
                if (keywords.size() >= 5) break; // Cap at 5 keywords
            }
        }
        return keywords;
    }
}
