# WhatsApp Auto Reply AI

Java prototype that watches `WhatsApp Web`, forwards new messages to a local `Ollama` model, and sends the generated reply back through the browser.

## Stack

- Java 21+
- Maven
- Selenium WebDriver
- Chrome / ChromeDriver
- Ollama local API

## How It Works

1. Opens `WhatsApp Web` in Chrome using a persistent profile.
2. Waits for you to scan the QR code the first time.
3. Polls unread chats.
4. Reads the newest incoming message.
5. Sends the message and recent chat memory to Ollama.
6. Types the model response into WhatsApp and sends it.

## Project Structure

- `src/main/java/com/example/whatsappai/Main.java`: app entrypoint
- `src/main/java/com/example/whatsappai/config/AppConfig.java`: environment config
- `src/main/java/com/example/whatsappai/ollama/OllamaClient.java`: local Ollama API client
- `src/main/java/com/example/whatsappai/memory/MessageMemory.java`: short in-memory chat history
- `src/main/java/com/example/whatsappai/whatsapp/WhatsAppBot.java`: Selenium automation logic

## Setup

1. Install and start Ollama.
2. Pull a model, for example:

```powershell
ollama pull llama3
```

3. Copy `.env.example` to `.env` and adjust values.
4. Run the project:

```powershell
mvn compile
mvn exec:java
```

5. When Chrome opens, scan the QR code on `WhatsApp Web`.

The browser profile is stored in `chrome-profile/`, so you should not need to scan every time.

## Notes

- The WhatsApp DOM changes from time to time, so you may need to tweak Selenium selectors in `WhatsAppBot.java`.
- This prototype does not include rate limiting, human handoff, message filtering, or persistent chat storage yet.
- It is safest to test with a secondary WhatsApp account first.

## Next Improvements

- Ignore groups and status updates
- Add allowlist/blocklist for contacts
- Add reply cooldowns
- Persist memory to a database
- Add logging and screenshots for failures
