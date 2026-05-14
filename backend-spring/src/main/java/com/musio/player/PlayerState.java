package com.musio.player;

import com.musio.model.Song;

import java.util.List;

public record PlayerState(
        Song currentSong,
        List<Song> queue,
        int currentIndex,
        boolean paused,
        int positionSeconds,
        Integer durationSeconds,
        PlaybackMode playbackMode,
        String lyricLine
) {
    public PlayerState {
        queue = queue == null ? List.of() : List.copyOf(queue);
        currentIndex = normalizeCurrentIndex(currentIndex, queue, currentSong);
        if (currentSong == null && currentIndex >= 0) {
            currentSong = queue.get(currentIndex);
        }
        positionSeconds = Math.max(0, positionSeconds);
        playbackMode = playbackMode == null ? PlaybackMode.SEQUENTIAL : playbackMode;
        lyricLine = lyricLine == null ? "" : lyricLine.strip();
    }

    public enum PlaybackMode {
        SEQUENTIAL,
        REPEAT_ONE,
        REPEAT_ALL,
        SHUFFLE
    }

    private static int normalizeCurrentIndex(int currentIndex, List<Song> queue, Song currentSong) {
        if (currentIndex >= 0 && currentIndex < queue.size()) {
            return currentIndex;
        }
        if (currentSong != null && currentSong.id() != null && !currentSong.id().isBlank()) {
            for (int index = 0; index < queue.size(); index++) {
                Song song = queue.get(index);
                if (song != null && currentSong.id().equals(song.id())) {
                    return index;
                }
            }
        }
        return -1;
    }
}
