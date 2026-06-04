package com.datapeice.videohostingbot.controller;

import com.datapeice.videohostingbot.model.Video;
import com.datapeice.videohostingbot.repository.VideoRepository;
import com.datapeice.videohostingbot.service.VideoStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Slf4j
@Controller
public class DashboardController {

    private final VideoRepository videoRepository;
    private final VideoStorageService videoStorageService;

    @Autowired
    public DashboardController(VideoRepository videoRepository, VideoStorageService videoStorageService) {
        this.videoRepository = videoRepository;
        this.videoStorageService = videoStorageService;
    }

    @GetMapping("/")
    public String viewDashboard(Model model) {
        List<Video> videos = videoRepository.findAll();
        
        long totalBytes = videos.stream().mapToLong(Video::getFileSize).sum();
        String totalSizeStr = formatSize(totalBytes);

        model.addAttribute("videos", videos);
        model.addAttribute("totalVideos", videos.size());
        model.addAttribute("totalSize", totalSizeStr);
        
        return "index";
    }

    @PostMapping("/delete/{id}")
    public String deleteVideo(@PathVariable UUID id) {
        try {
            log.info("Request received to delete video with ID: {}", id);
            videoStorageService.delete(id);
        } catch (IOException e) {
            log.error("Failed to delete video with ID: {}", id, e);
        }
        return "redirect:/";
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
