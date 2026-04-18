package com.dashie.caine.build;

import com.dashie.caine.CaineModClient;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Manages schematic file downloading and placement.
 * Schematics are downloaded to the config/caine/schematics/ directory.
 * Places structures by parsing .schem (Sponge Schematic) or generating commands
 * from litematic data via the structure generator.
 * <p>
 * Since we're a client-side mod, we place blocks using server commands (/setblock, /fill).
 */
public class SchematicManager {

    public static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB max
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);
    // Allowed URL patterns for schematic downloads
    private static final List<String> ALLOWED_HOSTS = List.of(
            "www.schematica.net", "schematica.net",
            "minecraftschematica.com", "www.minecraftschematica.com",
            "litematica.com", "www.litematica.com",
            "raw.githubusercontent.com", "github.com",
            "cdn.discordapp.com", "media.discordapp.net",
            "www.planetminecraft.com", "static.planetminecraft.com",
            "dl.dropboxusercontent.com",
            "drive.google.com", "docs.google.com"
    );
    private static final List<String> ALLOWED_EXTENSIONS = List.of(
            ".litematic", ".schem", ".schematic", ".nbt"
    );

    private final Path schematicsDir;
    private final HttpClient httpClient;

    public SchematicManager(Path configDir) {
        this.schematicsDir = configDir.resolve("schematics");
        try {
            Files.createDirectories(schematicsDir);
        } catch (IOException e) {
            CaineModClient.LOGGER.error("Failed to create schematics directory", e);
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DOWNLOAD_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Downloads a schematic from a URL and saves it locally.
     * Returns the local file path, or null on failure.
     */
    public Path downloadSchematic(String url) {
        return downloadSchematic(url, null);
    }

    /**
     * Downloads a schematic from a URL and saves it locally with an optional name override.
     * Handles special URL formats for Google Drive, Dropbox, etc.
     * Returns the local file path, or null on failure.
     */
    public Path downloadSchematic(String url, String nameOverride) {
        try {
            // Normalize the URL for known hosts (Google Drive, Dropbox, etc.)
            url = normalizeDownloadUrl(url);

            URI uri = URI.create(url);
            String host = uri.getHost();

            // Security: validate host against allowlist
            if (host == null || ALLOWED_HOSTS.stream().noneMatch(h -> host.equalsIgnoreCase(h))) {
                CaineModClient.LOGGER.warn("Blocked schematic download from untrusted host: {}", host);
                return null;
            }

            // Extract filename from URL or use override
            String extractedName;
            if (nameOverride != null && !nameOverride.isBlank()) {
                extractedName = nameOverride;
            } else {
                String path = uri.getPath();
                extractedName = path.substring(path.lastIndexOf('/') + 1);
                if (extractedName.isEmpty() || extractedName.equals("uc")
                        || extractedName.equals("export") || extractedName.equals("download")) {
                    extractedName = "downloaded_schematic";
                }
            }

            // Validate file extension
            final String nameForCheck = extractedName;
            boolean validExt = ALLOWED_EXTENSIONS.stream().anyMatch(ext -> nameForCheck.toLowerCase().endsWith(ext));
            if (!validExt) {
                extractedName = extractedName + ".litematic";
            }

            // Sanitize filename
            String filename = extractedName.replaceAll("[^a-zA-Z0-9._-]", "_");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(DOWNLOAD_TIMEOUT)
                    .header("User-Agent", "CAINE-Minecraft-Bot/1.0")
                    .GET()
                    .build();

            CaineModClient.LOGGER.info("Downloading schematic from: {} (resolved from original URL)", url);
            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                CaineModClient.LOGGER.warn("Schematic download failed: HTTP {}", response.statusCode());
                return null;
            }

            // Try to extract a better filename from Content-Disposition header
            String contentDisposition = response.headers().firstValue("Content-Disposition").orElse("");
            if (!contentDisposition.isEmpty()) {
                String cdFilename = extractFilenameFromContentDisposition(contentDisposition);
                if (cdFilename != null && (nameOverride == null || nameOverride.isBlank())) {
                    final String cdCheck = cdFilename;
                    boolean cdValidExt = ALLOWED_EXTENSIONS.stream().anyMatch(ext -> cdCheck.toLowerCase().endsWith(ext));
                    if (cdValidExt) {
                        filename = cdFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
                    }
                }
            }

            // Check content length
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (contentLength > MAX_FILE_SIZE) {
                CaineModClient.LOGGER.warn("Schematic too large: {} bytes (max {})", contentLength, MAX_FILE_SIZE);
                return null;
            }

            Path targetPath = schematicsDir.resolve(filename);
            try (InputStream is = response.body()) {
                // Read with size limit
                byte[] data = is.readNBytes((int) MAX_FILE_SIZE + 1);
                if (data.length > MAX_FILE_SIZE) {
                    CaineModClient.LOGGER.warn("Schematic download exceeded size limit");
                    return null;
                }
                Files.write(targetPath, data);
            }

            CaineModClient.LOGGER.info("Schematic saved: {} ({} bytes)", targetPath.getFileName(), Files.size(targetPath));
            return targetPath;

        } catch (Exception e) {
            CaineModClient.LOGGER.error("Failed to download schematic: {}", url, e);
            return null;
        }
    }

    /**
     * Normalizes download URLs for known file hosting services that don't serve
     * direct downloads from their share/view URLs.
     * <p>
     * Supported transformations:
     * - Google Drive: file/d/ID/view → uc?export=download&id=ID
     * - Google Drive: open?id=ID → uc?export=download&id=ID
     * - Dropbox: ?dl=0 → ?dl=1, or append ?dl=1 if missing
     */
    private String normalizeDownloadUrl(String url) {
        if (url == null) return url;

        // Google Drive share links: https://drive.google.com/file/d/FILE_ID/view?usp=sharing
        java.util.regex.Matcher driveMatcher = java.util.regex.Pattern
                .compile("drive\\.google\\.com/file/d/([a-zA-Z0-9_-]+)")
                .matcher(url);
        if (driveMatcher.find()) {
            String fileId = driveMatcher.group(1);
            String directUrl = "https://drive.google.com/uc?export=download&id=" + fileId;
            CaineModClient.LOGGER.info("Converted Google Drive share URL to direct download: {}", directUrl);
            return directUrl;
        }

        // Google Drive open links: https://drive.google.com/open?id=FILE_ID
        driveMatcher = java.util.regex.Pattern
                .compile("drive\\.google\\.com/open\\?id=([a-zA-Z0-9_-]+)")
                .matcher(url);
        if (driveMatcher.find()) {
            String fileId = driveMatcher.group(1);
            String directUrl = "https://drive.google.com/uc?export=download&id=" + fileId;
            CaineModClient.LOGGER.info("Converted Google Drive open URL to direct download: {}", directUrl);
            return directUrl;
        }

        // Google Docs export: https://docs.google.com/uc?export=download&id=FILE_ID (already direct)
        // No transformation needed

        // Dropbox: change ?dl=0 to ?dl=1, or add ?dl=1
        if (url.contains("dropbox.com")) {
            if (url.contains("dl=0")) {
                url = url.replace("dl=0", "dl=1");
                CaineModClient.LOGGER.info("Converted Dropbox URL to direct download (dl=1)");
            } else if (!url.contains("dl=1")) {
                url = url + (url.contains("?") ? "&dl=1" : "?dl=1");
                CaineModClient.LOGGER.info("Appended dl=1 to Dropbox URL for direct download");
            }
        }

        return url;
    }

    /**
     * Extracts filename from a Content-Disposition header value.
     * Handles both: filename="name.ext" and filename*=UTF-8''name.ext
     */
    private String extractFilenameFromContentDisposition(String header) {
        // Try filename*= first (RFC 6266)
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("filename\\*=(?:UTF-8''|utf-8'')(.+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(header);
        if (m.find()) {
            try {
                return java.net.URLDecoder.decode(m.group(1).trim().replaceAll("[;\"]", ""),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        // Try filename= (basic)
        m = java.util.regex.Pattern.compile("filename=\"?([^\";\n]+)\"?").matcher(header);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    /**
     * Lists all locally saved schematic files.
     */
    public List<String> listSchematics() {
        List<String> names = new ArrayList<>();
        try {
            if (Files.isDirectory(schematicsDir)) {
                Files.list(schematicsDir)
                        .filter(p -> ALLOWED_EXTENSIONS.stream().anyMatch(ext ->
                                p.getFileName().toString().toLowerCase().endsWith(ext)))
                        .forEach(p -> names.add(p.getFileName().toString()));
            }
        } catch (IOException e) {
            CaineModClient.LOGGER.error("Failed to list schematics", e);
        }
        return names;
    }

    /**
     * Gets the path to a locally stored schematic by name.
     */
    public Path getSchematicPath(String name) {
        Path path = schematicsDir.resolve(name);
        if (Files.exists(path)) return path;
        // Try with common extensions
        for (String ext : ALLOWED_EXTENSIONS) {
            Path withExt = schematicsDir.resolve(name + ext);
            if (Files.exists(withExt)) return withExt;
        }
        return null;
    }

    /**
     * Deletes a local schematic file.
     */
    public boolean deleteSchematic(String name) {
        Path path = getSchematicPath(name);
        if (path != null) {
            try {
                Files.deleteIfExists(path);
                CaineModClient.LOGGER.info("Deleted schematic: {}", name);
                return true;
            } catch (IOException e) {
                CaineModClient.LOGGER.error("Failed to delete schematic: {}", name, e);
            }
        }
        return false;
    }

    // ======================== SCHEMATIC → BUILD COMMANDS ========================

    private static final int MAX_SCHEMATIC_BLOCKS = 50_000;

    /**
     * Bounding box of a parsed schematic.
     */
    public record SchematicSize(int sizeX, int sizeY, int sizeZ) {}

    /**
     * Parses a schematic file and generates /setblock commands to build it.
     * Supports .litematic format (Litematica). CAINE has OP, so it executes
     * /setblock commands directly — no dependency on Litematica's paste.
     *
     * @param file    Path to the schematic file
     * @param originX World X for the schematic origin
     * @param originY World Y
     * @param originZ World Z
     * @return List of /setblock commands, empty if parsing failed
     */
    public List<String> schematicToCommands(Path file, int originX, int originY, int originZ) {
        String format = detectSchematicFormat(file);
        if ("litematic".equals(format)) {
            return parseLitematic(file, originX, originY, originZ);
        } else if ("schem".equals(format)) {
            return parseSchem(file, originX, originY, originZ);
        }
        CaineModClient.LOGGER.warn("Could not detect schematic format for: {}", file.getFileName());
        return List.of();
    }

    /**
     * Gets the bounding box size of a .litematic schematic from its metadata.
     * Returns null if the file cannot be read.
     */
    public SchematicSize getSchematicSize(Path file) {
        try {
            NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
            // Detect by content: .schem has Width/Height/Length or Schematic wrapper
            if (root.contains("Width") || root.contains("Schematic", NbtElement.COMPOUND_TYPE)) {
                NbtCompound data = root.contains("Schematic", NbtElement.COMPOUND_TYPE)
                        ? root.getCompound("Schematic") : root;
                return new SchematicSize(
                        data.getShort("Width"),
                        data.getShort("Height"),
                        data.getShort("Length"));
            }
            // .litematic has Metadata.EnclosingSize
            NbtCompound metadata = root.getCompound("Metadata");
            NbtCompound enclosing = metadata.getCompound("EnclosingSize");
            return new SchematicSize(
                    Math.abs(enclosing.getInt("x")),
                    Math.abs(enclosing.getInt("y")),
                    Math.abs(enclosing.getInt("z")));
        } catch (Exception e) {
            CaineModClient.LOGGER.warn("Failed to read schematic size: {}", file, e);
            return null;
        }
    }

    /**
     * Detects the schematic format by reading the NBT content, regardless of file extension.
     * Returns "litematic", "schem", or null if unrecognized.
     */
    private String detectSchematicFormat(Path file) {
        try {
            NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
            // Sponge Schematic v3: has "Schematic" compound wrapper
            if (root.contains("Schematic", NbtElement.COMPOUND_TYPE)) return "schem";
            // Sponge Schematic v2: has Width, Height, Length at top level
            if (root.contains("Width") && root.contains("Height") && root.contains("Length")) return "schem";
            // Litematica: has Regions compound
            if (root.contains("Regions", NbtElement.COMPOUND_TYPE)) return "litematic";
            // Fallback: try file extension
            String name = file.getFileName().toString().toLowerCase();
            if (name.endsWith(".litematic")) return "litematic";
            if (name.endsWith(".schem") || name.endsWith(".schematic")) return "schem";
            CaineModClient.LOGGER.warn("Could not detect schematic format from content or extension: {}", file.getFileName());
            return null;
        } catch (Exception e) {
            CaineModClient.LOGGER.warn("Failed to read schematic for format detection: {}", file.getFileName(), e);
            return null;
        }
    }

    /**
     * Parses a .litematic file and returns /setblock commands.
     * Supports all Litematica format versions (1-4). Always uses tight/spanning bit packing.
     */
    private List<String> parseLitematic(Path file, int originX, int originY, int originZ) {
        try {
            NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
            int version = root.getInt("Version");
            CaineModClient.LOGGER.info("Parsing .litematic v{} → setblock commands", version);

            NbtCompound regions = root.getCompound("Regions");
            List<String> commands = new ArrayList<>();

            for (String regionName : regions.getKeys()) {
                if (commands.size() >= MAX_SCHEMATIC_BLOCKS) {
                    CaineModClient.LOGGER.warn("Reached max block limit ({}) — truncating", MAX_SCHEMATIC_BLOCKS);
                    break;
                }
                NbtCompound region = regions.getCompound(regionName);
                parseLitematicRegion(region, originX, originY, originZ, commands);
            }

            CaineModClient.LOGGER.info("Generated {} setblock commands from .litematic", commands.size());
            return commands;

        } catch (Exception e) {
            CaineModClient.LOGGER.error("Failed to parse .litematic file: {}", file, e);
            return List.of();
        }
    }

    /**
     * Parses a .schem (Sponge Schematic v2/v3) file and returns /setblock commands.
     * Supports both v2 (flat structure) and v3 (nested under "Schematic" compound).
     * Block data is varint-encoded, blocks are indexed in Y*Length*Width + Z*Width + X order.
     */
    private List<String> parseSchem(Path file, int originX, int originY, int originZ) {
        try {
            NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());

            // v3 wraps everything under "Schematic", v2 is flat
            NbtCompound data = root.contains("Schematic", NbtElement.COMPOUND_TYPE)
                    ? root.getCompound("Schematic") : root;

            int version = data.getInt("Version");
            int width = data.getShort("Width");
            int height = data.getShort("Height");
            int length = data.getShort("Length");

            CaineModClient.LOGGER.info("Parsing .schem v{} ({}x{}x{}) → setblock commands",
                    version, width, height, length);

            // Offset (optional) — shifts the schematic origin
            int offX = 0, offY = 0, offZ = 0;
            if (data.contains("Offset", NbtElement.INT_ARRAY_TYPE)) {
                int[] offset = data.getIntArray("Offset");
                if (offset.length >= 3) {
                    offX = offset[0];
                    offY = offset[1];
                    offZ = offset[2];
                }
            }

            // Read palette and block data — location differs between v2 and v3
            NbtCompound paletteNbt;
            byte[] blockData;

            if (version >= 3 && data.contains("Blocks", NbtElement.COMPOUND_TYPE)) {
                // v3: Blocks.Palette and Blocks.Data
                NbtCompound blocks = data.getCompound("Blocks");
                paletteNbt = blocks.getCompound("Palette");
                blockData = blocks.getByteArray("Data");
            } else {
                // v2: Palette and BlockData at top level
                paletteNbt = data.getCompound("Palette");
                blockData = data.getByteArray("BlockData");
            }

            if (paletteNbt.isEmpty() || blockData.length == 0) {
                CaineModClient.LOGGER.warn(".schem file has empty palette or block data");
                return List.of();
            }

            // Build reverse palette: int ID → block state string
            String[] palette = new String[paletteNbt.getSize()];
            for (String blockState : paletteNbt.getKeys()) {
                int id = paletteNbt.getInt(blockState);
                if (id >= 0 && id < palette.length) {
                    palette[id] = blockState;
                }
            }

            // Decode varint block data and generate commands
            List<String> commands = new ArrayList<>();
            int index = 0;
            int dataPos = 0;

            while (dataPos < blockData.length && commands.size() < MAX_SCHEMATIC_BLOCKS) {
                // Read varint
                int paletteIndex = 0;
                int varintLength = 0;
                int currentByte;
                do {
                    if (dataPos >= blockData.length) break;
                    currentByte = blockData[dataPos] & 0xFF;
                    paletteIndex |= (currentByte & 0x7F) << (varintLength * 7);
                    varintLength++;
                    dataPos++;
                } while ((currentByte & 0x80) != 0 && varintLength < 5);

                if (paletteIndex >= 0 && paletteIndex < palette.length && palette[paletteIndex] != null) {
                    String blockState = palette[paletteIndex];
                    if (!isAirBlock(blockState)) {
                        // Index order: Y * length * width + Z * width + X
                        int x = index % width;
                        int z = (index / width) % length;
                        int y = index / (width * length);

                        int worldX = originX + offX + x;
                        int worldY = originY + offY + y;
                        int worldZ = originZ + offZ + z;

                        commands.add("setblock " + worldX + " " + worldY + " " + worldZ + " " + blockState);
                    }
                }
                index++;
            }

            CaineModClient.LOGGER.info("Generated {} setblock commands from .schem", commands.size());
            return commands;

        } catch (Exception e) {
            CaineModClient.LOGGER.error("Failed to parse .schem file: {}", file, e);
            return List.of();
        }
    }

    /**
     * Parses a single region within a .litematic file.
     */
    private void parseLitematicRegion(NbtCompound region, int originX, int originY, int originZ,
                                      List<String> commands) {
        // Region position (relative to schematic origin)
        NbtCompound posTag = region.getCompound("Position");
        int rpx = posTag.getInt("x");
        int rpy = posTag.getInt("y");
        int rpz = posTag.getInt("z");

        // Region size (can be negative — indicates selection was made in negative direction)
        NbtCompound sizeTag = region.getCompound("Size");
        int sizeX = sizeTag.getInt("x");
        int sizeY = sizeTag.getInt("y");
        int sizeZ = sizeTag.getInt("z");

        int absSizeX = Math.abs(sizeX);
        int absSizeY = Math.abs(sizeY);
        int absSizeZ = Math.abs(sizeZ);
        if (absSizeX == 0 || absSizeY == 0 || absSizeZ == 0) return;

        // Compute the min corner of the region in world space.
        // Position is the origin corner; negative size means blocks extend in -dir.
        int minX = rpx + Math.min(0, sizeX + 1);
        int minY = rpy + Math.min(0, sizeY + 1);
        int minZ = rpz + Math.min(0, sizeZ + 1);

        // Block state palette
        NbtList palette = region.getList("BlockStatePalette", NbtElement.COMPOUND_TYPE);
        if (palette.isEmpty()) return;

        String[] paletteEntries = new String[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            paletteEntries[i] = nbtToBlockState(palette.getCompound(i));
        }

        // Packed block state data (always tight/spanning format in .litematic)
        long[] blockStates = region.getLongArray("BlockStates");
        if (blockStates.length == 0) return;

        int bitsPerEntry = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        long mask = (1L << bitsPerEntry) - 1;

        CaineModClient.LOGGER.info("Region: size={}x{}x{}, palette={}, bits={}, longs={}",
                absSizeX, absSizeY, absSizeZ, palette.size(), bitsPerEntry, blockStates.length);

        // Iterate all blocks in Y-Z-X order and generate setblock commands
        for (int y = 0; y < absSizeY; y++) {
            for (int z = 0; z < absSizeZ; z++) {
                for (int x = 0; x < absSizeX; x++) {
                    if (commands.size() >= MAX_SCHEMATIC_BLOCKS) return;

                    int index = (y * absSizeZ + z) * absSizeX + x;
                    int paletteIndex = unpackTightEntry(blockStates, index, bitsPerEntry, mask);

                    if (paletteIndex < 0 || paletteIndex >= paletteEntries.length) continue;

                    String blockState = paletteEntries[paletteIndex];
                    if (isAirBlock(blockState)) continue;

                    int worldX = originX + minX + x;
                    int worldY = originY + minY + y;
                    int worldZ = originZ + minZ + z;

                    commands.add("setblock " + worldX + " " + worldY + " " + worldZ + " " + blockState);
                }
            }
        }
    }

    /**
     * Unpacks a block palette index from a tight/spanning packed long array.
     * .litematic ALWAYS uses tight packing — entries CAN span across two longs.
     * Uses long arithmetic to avoid integer overflow on large schematics.
     */
    private int unpackTightEntry(long[] data, int index, int bitsPerEntry, long mask) {
        long startOffset = (long) index * bitsPerEntry;
        int startArrIndex = (int) (startOffset >>> 6);       // divide by 64
        int endArrIndex = (int) (((long) (index + 1) * bitsPerEntry - 1) >>> 6);
        int startBitOffset = (int) (startOffset & 0x3F);     // mod 64

        if (startArrIndex >= data.length) return 0;

        if (startArrIndex == endArrIndex) {
            // Entry fits within one long
            return (int) ((data[startArrIndex] >>> startBitOffset) & mask);
        } else {
            // Entry spans two longs
            if (endArrIndex >= data.length) return 0;
            int endOffset = 64 - startBitOffset;
            return (int) (((data[startArrIndex] >>> startBitOffset)
                    | (data[endArrIndex] << endOffset)) & mask);
        }
    }

    /**
     * Converts an NBT block state palette entry to a /setblock-compatible string.
     * e.g. {Name:"minecraft:oak_stairs", Properties:{facing:"north"}} → "minecraft:oak_stairs[facing=north]"
     */
    private String nbtToBlockState(NbtCompound entry) {
        String name = entry.getString("Name");
        if (!entry.contains("Properties", NbtElement.COMPOUND_TYPE)) {
            return name;
        }
        NbtCompound props = entry.getCompound("Properties");
        if (props.isEmpty()) return name;

        StringBuilder sb = new StringBuilder(name);
        sb.append('[');
        boolean first = true;
        for (String key : props.getKeys()) {
            if (!first) sb.append(',');
            sb.append(key).append('=').append(props.getString(key));
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    private boolean isAirBlock(String blockState) {
        return blockState.equals("minecraft:air")
                || blockState.equals("minecraft:cave_air")
                || blockState.equals("minecraft:void_air");
    }
}
