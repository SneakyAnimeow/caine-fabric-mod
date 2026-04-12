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

    private final Deque<ChatEntry> history = new ArrayDeque<>();
    // Queue of senders who called CAINE (processed in order)
    private final Queue<String> triggerQueue = new ConcurrentLinkedQueue<>();
    // Tracks the ID of the last message we responded to, so we know what's new
    private volatile long lastRespondedMessageId = 0;
    private volatile long lastMessageTime = 0;
    // Toggled by $switch_auto_state
    private volatile boolean autoMessagesEnabled = true;
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

        // Check if someone mentioned CAINE (normal or screaming)
        if (CAINE_PATTERN.matcher(text).find()) {
            triggerQueue.add(senderName);
            CaineModClient.LOGGER.info("CAINE called by: {} (queue size: {})", senderName, triggerQueue.size());
        }
    }

    public synchronized void onGameMessage(Text message, boolean overlay) {
        if (overlay) return;
        String text = message.getString();
        if (text.isBlank()) return;
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

    public synchronized boolean hasRecentActivity() {
        return System.currentTimeMillis() - lastMessageTime < 300_000;
    }

    public boolean isAutoMessagesEnabled() {
        return autoMessagesEnabled;
    }

    public synchronized int getMessageCount() {
        return history.size();
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
