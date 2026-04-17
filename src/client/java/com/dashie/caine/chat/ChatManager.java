package com.dashie.caine.chat;

import com.dashie.caine.CaineModClient;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ChatManager {
    private static final int MAX_HISTORY = 50;
    // Matches "caine" as a word, or stretched variants like "CAAAAAINE", "caaaine", "caiiine", etc.
    private static final Pattern CAINE_PATTERN = Pattern.compile(
            "\\bc+a+i+n+e+\\b", Pattern.CASE_INSENSITIVE);
    private static final String SWITCH_CMD = "$switch_auto_state";
    private static final String PRO_PREFIX = "$pro ";

    private final Deque<ChatEntry> history = new ArrayDeque<>();
    // Queue of senders who called CAINE (processed in order)
    private final Queue<String> triggerQueue = new ConcurrentLinkedQueue<>();
    // Tracks the ID of the last message we responded to, so we know what's new
    private volatile long lastRespondedMessageId = 0;
    private volatile long lastMessageTime = 0;
    // Toggled by $switch_auto_state
    private volatile boolean autoMessagesEnabled = true;
    // Set when a trigger message starts with $pro
    private volatile boolean proRequested = false;
    // Admin pass check — intercept inventory data response
    private volatile boolean captureAdminCheck = false;
    private volatile String adminCheckResult = null;
    // Condition check — intercept /execute if result for run_script stop_condition
    private volatile boolean captureCondition = false;
    private volatile String conditionResult = null;
    // Counter used as unique message IDs
    private long messageIdCounter = 0;

    public record ChatEntry(long id, long timestamp, String sender, String message, boolean isSystem, boolean isOwnMessage) {
        public String formatted() {
            if (isOwnMessage) return "<CAINE (you)> " + message;
            if (isSystem) return "[SYSTEM] " + message;
            return "<" + sender + "> " + message;
        }
    }

    public synchronized void onChatMessage(Text message, @Nullable SignedMessage signedMessage,
                                            @Nullable GameProfile sender, MessageType.Parameters params,
                                            Instant receptionTimestamp) {
        String text = message.getString();
        String senderName = sender != null ? sender.getName() : extractSenderFromText(text);

        MinecraftClient client = MinecraftClient.getInstance();
        boolean isOwn = client.player != null && senderName.equals(client.player.getName().getString());

        addEntry(new ChatEntry(++messageIdCounter, System.currentTimeMillis(), senderName, text, false, isOwn));

        // Don't trigger on our own messages
        if (isOwn) return;

        // Check for $switch_auto_state command
        if (text.trim().equalsIgnoreCase(SWITCH_CMD) || text.contains(SWITCH_CMD)) {
            autoMessagesEnabled = !autoMessagesEnabled;
            CaineModClient.LOGGER.info("Auto messages toggled {} by {}", autoMessagesEnabled ? "ON" : "OFF", senderName);
            return;
        }

        // Extract the raw message body (without the "<Sender> " chat prefix)
        String rawBody = signedMessage != null
                ? signedMessage.getSignedContent()
                : stripChatPrefix(text, senderName);

        // Check for $pro prefix — strip it from the stored message and set flag
        String processedText = text;
        boolean isPro = false;
        if (rawBody.trim().toLowerCase().startsWith(PRO_PREFIX)) {
            isPro = true;
            processedText = rawBody.trim().substring(PRO_PREFIX.length());
            // Update the entry in history to strip the $pro prefix
            // (remove last entry and re-add without $pro)
            if (!history.isEmpty() && history.peekLast().id() == messageIdCounter) {
                history.removeLast();
                addEntry(new ChatEntry(messageIdCounter, System.currentTimeMillis(), senderName, processedText, false, isOwn));
            }
        }

        // Check if someone mentioned CAINE (normal or screaming)
        if (CAINE_PATTERN.matcher(processedText).find()) {
            if (isPro) proRequested = true;
            triggerQueue.add(senderName);
            CaineModClient.LOGGER.info("CAINE called by: {} (queue size: {}, pro: {})", senderName, triggerQueue.size(), isPro);
        }
    }

    public synchronized void onGameMessage(Text message, boolean overlay) {
        if (overlay) return;
        String text = message.getString();
        if (text.isBlank()) return;

        // Silently intercept admin check response — don't add to history
        if (captureAdminCheck && text.contains("has the following entity data")) {
            adminCheckResult = text;
            captureAdminCheck = false;
            return;
        }

        // Silently intercept condition check response (Test passed/Test failed)
        if (captureCondition && (text.contains("Test passed") || text.contains("Test failed"))) {
            conditionResult = text;
            captureCondition = false;
            return;
        }

        addEntry(new ChatEntry(++messageIdCounter, System.currentTimeMillis(), "SYSTEM", text, true, false));
    }

    private void addEntry(ChatEntry entry) {
        history.addLast(entry);
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
        lastMessageTime = System.currentTimeMillis();
    }

    private String extractSenderFromText(String message) {
        if (message.startsWith("<")) {
            int end = message.indexOf('>');
            if (end > 1) {
                return message.substring(1, end);
            }
        }
        return "Unknown";
    }

    /** Strips the leading "<SenderName> " prefix from a rendered chat line. */
    private String stripChatPrefix(String text, String senderName) {
        String prefix = "<" + senderName + "> ";
        if (text.startsWith(prefix)) return text.substring(prefix.length());
        // Also handle cases without the angle-bracket wrapper
        if (text.startsWith(senderName + ": ")) return text.substring(senderName.length() + 2);
        return text;
    }

    // --- Trigger queue ---

    public boolean hasPendingTrigger() {
        return !triggerQueue.isEmpty();
    }

    public String consumeTrigger() {
        return triggerQueue.poll();
    }

    // --- Message history with "already responded" tracking ---

    /**
     * Marks the current latest message ID as "responded to".
     * Called after CAINE finishes responding to a trigger.
     */
    public synchronized void markResponded() {
        lastRespondedMessageId = messageIdCounter;
    }

    /**
     * Returns formatted chat history, annotating which messages are NEW
     * (received after the last response) vs already seen.
     */
    public synchronized String getFormattedHistory(int count) {
        return history.stream()
                .skip(Math.max(0, history.size() - count))
                .map(e -> {
                    String prefix = e.id() > lastRespondedMessageId ? "[NEW] " : "";
                    return prefix + e.formatted();
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * Returns the count of messages that arrived AFTER the last response.
     */
    public synchronized int getNewMessageCount() {
        return (int) history.stream().filter(e -> e.id() > lastRespondedMessageId).count();
    }

    /**
     * Returns the raw text of recent NEW messages (for keyword extraction).
     */
    public synchronized String getRecentNewMessageText() {
        return history.stream()
                .filter(e -> e.id() > lastRespondedMessageId && !e.isOwnMessage() && !e.isSystem())
                .map(ChatEntry::message)
                .collect(Collectors.joining(" "));
    }

    public synchronized boolean hasRecentActivity() {
        return System.currentTimeMillis() - lastMessageTime < 300_000;
    }

    public boolean isAutoMessagesEnabled() {
        return autoMessagesEnabled;
    }

    /**
     * Returns and resets the pro model flag.
     * Called when consuming a trigger to check if $pro was requested.
     */
    public boolean consumeProFlag() {
        boolean was = proRequested;
        proRequested = false;
        return was;
    }

    /**
     * Start listening for the admin check inventory response.
     */
    public void startAdminCheck() {
        adminCheckResult = null;
        captureAdminCheck = true;
    }

    /**
     * Consume the admin check result. Returns true if the player has a
     * written book named "Admin Pass" signed by "VarenXD".
     * Resets the capture state regardless.
     */
    public boolean consumeAdminCheck() {
        captureAdminCheck = false;
        String result = adminCheckResult;
        adminCheckResult = null;
        if (result == null) return false;
        return result.contains("written_book")
                && result.contains("Admin Pass")
                && result.contains("VarenXD");
    }

    /**
     * Start listening for a condition check response (Test passed / Test failed).
     */
    public void startConditionCapture() {
        conditionResult = null;
        captureCondition = true;
    }

    /**
     * Consume the condition check result.
     * Returns true if "Test passed" (condition met), false if "Test failed".
     * Returns true (continue) if no response was captured (e.g. sendCommandFeedback off).
     */
    public boolean consumeConditionResult() {
        captureCondition = false;
        String result = conditionResult;
        conditionResult = null;
        if (result == null) return true; // No feedback — assume pass
        return result.contains("Test passed");
    }

    public synchronized int getMessageCount() {
        return history.size();
    }

    /**
     * Injects an internal system message into the chat history.
     * These are visible to CAINE in observe/followup rounds but NOT sent to the server.
     * Use this for action result feedback (download status, build status, etc.).
     */
    public synchronized void addInternalFeedback(String message) {
        addEntry(new ChatEntry(++messageIdCounter, System.currentTimeMillis(),
                "SYSTEM", "[CAINE Internal] " + message, true, false));
        CaineModClient.LOGGER.info("[Feedback] {}", message);
    }

    /**
     * Returns the names of the most recent distinct chat senders (excluding system and self).
     */
    public synchronized List<String> getRecentSenders(int maxCount) {
        List<String> senders = new ArrayList<>();
        Iterator<ChatEntry> it = history.descendingIterator();
        while (it.hasNext() && senders.size() < maxCount) {
            ChatEntry entry = it.next();
            if (!entry.isSystem() && !entry.isOwnMessage()
                    && !senders.contains(entry.sender())) {
                senders.add(entry.sender());
            }
        }
        return senders;
    }
}
