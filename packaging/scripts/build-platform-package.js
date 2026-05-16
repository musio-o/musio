#!/usr/bin/env node
const { spawnSync } = require("node:child_process");
const { chmodSync, cpSync, existsSync, mkdirSync, readdirSync, rmSync } = require("node:fs");
const { join, resolve } = require("node:path");

const TARGETS = {
  "linux-x64": { platform: "linux", arch: "x64" },
  "linux-arm64": { platform: "linux", arch: "arm64" },
  "darwin-x64": { platform: "darwin", arch: "x64" },
  "darwin-arm64": { platform: "darwin", arch: "arm64" },
  "win32-x64": { platform: "win32", arch: "x64" },
  "win32-arm64": { platform: "win32", arch: "arm64" }
};

const target = process.argv[2] ?? currentTarget();
const targetInfo = TARGETS[target];

if (!targetInfo) {
  fail("Unsupported Musio platform target: " + target);
}

if (target !== currentTarget() && process.env.MUSIO_ALLOW_CROSS_PLATFORM_BUILD !== "1") {
  fail(
    `Cannot build ${target} on ${currentTarget()}.`,
    "jlink and PyInstaller builds are platform-native. Run this on the target OS/CPU, or set MUSIO_ALLOW_CROSS_PLATFORM_BUILD=1 only when vendor/ is already prepared externally."
  );
}

const packagingRoot = resolve(__dirname, "..");
const repoRoot = resolve(packagingRoot, "..");
const platformRoot = join(packagingRoot, "platforms", target);
const vendorRoot = join(platformRoot, "vendor");

if (!existsSync(platformRoot)) {
  fail("Platform package directory does not exist: " + platformRoot);
}

run("npm", ["run", "build"], join(repoRoot, "frontend"));
run(mavenCommand(), ["-q", "-pl", "backend-spring", "-am", "-DskipTests", "package"], repoRoot);
run(mavenCommand(), ["-q", "-pl", "cli-java", "-am", "-DskipTests", "package"], repoRoot);

rmSync(vendorRoot, { recursive: true, force: true });
mkdirSync(join(vendorRoot, "lib"), { recursive: true });
mkdirSync(join(vendorRoot, "app"), { recursive: true });

cpSync(
  join(repoRoot, "cli-java", "target", "musio-cli.jar"),
  join(vendorRoot, "lib", "musio-cli.jar")
);
cpSync(
  findBackendJar(join(repoRoot, "backend-spring", "target")),
  join(vendorRoot, "app", "backend-spring.jar")
);
cpSync(
  join(repoRoot, "frontend", "dist"),
  join(vendorRoot, "app", "frontend"),
  { recursive: true }
);

buildRuntime(vendorRoot);
run("node", [join(packagingRoot, "scripts", "build-sidecar.js"), target, join(vendorRoot, "sidecar")], repoRoot);

const sidecarBinary = join(vendorRoot, "sidecar", process.platform === "win32" ? "qqmusic-sidecar.exe" : "qqmusic-sidecar");
if (existsSync(sidecarBinary) && process.platform !== "win32") {
  chmodSync(sidecarBinary, 0o755);
}

console.log("Musio platform vendor prepared at " + vendorRoot);

function buildRuntime(vendorDirectory) {
  const output = join(vendorDirectory, "runtime");
  const jlink = resolveJlink();
  const modules = process.env.MUSIO_JLINK_MODULES ?? "java.se,jdk.crypto.ec,jdk.unsupported,jdk.zipfs";
  run(jlink, [
    "--add-modules", modules,
    "--strip-debug",
    "--no-header-files",
    "--no-man-pages",
    "--compress", "zip-6",
    "--output", output
  ], repoRoot);
}

function resolveJlink() {
  const executable = process.platform === "win32" ? "jlink.exe" : "jlink";
  if (process.env.JAVA_HOME) {
    const candidate = join(process.env.JAVA_HOME, "bin", executable);
    if (existsSync(candidate)) {
      return candidate;
    }
  }
  return executable;
}

function findBackendJar(targetDir) {
  const jars = readdirSync(targetDir)
    .filter((name) => name.endsWith(".jar"))
    .filter((name) => !name.endsWith("-sources.jar"))
    .filter((name) => !name.endsWith("-javadoc.jar"))
    .filter((name) => !name.endsWith(".jar.original"))
    .filter((name) => !name.startsWith("original-"))
    .sort();

  const jar = jars.find((name) => name.startsWith("backend-spring-")) ?? jars[0];
  if (!jar) {
    fail("No backend jar found in " + targetDir);
  }
  return join(targetDir, jar);
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

function mavenCommand() {
  return process.platform === "win32" ? "mvn.cmd" : "mvn";
}

function currentTarget() {
  return `${process.platform}-${process.arch}`;
}

function fail(...lines) {
  for (const line of lines) {
    if (line) {
      console.error(line);
    }
  }
  process.exit(1);
}
