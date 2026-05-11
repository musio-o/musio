package com.musio.agent;

import com.musio.model.PendingConfirmation;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class ConfirmationService {
    private final Map<String, PendingConfirmation> pendingConfirmations = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<PendingConfirmation>> waitingConfirmations = new ConcurrentHashMap<>();

    public void save(String runId, PendingConfirmation confirmation) {
        pendingConfirmations.put(runId, confirmation);
    }

    public Optional<PendingConfirmation> find(String runId) {
        return Optional.ofNullable(pendingConfirmations.get(runId));
    }

    public void clear(String runId) {
        pendingConfirmations.remove(runId);
        waitingConfirmations.entrySet().removeIf(entry -> entry.getKey().startsWith(waitKeyPrefix(runId)));
    }

    public void prepare(String runId, String actionId) {
        waitingConfirmations.computeIfAbsent(waitKey(runId, actionId), key -> new CompletableFuture<>());
    }

    public PendingConfirmation await(String runId, String actionId, long timeoutSeconds) {
        String key = waitKey(runId, actionId);
        CompletableFuture<PendingConfirmation> future = waitingConfirmations.computeIfAbsent(key, ignored -> new CompletableFuture<>());
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return new PendingConfirmation(actionId, false, Map.of("reason", "timeout"));
        } catch (Exception e) {
            return new PendingConfirmation(actionId, false, Map.of("reason", "interrupted"));
        } finally {
            waitingConfirmations.remove(key);
        }
    }

    public boolean confirm(String runId, PendingConfirmation confirmation) {
        if (confirmation == null || confirmation.actionId() == null || confirmation.actionId().isBlank()) {
            return false;
        }
        CompletableFuture<PendingConfirmation> future = waitingConfirmations.get(waitKey(runId, confirmation.actionId()));
        return future != null && future.complete(confirmation);
    }

    private String waitKey(String runId, String actionId) {
        return waitKeyPrefix(runId) + (actionId == null ? "" : actionId.strip());
    }

    private String waitKeyPrefix(String runId) {
        return (runId == null ? "" : runId.strip()) + "|";
    }
}
