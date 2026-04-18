package com.dashie.caine.build;

import com.dashie.caine.CaineModClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Wraps Litematica integration via reflection.
 * Litematica is an optional dependency — if not installed, all methods are no-ops.
 * <p>
 * Uses reflection to access Litematica's schematic loading, placement creation,
 * and paste-to-world functionality. This allows CAINE to:
 * 1. Load .litematic files from disk
 * 2. Create a placement at a specific position
 * 3. Paste the schematic into the world (executes setblock commands)
 */
public class LitematicaWrapper {

    private final boolean available;
    private final boolean printerAvailable;

    // Cached reflection references
    private Class<?> litematicaSchematicClass;
    private Class<?> schematicPlacementClass;
    private Class<?> dataManagerClass;
    private Class<?> schematicPlacementManagerClass;
    private Class<?> layerRangeClass;
    private Class<?> schematicUtilsClass;
    private Class<?> toolModeClass;
    private Class<?> blockPosClass;

    // Methods
    private Method createFromFileMethod;       // LitematicaSchematic.createFromFile(File)
    private Method createForMethod;            // SchematicPlacement.createFor(ISchematic, BlockPos, String, boolean, boolean)
    private Method getSchematicPlacementManagerMethod; // DataManager.getSchematicPlacementManager()
    private Method addSchematicPlacementMethod; // SchematicPlacementManager.addSchematicPlacement(SchematicPlacement, boolean)
    private Method pasteCurrentPlacementToWorldMethod; // SchematicUtils.pasteCurrentPlacementToWorld() or similar

    // Alternate approach: direct command-based pasting
    private boolean useCommandPaste = false;

    public LitematicaWrapper() {
        this.available = FabricLoader.getInstance().isModLoaded("litematica");
        this.printerAvailable = FabricLoader.getInstance().isModLoaded("litematica-printer");
        if (available) {
            initReflection();
            CaineModClient.LOGGER.info("Litematica detected! Schematic loading and placement enabled.");
            if (printerAvailable) {
                CaineModClient.LOGGER.info("Litematica Printer detected! Schematics will be auto-built from hologram placements.");
            }
        } else {
            CaineModClient.LOGGER.info("Litematica not found. Schematic placement will fall back to direct commands.");
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isPrinterAvailable() {
        return printerAvailable;
    }

    private void initReflection() {
        try {
            // Core schematic class
            litematicaSchematicClass = Class.forName("fi.dy.masa.litematica.schematic.LitematicaSchematic");

            // SchematicPlacement — represents a placed schematic in the world
            schematicPlacementClass = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");

            // DataManager — central data access point
            dataManagerClass = Class.forName("fi.dy.masa.litematica.data.DataManager");

            // SchematicPlacementManager
            schematicPlacementManagerClass = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager");

            // SchematicUtils for pasting
            schematicUtilsClass = Class.forName("fi.dy.masa.litematica.util.SchematicUtils");

            // Try to find the createFromFile method
            // LitematicaSchematic.createFromFile(File dir, String fileName)
            try {
                createFromFileMethod = litematicaSchematicClass.getMethod("createFromFile",
                        File.class, String.class);
            } catch (NoSuchMethodException e) {
                // Older versions might have different signature
                try {
                    createFromFileMethod = litematicaSchematicClass.getMethod("createFromFile", File.class);
                } catch (NoSuchMethodException e2) {
                    CaineModClient.LOGGER.warn("Could not find LitematicaSchematic.createFromFile method");
                }
            }

            // DataManager.getSchematicPlacementManager()
            try {
                getSchematicPlacementManagerMethod = dataManagerClass.getMethod("getSchematicPlacementManager");
            } catch (NoSuchMethodException e) {
                CaineModClient.LOGGER.warn("Could not find DataManager.getSchematicPlacementManager method");
            }

            // Find placement creation — varies by Litematica version
            initPlacementMethods();

            CaineModClient.LOGGER.info("Litematica reflection initialized successfully");

        } catch (ClassNotFoundException e) {
            CaineModClient.LOGGER.warn("Litematica classes not found despite mod being loaded: {}", e.getMessage());
        } catch (Exception e) {
            CaineModClient.LOGGER.error("Failed to initialize Litematica reflection", e);
        }
    }

    private void initPlacementMethods() {
        try {
            // SchematicPlacementManager.addSchematicPlacement(SchematicPlacement, boolean)
            if (schematicPlacementManagerClass != null) {
                for (Method m : schematicPlacementManagerClass.getMethods()) {
                    if (m.getName().equals("addSchematicPlacement")) {
                        addSchematicPlacementMethod = m;
                        break;
                    }
                }
            }

            // SchematicPlacement creation — try multiple signatures
            if (schematicPlacementClass != null) {
                for (Method m : schematicPlacementClass.getMethods()) {
                    if (m.getName().equals("createFor") || m.getName().equals("create")) {
                        createForMethod = m;
                        CaineModClient.LOGGER.debug("Found placement creation method: {}", m);
                        break;
                    }
                }
            }

            // Paste method — SchematicUtils
            if (schematicUtilsClass != null) {
                for (Method m : schematicUtilsClass.getMethods()) {
                    String name = m.getName().toLowerCase();
                    if (name.contains("paste") && name.contains("world")) {
                        pasteCurrentPlacementToWorldMethod = m;
                        CaineModClient.LOGGER.debug("Found paste method: {}", m);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            CaineModClient.LOGGER.warn("Failed to find some Litematica placement methods", e);
        }
    }

    /**
     * Loads a .litematic schematic file via Litematica's API.
     * Returns the loaded schematic object (opaque — use with placeSchematic),
     * or null on failure.
     */
    public Object loadSchematic(File file) {
        if (!available || createFromFileMethod == null) return null;

        try {
            Object schematic;
            if (createFromFileMethod.getParameterCount() == 2) {
                // createFromFile(File dir, String fileName)
                schematic = createFromFileMethod.invoke(null, file.getParentFile(), file.getName());
            } else {
                // createFromFile(File)
                schematic = createFromFileMethod.invoke(null, file);
            }

            if (schematic != null) {
                CaineModClient.LOGGER.info("Litematica loaded schematic: {}", file.getName());
            } else {
                CaineModClient.LOGGER.warn("Litematica failed to parse schematic: {}", file.getName());
            }
            return schematic;
        } catch (Exception e) {
            CaineModClient.LOGGER.error("Failed to load schematic via Litematica: {}", file.getName(), e);
            return null;
        }
    }

    /**
     * Creates a schematic placement at the specified position and adds it to
     * Litematica's placement manager. This creates the hologram overlay.
     *
     * @param schematic The loaded schematic object (from loadSchematic)
     * @param x         World X coordinate for placement origin
     * @param y         World Y coordinate
     * @param z         World Z coordinate
     * @param name      Display name for the placement
     * @return The placement object, or null on failure
     */
    public Object createPlacement(Object schematic, int x, int y, int z, String name) {
        if (!available || schematic == null) return null;

        try {
            // Create a BlockPos for the origin
            net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);

            Object placement = null;

            // Try to create placement via reflection
            if (createForMethod != null) {
                Class<?>[] paramTypes = createForMethod.getParameterTypes();
                if (paramTypes.length >= 3) {
                    // Typical: createFor(ISchematic, BlockPos, String, boolean, boolean)
                    // or createFor(ISchematic, BlockPos, String)
                    Object[] args;
                    if (paramTypes.length == 5) {
                        args = new Object[]{schematic, pos, name, true, true};
                    } else if (paramTypes.length == 4) {
                        args = new Object[]{schematic, pos, name, true};
                    } else {
                        args = new Object[]{schematic, pos, name};
                    }
                    placement = createForMethod.invoke(null, args);
                }
            }

            if (placement != null && addSchematicPlacementMethod != null) {
                // Get the placement manager
                Object manager = getSchematicPlacementManagerMethod.invoke(null);
                if (manager != null) {
                    addSchematicPlacementMethod.invoke(manager, placement, true);
                    CaineModClient.LOGGER.info("Litematica placement created at ({}, {}, {}): {}",
                            x, y, z, name);
                }
            }

            return placement;
        } catch (Exception e) {
            CaineModClient.LOGGER.error("Failed to create Litematica placement", e);
            return null;
        }
    }

    /**
     * Attempts to paste the current schematic placement into the world.
     * This uses Litematica's built-in paste feature which generates setblock commands.
     *
     * @return true if paste was initiated successfully
     */
    public boolean pasteCurrentPlacement() {
        if (!available) return false;

        try {
            if (pasteCurrentPlacementToWorldMethod != null) {
                pasteCurrentPlacementToWorldMethod.invoke(null);
                CaineModClient.LOGGER.info("Litematica paste initiated");
                return true;
            } else {
                CaineModClient.LOGGER.warn("Litematica paste method not available");
                return false;
            }
        } catch (Exception e) {
            CaineModClient.LOGGER.error("Failed to paste Litematica placement", e);
            return false;
        }
    }

    /**
     * Full workflow: load a schematic file, create placement, and optionally paste into world.
     * If Litematica Printer is installed, only creates the hologram placement — the printer
     * mod will automatically build from the hologram.
     * If Printer is NOT installed, attempts to use Litematica's built-in paste (Creative mode).
     *
     * @param file The .litematic file to place
     * @param x    World X for placement origin
     * @param y    World Y
     * @param z    World Z
     * @return true if the workflow succeeded (placement created, and paste if applicable)
     */
    public boolean loadAndPlace(File file, int x, int y, int z) {
        if (!available) {
            CaineModClient.LOGGER.info("Litematica not available — cannot place schematic");
            return false;
        }

        Object schematic = loadSchematic(file);
        if (schematic == null) return false;

        String name = file.getName().replaceAll("\\.[^.]+$", "");
        Object placement = createPlacement(schematic, x, y, z, name);
        if (placement == null) {
            CaineModClient.LOGGER.warn("Failed to create placement for {}", file.getName());
            return false;
        }

        // If Litematica Printer is installed, we're done — the printer auto-builds from the hologram
        if (printerAvailable) {
            CaineModClient.LOGGER.info("Schematic hologram placed at ({}, {}, {}). Litematica Printer will auto-build it.",
                    x, y, z);
            return true;
        }

        // No printer — try Litematica's built-in paste (Creative mode / setblock commands)
        // Small delay for Litematica to process
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean pasted = pasteCurrentPlacement();
        if (pasted) {
            CaineModClient.LOGGER.info("Schematic pasted into world via Litematica at ({}, {}, {})", x, y, z);
        } else {
            // Paste failed but hologram is loaded — player can manually use Litematica
            CaineModClient.LOGGER.info("Schematic hologram placed at ({}, {}, {}). Manual paste may be needed.", x, y, z);
        }
        // Return true either way — the hologram is loaded
        return true;
    }

    /**
     * Gets info about Litematica's loaded placements for the prompt.
     *
     * @return Summary string or empty if unavailable
     */
    public String getPlacementInfo() {
        if (!available) return "";

        try {
            if (getSchematicPlacementManagerMethod != null) {
                Object manager = getSchematicPlacementManagerMethod.invoke(null);
                if (manager != null) {
                    // Try to get all placements
                    Method getAllMethod = null;
                    for (Method m : manager.getClass().getMethods()) {
                        if (m.getName().equals("getAllSchematicsPlacements")
                                || m.getName().equals("getSchematicPlacements")
                                || m.getName().equals("getAllPlacements")) {
                            getAllMethod = m;
                            break;
                        }
                    }
                    if (getAllMethod != null) {
                        Object placements = getAllMethod.invoke(manager);
                        if (placements instanceof java.util.Collection<?> coll) {
                            if (coll.isEmpty()) return "";
                            StringBuilder sb = new StringBuilder();
                            sb.append("Litematica placements loaded: ").append(coll.size()).append("\n");
                            int count = 0;
                            for (Object p : coll) {
                                if (count++ >= 5) {
                                    sb.append("  ... and ").append(coll.size() - 5).append(" more\n");
                                    break;
                                }
                                sb.append("  - ").append(p.toString()).append("\n");
                            }
                            return sb.toString();
                        }
                    }
                }
            }
        } catch (Exception e) {
            CaineModClient.LOGGER.debug("Failed to get Litematica placement info", e);
        }
        return "";
    }
}
