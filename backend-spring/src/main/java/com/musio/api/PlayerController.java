package com.musio.api;

import com.musio.player.PlayerQueueService;
import com.musio.player.PlayerState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/player")
public class PlayerController {
    private final PlayerQueueService playerQueueService;

    public PlayerController(PlayerQueueService playerQueueService) {
        this.playerQueueService = playerQueueService;
    }

    @GetMapping("/state")
    public PlayerState state() {
        return playerQueueService.state();
    }

    @PostMapping("/state")
    public PlayerState syncState(@RequestBody PlayerState state) {
        return playerQueueService.sync(state);
    }

    @PostMapping("/pause")
    public PlayerState pause() {
        return playerQueueService.pause();
    }

    @PostMapping("/resume")
    public PlayerState resume() {
        return playerQueueService.resume();
    }
}
