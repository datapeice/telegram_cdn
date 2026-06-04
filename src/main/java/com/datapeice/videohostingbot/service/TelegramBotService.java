package com.datapeice.videohostingbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class TelegramBotService {

    @Value("${bot.token:}")
    private String botToken;

    private final VideoStorageService videoStorageService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    
    private volatile boolean running = true;
    private Thread pollingThread;

    @Autowired
    public TelegramBotService(VideoStorageService videoStorageService) {
        this.videoStorageService = videoStorageService;
        this.objectMapper = new ObjectMapper();
        this.restTemplate = new RestTemplate();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startBot() {
        if (botToken == null || botToken.trim().isEmpty() || botToken.equalsIgnoreCase("PLACEHOLDER") || botToken.equals("${TELEGRAM_BOT_TOKEN}")) {
            log.warn("=========================================================================");
            log.warn("Telegram Bot Token is not configured. Telegram bot service will NOT start.");
            log.warn("Please set the TELEGRAM_BOT_TOKEN environment variable/property.");
            log.warn("=========================================================================");
            return;
        }

        log.info("Starting Telegram Bot long polling engine...");
        running = true;
        pollingThread = new Thread(this::pollUpdates, "tg-bot-polling");
        pollingThread.start();
    }

    public void stopBot() {
        running = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
        log.info("Telegram Bot long polling engine stopped.");
    }

    private void pollUpdates() {
        long offset = 0;
        String urlTemplate = "https://api.telegram.org/bot" + botToken + "/getUpdates?offset={offset}&timeout=30";

        log.info("Telegram Bot loop started successfully.");

        while (running) {
            try {
                Map<String, Object> params = new HashMap<>();
                params.put("offset", offset);

                String response = restTemplate.getForObject(urlTemplate, String.class, params);
                if (response != null) {
                    JsonNode root = objectMapper.readTree(response);
                    if (root.path("ok").asBoolean()) {
                        JsonNode result = root.path("result");
                        if (result.isArray()) {
                            for (JsonNode update : result) {
                                long updateId = update.path("update_id").asLong();
                                offset = updateId + 1;

                                JsonNode message = update.path("message");
                                if (!message.isMissingNode()) {
                                    handleMessage(message);
                                }
                            }
                        }
                    } else {
                        log.error("Telegram API returned ok=false: {}", response);
                        Thread.sleep(5000);
                    }
                }
            } catch (InterruptedException e) {
                log.info("Polling thread interrupted. Stopping...");
                break;
            } catch (Exception e) {
                log.error("Error in Telegram polling loop: {}", e.getMessage());
                try {
                    Thread.sleep(5000); // Back off in case of persistent errors
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handleMessage(JsonNode message) {
        long chatId = message.path("chat").path("id").asLong();
        
        // 1. Check if the message contains a video
        JsonNode video = message.path("video");
        if (!video.isMissingNode()) {
            String fileId = video.path("file_id").asText();
            String fileName = video.path("file_name").asText("video.mp4");
            processVideoUpload(chatId, fileId, fileName);
            return;
        }

        // 2. Check if the message contains a document (some videos are sent as files)
        JsonNode document = message.path("document");
        if (!document.isMissingNode()) {
            String mimeType = document.path("mime_type").asText("");
            if (mimeType.startsWith("video/")) {
                String fileId = document.path("file_id").asText();
                String fileName = document.path("file_name").asText("video.mp4");
                processVideoUpload(chatId, fileId, fileName);
                return;
            }
        }

        // 3. Fallback/Text handling
        String text = message.path("text").asText("");
        if (text.startsWith("/start")) {
            sendTextMessage(chatId, "<b>Привет!</b> 👋\n\n" +
                    "Я бот для хостинга видео под <b>SLCamera</b>.\n" +
                    "Просто пришли мне любой видеоролик (как видео или файл), " +
                    "и я сделаю его доступным по прямой ссылке, оптимизированным для стриминга!\n\n" +
                    "<i>Все видео будут транскодированы в формат MP4 (H.264/AAC) с FastStart заголовками " +
                    "для моментального воспроизведения со звуком на сервере StoryLegends.</i>");
        } else {
            sendTextMessage(chatId, "Пришли мне видеоролик, чтобы загрузить его на хостинг. 🎥");
        }
    }

    private void processVideoUpload(long chatId, String fileId, String fileName) {
        sendTextMessage(chatId, "⏳ Видео получено! Скачиваю и оптимизирую для SLCamera...");

        try {
            // 1. Get file info from Telegram
            String fileInfoUrl = "https://api.telegram.org/bot" + botToken + "/getFile?file_id=" + fileId;
            String fileInfoResponse = restTemplate.getForObject(fileInfoUrl, String.class);
            if (fileInfoResponse == null) {
                throw new RuntimeException("Could not retrieve file info from Telegram API");
            }

            JsonNode fileInfoRoot = objectMapper.readTree(fileInfoResponse);
            if (!fileInfoRoot.path("ok").asBoolean()) {
                throw new RuntimeException("Telegram getFile error: " + fileInfoRoot.path("description").asText());
            }

            String filePath = fileInfoRoot.path("result").path("file_path").asText();
            String downloadUrl = "https://api.telegram.org/file/bot" + botToken + "/" + filePath;

            // 2. Download to temporary file
            File tempFile = File.createTempFile("tg_upload_", ".tmp");
            tempFile.deleteOnExit();

            log.info("Downloading file from Telegram: {}", downloadUrl);
            URLConnection connection = java.net.URI.create(downloadUrl).toURL().openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);

            try (InputStream is = connection.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
            }

            // 3. Process video (transcode via FFmpeg & save to DB)
            var video = videoStorageService.store(tempFile, fileName, fileId);

            // 4. Cleanup temp file
            if (tempFile.exists()) {
                tempFile.delete();
            }

            // 5. Send success response with direct URL and copyable command
            String responseText = "✅ <b>Видео успешно загружено на хостинг!</b>\n\n" +
                    "<b>Файл:</b> <code>" + escapeHtml(video.getOriginalFilename()) + "</code>\n" +
                    "<b>Размер:</b> " + formatSize(video.getFileSize()) + "\n\n" +
                    "🔗 <b>Прямая ссылка:</b>\n<code>" + video.getDirectUrl() + "</code>\n\n" +
                    "🎮 <b>Команда для запуска в Minecraft (SLCamera):</b>\n" +
                    "<code>/camera video @a " + video.getDirectUrl() + "</code>\n\n" +
                    "<i>Команда скопируется при нажатии на неё (на телефоне) или при выделении. Вы можете управлять файлами через веб-панель.</i>";

            sendTextMessage(chatId, responseText);

        } catch (Exception e) {
            log.error("Failed to process video upload", e);
            sendTextMessage(chatId, "❌ <b>Ошибка при обработке видео:</b>\n" + escapeHtml(e.getMessage()));
        }
    }

    private void sendTextMessage(long chatId, String text) {
        String sendMessageUrl = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        Map<String, Object> request = new HashMap<>();
        request.put("chat_id", chatId);
        request.put("text", text);
        request.put("parse_mode", "HTML");

        try {
            restTemplate.postForObject(sendMessageUrl, request, String.class);
        } catch (Exception e) {
            log.error("Failed to send message to Telegram: {}", e.getMessage());
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
