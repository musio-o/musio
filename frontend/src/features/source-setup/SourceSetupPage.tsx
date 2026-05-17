import { useCallback, useEffect, useState } from "react";
import { Check, CircleDashed, LogIn, SkipForward } from "lucide-react";
import { EventLog, LoginStartResult, LoginStatus, ProviderStatus } from "../../shared/types";
import { providerSetupClient } from "./providerSetupClient";
import { ProviderLoginCard } from "./ProviderLoginCard";

type SourceSetupPageProps = {
  busy: boolean;
  selectedSources: string[];
  onBusyChange: (busy: boolean) => void;
  onEvent: (event: EventLog) => void;
  onProviderStatusesChange?: (statuses: ProviderStatus[]) => void;
  onContinue: () => void;
};

const QQ_PROVIDER = "qqmusic";
const TERMINAL_LOGIN_STATES = new Set(["DONE", "EXPIRED", "FAILED"]);
const SOURCE_OPTIONS = [
  {
    id: "qqmusic",
    label: "QQ 音乐",
    detail: "扫码登录后可使用搜索、播放链接、歌词、评论和歌单能力。",
    loginKind: "扫码登录",
    blocksReady: true
  },
  {
    id: "netease",
    label: "网易云音乐",
    detail: "预留音乐源，MVP 阶段暂未开放，不会阻塞进入核心页面。",
    loginKind: "暂未开放",
    blocksReady: false
  },
  {
    id: "local",
    label: "本地音乐",
    detail: "不需要账号登录；后续通过授权目录沙箱访问本地音频文件。",
    loginKind: "目录授权",
    blocksReady: false
  }
];

export function SourceSetupPage({
  busy,
  selectedSources,
  onBusyChange,
  onEvent,
  onProviderStatusesChange,
  onContinue
}: SourceSetupPageProps) {
  const [login, setLogin] = useState<LoginStartResult | null>(null);
  const [loginStatus, setLoginStatus] = useState<LoginStatus | null>(null);
  const [providerStatuses, setProviderStatuses] = useState<ProviderStatus[]>([]);
  const [skippedSources, setSkippedSources] = useState<Set<string>>(() => new Set());
  const [musicGeneBusy, setMusicGeneBusy] = useState(false);
  const selectedSet = new Set(selectedSources);
  const qqMusicSelected = selectedSet.has(QQ_PROVIDER);

  const refreshProviderStatuses = useCallback(() => {
    providerSetupClient.providers()
      .then((statuses) => {
        setProviderStatuses(statuses);
        onProviderStatusesChange?.(statuses);
      })
      .catch(() => setProviderStatuses([]));
  }, [onProviderStatusesChange]);

  useEffect(() => {
    refreshProviderStatuses();
  }, [refreshProviderStatuses]);

  useEffect(() => {
    if (loginStatus?.state === "DONE") {
      refreshProviderStatuses();
    }
  }, [loginStatus?.state, refreshProviderStatuses]);

  useEffect(() => {
    if (!login?.sessionId) {
      return;
    }

    if (loginStatus && TERMINAL_LOGIN_STATES.has(loginStatus.state)) {
      return;
    }

    let cancelled = false;

    const poll = async () => {
      try {
        const result = await providerSetupClient.loginStatus(QQ_PROVIDER, login.sessionId);
        if (cancelled) {
          return;
        }
        setLoginStatus((previous) => {
          if (previous?.state !== result.state) {
            onEvent({ id: crypto.randomUUID(), name: "login", detail: `登录状态：${loginStateLabel(result.state)}` });
          }
          return result;
        });
      } catch (error) {
        if (!cancelled) {
          onEvent({
            id: crypto.randomUUID(),
            name: "login",
            detail: error instanceof Error ? error.message : "登录状态轮询失败"
          });
        }
      }
    };

    const interval = window.setInterval(() => {
      if (!loginStatus || !TERMINAL_LOGIN_STATES.has(loginStatus.state)) {
        void poll();
      }
    }, 2000);

    void poll();

    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, [login?.sessionId, loginStatus?.state, onEvent]);

  async function startLogin() {
    onBusyChange(true);
    try {
      await beginLogin();
    } catch (error) {
      onEvent({
        id: crypto.randomUUID(),
        name: "login",
        detail: error instanceof Error ? error.message : "启动音乐源登录失败"
      });
    } finally {
      onBusyChange(false);
    }
  }

  async function relogin() {
    onBusyChange(true);
    try {
      const logout = await providerSetupClient.logout(QQ_PROVIDER);
      setLogin(null);
      setLoginStatus(logout);
      refreshProviderStatuses();
      onEvent({ id: crypto.randomUUID(), name: "login", detail: "已清理本地 QQ 音乐凭证，准备重新扫码" });
      await beginLogin();
    } catch (error) {
      onEvent({
        id: crypto.randomUUID(),
        name: "login",
        detail: error instanceof Error ? error.message : "重新登录失败"
      });
    } finally {
      onBusyChange(false);
    }
  }

  async function beginLogin() {
    const result = await providerSetupClient.startLogin(QQ_PROVIDER);
    setSkippedSources((current) => {
      const next = new Set(current);
      next.delete(QQ_PROVIDER);
      return next;
    });
    setLogin(result);
    setLoginStatus({
      sessionId: result.sessionId,
      provider: result.provider,
      state: result.state,
      credentialStored: false,
      message: result.message
    });
    onEvent({ id: crypto.randomUUID(), name: "login", detail: `已开始登录：${loginStateLabel(result.state)}` });
  }

  function skipSource(sourceId: string) {
    setSkippedSources((current) => new Set(current).add(sourceId));
    onEvent({ id: crypto.randomUUID(), name: "source", detail: `已跳过：${sourceDisplayName(sourceId)}` });
  }

  async function generateMusicGene() {
    setMusicGeneBusy(true);
    onBusyChange(true);
    try {
      await providerSetupClient.musicGene(QQ_PROVIDER);
      onEvent({ id: crypto.randomUUID(), name: "music_gene", detail: "QQ 音乐基因已生成" });
      refreshProviderStatuses();
    } catch (error) {
      onEvent({
        id: crypto.randomUUID(),
        name: "music_gene",
        detail: error instanceof Error ? error.message : "生成音乐基因失败"
      });
    } finally {
      setMusicGeneBusy(false);
      onBusyChange(false);
    }
  }

  const statusByProvider = new Map(providerStatuses.map((item) => [providerKey(item.provider), item]));
  const selectedOptions = SOURCE_OPTIONS.filter((source) => selectedSet.has(source.id));
  const displayOptions = selectedOptions.length > 0 ? selectedOptions : SOURCE_OPTIONS.filter((source) => source.id === QQ_PROVIDER);
  const readyCount = displayOptions.filter((source) => sourceReadiness(source.id, statusByProvider.get(source.id), loginStatus, skippedSources).ready).length;
  const actionableCount = displayOptions.filter((source) => sourceReadiness(source.id, statusByProvider.get(source.id), loginStatus, skippedSources).actionable).length;
  const blockedCount = displayOptions.filter((source) => {
    const readiness = sourceReadiness(source.id, statusByProvider.get(source.id), loginStatus, skippedSources);
    return readiness.actionable && !readiness.ready && !readiness.skipped;
  }).length;

  return (
    <section className="source-activation">
      <section className="source-hero">
        <div>
          <p className="eyebrow">终端已选择</p>
          <div className="readiness-meter">
            <span>{readyCount}</span>
            <small>/{displayOptions.length} 已就绪</small>
          </div>
        </div>
        <div className="source-hero-copy">
          <div className="source-hero-cta">
            <div>
              <span>进入策略</span>
              <h2>可以先进入，也可以稍后连接</h2>
              <p>
                登录是能力检查，不是硬性门槛。至少一个音乐源就绪后可以进入核心页面，也可以先进入受限模式，稍后再连接音乐源。
              </p>
            </div>
            <button className="primary-action" type="button" onClick={onContinue}>
              {readyCount > 0 ? "进入核心页面" : "进入受限模式"}
            </button>
          </div>
        </div>
      </section>

      <section className="panel source-readiness-panel">
        <div className="panel-heading">
          <h2>激活时间线</h2>
          <span>{blockedCount > 0 ? `${blockedCount} 项待处理` : "已清理"}</span>
        </div>
        <div className="source-timeline">
          {displayOptions.map((source, index) => {
            const providerStatus = statusByProvider.get(source.id);
            const readiness = sourceReadiness(source.id, providerStatus, loginStatus, skippedSources);
            return (
              <article className={`source-step ${readiness.tone}`} key={source.id}>
                <div className="source-step-index">{String(index + 1).padStart(2, "0")}</div>
                <div className="source-step-body">
                  <div className="source-step-title">
                    <h3>{source.label}</h3>
                    <span>{readiness.label}</span>
                  </div>
                  <p>{providerStatus?.message ?? source.detail}</p>
                  <div className="source-step-meta">
                    <span>{source.loginKind}</span>
                    <span>{selectedSet.has(source.id) ? "已选择" : "未选择"}</span>
                  </div>
                </div>
                <div className="source-step-mark" aria-hidden="true">
                  {readiness.ready ? <Check size={18} /> : <CircleDashed size={18} />}
                </div>
              </article>
            );
          })}
        </div>
      </section>

      <div className="source-detail-grid">
        {qqMusicSelected ? (
          <ProviderLoginCard
            providerLabel="QQ 音乐"
            login={login}
            loginStatus={loginStatus}
            authenticated={Boolean(statusByProvider.get(QQ_PROVIDER)?.authenticated)}
            credentialStored={Boolean(statusByProvider.get(QQ_PROVIDER)?.credentialStored)}
            skipped={skippedSources.has(QQ_PROVIDER)}
            connectionState={statusByProvider.get(QQ_PROVIDER)?.connectionState}
            musicGeneState={statusByProvider.get(QQ_PROVIDER)?.musicGeneState}
            busy={busy}
            musicGeneBusy={musicGeneBusy}
            onStartLogin={startLogin}
            onRelogin={relogin}
            onSkip={() => skipSource(QQ_PROVIDER)}
            onGenerateMusicGene={generateMusicGene}
          />
        ) : (
          <section className="panel auth-panel">
            <div className="panel-heading">
              <h2>QQ 音乐</h2>
              <span>未选择</span>
            </div>
            <p className="empty-copy">在终端启动流程中选择 QQ 音乐后，才会进入 MVP 登录流程。</p>
          </section>
        )}

        <section className="panel source-policy-panel">
          <div className="panel-heading">
            <h2>进入策略</h2>
            <span>{actionableCount} 项可操作</span>
          </div>
          <div className="policy-list">
            <div>
              <span>部分登录</span>
              <strong>允许</strong>
            </div>
            <div>
              <span>本地音乐源</span>
              <strong>不需要账号登录</strong>
            </div>
            <div>
              <span>目录访问</span>
              <strong>后续必须经过沙箱授权</strong>
            </div>
          </div>
          <button className="secondary-action" type="button" onClick={onContinue}>
            <LogIn size={17} />
            继续
          </button>
          {blockedCount > 0 ? (
            <button className="ghost-action" type="button" onClick={() => displayOptions.forEach((source) => {
              const readiness = sourceReadiness(source.id, statusByProvider.get(source.id), loginStatus, skippedSources);
              if (readiness.actionable && !readiness.ready) {
                skipSource(source.id);
              }
            })}>
              <SkipForward size={16} />
              跳过待处理项
            </button>
          ) : null}
        </section>
      </div>
    </section>
  );
}

function providerKey(provider: string) {
  const normalized = provider.replace(/[_-]/g, "").toLowerCase();
  if (normalized === "qqmusic" || normalized === "qq") {
    return "qqmusic";
  }
  if (normalized === "neteasecloudmusic" || normalized === "netease") {
    return "netease";
  }
  return normalized;
}

function sourceReadiness(
  sourceId: string,
  providerStatus: ProviderStatus | undefined,
  loginStatus: LoginStatus | null,
  skippedSources: Set<string>
) {
  if (sourceId === QQ_PROVIDER) {
    if (providerStatus?.connectionState === "EXPIRED") {
      return { label: "登录已过期", ready: false, actionable: true, skipped: false, tone: "error" };
    }
    if (providerStatus?.connectionState === "UNVERIFIED") {
      return { label: "等待校验", ready: false, actionable: true, skipped: false, tone: "pending" };
    }
    if (providerStatus?.authenticated || loginStatus?.state === "DONE") {
      return {
        label: providerStatus?.musicGeneState === "READY" ? "已连接 / 基因已就绪" : "已连接 / 待生成音乐基因",
        ready: true,
        actionable: true,
        skipped: false,
        tone: "ready"
      };
    }
    if (skippedSources.has(sourceId)) {
      return { label: "已跳过", ready: false, actionable: true, skipped: true, tone: "skipped" };
    }
    if (loginStatus?.state === "EXPIRED" || loginStatus?.state === "FAILED") {
      return { label: loginStateLabel(loginStatus.state), ready: false, actionable: true, skipped: false, tone: "error" };
    }
    if (loginStatus) {
      return { label: loginStateLabel(loginStatus.state), ready: false, actionable: true, skipped: false, tone: "pending" };
    }
    return { label: "需要登录", ready: false, actionable: true, skipped: false, tone: "pending" };
  }

  if (sourceId === "local") {
    return {
      label: providerStatus?.available ? "已就绪" : "沙箱预留",
      ready: Boolean(providerStatus?.available),
      actionable: false,
      skipped: !providerStatus?.available,
      tone: providerStatus?.available ? "ready" : "skipped"
    };
  }

  return {
    label: providerStatus?.available ? "可用" : "暂未开放",
    ready: Boolean(providerStatus?.authenticated),
    actionable: Boolean(providerStatus?.available),
    skipped: !providerStatus?.available,
    tone: providerStatus?.available ? "pending" : "skipped"
  };
}

function sourceDisplayName(sourceId: string) {
  switch (sourceId) {
    case "qqmusic":
      return "QQ 音乐";
    case "netease":
      return "网易云音乐";
    case "local":
      return "本地音乐";
    default:
      return sourceId;
  }
}

function loginStateLabel(state: string) {
  switch (state) {
    case "CREATED":
      return "已创建";
    case "NOT_SCANNED":
      return "等待扫码";
    case "SCANNED":
      return "已扫码，等待确认";
    case "DONE":
      return "已登录";
    case "EXPIRED":
      return "二维码已过期";
    case "FAILED":
      return "登录失败";
    case "LOGGED_OUT":
      return "已退出";
    default:
      return state;
  }
}
