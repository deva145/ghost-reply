package com.example.whatsappai.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessageMemory {
    private final Map<String, Deque<String>> memoryByChat = new ConcurrentHashMap<>();
    private final int maxMessages;

    public MessageMemory(int maxMessages) {
        this.maxMessages = Math.max(1, maxMessages);
    }

    public void rememberUserMessage(String chatName, String message) {
        append(chatName, "User: " + message);
    }

    public void rememberAssistantMessage(String chatName, String message) {
        append(chatName, "Assistant: " + message);
    }

    public List<String> historyFor(String chatName) {
        Deque<String> deque = memoryByChat.get(chatName);
        return deque == null ? List.of() : new ArrayList<>(deque);
    }

    private void append(String chatName, String message) {
        Deque<String> deque = memoryByChat.computeIfAbsent(chatName, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(message);
            while (deque.size() > maxMessages) {
                deque.removeFirst();
            }
        }
    }
}
