package com.dashie.caine.ai;

import com.dashie.caine.CaineModClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates Minecraft build commands for complex structures by making a dedicated
 * Gemini CLI call with a specialized structure-generation prompt.
 * This leverages gemini-cli's ability to reason about 3D space and architecture
 * without being constrained by CAINE's personality system prompt.
 */
public class StructureGenerator {

    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[\\s*\"[^\"]*\".*?\\]", Pattern.DOTALL);
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```(?:json)?\\s*\\n?(\\[.*?\\])\\s*```", Pattern.DOTALL);

    private final GeminiRunner geminiRunner;

    public StructureGenerator(GeminiRunner geminiRunner) {
        this.geminiRunner = geminiRunner;
    }

    /**
     * Generates Minecraft build commands for a described structure.
     *
     * @param description What to build (e.g. "a medieval castle with towers and a moat")
     * @param width       Approximate width (X axis), 0 = let AI decide
     * @param height      Approximate height (Y axis), 0 = let AI decide
     * @param depth       Approximate depth (Z axis), 0 = let AI decide
     * @param style       Architectural style hint (e.g. "medieval", "modern", "japanese"), empty = any
     * @param posX        Current X position (for relative coordinates)
     * @param posY        Current Y position (for relative coordinates)
     * @param posZ        Current Z position (for relative coordinates)
     * @param usePro      Whether to use the pro model for better quality
     * @return List of Minecraft commands to execute
     */
    public List<String> generate(String description, int width, int height, int depth,
                                  String style, double posX, double posY, double posZ,
                                  boolean usePro) {
        String prompt = buildStructurePrompt(description, width, height, depth, style, posX, posY, posZ);

        try {
            CaineModClient.LOGGER.info("Generating structure: '{}' ({}x{}x{}, style: {})",
                    description, width, height, depth, style.isEmpty() ? "any" : style);

            String response = geminiRunner.sendRawPromptSync(prompt, usePro);
            List<String> commands = parseCommandsFromResponse(response);

            if (commands.isEmpty()) {
                CaineModClient.LOGGER.warn("Structure generator returned no commands for: {}", description);
                return List.of();
            }

            CaineModClient.LOGGER.info("Structure generator produced {} commands for '{}'", commands.size(), description);
            return commands;

        } catch (Exception e) {
            CaineModClient.LOGGER.error("Failed to generate structure: {}", description, e);
            return List.of();
        }
    }

    private String buildStructurePrompt(String description, int width, int height, int depth,
                                         String style, double posX, double posY, double posZ) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are a Minecraft structure architect. Generate Minecraft Java Edition commands to build the described structure.\n\n");

        sb.append("RULES:\n");
        sb.append("- Use ONLY /fill and /setblock commands (no leading slash in output)\n");
        sb.append("- Use RELATIVE coordinates (~) based on the player's current position\n");
        sb.append("- ~0 ~0 ~0 is the player's feet. Build starting at ~1 ~0 ~1 to avoid trapping the player\n");
        sb.append("- Use Minecraft Java Edition 1.21+ block IDs (e.g. oak_planks, stone_bricks, glass_pane)\n");
        sb.append("- For /fill: use 'hollow' to create walls with air inside, 'outline' for just edges, 'replace' for substitution\n");
        sb.append("- Build layer by layer from bottom to top for best results\n");
        sb.append("- Include interior details: furniture, lighting, doors, windows, stairs, decorations\n");
        sb.append("- Make the structure visually impressive and detailed\n");
        sb.append("- Add exterior details: paths, landscaping, lighting, fences where appropriate\n");
        sb.append("- Use a variety of block types for visual interest (mix materials, use stairs/slabs for detail)\n");
        sb.append("- Output ONLY a JSON array of command strings. No explanation. No markdown. Just the raw JSON array.\n\n");

        sb.append("STRUCTURE REQUEST:\n");
        sb.append("Description: ").append(description).append("\n");

        if (width > 0 || height > 0 || depth > 0) {
            sb.append("Approximate dimensions: ");
            if (width > 0) sb.append("width=").append(width).append(" ");
            if (height > 0) sb.append("height=").append(height).append(" ");
            if (depth > 0) sb.append("depth=").append(depth).append(" ");
            sb.append("\n");
        }

        if (!style.isEmpty()) {
            sb.append("Style: ").append(style).append("\n");
        }

        sb.append("\nPlayer position: ").append((int) posX).append(", ").append((int) posY).append(", ").append((int) posZ).append("\n");
        sb.append("Build using relative coordinates from the player's current position.\n\n");

        sb.append("OUTPUT FORMAT: A JSON array of command strings, e.g.:\n");
        sb.append("[\"fill ~1 ~0 ~1 ~10 ~0 ~10 stone\",\"fill ~1 ~1 ~1 ~10 ~4 ~10 oak_planks hollow\",\"setblock ~5 ~1 ~1 oak_door[half=lower]\"]\n\n");
        sb.append("Generate the commands now. Output ONLY the JSON array, nothing else:");

        return sb.toString();
    }

    /**
     * Parses the Gemini response to extract a list of Minecraft commands.
     * Handles various response formats: raw JSON array, JSON in code blocks, JSON envelope.
     */
    private List<String> parseCommandsFromResponse(String rawResponse) {
        String response = extractAIText(rawResponse);
        if (response.isBlank()) return List.of();

        // Strategy 1: Try to parse the entire response as a JSON array
        List<String> commands = tryParseJsonArray(response.trim());
        if (!commands.isEmpty()) return commands;

        // Strategy 2: Extract JSON array from a markdown code block
        Matcher blockMatcher = JSON_BLOCK_PATTERN.matcher(response);
        if (blockMatcher.find()) {
            commands = tryParseJsonArray(blockMatcher.group(1));
            if (!commands.isEmpty()) return commands;
        }

        // Strategy 3: Find any JSON array in the text
        Matcher arrayMatcher = JSON_ARRAY_PATTERN.matcher(response);
        if (arrayMatcher.find()) {
            commands = tryParseJsonArray(arrayMatcher.group());
            if (!commands.isEmpty()) return commands;
        }

        // Strategy 4: Find the first [ and matching ]
        int bracketStart = response.indexOf('[');
        int bracketEnd = findMatchingBracket(response, bracketStart);
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            commands = tryParseJsonArray(response.substring(bracketStart, bracketEnd + 1));
            if (!commands.isEmpty()) return commands;
        }

        // Strategy 5: Line-by-line — each line is a command (strip quotes, slashes)
        commands = new ArrayList<>();
        for (String line : response.split("\n")) {
            String cmd = line.trim()
                    .replaceAll("^[\"',\\[\\]]+", "")
                    .replaceAll("[\"',\\[\\]]+$", "")
                    .trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1).trim();
            if (!cmd.isEmpty() && (cmd.startsWith("fill ") || cmd.startsWith("setblock ")
                    || cmd.startsWith("clone ") || cmd.startsWith("execute "))) {
                commands.add(cmd);
            }
        }

        return commands;
    }

    /**
     * Extracts the AI text from Gemini CLI output (handles JSON envelope format).
     */
    private String extractAIText(String output) {
        if (output == null || output.isBlank()) return "";
        String trimmed = output.trim();

        // Try JSON envelope
        try {
            JsonObject envelope = JsonParser.parseString(trimmed).getAsJsonObject();
            if (envelope.has("response")) {
                return envelope.get("response").getAsString();
            }
        } catch (Exception ignored) {}

        // Try stream JSON — find last "result" line
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

        return trimmed;
    }

    private List<String> tryParseJsonArray(String text) {
        try {
            JsonElement el = JsonParser.parseString(text.trim());
            if (el.isJsonArray()) {
                JsonArray array = el.getAsJsonArray();
                List<String> commands = new ArrayList<>();
                for (JsonElement item : array) {
                    if (!item.isJsonNull()) {
                        String cmd = item.getAsString().trim();
                        // Strip leading slash if present
                        if (cmd.startsWith("/")) cmd = cmd.substring(1).trim();
                        if (!cmd.isEmpty()) {
                            commands.add(cmd);
                        }
                    }
                }
                return commands;
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    private int findMatchingBracket(String text, int start) {
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

            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
