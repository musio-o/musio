package com.musio.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.config.MusioConfigService;
import com.musio.model.ChatConfirmation;
import com.musio.model.Song;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationHistoryService {
    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryService.class);
    private static final String DEFAULT_USER_ID = "local";

    private final MusioConfigService configService;
    private final ObjectMapper objectMapper;
    private final Map<String, Object> userLocks = new ConcurrentHashMap<>();

    public ConversationHistoryService(MusioConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper.findAndRegisterModules();
    }

    public String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? DEFAULT_USER_ID : userId.trim();
    }

    public List<ConversationHistoryMessage> load(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        synchronized (lock(normalizedUserId)) {
            Path path = historyPath(normalizedUserId);
            if (!Files.isRegularFile(path)) {
                return List.of();
            }

            List<ConversationHistoryMessage> messages = new ArrayList<>();
            try {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        ConversationHistoryMessage message = objectMapper.readValue(line, ConversationHistoryMessage.class);
                        if (isSupported(message)) {
                            messages.add(message);
                        }
                    } catch (JsonProcessingException e) {
                        log.warn("Skipping invalid conversation history line in {}", path);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read conversation history: " + path, e);
            }
            return List.copyOf(messages);
        }
    }

    public void appendTurn(String userId, String userMessage, String assistantMessage) {
        appendTurn(userId, userMessage, assistantMessage, List.of());
    }

    public void appendTurn(String userId, String userMessage, String assistantMessage, List<Song> songs) {
        appendTurn(userId, userMessage, assistantMessage, songs, null);
    }

    public void appendTurn(String userId, String userMessage, String assistantMessage, List<Song> songs, ChatConfirmation confirmation) {
        String normalizedUserId = normalizeUserId(userId);
        synchronized (lock(normalizedUserId)) {
            Path path = historyPath(normalizedUserId);
            try {
                Files.createDirectories(path.getParent());
                List<String> lines = List.of(
                        writeJson(new ConversationHistoryMessage("user", userMessage, Instant.now(), List.of())),
                        writeJson(new ConversationHistoryMessage("assistant", assistantMessage, Instant.now(), safeSongs(songs), confirmation))
                );
                Files.write(path, lines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to append conversation history: " + path, e);
            }
        }
    }

    public void clear(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        synchronized (lock(normalizedUserId)) {
            Path path = historyPath(normalizedUserId);
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to clear conversation history: " + path, e);
            }
        }
    }

    private boolean isSupported(ConversationHistoryMessage message) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            return false;
        }
        return "user".equals(message.role()) || "assistant".equals(message.role());
    }

    private Path historyPath(String userId) {
        return configService.config().storage().home()
                .resolve("conversations")
                .resolve(safeFileName(userId) + ".jsonl")
                .toAbsolutePath()
                .normalize();
    }

    private String safeFileName(String userId) {
        String safe = userId.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? DEFAULT_USER_ID : safe;
    }

    private Object lock(String userId) {
        return userLocks.computeIfAbsent(safeFileName(userId), ignored -> new Object());
    }

    private List<Song> safeSongs(List<Song> songs) {
        return songs == null ? List.of() : List.copyOf(songs);
    }

    private String writeJson(ConversationHistoryMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize conversation history message", e);
        }
    }
}
