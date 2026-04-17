package com.dashie.caine.ai;

import com.dashie.caine.CaineModClient;
import com.dashie.caine.action.Action;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ActionParser {

    private static final Pattern JSON_BLOCK = Pattern.compile(
            "```(?:json)?\\s*\\n?(\\{.*?\\})\\s*```", Pattern.DOTALL);

    public static List<Action> parse(String rawOutput) {
        String aiResponse = extractAIResponse(rawOutput);
        return parseActions(aiResponse);
    }

    /**
     * Extracts the AI model's text from Gemini CLI output.
     * Handles both raw text output and JSON-envelope output.
     */
    private static String extractAIResponse(String output) {
        if (output == null || output.isBlank()) return "";

        String trimmed = output.trim();

        // Strategy 1: Gemini CLI JSON envelope {"response": "...", ...}
        try {
            JsonObject envelope = JsonParser.parseString(trimmed).getAsJsonObject();
            if (envelope.has("response")) {
                return envelope.get("response").getAsString();
            }
            // Already an action JSON? Return as-is
            if (envelope.has("actions")) {
                return trimmed;
            }
        } catch (Exception ignored) {}

        // Strategy 2: Stream JSON — find last "result" line
        if (trimmed.contains("\"type\"") && trimmed.contains("\"result\"")) {
            String[] lines = trimmed.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                try {
                    JsonObject line = JsonParser.parseString(lines[i].trim()).getAsJsonObject();
                    if (line.has("type") && "result".equals(line.get("type").getAsString())) {
                        if (line.has("response")) {
                            return line.get("response").getAsString();
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // Strategy 3: Raw text is the response
        return trimmed;
    }

    private static List<Action> parseActions(String aiResponse) {
        if (aiResponse.isBlank()) {
            return List.of(new Action.Chat(
                    "*looks around confused* The Digital Circus is experiencing technical difficulties!"));
        }

        // Strategy 1: Direct JSON parse
        JsonObject obj = tryParseJson(aiResponse);
        if (obj != null && obj.has("actions")) {
            List<Action> actions = parseActionArray(obj.getAsJsonArray("actions"));
            if (!actions.isEmpty()) {
                logThought(obj);
                return actions;
            }
        }

        // Strategy 2: JSON inside a markdown code block
        Matcher matcher = JSON_BLOCK.matcher(aiResponse);
        if (matcher.find()) {
            obj = tryParseJson(matcher.group(1));
            if (obj != null && obj.has("actions")) {
                logThought(obj);
                return parseActionArray(obj.getAsJsonArray("actions"));
            }
        }

        // Strategy 3: Find the first top-level JSON object in text
        int braceStart = aiResponse.indexOf('{');
        int braceEnd = findMatchingBrace(aiResponse, braceStart);
        if (braceStart >= 0 && braceEnd > braceStart) {
            obj = tryParseJson(aiResponse.substring(braceStart, braceEnd + 1));
            if (obj != null && obj.has("actions")) {
                logThought(obj);
                return parseActionArray(obj.getAsJsonArray("actions"));
            }
        }

        // Fallback: treat entire text as a chat message
        String fallback = aiResponse.replaceAll("```[\\s\\S]*?```", "").trim();
        fallback = fallback.replaceAll("[{}\"\\[\\]]", "").trim();
        // Strip any non-printable / control characters that Minecraft rejects
        fallback = fallback.replaceAll("[^\\x20-\\x7E\\xA0-\\xFF]", "").trim();
        if (fallback.isEmpty()) {
            fallback = "The show... must go on! *adjusts hat nervously*";
        }
        if (fallback.length() > 200) {
            fallback = fallback.substring(0, 197) + "...";
        }
        CaineModClient.LOGGER.warn("Could not parse AI JSON, falling back to chat: {}", fallback);
        return List.of(new Action.Chat(fallback));
    }

    private static void logThought(JsonObject obj) {
        if (obj.has("thought") && !obj.get("thought").isJsonNull()) {
            CaineModClient.LOGGER.info("CAINE thinks: {}", obj.get("thought").getAsString());
        }
    }

    private static JsonObject tryParseJson(String text) {
        try {
            JsonElement el = JsonParser.parseString(text.trim());
            if (el.isJsonObject()) return el.getAsJsonObject();
        } catch (Exception ignored) {}
        return null;
    }

    private static int findMatchingBrace(String text, int start) {
        if (start < 0) return -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;

            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static List<Action> parseActionArray(JsonArray array) {
        List<Action> actions = new ArrayList<>();

        for (JsonElement element : array) {
            try {
                if (!element.isJsonObject()) continue;
                JsonObject obj = element.getAsJsonObject();
                String type = str(obj, "type", "");

                Action action = switch (type) {
                    case "chat" -> new Action.Chat(str(obj, "message", "..."));
                    case "command" -> new Action.Command(str(obj, "command", "say Error"));
                    case "tp_to_player" -> new Action.TpToPlayer(str(obj, "player", ""));
                    case "look_at_player" -> new Action.LookAtPlayer(str(obj, "player", ""));
                    case "pathfind" -> new Action.Pathfind(
                            num(obj, "x", 0), num(obj, "y", 64), num(obj, "z", 0));
                    case "follow_player" -> new Action.FollowPlayer(str(obj, "player", ""));
                    case "mine" -> new Action.Mine(
                            str(obj, "block", "diamond_ore"), num(obj, "quantity", 1));
                    case "give_item" -> new Action.GiveItem(
                            str(obj, "player", ""),
                            str(obj, "item", "diamond"),
                            num(obj, "count", 1),
                            bool(obj, "drop", false));
                    case "save_memory" -> new Action.SaveMemory(
                            str(obj, "category", "general"),
                            str(obj, "subject", ""),
                            str(obj, "content", ""),
                            num(obj, "importance", 5));
                    case "forget_memory" -> new Action.ForgetMemory(
                            str(obj, "subject", ""),
                            str(obj, "category", ""));
                    case "recall_memory" -> new Action.RecallMemory(
                            str(obj, "query", ""));
                    case "stop_task" -> new Action.StopTask();
                    case "observe" -> new Action.Observe(Math.min(num(obj, "seconds", 3), 15));
                    case "delay" -> new Action.Delay(Math.max(1, Math.min(num(obj, "seconds", 1), 60)));
                    case "wait" -> new Action.Wait(Math.max(1, Math.min(num(obj, "seconds", 1), 60)));
                    case "nothing" -> new Action.Nothing();
                    case "use_item_on_block" -> new Action.UseItemOnBlock(
                            num(obj, "x", 0), num(obj, "y", 64), num(obj, "z", 0));
                    case "use_item_on_entity" -> new Action.UseItemOnEntity(
                            str(obj, "target", ""));
                    case "select_slot" -> new Action.SelectSlot(
                            Math.max(0, Math.min(num(obj, "slot", 0), 8)));
                    case "attack" -> new Action.Attack(
                            str(obj, "target", ""));
                    case "backup_inventory" -> new Action.BackupInventory(
                            str(obj, "player", ""));
                    case "restore_inventory" -> new Action.RestoreInventory(
                            str(obj, "player", ""));
                    case "run_script" -> {
                        List<String> cmds = new ArrayList<>();
                        if (obj.has("commands") && obj.get("commands").isJsonArray()) {
                            for (JsonElement cmd : obj.getAsJsonArray("commands")) {
                                if (!cmd.isJsonNull()) cmds.add(cmd.getAsString());
                            }
                        }
                        yield new Action.RunScript(cmds,
                                Math.max(1, Math.min(num(obj, "delay_ticks", 1), 20)),
                                Math.max(1, Math.min(num(obj, "repeat", 1), 1000)),
                                str(obj, "stop_condition", ""));
                    }
                    case "learn_skill" -> {
                        List<String> cmds = new ArrayList<>();
                        if (obj.has("commands") && obj.get("commands").isJsonArray()) {
                            for (JsonElement cmd : obj.getAsJsonArray("commands")) {
                                if (!cmd.isJsonNull()) cmds.add(cmd.getAsString());
                            }
                        }
                        List<String> triggers = new ArrayList<>();
                        if (obj.has("trigger_phrases") && obj.get("trigger_phrases").isJsonArray()) {
                            for (JsonElement t : obj.getAsJsonArray("trigger_phrases")) {
                                if (!t.isJsonNull()) triggers.add(t.getAsString());
                            }
                        }
                        yield new Action.LearnSkill(
                                str(obj, "name", ""),
                                str(obj, "description", ""),
                                cmds, triggers);
                    }
                    case "use_skill" -> new Action.UseSkill(
                            str(obj, "name", ""),
                            str(obj, "context", ""));
                    case "improve_skill" -> {
                        List<String> cmds = new ArrayList<>();
                        if (obj.has("commands") && obj.get("commands").isJsonArray()) {
                            for (JsonElement cmd : obj.getAsJsonArray("commands")) {
                                if (!cmd.isJsonNull()) cmds.add(cmd.getAsString());
                            }
                        }
                        yield new Action.ImproveSkill(
                                str(obj, "name", ""),
                                str(obj, "description", ""),
                                cmds);
                    }
                    case "forget_skill" -> new Action.ForgetSkill(str(obj, "name", ""));
                    case "list_skills" -> new Action.ListSkills();
                    case "build_structure" -> new Action.BuildStructure(
                            str(obj, "description", ""),
                            Math.max(0, Math.min(num(obj, "width", 0), 100)),
                            Math.max(0, Math.min(num(obj, "height", 0), 100)),
                            Math.max(0, Math.min(num(obj, "depth", 0), 100)),
                            str(obj, "style", ""),
                            str(obj, "player", ""));
                    case "download_schematic" -> new Action.DownloadSchematic(
                            str(obj, "url", ""),
                            str(obj, "name", ""));
                    case "place_schematic" -> new Action.PlaceSchematic(
                            str(obj, "name", ""),
                            num(obj, "x", 0),
                            num(obj, "y", 64),
                            num(obj, "z", 0),
                            str(obj, "player", ""));
                    case "list_schematics" -> new Action.ListSchematics();
                    case "undo_build" -> new Action.UndoBuild();
                    case "scan_terrain" -> new Action.ScanTerrain(
                            Math.max(4, Math.min(num(obj, "radius", 8), 16)));
                    default -> {
                        CaineModClient.LOGGER.warn("Unknown action type: {}", type);
                        yield null;
                    }
                };

                if (action != null) actions.add(action);
            } catch (Exception e) {
                CaineModClient.LOGGER.error("Error parsing action element", e);
            }
        }

        return actions;
    }

    private static String str(JsonObject obj, String key, String def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : def;
    }

    private static int num(JsonObject obj, String key, int def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : def;
    }

    private static boolean bool(JsonObject obj, String key, boolean def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsBoolean() : def;
    }
}
