import type { CSSProperties } from "react";

type ProgressBarProps = {
  positionSeconds: number;
  durationSeconds: number | null;
};

const PROGRESS_SEGMENT_COUNT = 32;

export function ProgressBar({ positionSeconds, durationSeconds }: ProgressBarProps) {
  const progress = durationSeconds ? Math.min(100, Math.round((positionSeconds / durationSeconds) * 100)) : 0;
  const activeSegments = Math.round((progress / 100) * PROGRESS_SEGMENT_COUNT);
  const currentTime = formatTime(positionSeconds);
  const totalTime = formatTime(durationSeconds ?? 0);
  const valueNow = durationSeconds ? Math.min(positionSeconds, durationSeconds) : 0;

  return (
    <div className="progress-row">
      <span>{currentTime}</span>
      <div
        className="progress-track"
        role="progressbar"
        aria-label="播放进度"
        aria-valuemin={0}
        aria-valuemax={durationSeconds ?? 0}
        aria-valuenow={valueNow}
        aria-valuetext={`${currentTime} / ${totalTime}`}
        style={{ "--progress": `${progress}%` } as CSSProperties}
      >
        {Array.from({ length: PROGRESS_SEGMENT_COUNT }, (_, index) => (
          <i key={index} className={index < activeSegments ? "active" : undefined} />
        ))}
      </div>
      <span>{totalTime}</span>
    </div>
  );
}

function formatTime(seconds: number) {
  const minutes = Math.floor(seconds / 60);
  const rest = Math.floor(seconds % 60).toString().padStart(2, "0");
  return `${minutes}:${rest}`;
}
