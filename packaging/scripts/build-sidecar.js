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
  const candidates = [];
  if (process.env.MUSIO_PYTHON_EXE) {
    candidates.push([process.env.MUSIO_PYTHON_EXE]);
  }
  if (process.platform === "win32") {
    candidates.push(["py", "-3.11"], ["python"], ["python3"]);
  } else {
    candidates.push(["python3"], ["python"]);
  }

  for (const candidate of candidates) {
    const result = spawnSync(candidate[0], [
      ...candidate.slice(1),
      "-c",
      "import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)"
    ], {
      stdio: "ignore",
      shell: process.platform === "win32"
    });
    if (result.status === 0) {
      return candidate;
    }
  }

  fail("Python 3.11+ is required to build the QQMusic sidecar binary.");
}

function run(command, args, cwd) {
  const result = spawnSync(command, args, {
    cwd,
    stdio: "inherit",
    shell: process.platform === "win32"
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
