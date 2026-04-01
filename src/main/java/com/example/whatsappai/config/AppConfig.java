package com.example.whatsappai.config;

import io.github.cdimascio.dotenv.Dotenv;

public record AppConfig(
        String ollamaBaseUrl,
        String ollamaModel,
        String systemPrompt,
        long pollIntervalMs,
        int maxMemoryMessages,
        String chromeProfileDir,
        String whatsappWebUrl
) {
    public static AppConfig load() {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMalformed()
                .ignoreIfMissing()
                .load();

        return new AppConfig(
                getOrDefault(dotenv, "OLLAMA_BASE_URL", "http://localhost:11434"),
                getOrDefault(dotenv, "OLLAMA_MODEL", "llama3"),
                getOrDefault(dotenv, "SYSTEM_PROMPT",
                        "You are a concise, helpful WhatsApp assistant. Keep replies short and natural."),
                Long.parseLong(getOrDefault(dotenv, "POLL_INTERVAL_MS", "3000")),
                Integer.parseInt(getOrDefault(dotenv, "MAX_MEMORY_MESSAGES", "6")),
                getOrDefault(dotenv, "CHROME_PROFILE_DIR", "chrome-profile"),
                getOrDefault(dotenv, "WHATSAPP_WEB_URL", "https://web.whatsapp.com/")
        );
    }

    private static String getOrDefault(Dotenv dotenv, String key, String fallback) {
        String value = dotenv.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
