import { CircleStop } from "lucide-react";
import { EventLog } from "../../shared/types";

type AgentEventsProps = {
  events: EventLog[];
  onClear: () => void;
};

export function AgentEvents({ events, onClear }: AgentEventsProps) {
  return (
    <section className="panel event-panel">
      <div className="panel-heading">
        <h2>执行事件</h2>
        <button type="button" onClick={onClear} aria-label="清空事件">
          <CircleStop size={17} />
        </button>
      </div>
      <div className="event-list">
        {events.map((item) => (
          <article key={item.id} className="event-row">
            <span>{eventNameLabel(item.name)}</span>
            <p>{item.detail}</p>
          </article>
        ))}
      </div>
    </section>
  );
}

function eventNameLabel(name: string) {
  switch (name) {
    case "run":
      return "任务";
    case "agent":
      return "Agent";
    case "tool_start":
      return "工具开始";
    case "tool_result":
      return "工具结果";
    case "song_cards":
      return "歌曲卡片";
    case "queue":
      return "队列";
    case "favorite":
      return "收藏";
    case "trace_step":
      return "进展";
    case "agent_error":
    case "error":
      return "错误";
    case "search":
      return "搜索";
    case "player":
      return "播放器";
    case "login":
      return "登录";
    case "source":
      return "音乐源";
    case "music_gene":
      return "音乐基因";
    default:
      return name;
  }
}
