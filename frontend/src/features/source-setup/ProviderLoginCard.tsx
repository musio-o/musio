import { QrCode, RotateCcw, SkipForward } from "lucide-react";
import { LoginStartResult, LoginStatus } from "../../shared/types";

type ProviderLoginCardProps = {
  providerLabel: string;
  login: LoginStartResult | null;
  loginStatus: LoginStatus | null;
  authenticated: boolean;
  credentialStored: boolean;
  skipped: boolean;
  connectionState?: string;
  musicGeneState?: string;
  busy: boolean;
  musicGeneBusy: boolean;
  onStartLogin: () => void;
  onRelogin: () => void;
  onSkip: () => void;
  onGenerateMusicGene: () => void;
};

export function ProviderLoginCard({
  providerLabel,
  login,
  loginStatus,
  authenticated,
  credentialStored,
  skipped,
  connectionState,
  musicGeneState,
  busy,
  musicGeneBusy,
  onStartLogin,
  onRelogin,
  onSkip,
  onGenerateMusicGene
}: ProviderLoginCardProps) {
  const state = providerStateLabel(connectionState, musicGeneState, authenticated, skipped, loginStatus?.state);
  const hasQr = Boolean(login?.qrCodeDataUrl);
  const canRelogin = !hasQr && (authenticated || credentialStored || Boolean(loginStatus?.credentialStored));

  return (
    <section className="panel auth-panel">
      <div className="panel-heading">
        <h2>{providerLabel}</h2>
        <span>{state}</span>
      </div>
      <div className="qr-box">
        {hasQr ? <img src={login?.qrCodeDataUrl ?? ""} alt={`${providerLabel} 登录二维码`} /> : <QrCode size={96} />}
      </div>
      <p className="auth-copy">{loginCopy(connectionState, musicGeneState, loginStatus?.state)}</p>
      <div className="auth-actions">
        {authenticated && musicGeneState !== "READY" ? (
          <button className="primary-action" type="button" onClick={onGenerateMusicGene} disabled={busy || musicGeneBusy}>
            <RotateCcw size={18} />
            {musicGeneBusy ? "生成中" : "生成音乐基因"}
          </button>
        ) : !authenticated ? (
          <button className="primary-action" type="button" onClick={onStartLogin} disabled={busy || authenticated}>
            {hasQr ? <RotateCcw size={18} /> : <QrCode size={18} />}
            {hasQr ? "刷新二维码" : "开始扫码登录"}
          </button>
        ) : null}
        {canRelogin ? (
          <button className="secondary-action" type="button" onClick={onRelogin} disabled={busy}>
            <RotateCcw size={16} />
            重新登录
          </button>
        ) : null}
        <button className="ghost-action" type="button" onClick={onSkip} disabled={busy || authenticated}>
          <SkipForward size={16} />
          跳过
        </button>
      </div>
    </section>
  );
}

function providerStateLabel(
  connectionState: string | undefined,
  musicGeneState: string | undefined,
  authenticated: boolean,
  skipped: boolean,
  loginState?: string
) {
  if (authenticated) {
    return musicGeneState === "READY" ? "已连接 / 基因已就绪" : "已连接 / 待生成音乐基因";
  }
  if (connectionState === "EXPIRED") {
    return "登录已过期";
  }
  if (connectionState === "UNVERIFIED") {
    return "等待校验";
  }
  if (skipped) {
    return "已跳过";
  }
  return loginStateLabel(loginState);
}

function loginStateLabel(state?: string) {
  switch (state) {
    case "CREATED":
      return "已创建";
    case "NOT_SCANNED":
      return "等待扫码";
    case "SCANNED":
      return "已扫码";
    case "DONE":
      return "已连接";
    case "EXPIRED":
      return "已过期";
    case "FAILED":
      return "失败";
    case "LOGGED_OUT":
      return "已退出";
    default:
      return "未连接";
  }
}

function loginCopy(connectionState?: string, musicGeneState?: string, state?: string) {
  if (connectionState === "EXPIRED") {
    return "QQ 音乐登录已过期，请重新扫码。";
  }
  if (connectionState === "UNVERIFIED") {
    return "已发现本地凭证，但当前无法完成远端校验。请确认 QQMusic sidecar 已启动。";
  }
  if (musicGeneState === "READY") {
    return "QQ 音乐已连接，音乐基因已生成。";
  }
  if (musicGeneState === "MISSING") {
    return "QQ 音乐已连接，可以生成音乐基因以增强后续推荐。";
  }
  switch (state) {
    case "CREATED":
    case "NOT_SCANNED":
      return "二维码已生成，请使用 QQ 扫码登录。";
    case "SCANNED":
      return "已扫码，请在 QQ 音乐中确认登录。";
    case "DONE":
      return "QQ 音乐已连接，可以进入核心页面。";
    case "EXPIRED":
      return "二维码已过期，请刷新后重新扫码。";
    case "FAILED":
      return "登录失败，可以重试或先进入受限模式。";
    case "LOGGED_OUT":
      return "当前已退出 QQ 音乐登录。";
    default:
      return "使用 QQ 音乐扫码登录；也可以先跳过，进入受限模式。";
  }
}
