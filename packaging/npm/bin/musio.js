#!/usr/bin/env node
const { spawnSync } = require("node:child_process");
const { existsSync } = require("node:fs");
const { join, resolve } = require("node:path");

const root = resolve(__dirname, "..");
const cliJar = join(root, "dist", "lib", "musio-cli.jar");

if (!existsSync(cliJar)) {
  console.error("Musio CLI jar was not found in this npm package.");
  console.error("Expected: " + cliJar);
  process.exit(1);
}

const javaCommand = resolveJavaCommand();
const javaCheck = spawnSync(javaCommand, ["-version"], {
  encoding: "utf8"
});

if (javaCheck.error || javaCheck.status !== 0) {
  console.error("Musio requires Java 21+ to be available on PATH or JAVA_HOME.");
  if (javaCheck.error) {
    console.error(javaCheck.error.message);
  }
  process.exit(1);
}

const result = spawnSync(javaCommand, ["-jar", cliJar, ...process.argv.slice(2)], {
  stdio: "inherit",
  env: {
    ...process.env,
    MUSIO_HOME: root
  }
});

process.exit(result.status ?? 1);

function resolveJavaCommand() {
  if (process.env.JAVA_HOME) {
    const executable = process.platform === "win32" ? "java.exe" : "java";
    const candidate = join(process.env.JAVA_HOME, "bin", executable);
    if (existsSync(candidate)) {
      return candidate;
    }
  }
  return "java";
}
