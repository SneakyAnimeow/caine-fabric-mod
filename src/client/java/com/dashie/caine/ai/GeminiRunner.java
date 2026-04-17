package com.dashie.caine.ai;

import com.dashie.caine.CaineModClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Manages Gemini CLI processes with a pre-warm pool to eliminate cold-start latency.
 *
 * <p>Gemini CLI's `-p` flag docs say: "Appended to input on stdin (if any)."
 * We exploit this by spawning processes with `-p ""` (headless mode, empty prompt).
 * The process starts, initializes (loads Node.js modules, authenticates), then blocks
 * waiting for stdin. When a request arrives, we write the prompt to stdin and close it
 * — the process reads stdin, appends "" (the -p value), and processes instantly.
 *
 * <p>Stale processes are refreshed every 5 minutes by the keepalive scheduler.
 */
public class GeminiRunner {
    private static final long TIMEOUT_SECONDS = 120;
    private static final String PRO_MODEL = "pro";
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {5_000, 15_000, 30_000};
    private static final long WARM_PROCESS_MAX_AGE_MS = 5 * 60 * 1000; // 5 minutes
    private static final long KEEPALIVE_CHECK_INTERVAL_MS = 30_000; // 30 seconds

    private final Path systemPromptPath;
    private final String geminiBinary;
    private final ExecutorService executor;
    private volatile boolean processing = false;

    // Pre-warmed processes: already initialized, blocking on stdin
    private Process warmProcess;            // Default model, with CAINE system prompt
    private Process warmRawProcess;         // Default model, without system prompt
    private long warmProcessSpawnedAt;
    private long warmRawProcessSpawnedAt;
    private final Object warmLock = new Object();
    private final ScheduledExecutorService keepaliveScheduler;

    public GeminiRunner(Path systemPromptPath) {
        this.systemPromptPath = systemPromptPath;
        this.geminiBinary = findGeminiBinary();
        CaineModClient.LOGGER.info("Gemini CLI binary: {}", geminiBinary);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CAINE-Gemini");
            t.setDaemon(true);
            return t;
        });

        // Keepalive scheduler for warm process management
        this.keepaliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CAINE-Gemini-Keepalive");
            t.setDaemon(true);
            return t;
        });

        // Pre-warm processes immediately
        ensureWarmProcesses();
        // Periodically check warm processes — respawn dead ones and refresh stale ones
        keepaliveScheduler.scheduleAtFixedRate(this::refreshWarmProcesses,
                KEEPALIVE_CHECK_INTERVAL_MS, KEEPALIVE_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public boolean isProcessing() {
        return processing;
    }

    /**
     * Synchronous call to Gemini CLI with CAINE system prompt.
     */
    public String sendPromptSync(String prompt, boolean usePro) throws Exception {
        return callGemini(prompt, usePro ? PRO_MODEL : null, true);
    }

    /**
     * Synchronous call to Gemini CLI WITHOUT the CAINE system prompt.
     */
    public String sendRawPromptSync(String prompt, boolean usePro) throws Exception {
        return callGemini(prompt, usePro ? PRO_MODEL : null, false);
    }

    public void sendPrompt(String prompt, boolean usePro, Consumer<String> onSuccess, Consumer<Exception> onError) {
        if (processing) {
            onError.accept(new IllegalStateException("Already processing a request"));
            return;
        }

        processing = true;
        CompletableFuture.runAsync(() -> {
            try {
                String response = callGemini(prompt, usePro ? PRO_MODEL : null, true);
                if (usePro) {
                    CaineModClient.LOGGER.info("Used PRO model for this request");
                }
                onSuccess.accept(response);
            } catch (Exception e) {
                CaineModClient.LOGGER.error("Gemini CLI error", e);
                onError.accept(e);
            } finally {
                processing = false;
            }
        }, executor);
    }

    // ======================== WARM PROCESS MANAGEMENT ========================

    /**
     * Ensures warm processes are spawned and ready. Safe to call multiple times.
     */
    private void ensureWarmProcesses() {
        synchronized (warmLock) {
            if (warmProcess == null || !warmProcess.isAlive()) {
                try {
                    warmProcess = spawnWarmProcess(null, true);
                    warmProcessSpawnedAt = System.currentTimeMillis();
                    CaineModClient.LOGGER.info("Pre-warmed Gemini process (with system prompt)");
                } catch (Exception e) {
                    CaineModClient.LOGGER.warn("Failed to pre-warm Gemini process", e);
                    warmProcess = null;
                }
            }
            if (warmRawProcess == null || !warmRawProcess.isAlive()) {
                try {
                    warmRawProcess = spawnWarmProcess(null, false);
                    warmRawProcessSpawnedAt = System.currentTimeMillis();
                    CaineModClient.LOGGER.info("Pre-warmed Gemini raw process (no system prompt)");
                } catch (Exception e) {
                    CaineModClient.LOGGER.warn("Failed to pre-warm raw Gemini process", e);
                    warmRawProcess = null;
                }
            }
        }
    }

    /**
     * Checks warm processes: kills stale ones and respawns dead ones.
     * Runs every 30 seconds to ensure processes are always ready.
     */
    private void refreshWarmProcesses() {
        synchronized (warmLock) {
            long now = System.currentTimeMillis();

            // Check system prompt process
            if (warmProcess != null) {
                boolean alive = warmProcess.isAlive();
                long age = now - warmProcessSpawnedAt;
                if (!alive || age > WARM_PROCESS_MAX_AGE_MS) {
                    if (!alive) {
                        CaineModClient.LOGGER.debug("Warm Gemini process died (age: {}s), respawning", age / 1000);
                    } else {
                        CaineModClient.LOGGER.info("Refreshing stale warm Gemini process (age: {}s)", age / 1000);
                        warmProcess.destroyForcibly();
                    }
                    warmProcess = null;
                }
            }

            // Check raw process
            if (warmRawProcess != null) {
                boolean alive = warmRawProcess.isAlive();
                long age = now - warmRawProcessSpawnedAt;
                if (!alive || age > WARM_PROCESS_MAX_AGE_MS) {
                    if (!alive) {
                        CaineModClient.LOGGER.debug("Warm raw Gemini process died (age: {}s), respawning", age / 1000);
                    } else {
                        CaineModClient.LOGGER.info("Refreshing stale warm raw Gemini process (age: {}s)", age / 1000);
                        warmRawProcess.destroyForcibly();
                    }
                    warmRawProcess = null;
                }
            }

            ensureWarmProcesses();
        }
    }

    /**
     * Takes a pre-warmed process for immediate use. Returns null if none available.
     * A replacement is scheduled to spawn in the background.
     */
    private Process takeWarmProcess(boolean withSystemPrompt) {
        synchronized (warmLock) {
            Process p;
            if (withSystemPrompt) {
                p = warmProcess;
                warmProcess = null;
            } else {
                p = warmRawProcess;
                warmRawProcess = null;
            }
            if (p != null && !p.isAlive()) {
                CaineModClient.LOGGER.warn("Warm process was dead on take, discarding");
                p = null;
            }
            // Schedule replacement pre-warm in background
            keepaliveScheduler.submit(this::ensureWarmProcesses);
            return p;
        }
    }

    // ======================== CORE GEMINI CALLS ========================

    private String callGemini(String prompt, String model, boolean withSystemPrompt) throws Exception {
        Exception lastError = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return callGeminiOnce(prompt, model, withSystemPrompt);
            } catch (Exception e) {
                lastError = e;
                if (attempt < MAX_RETRIES - 1) {
                    long delay = RETRY_DELAYS_MS[attempt];
                    CaineModClient.LOGGER.warn("Gemini CLI attempt {}/{} failed: {}. Retrying in {}s...",
                            attempt + 1, MAX_RETRIES, e.getMessage(), delay / 1000);
                    Thread.sleep(delay);
                }
            }
        }
        throw lastError;
    }

    /**
     * Executes a single Gemini CLI call using a pre-warmed or freshly spawned process.
     * Sends the prompt via stdin and reads the response from stdout.
     */
    private String callGeminiOnce(String prompt, String model, boolean withSystemPrompt) throws Exception {
        // Try to use a pre-warmed process (only available for default model)
        Process process = null;
        boolean usedWarm = false;

        if (model == null) {
            process = takeWarmProcess(withSystemPrompt);
            if (process != null) {
                usedWarm = true;
            }
        }

        // No warm process available — spawn a fresh one
        if (process == null) {
            process = spawnWarmProcess(model, withSystemPrompt);
        }

        CaineModClient.LOGGER.info("Calling Gemini CLI (prompt length: {} chars, warm: {}, system: {})...",
                prompt.length(), usedWarm, withSystemPrompt);
        long start = System.currentTimeMillis();

        // Write prompt to stdin, then close to signal EOF — triggers processing
        try (OutputStream os = process.getOutputStream()) {
            os.write(prompt.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        // Read all output
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Gemini CLI timed out after " + TIMEOUT_SECONDS + " seconds");
        }

        long elapsed = System.currentTimeMillis() - start;
        CaineModClient.LOGGER.info("Gemini CLI responded in {}ms (exit code: {}, warm: {})",
                elapsed, process.exitValue(), usedWarm);

        if (process.exitValue() != 0) {
            CaineModClient.LOGGER.warn("Gemini CLI non-zero exit: {}. Output: {}",
                    process.exitValue(), output.length() > 500 ? output.substring(0, 500) + "..." : output);
            throw new RuntimeException("Gemini CLI failed (exit " + process.exitValue() + "): "
                    + (output.length() > 200 ? output.substring(0, 200) : output));
        }

        if (output.isBlank()) {
            throw new RuntimeException("Gemini CLI returned empty output (exit code: " + process.exitValue() + ")");
        }

        return output;
    }

    // ======================== PROCESS SPAWNING ========================

    /**
     * Spawns a gemini-cli process in headless mode that reads its prompt from stdin.
     * Uses `-p ""` to activate non-interactive mode while keeping the prompt empty —
     * the actual prompt is delivered via stdin (gemini docs: "-p is appended to stdin input").
     *
     * <p>For pre-warming: the process starts, loads Node.js + gemini modules + authenticates,
     * then blocks waiting for stdin data. This eliminates 1-3 seconds of cold-start latency.
     */
    private Process spawnWarmProcess(String model, boolean withSystemPrompt) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(geminiBinary);
        if (model != null && !model.isEmpty()) {
            command.add("-m");
            command.add(model);
        }
        // -p "" enters non-interactive headless mode with an empty prompt suffix.
        // The real prompt comes from stdin — gemini prepends stdin content to the -p value.
        command.add("-p");
        command.add("");

        ProcessBuilder pb = new ProcessBuilder(command);

        if (withSystemPrompt) {
            pb.environment().put("GEMINI_SYSTEM_MD", systemPromptPath.toAbsolutePath().toString());
        }

        String homePath = System.getProperty("user.home");
        String existingPath = pb.environment().getOrDefault("PATH", "/usr/bin:/bin");
        pb.environment().put("PATH", homePath + "/.nvm/versions/node/" + detectNvmNodeVersion() + "/bin:"
                + homePath + "/.bun/bin:/usr/local/bin:/opt/homebrew/bin:" + existingPath);
        pb.environment().put("NO_COLOR", "1");
        pb.environment().put("TERM", "dumb");
        pb.redirectErrorStream(true);

        return pb.start();
    }

    // ======================== STATIC HELPERS ========================

    private static String findGeminiBinary() {
        String[] candidates = {
                System.getProperty("user.home") + "/.nvm/versions/node/" + detectNvmNodeVersion() + "/bin/gemini",
                System.getProperty("user.home") + "/.bun/bin/gemini",
                "/usr/local/bin/gemini",
                "/opt/homebrew/bin/gemini",
                System.getProperty("user.home") + "/.npm-global/bin/gemini",
        };
        for (String path : candidates) {
            if (Files.isExecutable(Path.of(path))) {
                return path;
            }
        }
        return "gemini";
    }

    private static String detectNvmNodeVersion() {
        try {
            Path nvmDir = Path.of(System.getProperty("user.home"), ".nvm", "versions", "node");
            if (Files.isDirectory(nvmDir)) {
                return Files.list(nvmDir)
                        .filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .sorted((a, b) -> b.compareTo(a))
                        .findFirst()
                        .orElse("none");
            }
        } catch (Exception ignored) {}
        return "none";
    }
}
