#!/usr/bin/env node
const { spawn } = require("node:child_process");
const { existsSync } = require("node:fs");
const { createRequire } = require("node:module");
const { dirname, join, resolve } = require("node:path");

const requireFromHere = createRequire(__filename);

const PLATFORM_PACKAGE_BY_TARGET = {
  "linux-x64": "@mindforge-x/musio-linux-x64",
  "linux-arm64": "@mindforge-x/musio-linux-arm64",
  "darwin-x64": "@mindforge-x/musio-darwin-x64",
  "darwin-arm64": "@mindforge-x/musio-darwin-arm64",
  "win32-x64": "@mindforge-x/musio-win32-x64",
  "win32-arm64": "@mindforge-x/musio-win32-arm64"
};

const target = detectTarget();
const releaseRoot = resolveReleaseRoot(target);
const releaseDirectory = resolveReleaseDirectory(releaseRoot);
const javaCommand = resolveJavaCommand(releaseDirectory);
const cliJar = join(releaseDirectory, "lib", "musio-cli.jar");

if (!existsSync(cliJar)) {
  fail(
    "Musio CLI jar was not found in the platform package.",
    "Expected: " + cliJar
  );
}

if (!existsSync(javaCommand) && javaCommand !== "java") {
  fail(
    "Bundled Java runtime was not found in the platform package.",
    "Expected: " + javaCommand
  );
}

const child = spawn(javaCommand, ["-jar", cliJar, ...process.argv.slice(2)], {
  stdio: "inherit",
  env: {
    ...process.env,
    MUSIO_HOME: releaseRoot
  }
});

child.on("error", (error) => {
  fail("Failed to start Musio.", error.message);
});

for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"]) {
  process.on(signal, () => {
    if (!child.killed) {
      child.kill(signal);
    }
  });
}

child.on("exit", (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
  } else {
    process.exit(code ?? 1);
  }
});

function detectTarget() {
  const supported = new Set(Object.keys(PLATFORM_PACKAGE_BY_TARGET));
  const value = `${process.platform}-${process.arch}`;
  if (!supported.has(value)) {
    fail(`Unsupported platform: ${process.platform} (${process.arch})`);
  }
  return value;
}

function resolveReleaseRoot(targetName) {
  if (process.env.MUSIO_RELEASE_ROOT) {
    return resolve(process.env.MUSIO_RELEASE_ROOT);
  }

  const platformPackage = PLATFORM_PACKAGE_BY_TARGET[targetName];
  try {
    const packageJsonPath = requireFromHere.resolve(`${platformPackage}/package.json`);
    return dirname(packageJsonPath);
  } catch {
    const localRoot = resolve(__dirname, "..");
    if (hasReleaseDirectory(localRoot, "vendor") || hasReleaseDirectory(localRoot, "dist")) {
      return localRoot;
    }
    fail(
      `Missing optional dependency ${platformPackage}.`,
      "Reinstall Musio: npm install -g @mindforge-x/musio"
    );
  }
}

function resolveReleaseDirectory(root) {
  const vendor = join(root, "vendor");
  if (hasReleaseDirectory(root, "vendor")) {
    return vendor;
  }
  const dist = join(root, "dist");
  if (hasReleaseDirectory(root, "dist")) {
    return dist;
  }
  fail(
    "Musio platform package is incomplete.",
    "Expected a vendor/ or dist/ release directory under: " + root
  );
}

function hasReleaseDirectory(root, child = "vendor") {
  const releaseDirectory = join(root, child);
  return existsSync(join(releaseDirectory, "lib", "musio-cli.jar"))
    && existsSync(join(releaseDirectory, "app", "backend-spring.jar"));
}

function resolveJavaCommand(releaseDirectory) {
  const executable = process.platform === "win32" ? "java.exe" : "java";
  const bundled = join(releaseDirectory, "runtime", "bin", executable);
  if (existsSync(bundled)) {
    return bundled;
  }

  if (process.env.JAVA_HOME) {
    const candidate = join(process.env.JAVA_HOME, "bin", executable);
    if (existsSync(candidate)) {
      return candidate;
    }
  }

  return "java";
}

function fail(...lines) {
  for (const line of lines) {
    if (line) {
      console.error(line);
    }
  }
  process.exit(1);
}
