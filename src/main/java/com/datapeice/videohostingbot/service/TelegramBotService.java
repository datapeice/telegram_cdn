package com.datapeice.videohostingbot.service;

import com.datapeice.videohostingbot.model.Video;
import com.datapeice.videohostingbot.repository.VideoRepository;
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
import java.util.Optional;

@Slf4j
@Service
public class TelegramBotService {

    @Value("${bot.token:}")
    private String botToken;

    @Value("${bot.whitelist:}")
    private String whitelistStr;

    private final VideoStorageService videoStorageService;
    private final VideoRepository videoRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    
    private final java.util.Set<Long> whitelist = new java.util.HashSet<>();
    private volatile boolean running = true;
    private Thread pollingThread;

    @Autowired
    public TelegramBotService(VideoStorageService videoStorageService, VideoRepository videoRepository) {
        this.videoStorageService = videoStorageService;
        this.videoRepository = videoRepository;
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

        // Parse whitelist
        if (whitelistStr != null && !whitelistStr.trim().isEmpty()) {
            for (String s : whitelistStr.split(",")) {
                try {
                    whitelist.add(Long.parseLong(s.trim()));
                } catch (NumberFormatException e) {
                    log.error("Invalid user ID in whitelist: {}", s);
                }
            }
            log.info("Loaded {} whitelisted Telegram user IDs", whitelist.size());
        } else {
            log.info("Telegram whitelist is empty. All users are allowed to use the bot.");
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

    private boolean isUserAllowed(long userId) {
        if (whitelist.isEmpty()) {
            return true;
        }
        return whitelist.contains(userId);
    }

    private void handleMessage(JsonNode message) {
        long chatId = message.path("chat").path("id").asLong();
        long userId = message.path("from").path("id").asLong();

        if (!isUserAllowed(userId)) {
            String username = message.path("from").path("username").asText("unknown");
            log.warn("Unauthorized access attempt: User {} (ID: {}) is not whitelisted.", username, userId);
            sendTextMessage(chatId, "❌ <b>Доступ запрещен.</b>\n" +
                    "Вы не находитесь в белом списке администраторов бота.\n" +
                    "Ваш Telegram ID: <code>" + userId + "</code>\n\n" +
                    "<i>Попросите владельца хоста добавить ваш ID в переменную TELEGRAM_WHITELIST в файле .env</i>");
            return;
        }
        
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
                    "Я бот для хостинга видео.\n" +
                    "Просто пришли мне любой видеоролик (как видео или файл) или <b>прямую ссылку на видео</b> (начиная с http/https), " +
                    "и я сделаю его доступным по прямой ссылке, оптимизированным для стриминга!\n\n" +
                    "<i>Все видео будут автоматически подготовлены для моментального воспроизведения со звуком в 720p 30 FPS.</i>\n\n" +
                    "<b>Доступные команды:</b>\n" +
                    "📋 /list — Показать список всех загруженных видео\n" +
                    "❌ /delete &lt;id&gt; — Удалить видео по его ID/имени файла");
        } else if (text.startsWith("/list")) {
            handleListCommand(chatId);
        } else if (text.startsWith("/delete") || text.startsWith("/del")) {
            handleDeleteCommand(chatId, text);
        } else if (text.startsWith("http://") || text.startsWith("https://")) {
            String url = text.trim();
            String fileName = "downloaded_video.mp4";
            
            // Automatically detect Google Drive sharing links and convert them to direct download links
            if (url.contains("drive.google.com")) {
                String fileId = null;
                if (url.contains("/file/d/")) {
                    int start = url.indexOf("/file/d/") + 8;
                    int end = url.indexOf("/", start);
                    if (end == -1) {
                        end = url.indexOf("?", start);
                    }
                    if (end == -1) {
                        end = url.length();
                    }
                    fileId = url.substring(start, end);
                } else if (url.contains("id=")) {
                    int start = url.indexOf("id=") + 3;
                    int end = url.indexOf("&", start);
                    if (end == -1) {
                        end = url.length();
                    }
                    fileId = url.substring(start, end);
                }
                
                if (fileId != null) {
                    url = "https://drive.usercontent.google.com/download?id=" + fileId + "&export=download&confirm=t";
                    fileName = "drive_" + fileId + ".mp4";
                    log.info("Converted Google Drive sharing link to direct link: {}", url);
                }
            } else {
                if (url.contains("/")) {
                    String lastPart = url.substring(url.lastIndexOf("/") + 1);
                    if (lastPart.contains("?")) {
                        lastPart = lastPart.substring(0, lastPart.indexOf("?"));
                    }
                    if (lastPart.endsWith(".mp4") || lastPart.endsWith(".mov") || lastPart.endsWith(".avi") || lastPart.endsWith(".mkv")) {
                        fileName = lastPart;
                    }
                }
            }
            processVideoUrlDownload(chatId, url, fileName);
        } else {
            sendTextMessage(chatId, "Пришли мне видеоролик или прямую ссылку на видео, чтобы загрузить его на хостинг. 🎥");
        }
    }

    private void processVideoUpload(long chatId, String fileId, String fileName) {
        // Check if this video has already been uploaded and processed
        Optional<Video> existingVideo = videoRepository.findByTelegramFileId(fileId);
        if (existingVideo.isPresent()) {
            Video video = existingVideo.get();
            if (video.getFileSize() < 50 * 1024) {
                log.warn("Found existing uploaded video record but it is extremely small ({} bytes). Deleting it and re-uploading...", video.getFileSize());
                try {
                    videoStorageService.delete(video.getId());
                } catch (Exception e) {
                    log.error("Failed to delete small video record: {}", e.getMessage());
                }
            } else {
                String responseText = buildSuccessMessage(
                        "✅ <b>Это видео уже загружено на хостинг!</b>",
                        video,
                        ""
                );
                sendTextMessage(chatId, responseText);
                return;
            }
        }

        sendTextMessage(chatId, "⏳ Видео получено! Скачиваю и оптимизирую для стриминга...");

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
            String responseText = buildSuccessMessage(
                    "✅ <b>Видео успешно загружено на хостинг!</b>",
                    video,
                    ""
            );

            sendTextMessage(chatId, responseText);

        } catch (Exception e) {
            log.error("Failed to process video upload", e);
            sendTextMessage(chatId, "❌ <b>Ошибка при обработке видео:</b>\n" + escapeHtml(e.getMessage()));
        }
    }

    private String buildSuccessMessage(String header, Video video, String footer) {
        String scheduledTime = java.time.format.DateTimeFormatter.ISO_INSTANT.format(
                java.time.Instant.now().plusSeconds(10)
        );
        String msg = header + "\n\n" +
                "<b>Файл:</b> <code>" + escapeHtml(video.getOriginalFilename()) + "</code>\n" +
                "<b>Размер:</b> " + formatSize(video.getFileSize()) + "\n\n" +
                "🔗 <b>Прямая ссылка:</b>\n<code>" + video.getDirectUrl() + "</code>\n\n" +
                "🎮 <b>Команда для запуска:</b>\n" +
                "<code>/camera video @a \"" + video.getDirectUrl() + "\"</code>\n\n" +
                "🕒 <b>Запуск по расписанию (текущее UTC + 10 сек):</b>\n" +
                "<code>/camera video @a \"" + video.getDirectUrl() + "\" \"" + scheduledTime + "\"</code>";
        if (footer != null && !footer.trim().isEmpty()) {
            msg += "\n\n" + footer;
        }
        return msg;
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

    private void processVideoUrlDownload(long chatId, String downloadUrl, String fileName) {
        sendTextMessage(chatId, "⏳ Ссылка получена! Скачиваю видео по прямой ссылке и оптимизирую для стриминга...");

        try {
            // 1. Download to temporary file
            File tempFile = File.createTempFile("url_download_", ".tmp");
            tempFile.deleteOnExit();

            log.info("Downloading file from URL: {}", downloadUrl);
            URLConnection connection = java.net.URI.create(downloadUrl).toURL().openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);

            // Use a unique fileId derived from the URL to avoid duplicating the same URL download
            String fileId = "url_" + java.util.UUID.nameUUIDFromBytes(downloadUrl.getBytes()).toString();

            // Check if already processed
            Optional<Video> existingVideo = videoRepository.findByTelegramFileId(fileId);
            if (existingVideo.isPresent()) {
                Video video = existingVideo.get();
                if (video.getFileSize() < 50 * 1024) {
                    log.warn("Found existing downloaded video record but it is extremely small ({} bytes). Deleting it and re-downloading...", video.getFileSize());
                    try {
                        videoStorageService.delete(video.getId());
                    } catch (Exception e) {
                        log.error("Failed to delete small video record: {}", e.getMessage());
                    }
                } else {
                    String responseText = buildSuccessMessage(
                            "✅ <b>Это видео уже загружено на хостинг!</b>",
                            video,
                            ""
                    );
                    sendTextMessage(chatId, responseText);
                    return;
                }
            }

            try (InputStream is = connection.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
            }

            // 2. Process video (transcode via FFmpeg & save to DB)
            var video = videoStorageService.store(tempFile, fileName, fileId);

            // 3. Cleanup temp file
            if (tempFile.exists()) {
                tempFile.delete();
            }

            // 4. Send success response
            String responseText = buildSuccessMessage(
                    "✅ <b>Видео успешно загружено на хостинг!</b>",
                    video,
                    ""
            );

            sendTextMessage(chatId, responseText);

        } catch (Exception e) {
            log.error("Failed to process URL download", e);
            sendTextMessage(chatId, "❌ <b>Ошибка при загрузке по ссылке:</b>\n" + escapeHtml(e.getMessage()));
        }
    }

    private void handleListCommand(long chatId) {
        try {
            java.util.List<Video> videos = videoRepository.findAll();
            if (videos.isEmpty()) {
                sendTextMessage(chatId, "📭 <b>База пуста.</b> Видео на хостинге пока нет.");
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("🗄 <b>База загруженных видео (Всего: ").append(videos.size()).append("):</b>\n\n");

            // Sort by creation date descending (latest first)
            videos.sort((v1, v2) -> {
                if (v1.getCreatedAt() == null || v2.getCreatedAt() == null) return 0;
                return v2.getCreatedAt().compareTo(v1.getCreatedAt());
            });

            int count = 0;
            for (Video video : videos) {
                if (count >= 30) {
                    sb.append("<i>...и еще ").append(videos.size() - 30).append(" видео (посмотрите их на веб-панели)</i>\n");
                    break;
                }

                String cleanFilename = video.getStoredFilename();
                String deleteArg = cleanFilename.endsWith(".mp4") ? cleanFilename.substring(0, cleanFilename.length() - 4) : cleanFilename;

                sb.append(count + 1).append(". 🎥 <b>").append(escapeHtml(video.getOriginalFilename())).append("</b>\n");
                sb.append("• Размер: ").append(formatSize(video.getFileSize())).append("\n");
                sb.append("• Ссылка: <a href=\"").append(video.getDirectUrl()).append("\">открыть</a>\n");
                sb.append("• Удалить: <code>/delete ").append(deleteArg).append("</code>\n\n");
                count++;
            }

            sendTextMessage(chatId, sb.toString());
        } catch (Exception e) {
            log.error("Failed to list videos", e);
            sendTextMessage(chatId, "❌ <b>Ошибка при получении списка видео:</b>\n" + escapeHtml(e.getMessage()));
        }
    }

    private void handleDeleteCommand(long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            sendTextMessage(chatId, "❌ <b>Использование:</b>\n<code>/delete &lt;имя_файла_или_UUID&gt;</code>\n\n<i>Пример: /delete bccd7426-735b-4171-85ca-3752929f6c1f</i>");
            return;
        }

        String target = parts[1].trim();
        if (target.isEmpty()) {
            sendTextMessage(chatId, "❌ Укажите имя файла или UUID для удаления.");
            return;
        }

        try {
            Optional<Video> videoOpt = Optional.empty();

            // 1. Try to parse as UUID
            try {
                java.util.UUID uuid = java.util.UUID.fromString(target);
                videoOpt = videoRepository.findById(uuid);
            } catch (IllegalArgumentException ignored) {}

            // 2. If not found by UUID, try to search by storedFilename
            if (videoOpt.isEmpty()) {
                videoOpt = videoRepository.findByStoredFilename(target);
            }

            // 3. Try with .mp4 extension appended
            if (videoOpt.isEmpty() && !target.endsWith(".mp4")) {
                videoOpt = videoRepository.findByStoredFilename(target + ".mp4");
            }

            if (videoOpt.isEmpty()) {
                sendTextMessage(chatId, "❌ <b>Видео не найдено:</b> <code>" + escapeHtml(target) + "</code>\nПроверьте имя файла с помощью команды /list");
                return;
            }

            Video video = videoOpt.get();
            videoStorageService.delete(video.getId());
            sendTextMessage(chatId, "✅ <b>Видео успешно удалено!</b>\n\n<b>Файл:</b> <code>" + escapeHtml(video.getOriginalFilename()) + "</code>\n<b>Stored ID:</b> <code>" + video.getStoredFilename() + "</code>");
        } catch (Exception e) {
            log.error("Failed to delete video via command: {}", target, e);
            sendTextMessage(chatId, "❌ <b>Ошибка при удалении видео:</b>\n" + escapeHtml(e.getMessage()));
        }
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
