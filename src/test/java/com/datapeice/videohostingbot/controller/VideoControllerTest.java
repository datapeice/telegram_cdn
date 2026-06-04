package com.datapeice.videohostingbot.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class VideoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Value("${bot.upload-dir:build/hosted-videos-test}")
    private String uploadDir;

    private File testFile;
    private static final String TEST_FILENAME = "test-stream-video.mp4";
    private static final int FILE_SIZE = 100;

    @BeforeEach
    public void setup() throws IOException {
        Path rootLocation = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(rootLocation);
        
        testFile = rootLocation.resolve(TEST_FILENAME).toFile();
        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            // Write 100 dummy bytes
            byte[] dummyData = new byte[FILE_SIZE];
            for (int i = 0; i < FILE_SIZE; i++) {
                dummyData[i] = (byte) i;
            }
            fos.write(dummyData);
        }
    }

    @AfterEach
    public void cleanup() {
        if (testFile != null && testFile.exists()) {
            testFile.delete();
        }
    }

    @Test
    public void testFullVideoDownload() throws Exception {
        mockMvc.perform(get("/videos/" + TEST_FILENAME))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/mp4"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(FILE_SIZE)));
    }

    @Test
    public void testVideoRangeRequest() throws Exception {
        // Request bytes 10 to 20 (inclusive, total 11 bytes)
        mockMvc.perform(get("/videos/" + TEST_FILENAME)
                        .header(HttpHeaders.RANGE, "bytes=10-20"))
                .andExpect(status().isPartialContent())
                .andExpect(content().contentType("video/mp4"))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 10-20/" + FILE_SIZE))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "11"));
    }

    @Test
    public void testVideoNotFound() throws Exception {
        mockMvc.perform(get("/videos/non-existent-file.mp4"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void testDashboardRendering() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attributeExists("videos"))
                .andExpect(model().attributeExists("totalVideos"))
                .andExpect(model().attributeExists("totalSize"));
    }
}
