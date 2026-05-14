package com.musio.player;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PlayerQueueService {
    private PlayerState state = new PlayerState(
            null,
            List.of(),
            -1,
            true,
            0,
            null,
            PlayerState.PlaybackMode.SEQUENTIAL,
            "No lyric loaded"
    );

    public synchronized PlayerState state() {
        return state;
    }

    public synchronized PlayerState sync(PlayerState nextState) {
        if (nextState == null) {
            return state;
        }
        state = nextState;
        return state;
    }

    public synchronized PlayerState pause() {
        state = new PlayerState(
                state.currentSong(),
                state.queue(),
                state.currentIndex(),
                true,
                state.positionSeconds(),
                state.durationSeconds(),
                state.playbackMode(),
                state.lyricLine()
        );
        return state;
    }

    public synchronized PlayerState resume() {
        state = new PlayerState(
                state.currentSong(),
                state.queue(),
                state.currentIndex(),
                false,
                state.positionSeconds(),
                state.durationSeconds(),
                state.playbackMode(),
                state.lyricLine()
        );
        return state;
    }
}
