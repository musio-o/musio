#!/usr/bin/env node
const { existsSync } = require("node:fs");
const { join, resolve } = require("node:path");

const target = process.argv[2] ?? `${process.platform}-${process.arch}`;
const packagingRoot = resolve(__dirname, "..");
const packageRoot = join(packagingRoot, "platforms", target);
const vendorRoot = join(packageRoot, "vendor");
const windowsTarget = target.startsWith("win32-");
const javaExecutable = windowsTarget ? "java.exe" : "java";
const sidecarExecutable = windowsTarget ? "qqmusic-sidecar.exe" : "qqmusic-sidecar";

const required = [
  join(vendorRoot, "runtime", "bin", javaExecutable),
  join(vendorRoot, "lib", "musio-cli.jar"),
  join(vendorRoot, "app", "backend-spring.jar"),
  join(vendorRoot, "sidecar", sidecarExecutable)
];

const missing = required.filter((path) => !existsSync(path));

if (missing.length > 0) {
  console.error(`Musio platform package ${target} is incomplete.`);
  for (const path of missing) {
    console.error("Missing: " + path);
  }
  console.error(`Run \`npm run build:vendor\` from packaging/platforms/${target}.`);
  process.exit(1);
}
