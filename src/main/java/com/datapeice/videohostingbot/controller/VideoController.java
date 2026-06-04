package com.datapeice.videohostingbot.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@RestController
public class VideoController {

    @Value("${bot.upload-dir:hosted-videos}")
    private String uploadDir;

    @GetMapping("/videos/{filename}")
    public ResponseEntity<Resource> streamVideo(@PathVariable String filename) {
        try {
            Path rootLocation = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path filePath = rootLocation.resolve(filename).normalize();

            // Prevent path traversal attacks
            if (!filePath.startsWith(rootLocation)) {
                log.warn("Path traversal attempt blocked: {}", filename);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            File file = filePath.toFile();
            if (!file.exists() || !file.isFile()) {
                log.warn("Requested video not found: {}", filename);
                return ResponseEntity.notFound().build();
            }

            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "video/mp4";
            }

            Resource resource = new FileSystemResource(file);

            log.info("Streaming video: {} (Content-Type: {}, Size: {} bytes)", 
                    filename, contentType, file.length());

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getName() + "\"")
                    .body(resource);

        } catch (IOException e) {
            log.error("Error streaming video: {}", filename, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
