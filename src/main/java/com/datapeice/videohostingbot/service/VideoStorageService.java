package com.datapeice.videohostingbot.service;

import com.datapeice.videohostingbot.model.Video;
import com.datapeice.videohostingbot.repository.VideoRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class VideoStorageService {

    @Value("${bot.upload-dir:hosted-videos}")
    private String uploadDir;

    @Value("${bot.external-url:http://localhost:8080}")
    private String externalUrl;

    private final VideoRepository videoRepository;
    private Path rootLocation;

    @Autowired
    public VideoStorageService(VideoRepository videoRepository) {
        this.videoRepository = videoRepository;
    }

    @PostConstruct
    public void init() {
        try {
            this.rootLocation = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(this.rootLocation);
            log.info("Initialized storage directory at: {}", this.rootLocation);
        } catch (IOException e) {
            log.error("Could not initialize storage directory", e);
            throw new RuntimeException("Could not initialize storage directory", e);
        }
    }

    /**
     * Stores a video file, transcodes it with FFmpeg to make it streamable for SLCamera,
     * and saves its metadata to the database.
     */
    public Video store(File tempFile, String originalFilename, String telegramFileId) throws IOException {
        String storedFilename = UUID.randomUUID().toString() + ".mp4";
        Path destination = this.rootLocation.resolve(storedFilename);

        log.info("Processing upload. Original: '{}', Telegram File ID: '{}'", originalFilename, telegramFileId);

        boolean ffmpegSuccess = false;
        try {
            ffmpegSuccess = runFFmpegTranscode(tempFile, destination.toFile());
        } catch (Exception e) {
            log.warn("FFmpeg transcoding failed, falling back to direct copy: {}", e.getMessage());
        }

        if (!ffmpegSuccess) {
            log.info("Copying video file as-is (FFmpeg fallback)");
            Files.copy(tempFile.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);
        }

        long fileSize = Files.size(destination);
        String directUrl = buildDirectUrl(storedFilename);

        Video video = Video.builder()
                .telegramFileId(telegramFileId)
                .originalFilename(originalFilename)
                .storedFilename(storedFilename)
                .fileSize(fileSize)
                .createdAt(LocalDateTime.now())
                .directUrl(directUrl)
                .build();

        Video savedVideo = videoRepository.save(video);
        log.info("Video successfully hosted! Direct link: {}", directUrl);
        return savedVideo;
    }

    /**
     * Deletes a video file from disk and database.
     */
    public void delete(UUID id) throws IOException {
        Optional<Video> optionalVideo = videoRepository.findById(id);
        if (optionalVideo.isPresent()) {
            Video video = optionalVideo.get();
            Path filePath = this.rootLocation.resolve(video.getStoredFilename());
            
            try {
                if (Files.exists(filePath)) {
                    Files.delete(filePath);
                    log.info("Deleted file from disk: {}", filePath);
                } else {
                    log.warn("File to delete not found on disk: {}", filePath);
                }
            } catch (IOException e) {
                log.error("Failed to delete file from disk: {}", filePath, e);
                throw e;
            }

            videoRepository.delete(video);
            log.info("Deleted video metadata from database. ID: {}", id);
        } else {
            log.warn("Attempted to delete non-existing video. ID: {}", id);
        }
    }

    private boolean runFFmpegTranscode(File input, File output) {
        log.info("Running FFmpeg transcode (H.264, AAC, FastStart) on: {}", input.getAbsolutePath());

        // Command: ffmpeg -y -i <input> -map 0:v:0 -map 0:a? -c:v libx264 -profile:v baseline -bf 0 -pix_fmt yuv420p -c:a aac -ar 44100 -ac 2 -b:a 128k -movflags +faststart <output>
        ProcessBuilder processBuilder = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", input.getAbsolutePath(),
                "-map", "0:v:0",
                "-map", "0:a?",
                "-c:v", "libx264",
                "-profile:v", "baseline",
                "-bf", "0",
                "-pix_fmt", "yuv420p",
                "-vf", "scale='min(1280,iw)':'min(720,ih)':force_original_aspect_ratio=decrease,scale=trunc(iw/2)*2:trunc(ih/2)*2",
                "-c:a", "aac",
                "-ar", "44100",
                "-ac", "2",
                "-b:a", "128k",
                "-b:v", "1000k",
                "-r", "30",
                "-movflags", "+faststart",
                output.getAbsolutePath()
        );

        // Redirect error stream so we can log it if needed
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            
            // We need to read the output stream to avoid process hang
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.trace("FFmpeg: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("FFmpeg transcode completed successfully.");
                return true;
            } else {
                log.warn("FFmpeg exited with error code: {}", exitCode);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            log.warn("Could not execute FFmpeg process: {}", e.getMessage());
            return false;
        }
    }

    private String buildDirectUrl(String filename) {
        String base = externalUrl;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/videos/" + filename;
    }
}
