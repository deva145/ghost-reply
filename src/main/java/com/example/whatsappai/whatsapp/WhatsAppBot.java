package com.example.whatsappai.whatsapp;

import com.example.whatsappai.config.AppConfig;
import com.example.whatsappai.memory.MessageMemory;
import com.example.whatsappai.ollama.OllamaClient;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class WhatsAppBot {
    private static final By CHAT_LIST_READY = By.xpath("//div[@id='pane-side']");
    private static final By CHAT_TITLE = By.xpath("//div[@id='main']//header//*[self::span or self::div][@title]");
    private static final By INCOMING_MESSAGES = By.xpath(
            "//div[@id='main']//div[contains(@class,'message-in')]//span[contains(@class,'selectable-text') and @dir='ltr']");
    private static final By MESSAGE_BOX = By.xpath(
            "//div[@id='main']//footer//div[@contenteditable='true' and @role='textbox']");

    private final AppConfig config;
    private final OllamaClient ollamaClient;
    private final MessageMemory memory;
    private final Map<String, String> lastHandledFingerprintByChat = new ConcurrentHashMap<>();
    private final Map<String, Boolean> introSentByChat = new ConcurrentHashMap<>();
    private final AtomicLong loopCount = new AtomicLong();
    private boolean domDebugPrinted;

    public WhatsAppBot(AppConfig config, OllamaClient ollamaClient, MessageMemory memory) {
        this.config = config;
        this.ollamaClient = ollamaClient;
        this.memory = memory;
    }

    public void start() throws Exception {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--start-maximized");
        options.addArguments("--disable-notifications");
        options.addArguments("--user-data-dir=" + profileDirectory().toAbsolutePath());
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--no-first-run");
        options.addArguments("--no-default-browser-check");
        options.addArguments("--disable-background-networking");
        options.addArguments("--disable-sync");
        options.addArguments("--disable-gpu");

        WebDriver driver = new ChromeDriver(options);
        Runtime.getRuntime().addShutdownHook(new Thread(driver::quit));

        driver.get(config.whatsappWebUrl());
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofMinutes(3));
        wait.until(ExpectedConditions.presenceOfElementLocated(CHAT_LIST_READY));

        System.out.println("WhatsApp Web is ready. Monitoring unread chats...");
        if (!domDebugPrinted) {
            debugDom(driver);
            domDebugPrinted = true;
        }

        while (true) {
            try {
                processUnreadChats(driver);
            } catch (Exception exception) {
                System.err.println("Loop error: " + exception.getMessage());
            }
            Thread.sleep(config.pollIntervalMs());
        }
    }

    private void processUnreadChats(WebDriver driver) throws Exception {
        long currentLoop = loopCount.incrementAndGet();
        List<ChatEntry> chatEntries = readVisibleChats(driver);
        List<ChatEntry> unreadChats = chatEntries.stream()
                .filter(ChatEntry::unread)
                .filter(entry -> entry.name().length() <= 80)
                .toList();

        if (currentLoop % 10 == 0) {
            System.out.printf(
                    "Polling WhatsApp... visible chats: %d, unread chats found: %d%n",
                    chatEntries.size(),
                    unreadChats.size()
            );

            if (!chatEntries.isEmpty()) {
                System.out.println("Visible chats snapshot: " + visibleChatNames(chatEntries));
            }
        }

        ChatEntry chatEntry = !unreadChats.isEmpty() ? unreadChats.get(0) : currentOpenChatEntry(driver);
        if (chatEntry == null) {
            return;
        }

        System.out.printf("Trying chat: %s%n", chatEntry.name());

        try {
            WebElement body = driver.findElement(By.tagName("body"));
            body.sendKeys(Keys.ESCAPE);
            Thread.sleep(500);
        } catch (Exception ignored) {
        }

        boolean opened = openChatDirectly(driver, chatEntry.name());
        if (!opened) {
            System.out.println("Could not open chat: " + chatEntry.name());
            return;
        }

        String chatName = currentChatTitle(driver);
        System.out.printf("Opened chat header: %s%n", chatName);

        if (chatName.isBlank()) {
            System.out.println("Chat header still blank, skipping.");
            return;
        }

        MessageSnapshot incomingMessage = latestIncomingMessage(driver);
        System.out.printf("Latest incoming message: %s%n", incomingMessage.text());

        if (incomingMessage.text().isBlank()) {
            System.out.println("No incoming message text found in opened chat.");
            return;
        }

        String lastHandled = lastHandledFingerprintByChat.get(chatName);
        if (incomingMessage.fingerprint().equals(lastHandled)) {
            return;
        }

        System.out.printf("New message from %s: %s%n", chatName, incomingMessage.text());
        memory.rememberUserMessage(chatName, incomingMessage.text());

        System.out.println("Requesting reply from Ollama...");
        String reply = ollamaClient.generateReply(chatName, incomingMessage.text(), memory.historyFor(chatName));
        System.out.printf("Generated reply: %s%n", reply);

        if (!Boolean.TRUE.equals(introSentByChat.get(chatName))) {
            String intro = "Right now you are talking to Deva's assistant.";
            sendReply(driver, intro);
            memory.rememberAssistantMessage(chatName, intro);
            introSentByChat.put(chatName, true);
        }

        sendReply(driver, reply);

        memory.rememberAssistantMessage(chatName, reply);
        lastHandledFingerprintByChat.put(chatName, incomingMessage.fingerprint());
        System.out.printf("Reply sent to %s: %s%n", chatName, reply);
    }

    private ChatEntry currentOpenChatEntry(WebDriver driver) {
        String chatName = currentChatTitle(driver);
        return chatName.isBlank() ? null : new ChatEntry(chatName, false);
    }

    private List<ChatEntry> readVisibleChats(WebDriver driver) {
        Object result = ((JavascriptExecutor) driver).executeScript("""
                const panel = document.querySelector('#pane-side');
                if (!panel) return [];
                const titleNodes = [...panel.querySelectorAll('span[title]')];
                const rows = [];
                const seen = new Set();

                for (const node of titleNodes) {
                  const name = (node.getAttribute('title') || '').trim();
                  if (!name || seen.has(name) || name.length > 80) continue;

                  const row = node.closest('div[role="listitem"], div[role="gridcell"], div._ak8q, div[tabindex="-1"]') || node.parentElement;
                  const text = (row?.innerText || '').trim();
                  const html = (row?.innerHTML || '').toLowerCase();
                  const unread = /\\bunread\\b/.test(text.toLowerCase())
                    || /aria-label="[^"]*unread/.test(html)
                    || /data-icon="[^"]*unread/.test(html)
                    || /\\n\\d+\\n?$/.test(text)
                    || />(\\d+)</.test(html);

                  rows.push({ name, unread });
                  seen.add(name);
                }

                return rows;
                """);

        List<ChatEntry> chatEntries = new ArrayList<>();
        if (result instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    String name = stringValue(map.get("name"));
                    boolean unread = booleanValue(map.get("unread"));
                    if (!name.isBlank()) {
                        chatEntries.add(new ChatEntry(name, unread));
                    }
                }
            }
        }
        return chatEntries;
    }

    private boolean openChatDirectly(WebDriver driver, String chatName) {
        try {
            By chatRow = By.xpath(
                    "//div[@id='pane-side']//span[@title=\"" + escapeXPath(chatName) + "\"]" +
                            "/ancestor::div[@role='listitem' or @role='gridcell' or @tabindex='-1'][1]"
            );
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            WebElement rowElement = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(chatRow)
            );

            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block: 'center'});", rowElement);
            new Actions(driver)
                    .moveToElement(rowElement)
                    .pause(Duration.ofMillis(150))
                    .click(rowElement)
                    .perform();

            Thread.sleep(1500);
            boolean mainReady = Boolean.TRUE.equals(((JavascriptExecutor) driver).executeScript("""
                    return !!document.querySelector('#main header') || !!document.querySelector('#main footer');
                    """));
            System.out.println("Main chat pane ready: " + mainReady);
            return mainReady;
        } catch (Exception exception) {
            System.out.println("openChatDirectly error: " + exception.getMessage());
        }
        return false;
    }

    private void debugDom(WebDriver driver) {
        Object result = ((JavascriptExecutor) driver).executeScript("""
                const side = document.querySelector('#side');
                if (!side) return 'NO #side found';

                const editables = [...document.querySelectorAll('[contenteditable="true"]')];
                const info = editables.map(el => ({
                  tag: el.tagName,
                  id: el.id,
                  role: el.getAttribute('role'),
                  ariaLabel: el.getAttribute('aria-label'),
                  dataTab: el.getAttribute('data-tab'),
                  placeholder: el.getAttribute('data-placeholder'),
                  inSide: side.contains(el),
                  inMain: !!el.closest('#main'),
                  visible: el.offsetParent !== null,
                  classes: (el.className || '').toString().substring(0, 80)
                }));
                return JSON.stringify(info, null, 2);
                """);
        System.out.println("=== EDITABLE ELEMENTS DEBUG ===");
        System.out.println(result);
        System.out.println("=== END DEBUG ===");
    }

    private String currentChatTitle(WebDriver driver) {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        String[] jsAttempts = {
                "const e=document.querySelector('#main header span[dir=\"auto\"]'); return e?e.innerText.trim():null;",
                "const e=document.querySelector('#main header span[title]'); return e?e.getAttribute('title'):null;",
                "const e=document.querySelector('#main header [title]'); return e?e.getAttribute('title'):null;",
                "const e=document.querySelector('#app header span[dir]'); return e?e.innerText.trim():null;"
        };

        for (String js : jsAttempts) {
            try {
                Object result = ((JavascriptExecutor) driver).executeScript(js);
                String value = stringValue(result).trim();
                if (!value.isBlank() && !value.equals("null")) {
                    System.out.println("Chat title found: " + value);
                    return value;
                }
            } catch (Exception ignored) {
            }
        }

        Object html = ((JavascriptExecutor) driver).executeScript(
                "const h=document.querySelector('#main header'); return h?h.innerText.substring(0,200):'NO HEADER';"
        );
        System.out.println("Header innerText: " + html);
        return "";
    }

    private String visibleChatNames(List<ChatEntry> chatEntries) {
        return chatEntries.stream()
                .limit(5)
                .map(entry -> entry.name() + (entry.unread() ? " (unread)" : ""))
                .reduce((left, right) -> left + ", " + right)
                .orElse("(none)");
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private String escapeXPath(String value) {
        return value.replace("\"", "\\\"");
    }

    private MessageSnapshot latestIncomingMessage(WebDriver driver) {
        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        List<WebElement> messages = driver.findElements(INCOMING_MESSAGES);
        System.out.println("Found " + messages.size() + " incoming message elements");
        for (int index = messages.size() - 1; index >= 0; index--) {
            String text = messages.get(index).getText();
            if (text != null && !text.isBlank()) {
                String normalized = text.trim();
                return new MessageSnapshot(normalized, normalized + "|selenium|" + messages.size());
            }
        }

        Object jsMessage = ((JavascriptExecutor) driver).executeScript("""
                const main = document.querySelector('#main');
                if (!main) return { text: '', fingerprint: '' };

                const incomingContainers = [
                  ...main.querySelectorAll('div.message-in, div[class*="message-in"], div[aria-label*="message-in"]')
                ];

                const extracted = incomingContainers
                  .map((container, index) => {
                    const textNodes = [...container.querySelectorAll('span.selectable-text, div.copyable-text, span[dir="ltr"], span[dir="auto"]')];
                    const values = textNodes
                      .map(el => (el.innerText || el.textContent || '').trim())
                      .filter(text =>
                        text &&
                        !/^\\d{1,2}:\\d{2}(?:\\s?[ap]m)?$/i.test(text) &&
                        !/^today$/i.test(text) &&
                        !/^yesterday$/i.test(text) &&
                        !/unread messages?/i.test(text) &&
                        text.length > 1
                      );
                    if (!values.length) return null;
                    const text = values[values.length - 1];
                    const fullText = (container.innerText || '').trim();
                    return { text, fingerprint: fullText || (text + '|js-in|' + index) };
                  })
                  .filter(Boolean);

                if (!extracted.length) return { text: '', fingerprint: '' };
                return extracted[extracted.length - 1];
                """);
        MessageSnapshot fallbackSnapshot = messageSnapshot(jsMessage);
        if (!fallbackSnapshot.text().isBlank()) {
            System.out.println("JS fallback incoming message found.");
            return fallbackSnapshot;
        }

        Object debug = ((JavascriptExecutor) driver).executeScript("""
                const main = document.querySelector('#main');
                if (!main) return 'NO #main';
                const msgDivs = main.querySelectorAll('div[class*="message"], div.message-in, div.message-out, span.selectable-text, div.copyable-text');
                return 'message-like elements found: ' + msgDivs.length +
                       ' | main innerHTML length: ' + main.innerHTML.length;
                """);
        System.out.println("Message debug: " + debug);

        return new MessageSnapshot("", "");
    }

    private MessageSnapshot messageSnapshot(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new MessageSnapshot(
                    stringValue(map.get("text")).trim(),
                    stringValue(map.get("fingerprint")).trim()
            );
        }
        return new MessageSnapshot("", "");
    }

    private void sendReply(WebDriver driver, String reply) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        WebElement messageBox = wait.until(ExpectedConditions.elementToBeClickable(MESSAGE_BOX));
        messageBox.click();
        messageBox.sendKeys(reply);
        messageBox.sendKeys(Keys.ENTER);
    }

    private Path profileDirectory() {
        return Path.of(config.chromeProfileDir());
    }

    private record MessageSnapshot(String text, String fingerprint) {
    }

    private record ChatEntry(String name, boolean unread) {
    }
}
