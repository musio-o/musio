package com.musio.cli.process;

import com.musio.cli.config.MusioCliConfig;
import com.musio.cli.config.MusioCliConfigStore;
import com.musio.cli.ui.CliTimeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class LocalProcessManager {
    private final Path root;
    private final Path releaseDirectory;
    private final Path runDirectory;
    private final Path runtimeDirectory;
    private final MusioCliConfig config;
    private final boolean releaseMode;
    private final HttpProbe httpProbe = new HttpProbe();

    public LocalProcessManager() {
        this(new MusioCliConfigStore().load());
    }

    public LocalProcessManager(MusioCliConfig config) {
        this.config = config;
        this.root = new ProjectRootResolver().resolve();
        this.releaseDirectory = ProjectRootResolver.releaseDirectory(root).orElse(null);
        this.releaseMode = releaseDirectory != null;
        this.runDirectory = runDirectory(root, config, releaseMode);
        this.runtimeDirectory = musioHome(config).resolve("runtime");
    }

    public boolean startRequiredServices() {
        createRunDirectory();
        CliTimeline.step("启动本地服务");
        boolean ready = true;
        for (LocalService service : servicesToStart()) {
            ready = startIfNeeded(service) && ready;
        }
        return ready;
    }

    public Path root() {
        return root;
    }

    public Path runDirectory() {
        return runDirectory;
    }

    public String webBaseUrl() {
        return releaseMode ? config.backendBaseUrl() : config.webBaseUrl();
    }

    public int stopServices() {
        CliTimeline.step("停止本地服务");
        int failures = 0;
        for (LocalService service : servicesToStop()) {
            CliTimeline.branch(service.displayName());
            Path pidPath = pidPath(service);
            if (!Files.isRegularFile(pidPath)) {
                CliTimeline.muted("没有找到 pid 文件");
                continue;
            }
            try {
                long pid = Long.parseLong(Files.readString(pidPath).trim());
                Optional<ProcessHandle> handle = ProcessHandle.of(pid);
                if (handle.isEmpty() || !handle.get().isAlive()) {
                    Files.deleteIfExists(pidPath);
                    CliTimeline.success("进程已不在运行");
                    continue;
                }
                if (stopProcessTree(handle.get())) {
                    Files.deleteIfExists(pidPath);
                    CliTimeline.success("已停止 pid=" + pid);
                } else {
                    failures++;
                    CliTimeline.error("未能停止 pid=" + pid);
                }
            } catch (IOException | NumberFormatException e) {
                failures++;
                CliTimeline.error("读取 pid 失败：" + pidPath);
            }
        }
        CliTimeline.end(failures == 0 ? "Musio 服务已停止" : "Musio 服务停止未完全成功");
        return failures == 0 ? 0 : 1;
    }

    private boolean startIfNeeded(LocalService service) {
        var healthUri = service.healthUri(config);
        CliTimeline.branch(service.displayName());
        if (httpProbe.isReady(healthUri)) {
            CliTimeline.success("已在运行：" + healthUri);
            return true;
        }
        if (httpProbe.canConnect(healthUri)) {
            CliTimeline.error("端口已被其他服务占用：" + healthUri.getHost() + ":" + healthUri.getPort());
            CliTimeline.detail("可修改端口：musio config set " + service.portConfigKey() + " <port>");
            return false;
        }

        CliTimeline.pending("正在启动");
        Process process;
        try {
            process = launch(service);
        } catch (IllegalStateException e) {
            CliTimeline.error(e.getMessage());
            return false;
        }
        writePid(service, process);
        if (!releaseMode && service == LocalService.BACKEND) {
            CliTimeline.muted("Spring 首次启动可能会下载 Maven 依赖，最长等待 "
                    + service.timeout().toSeconds() + "s");
        } else if (releaseMode && service == LocalService.QQMUSIC_SIDECAR) {
            CliTimeline.muted("使用发布包内置 QQMusic sidecar");
        }

        if (httpProbe.waitUntilReady(healthUri, service.timeout())) {
            CliTimeline.success("ready: " + healthUri);
            return true;
        } else {
            if (!process.isAlive()) {
                CliTimeline.error("进程在 ready 前退出，exit code: " + process.exitValue());
            }
            CliTimeline.error("未在 " + service.timeout().toSeconds() + "s 内 ready");
            CliTimeline.detail("日志：" + logPath(service));
            return false;
        }
    }

    private Process launch(LocalService service) {
        ProcessBuilder builder = releaseMode ? releaseProcess(service) : devProcess(service);
        if (builder.directory() == null) {
            builder.directory(root.toFile());
        }
        configureEnvironment(builder.environment());
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath(service).toFile()));
        builder.redirectErrorStream(true);
        try {
            return builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start " + service.displayName() + " from " + root, e);
        }
    }

    private void configureEnvironment(Map<String, String> environment) {
        environment.put("MUSIO_CONFIG", config.configPath().toString());
        environment.put("MUSIO_HOME", root.toString());
        environment.put("MUSIO_RUNTIME_MODE", releaseMode ? "release" : "dev");
        environment.put("MUSIO_SERVER_HOST", config.serverHost());
        environment.put("MUSIO_SERVER_PORT", Integer.toString(config.serverPort()));
        environment.put("MUSIO_WEB_HOST", config.webHost());
        environment.put("MUSIO_WEB_PORT", Integer.toString(config.webPort()));
        environment.put("MUSIO_BACKEND_BASE_URL", config.backendBaseUrl());
        environment.put("MUSIO_CORS_ALLOWED_ORIGINS", corsAllowedOrigins());
        environment.put("MUSIO_BACKEND_LOG_FILE", logPath(LocalService.BACKEND).toString());
        environment.put("MUSIO_QQMUSIC_HOST", config.qqMusicSidecarHost());
        environment.put("MUSIO_QQMUSIC_PORT", Integer.toString(config.qqMusicSidecarPort()));
        environment.put("MUSIO_QQMUSIC_SIDECAR_BASE_URL", config.qqMusicSidecarBaseUrl());
    }

    private ProcessBuilder devProcess(LocalService service) {
        return isWindows() ? windowsProcess(service) : unixProcess(service);
    }

    private ProcessBuilder releaseProcess(LocalService service) {
        return switch (service) {
            case QQMUSIC_SIDECAR -> releaseSidecarProcess();
            case BACKEND -> new ProcessBuilder(releaseJavaExecutable(), "-jar", releaseBackendJar().toString());
            case FRONTEND -> throw new IllegalStateException("生产模式不再启动独立 React frontend");
        };
    }

    private ProcessBuilder releaseSidecarProcess() {
        Path sidecarBinary = releaseSidecarBinary();
        if (Files.isRegularFile(sidecarBinary)) {
            return new ProcessBuilder(sidecarBinary.toString())
                    .directory(sidecarBinary.getParent().toFile());
        }

        Path sidecarDirectory = releaseSidecarSourceDirectory();
        if (!Files.isDirectory(sidecarDirectory)) {
            throw new IllegalStateException("未找到 QQMusic sidecar 发布产物：" + releaseDirectory.resolve("sidecar"));
        }
        Path python = prepareReleaseSidecarPython(sidecarDirectory);
        return new ProcessBuilder(python.toString(), "-m", "app.main")
                .directory(sidecarDirectory.toFile());
    }

    private ProcessBuilder unixProcess(LocalService service) {
        if (!isLinux()) {
            return new ProcessBuilder("/bin/bash", root.resolve(service.unixScript()).toString());
        }
        ProcessBuilder builder = new ProcessBuilder(
                "setsid",
                "/bin/bash",
                root.resolve(service.unixScript()).toString()
        );
        builder.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
        return builder;
    }

    private ProcessBuilder windowsProcess(LocalService service) {
        String script = root.resolve(service.windowsScript()).toString();
        if (service == LocalService.FRONTEND) {
            return new ProcessBuilder(
                    "powershell.exe",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    script,
                    "-NoBrowser"
            );
        }
        return new ProcessBuilder(
                "powershell.exe",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script
        );
    }

    private Path prepareReleaseSidecarPython(Path sidecarDirectory) {
        Path venvPython = sidecarVenvPython();
        if (Files.isRegularFile(venvPython)) {
            return venvPython;
        }
        try {
            Files.createDirectories(runtimeDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 Musio runtime 目录：" + runtimeDirectory, e);
        }

        List<String> pythonCommand = findPythonCommand()
                .orElseThrow(() -> new IllegalStateException(
                        "未找到 Python 3.11+。请安装 Python 3.11+，或设置 MUSIO_PYTHON_EXE。"));

        CliTimeline.muted("创建 Python venv: " + sidecarVenvDirectory());
        runSetupCommand(append(pythonCommand, "-m", "venv", sidecarVenvDirectory().toString()), sidecarDirectory);
        CliTimeline.muted("安装 QQMusic sidecar 依赖");
        runSetupCommand(
                List.of(
                        venvPython.toString(),
                        "-m",
                        "pip",
                        "install",
                        "-r",
                        sidecarDirectory.resolve("requirements.txt").toString()
                ),
                sidecarDirectory
        );
        return venvPython;
    }

    private void runSetupCommand(List<String> command, Path directory) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        configureEnvironment(builder.environment());
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath(LocalService.QQMUSIC_SIDECAR).toFile()));
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("准备 QQMusic sidecar 运行环境失败，日志：" + logPath(LocalService.QQMUSIC_SIDECAR));
            }
        } catch (IOException e) {
            throw new IllegalStateException("启动 QQMusic sidecar 环境准备命令失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("QQMusic sidecar 环境准备被中断", e);
        }
    }

    private void createRunDirectory() {
        try {
            Files.createDirectories(runDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create Musio run directory: " + runDirectory, e);
        }
    }

    private void writePid(LocalService service, Process process) {
        try {
            Files.writeString(
                    pidPath(service),
                    Long.toString(process.pid()),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write pid file for " + service.displayName(), e);
        }
    }

    private Path logPath(LocalService service) {
        return runDirectory.resolve(fileStem(service) + ".log");
    }

    private Path pidPath(LocalService service) {
        return runDirectory.resolve(fileStem(service) + ".pid");
    }

    private String fileStem(LocalService service) {
        return service.processName();
    }

    private List<LocalService> servicesToStart() {
        if (releaseMode) {
            return List.of(LocalService.QQMUSIC_SIDECAR, LocalService.BACKEND);
        }
        return List.of(LocalService.QQMUSIC_SIDECAR, LocalService.BACKEND, LocalService.FRONTEND);
    }

    private List<LocalService> servicesToStop() {
        if (releaseMode) {
            return List.of(LocalService.BACKEND, LocalService.QQMUSIC_SIDECAR);
        }
        return List.of(LocalService.FRONTEND, LocalService.BACKEND, LocalService.QQMUSIC_SIDECAR);
    }

    private boolean stopProcessTree(ProcessHandle handle) {
        List<ProcessHandle> descendants = new ArrayList<>(handle.descendants().toList());
        Collections.reverse(descendants);
        for (ProcessHandle descendant : descendants) {
            descendant.destroy();
        }
        handle.destroy();
        if (waitForExit(handle)) {
            return true;
        }
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
        handle.destroyForcibly();
        return waitForExit(handle);
    }

    private boolean waitForExit(ProcessHandle handle) {
        try {
            handle.onExit().get(5, TimeUnit.SECONDS);
            return !handle.isAlive();
        } catch (Exception e) {
            return false;
        }
    }

    private String corsAllowedOrigins() {
        if (!releaseMode) {
            return config.corsAllowedOrigins();
        }
        return config.backendBaseUrl() + "," + config.corsAllowedOrigins();
    }

    private Path releaseBackendJar() {
        return releaseDirectory.resolve("app").resolve("backend-spring.jar");
    }

    private Path releaseSidecarBinary() {
        String executable = isWindows() ? "qqmusic-sidecar.exe" : "qqmusic-sidecar";
        return releaseDirectory.resolve("sidecar").resolve(executable);
    }

    private Path releaseSidecarSourceDirectory() {
        return releaseDirectory.resolve("providers").resolve("qqmusic-python-sidecar");
    }

    private Path sidecarVenvDirectory() {
        return runtimeDirectory.resolve("qqmusic-python-sidecar-venv");
    }

    private Path sidecarVenvPython() {
        Path venv = sidecarVenvDirectory();
        if (isWindows()) {
            return venv.resolve("Scripts").resolve("python.exe");
        }
        return venv.resolve("bin").resolve("python");
    }

    private Optional<List<String>> findPythonCommand() {
        List<List<String>> candidates = new ArrayList<>();
        String configured = System.getenv("MUSIO_PYTHON_EXE");
        if (configured != null && !configured.isBlank()) {
            candidates.add(List.of(configured));
        }
        if (isWindows()) {
            candidates.add(List.of("py", "-3.11"));
            candidates.add(List.of("python"));
            candidates.add(List.of("python3"));
        } else {
            candidates.add(List.of("python3"));
            candidates.add(List.of("python"));
        }
        return candidates.stream()
                .filter(this::isPython311OrNewer)
                .findFirst();
    }

    private boolean isPython311OrNewer(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(append(
                command,
                "-c",
                "import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)"
        ));
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            return builder.start().waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private List<String> append(List<String> command, String... args) {
        List<String> result = new ArrayList<>(command);
        result.addAll(List.of(args));
        return result;
    }

    private String javaExecutable() {
        String executable = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private String releaseJavaExecutable() {
        String executable = isWindows() ? "java.exe" : "java";
        Path bundledJava = releaseDirectory.resolve("runtime").resolve("bin").resolve(executable);
        if (Files.isRegularFile(bundledJava)) {
            return bundledJava.toString();
        }
        return javaExecutable();
    }

    private static Path runDirectory(Path root, MusioCliConfig config, boolean releaseMode) {
        if (releaseMode) {
            return musioHome(config).resolve("run");
        }
        return root.resolve(".musio").resolve("run");
    }

    private static Path musioHome(MusioCliConfig config) {
        Path parent = config.configPath().getParent();
        if (parent != null) {
            return parent;
        }
        return Path.of(System.getProperty("user.home"), ".musio").toAbsolutePath().normalize();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
}
