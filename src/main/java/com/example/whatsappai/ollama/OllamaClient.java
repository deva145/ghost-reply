package com.example.whatsappai.ollama;

import com.example.whatsappai.config.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class OllamaClient {
    private final AppConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaClient(AppConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public String generateReply(String chatName, String incomingMessage, List<String> history)
            throws IOException, InterruptedException {
        StringBuilder prompt = new StringBuilder();
        prompt.append(config.systemPrompt()).append("\n\n");
        prompt.append("Identity rules:\n");
        prompt.append("- You are Deva's assistant.\n");
        prompt.append("- If asked who you are, say you are Deva's assistant.\n");
        prompt.append("- Do not say you are the user's assistant or use the chat name as your identity.\n");
        prompt.append("- Keep replies short, natural, and WhatsApp-friendly.\n\n");
        prompt.append("Chat: ").append(chatName).append('\n');

        if (!history.isEmpty()) {
            prompt.append("Recent conversation:\n");
            for (String line : history) {
                prompt.append("- ").append(line).append('\n');
            }
            prompt.append('\n');
        }

        prompt.append("Latest user message:\n");
        prompt.append(incomingMessage).append("\n\n");
        prompt.append("Reply with only the message text.");

        String requestBody = objectMapper.createObjectNode()
                .put("model", config.ollamaModel())
                .put("prompt", prompt.toString())
                .put("stream", false)
                .toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.ollamaBaseUrl() + "/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Ollama request failed: HTTP " + response.statusCode() + " - " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode replyNode = root.get("response");
        if (replyNode == null || replyNode.asText().isBlank()) {
            throw new IOException("Ollama returned an empty response.");
        }

        return replyNode.asText().trim();
    }
}
