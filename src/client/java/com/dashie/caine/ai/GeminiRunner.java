package com.dashie.caine.ai;

import com.dashie.caine.CaineModClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class GeminiRunner {
    private static final long TIMEOUT_SECONDS = 120;
    private static final String PRO_MODEL = "pro";
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {5_000, 15_000, 30_000};

    private final Path systemPromptPath;
    private final String geminiBinary;
    private final ExecutorService executor;
    private volatile boolean processing = false;

    public GeminiRunner(Path systemPromptPath) {
        this.systemPromptPath = systemPromptPath;
        this.geminiBinary = findGeminiBinary();
        CaineModClient.LOGGER.info("Gemini CLI binary: {}", geminiBinary);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CAINE-Gemini");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean isProcessing() {
        return processing;
    }

    /**
     * Synchronous call to Gemini CLI. Blocks until response is received.
     * Used for followup rounds during observe-think-act loops.
     * Does NOT check/set the processing flag (caller manages that).
     */
    public String sendPromptSync(String prompt, boolean usePro) throws Exception {
        return callGemini(prompt, usePro ? PRO_MODEL : null);
    }

    public void sendPrompt(String prompt, boolean usePro, Consumer<String> onSuccess, Consumer<Exception> onError) {
        if (processing) {
            onError.accept(new IllegalStateException("Already processing a request"));
            return;
        }

        processing = true;
        CompletableFuture.runAsync(() -> {
            try {
                String response = callGemini(prompt, usePro ? PRO_MODEL : null);
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

    private static String findGeminiBinary() {
        // Try common locations for the gemini CLI binary
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
        // Fallback: hope it's on PATH
        return "gemini";
    }

    private static String detectNvmNodeVersion() {
        try {
            Path nvmDir = Path.of(System.getProperty("user.home"), ".nvm", "versions", "node");
            if (Files.isDirectory(nvmDir)) {
                // Find the latest (or any) node version directory
                return Files.list(nvmDir)
                        .filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .sorted((a, b) -> b.compareTo(a)) // reverse lexicographic = latest first
                        .findFirst()
                        .orElse("none");
            }
        } catch (Exception ignored) {}
        return "none";
    }

    private String callGemini(String prompt, String model) throws Exception {
        Exception lastError = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return callGeminiOnce(prompt, model);
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

    private String callGeminiOnce(String prompt, String model) throws Exception {
        // Build process: gemini [-m model] -p "<prompt>"
        // System prompt is set via GEMINI_SYSTEM_MD env var
        List<String> command = new ArrayList<>();
        command.add(geminiBinary);
        if (model != null && !model.isEmpty()) {
            command.add("-m");
            command.add(model);
        }
        command.add("-p");
        command.add(prompt);
        ProcessBuilder pb = new ProcessBuilder(command);

        // Set system prompt path
        pb.environment().put("GEMINI_SYSTEM_MD", systemPromptPath.toAbsolutePath().toString());
        // Inherit user PATH so gemini can find node, etc.
        String homePath = System.getProperty("user.home");
        String existingPath = pb.environment().getOrDefault("PATH", "/usr/bin:/bin");
        pb.environment().put("PATH", homePath + "/.nvm/versions/node/" + detectNvmNodeVersion() + "/bin:"
                + homePath + "/.bun/bin:/usr/local/bin:/opt/homebrew/bin:" + existingPath);
        // Disable color/formatting for clean output
        pb.environment().put("NO_COLOR", "1");
        pb.environment().put("TERM", "dumb");
        pb.redirectErrorStream(true);

        CaineModClient.LOGGER.info("Calling Gemini CLI (prompt length: {} chars)...", prompt.length());
        long start = System.currentTimeMillis();

        Process process = pb.start();

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
        CaineModClient.LOGGER.info("Gemini CLI responded in {}ms (exit code: {})", elapsed, process.exitValue());

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
}
