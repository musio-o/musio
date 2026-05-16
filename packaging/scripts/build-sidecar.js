#!/usr/bin/env node
const { chmodSync, existsSync, mkdirSync, writeFileSync } = require("node:fs");
const { join, resolve } = require("node:path");
const { spawnSync } = require("node:child_process");

const target = process.argv[2] ?? `${process.platform}-${process.arch}`;
const outputDirectory = process.argv[3] ? resolve(process.argv[3]) : null;

if (!outputDirectory) {
  fail("Usage: node packaging/scripts/build-sidecar.js <target> <output-directory>");
}

if (target !== `${process.platform}-${process.arch}` && process.env.MUSIO_ALLOW_CROSS_PLATFORM_BUILD !== "1") {
  fail(`Cannot build sidecar target ${target} on ${process.platform}-${process.arch}.`);
}

const packagingRoot = resolve(__dirname, "..");
const repoRoot = resolve(packagingRoot, "..");
const sidecarRoot = join(repoRoot, "providers", "qqmusic-python-sidecar");
const buildRoot = join(packagingRoot, ".build", `sidecar-${target}`);
const venvRoot = join(buildRoot, "venv");
const entrypoint = join(buildRoot, "qqmusic_sidecar_entry.py");
const python = venvPython();
const executableName = target.startsWith("win32-") ? "qqmusic-sidecar.exe" : "qqmusic-sidecar";

mkdirSync(buildRoot, { recursive: true });
mkdirSync(outputDirectory, { recursive: true });

if (!existsSync(python)) {
  const pythonCommand = findPythonCommand();
  run(pythonCommand[0], [...pythonCommand.slice(1), "-m", "venv", venvRoot], sidecarRoot);
}

run(python, ["-m", "pip", "install", "-r", join(sidecarRoot, "requirements.txt"), "pyinstaller>=6.0"], sidecarRoot);
writeFileSync(entrypoint, sidecarEntrypoint(), "utf8");

run(python, [
  "-m", "PyInstaller",
  "--clean",
  "--noconfirm",
  "--onefile",
  "--name", executableName.replace(/\.exe$/, ""),
  "--paths", sidecarRoot,
  "--distpath", outputDirectory,
  "--workpath", join(buildRoot, "pyinstaller-work"),
  "--specpath", buildRoot,
  "--hidden-import", "uvicorn.logging",
  "--hidden-import", "uvicorn.loops",
  "--hidden-import", "uvicorn.loops.auto",
  "--hidden-import", "uvicorn.protocols.http.auto",
  "--hidden-import", "uvicorn.protocols.websockets.auto",
  "--hidden-import", "uvicorn.lifespan.on",
  entrypoint
], sidecarRoot);

const binary = join(outputDirectory, executableName);
if (!existsSync(binary)) {
  fail("PyInstaller did not create sidecar binary: " + binary);
}
if (!target.startsWith("win32-")) {
  chmodSync(binary, 0o755);
}

console.log("Musio QQMusic sidecar binary prepared at " + binary);

function sidecarEntrypoint() {
  return [
    "import os",
    "import uvicorn",
    "from app.main import app",
    "",
    "if __name__ == \"__main__\":",
    "    host = os.environ.get(\"MUSIO_QQMUSIC_HOST\", \"127.0.0.1\")",
    "    port = int(os.environ.get(\"MUSIO_QQMUSIC_PORT\", \"18767\"))",
    "    uvicorn.run(app, host=host, port=port, reload=False)",
    ""
  ].join("\n");
}

function venvPython() {
  if (process.platform === "win32") {
    return join(venvRoot, "Scripts", "python.exe");
  }
  return join(venvRoot, "bin", "python");
}

function findPythonCommand() {
  const candidates = pythonCandidates();
  const attempts = [];

  if (candidates.length === 0) {
    fail("Python 3.11+ is required to build the QQMusic sidecar binary.");
  }

  const versionCheck = [
    "-c",
    "import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)"
  ];

  for (const candidate of candidates) {
    const result = spawnSync(candidate[0], [...candidate.slice(1), ...versionCheck], {
      stdio: "ignore"
    });
    if (result.status === 0) {
      return candidate;
    }
    attempts.push(describePythonAttempt(candidate, result));
  }

  fail(
    "Python 3.11+ is required to build the QQMusic sidecar binary.",
    attempts.length ? "Tried: " + attempts.join("; ") : null
  );
}

function pythonCandidates() {
  const candidates = [];
  const seen = new Set();
  const add = (candidate) => {
    if (!candidate || !candidate[0]) {
      return;
    }
    const key = candidate.join("\0");
    if (!seen.has(key)) {
      seen.add(key);
      candidates.push(candidate);
    }
  };

  add([process.env.MUSIO_PYTHON_EXE]);
  add(setupPythonExecutable());

  if (process.platform === "win32") {
    add(["py", "-3.12"]);
    add(["py", "-3.11"]);
    add(["py", "-3"]);
    add(["python"]);
    add(["python3"]);
  } else {
    add(["python3"]);
    add(["python"]);
  }

  return candidates;
}

function setupPythonExecutable() {
  const pythonRoot = process.env.pythonLocation
    || process.env.Python3_ROOT_DIR
    || process.env.Python_ROOT_DIR;
  if (!pythonRoot) {
    return null;
  }
  const executable = process.platform === "win32"
    ? join(pythonRoot, "python.exe")
    : join(pythonRoot, "bin", "python");
  return existsSync(executable) ? [executable] : null;
}

function describePythonAttempt(candidate, result) {
  const command = candidate.join(" ");
  if (result.error) {
    return `${command} (${result.error.code ?? result.error.message})`;
  }
  return `${command} (exit ${result.status ?? "unknown"})`;
}

function run(command, args, cwd) {
  const result = spawnSync(command, args, {
    cwd,
    stdio: "inherit"
  });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

function fail(...lines) {
  for (const line of lines) {
    if (line) {
      console.error(line);
    }
  }
  process.exit(1);
}
