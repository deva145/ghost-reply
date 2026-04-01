package com.example.whatsappai;

import com.example.whatsappai.config.AppConfig;
import com.example.whatsappai.memory.MessageMemory;
import com.example.whatsappai.ollama.OllamaClient;
import com.example.whatsappai.whatsapp.WhatsAppBot;

public class Main {
    public static void main(String[] args) {
        try {
            AppConfig config = AppConfig.load();
            MessageMemory memory = new MessageMemory(config.maxMemoryMessages());
            OllamaClient ollamaClient = new OllamaClient(config);
            WhatsAppBot bot = new WhatsAppBot(config, ollamaClient, memory);

            bot.start();
        } catch (Exception exception) {
            System.err.println("Application failed to start: " + exception.getMessage());
            exception.printStackTrace();
            System.exit(1);
        }
    }
}
