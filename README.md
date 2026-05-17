# Musio

Musio 是一个本地优先的音乐 Agent。它会在本机启动音乐源适配器、Spring Boot 后端、React Web 控制台和 Java CLI 启动器，让用户可以通过网页或命令行连接 QQ 音乐、搜索歌曲、播放音乐、查看歌词/评论，并让 Agent 基于本地记忆和音乐源能力完成推荐、歌单和对话任务。

当前实现优先支持 QQ 音乐。未来会支持网易云音乐和本地音乐源等更多音乐源。

## 项目结构

```text
backend-spring/                   Spring Boot API、Agent Runtime、配置、记忆和音乐接口
cli-java/                         Java CLI，本地服务启动器和诊断命令
frontend/                         React + Vite Web 控制台
providers/qqmusic-python-sidecar/ QQ 音乐 Python HTTP sidecar
config/musio.example.toml         用户配置模板
packaging/                        npm 平台包和发布包构建脚本
scripts/                          本地开发启动、停止和辅助脚本
docker-compose.yml                仅用于启动 QQMusic sidecar 的 Docker Compose 示例
```

## 默认端口

```text
Spring backend:          http://127.0.0.1:18765
React web dev server:    http://127.0.0.1:18766
QQMusic Python sidecar:  http://127.0.0.1:18767
```

开发模式下浏览器访问 `http://127.0.0.1:18766/`。发布模式下前端静态资源由 Spring backend 托管，通常访问 backend 地址即可。

## 安装方式一：使用发布包

如果使用已经发布到 npm 的 Musio 包，可以直接全局安装：

```bash
npm install -g @mindforge-x/musio
musio
```

也可以在某个项目内局部安装后运行：

```bash
npm install @mindforge-x/musio
npx musio
```

发布包会通过可选平台依赖安装匹配当前系统和 CPU 架构的运行时包。启动后直接执行 `musio`，CLI 会创建配置文件、选择音乐源、启动本地服务并打开 Web 页面。

常用命令：

```bash
musio                 # 启动本地服务并打开 Web 控制台
musio web             # 同上
musio doctor          # 检查 java、mvn、python3、node、npm 等运行时依赖
musio status          # 查看后端系统状态
musio stop            # 停止本地 Musio 服务
musio config get      # 查看 CLI 可管理的本地配置
musio config set server.port 18775
```

## 安装方式二：从源码运行

源码开发适合调试后端、前端、CLI 或 QQ 音乐 sidecar。推荐先安装这些依赖：

```text
Java JDK 21+
Maven 3.6+
Node.js 18+ 和 npm
Python 3.11+
```

克隆并进入项目：

```bash
git clone https://github.com/mindforge-x/musio.git
cd musio
```

创建用户配置：

```bash
mkdir -p ~/.musio
cp config/musio.example.toml ~/.musio/config.toml
```

安装 QQ 音乐 sidecar 依赖：

```bash
cd providers/qqmusic-python-sidecar
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cd ../..
```

安装前端依赖：

```bash
cd frontend
npm install
cd ..
```

如果 npm、pip 或 Maven 下载依赖需要代理，请使用对应工具的代理配置。例如 npm 可以临时指定：

```bash
npm install --proxy=http://127.0.0.1:7890 --https-proxy=http://127.0.0.1:7890
```

预热 Java 依赖并构建 CLI：

```bash
mvn -q -pl backend-spring -am -DskipTests compile
mvn -q -pl cli-java -am -DskipTests package
```

一条命令启动全部开发服务：

```bash
./scripts/dev.sh
```

也可以分三个终端分别启动，便于单独看日志：

```bash
./scripts/dev-sidecar.sh
./scripts/dev-backend.sh
./scripts/dev-frontend.sh
```

停止开发服务：

```bash
./scripts/stop-dev.sh
```

源码模式下也可以直接运行构建出的 CLI：

```bash
java -jar cli-java/target/musio-cli.jar
java -jar cli-java/target/musio-cli.jar doctor
java -jar cli-java/target/musio-cli.jar stop
```

## Windows 安装和运行

Windows 下建议在 PowerShell 中运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev.ps1
```

该脚本会检查 JDK、Maven、Node.js 和 Python 3.11+，并在需要时自动安装前端依赖、创建 `providers\qqmusic-python-sidecar\.venv-win`、启动 sidecar、backend 和 frontend。

停止服务：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\win\stop-windows.ps1
```

如果 Windows 上有多个 Python，可以指定解释器：

```powershell
$env:MUSIO_PYTHON_EXE = "C:\Path\To\Python311\python.exe"
powershell -ExecutionPolicy Bypass -File .\scripts\dev.ps1
```

## WSL 注意事项

如果在 WSL 中运行项目，但 Java 和 Maven 实际安装在 Windows，`scripts/dev-backend.sh` 会回退到 `scripts/mvn-win-jdk21.sh`。默认 Windows 路径是：

```text
D:\Env\JDK21\bin\java.exe
D:\Env\Maven\apache-maven-3.6.1-bin\apache-maven-3.6.1
```

路径不一致时设置：

```bash
export MUSIO_JAVA_EXE_WIN='D:\Your\JDK21\bin\java.exe'
export MUSIO_MAVEN_HOME_WIN='D:\Your\Maven'
```

如果后端通过 Windows JDK 启动，Java 看到的用户目录是 Windows 用户目录，因此默认配置文件会变成：

```text
C:\Users\<you>\.musio\config.toml
/mnt/c/Users/<you>/.musio/config.toml
```

为了避免路径混乱，推荐显式指定配置文件：

```bash
export MUSIO_CONFIG="$HOME/.musio/config.toml"
```

## 用户配置

Musio 默认读取：

```text
~/.musio/config.toml
```

Windows 默认读取：

```text
C:\Users\<you>\.musio\config.toml
```

可以通过环境变量覆盖配置文件位置：

```bash
export MUSIO_CONFIG=/absolute/path/to/config.toml
```

首次通过 `musio` CLI 启动时，如果配置文件不存在，CLI 会自动创建默认配置。源码开发时也可以手动复制 `config/musio.example.toml`。

推荐配置模板：

```toml
[ai]
provider = "openai-compatible"
base_url = "http://127.0.0.1:11434/v1"
api_key = "${MUSIO_AI_API_KEY:}"
model = "qwen2.5:7b"
temperature = 0.7
max_tokens = 2048

[server]
host = "127.0.0.1"
port = 18765

[web]
host = "127.0.0.1"
port = 18766

[providers.qqmusic]
sidecar_host = "127.0.0.1"
sidecar_port = 18767
sidecar_base_url = "http://127.0.0.1:18767"
allow_static_manifest_fallback = false

[storage]
home = "~/.musio"
```

配置说明：

| 配置项 | 说明 |
| --- | --- |
| `ai.provider` | 模型供应商标识。当前调用链按 OpenAI-compatible Chat Completions 形状发送请求。 |
| `ai.base_url` | 模型服务地址。可以是服务根地址、`/v1` 地址，或完整 `/v1/chat/completions` 地址。 |
| `ai.api_key` | API Key。支持 `${ENV_NAME}` 或 `${ENV_NAME:fallback}` 形式引用环境变量。 |
| `ai.model` | 模型名称，必须与目标模型服务中的名称一致。 |
| `ai.temperature` | 模型采样温度。 |
| `ai.max_tokens` | 单次模型响应的最大 token 数。 |
| `server.host` / `server.port` | Spring backend 监听地址和端口。 |
| `web.host` / `web.port` | 开发模式下 Vite Web 服务监听地址和端口。 |
| `providers.qqmusic.sidecar_host` / `sidecar_port` | QQMusic sidecar 监听地址和端口。设置后后端会优先按 host/port 推导 sidecar URL。 |
| `providers.qqmusic.sidecar_base_url` | QQMusic sidecar 完整地址。未单独设置 host/port 时使用。 |
| `providers.qqmusic.allow_static_manifest_fallback` | 开发兼容开关。建议保持 `false`，让 Agent 使用 sidecar 实时暴露的工具清单。 |
| `storage.home` | Musio 数据目录，保存登录凭证、设备信息、歌单、记忆、运行时文件等。 |

AI 配置文件会被后端监听并动态重载。服务端口、存储目录和音乐源 sidecar 配置通常需要重启本地服务后生效。

## AI 模型配置示例

本地 Ollama：

```toml
[ai]
provider = "ollama"
base_url = "http://127.0.0.1:11434/v1"
api_key = ""
model = "qwen2.5:7b"
temperature = 0.7
max_tokens = 2048
```

OpenAI：

```toml
[ai]
provider = "openai"
base_url = "https://api.openai.com"
api_key = "${OPENAI_API_KEY}"
model = "your-openai-model"
temperature = 0.7
max_tokens = 2048
```

把 `model` 替换成你的账号或网关实际可用的模型名。

本地或内网 OpenAI-compatible 网关：

```toml
[ai]
provider = "openai-compatible"
base_url = "http://127.0.0.1:8000/v1"
api_key = "${MUSIO_AI_API_KEY:}"
model = "your-model-name"
temperature = 0.7
max_tokens = 2048
```

如果 API Key 不想写入配置文件，使用环境变量：

```bash
export OPENAI_API_KEY="sk-..."
```

或：

```bash
export MUSIO_AI_API_KEY="your-key"
```

## QQ 音乐配置和登录

QQMusic sidecar 默认地址是：

```text
http://127.0.0.1:18767
```

启动 Web 控制台后进入登录页面，使用 QQ 音乐 App 或 QQ 扫码完成授权。凭证默认写入：

```text
~/.musio/credentials/qqmusic.json
```

设备信息默认写入：

```text
~/.musio/qqmusic-device.json
```

可以用环境变量覆盖 sidecar 的调试配置：

```bash
export MUSIO_QQMUSIC_CREDENTIALS=/path/to/qqmusic.json
export MUSIO_QQMUSIC_DEVICE_PATH=/path/to/qqmusic-device.json
export MUSIO_QQMUSIC_PROXY=http://127.0.0.1:7890
```

`MUSIO_QQMUSIC_PROXY` 只传给 `qqmusic-api-python` 客户端请求。二维码登录过程需要访问 QQ 登录接口，如果网络环境有限，请同时确保系统代理或运行环境网络可用。

## 环境变量

大部分情况下只需要编辑 `~/.musio/config.toml`。下面这些环境变量适合临时调试、CI 或特殊本地环境：

| 环境变量 | 作用 |
| --- | --- |
| `MUSIO_CONFIG` | 指定配置文件路径。 |
| `MUSIO_HOME` | 运行时数据目录。CLI 启动子进程时会设置为 `storage.home`，手动运行 sidecar 时可用它共享同一份凭证目录。 |
| `MUSIO_STORAGE_HOME` | 指定默认数据目录；如果 TOML 中配置了 `[storage].home`，以 TOML 为准。 |
| `MUSIO_AI_PROVIDER` | AI provider 默认值；配置文件中缺少 `ai.provider` 时使用。 |
| `MUSIO_AI_BASE_URL` | AI base URL 默认值。 |
| `MUSIO_AI_API_KEY` | AI API Key 默认值，也可被 TOML 中 `${MUSIO_AI_API_KEY:}` 引用。 |
| `MUSIO_AI_MODEL` | AI model 默认值。 |
| `MUSIO_AI_TEMPERATURE` | AI temperature 默认值。 |
| `MUSIO_AI_MAX_TOKENS` | AI max tokens 默认值。 |
| `MUSIO_SERVER_HOST` / `MUSIO_SERVER_PORT` | Spring backend 启动地址和端口。 |
| `MUSIO_WEB_HOST` / `MUSIO_WEB_PORT` | Vite Web 开发服务地址和端口。 |
| `MUSIO_BACKEND_BASE_URL` | 前端开发服务器代理到的后端地址，通常由脚本自动设置。 |
| `VITE_MUSIO_BACKEND_URL` | Vite 代理使用的后端地址。 |
| `MUSIO_CORS_ALLOWED_ORIGINS` | 后端允许的前端来源，多个来源用英文逗号分隔。 |
| `MUSIO_QQMUSIC_HOST` / `MUSIO_QQMUSIC_PORT` | QQMusic sidecar 启动地址和端口。 |
| `MUSIO_QQMUSIC_SIDECAR_BASE_URL` | 后端访问 QQMusic sidecar 的默认地址。 |
| `MUSIO_QQMUSIC_CREDENTIALS` | QQ 音乐凭证文件路径。 |
| `MUSIO_QQMUSIC_DEVICE_PATH` | QQMusic API 设备信息文件路径。 |
| `MUSIO_QQMUSIC_PROXY` | QQMusic API 客户端代理地址。 |
| `MUSIO_SELECTED_SOURCES` | 当前启用的音乐源 ID 列表，逗号分隔。 |
| `MUSIO_ACTIVE_SOURCE` | 当前默认音乐源 ID。 |
| `MUSIO_BACKEND_LOG_FILE` | Spring backend 日志文件路径。 |
| `MUSIO_PYTHON_EXE` | 指定 Python 3.11+ 解释器，主要用于 Windows 或发布包 fallback。 |
| `MUSIO_JAVA_EXE_WIN` | WSL 调用 Windows JDK 时的 `java.exe` 路径。 |
| `MUSIO_MAVEN_HOME_WIN` | WSL 调用 Windows Maven 时的 Maven Home。 |

## 构建

只构建前端：

```bash
cd frontend
npm run build
```

构建 Java 模块：

```bash
mvn -DskipTests package
```

如果希望 backend jar 内包含前端静态资源，先执行 `npm run build`，再执行 Maven package。Spring backend 的 Maven resources 会把 `frontend/dist` 复制到后端静态资源目录中。

平台 npm 包和发布流程见 `packaging/README.md`。

## Docker

当前 `docker-compose.yml` 只定义了 QQMusic sidecar，不会启动完整 Musio：

```bash
docker compose up --build qqmusic-sidecar
```

sidecar 启动后默认监听：

```text
http://127.0.0.1:18767
```

完整开发体验仍建议使用 `./scripts/dev.sh` 或 Windows `.\scripts\dev.ps1`。

## 常见问题

端口被占用时，先停止本地服务：

```bash
./scripts/stop-dev.sh
```

或改配置端口：

```bash
java -jar cli-java/target/musio-cli.jar config set server.port 18775
java -jar cli-java/target/musio-cli.jar config set web.port 18776
java -jar cli-java/target/musio-cli.jar config set providers.qqmusic.sidecar_port 18777
```

后端首次启动较慢通常是 Maven 正在下载依赖。CLI 对 Spring backend 的 ready 等待时间是 300 秒，日志在开发模式下写入：

```text
.musio/run/
```

发布模式下运行日志和 pid 文件写入：

```text
~/.musio/run/
```

如果浏览器能打开前端但 API 请求失败，检查：

```text
http://127.0.0.1:18765/actuator/health
http://127.0.0.1:18767/health
```

如果 QQ 音乐显示未登录或登录过期，重新进入登录页面扫码。也可以删除 `~/.musio/credentials/qqmusic.json` 后重新登录。

如果修改了配置但没有生效，先确认实际使用的配置路径：

```bash
java -jar cli-java/target/musio-cli.jar config get config.path
```

AI 配置支持动态重载；端口、存储目录、sidecar 地址等运行时配置改动后建议执行：

```bash
java -jar cli-java/target/musio-cli.jar stop
java -jar cli-java/target/musio-cli.jar
```
