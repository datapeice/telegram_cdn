package com.datapeice.videohostingbot.repository;

import com.datapeice.videohostingbot.model.Video;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface VideoRepository extends JpaRepository<Video, UUID> {
    Optional<Video> findByTelegramFileId(String telegramFileId);
    Optional<Video> findByStoredFilename(String storedFilename);
}
