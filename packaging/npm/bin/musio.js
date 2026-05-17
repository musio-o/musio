#!/usr/bin/env node
const { spawn, spawnSync } = require("node:child_process");
const { existsSync } = require("node:fs");
const { createRequire } = require("node:module");
const { dirname, join, resolve } = require("node:path");

const requireFromHere = createRequire(__filename);
const packageRoot = resolve(__dirname, "..");

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
const musioVersion = process.env.MUSIO_VERSION || packageVersion();

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

const child = spawn(javaCommand, ["-Dmusio.version=" + musioVersion, "-jar", cliJar, ...process.argv.slice(2)], {
  stdio: "inherit",
  env: {
    ...process.env,
    MUSIO_VERSION: musioVersion,
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
  const packageJsonPath = resolvePlatformPackageManifest(platformPackage);
  if (packageJsonPath) {
    return dirname(packageJsonPath);
  }

  const localRoot = packageRoot;
  if (hasReleaseDirectory(localRoot, "vendor") || hasReleaseDirectory(localRoot, "dist")) {
    return localRoot;
  }

  const installedPackageJsonPath = installPlatformPackage(platformPackage);
  if (installedPackageJsonPath) {
    return dirname(installedPackageJsonPath);
  }

  fail(
    `Missing optional dependency ${platformPackage}.`,
    "Reinstall Musio: npm install -g @mindforge-x/musio",
    "For local project installs, run: npm install @mindforge-x/musio"
  );
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

function resolvePlatformPackageManifest(packageName) {
  try {
    return requireFromHere.resolve(`${packageName}/package.json`);
  } catch {
    return null;
  }
}

function installPlatformPackage(packageName) {
  if (process.env.MUSIO_SKIP_PLATFORM_INSTALL === "1") {
    return null;
  }

  const spec = `${packageName}@${platformPackageVersion(packageName)}`;
  console.error(`Installing Musio platform runtime: ${spec}`);
  const result = spawnSync(npmCommand(), [
    "install",
    "--no-save",
    "--package-lock=false",
    "--ignore-scripts",
    "--include=optional",
    "--no-audit",
    "--fund=false",
    spec
  ], {
    cwd: packageRoot,
    stdio: "inherit",
    shell: process.platform === "win32"
  });

  if (result.status !== 0) {
    return null;
  }
  return resolvePlatformPackageManifest(packageName);
}

function platformPackageVersion(packageName) {
  const pkg = require(join(packageRoot, "package.json"));
  return pkg.optionalDependencies?.[packageName] ?? pkg.version;
}

function packageVersion() {
  const pkg = require(join(packageRoot, "package.json"));
  return pkg.version;
}

function npmCommand() {
  return process.platform === "win32" ? "npm.cmd" : "npm";
}

function fail(...lines) {
  for (const line of lines) {
    if (line) {
      console.error(line);
    }
  }
  process.exit(1);
}
