#!/usr/bin/env node
const { existsSync } = require("node:fs");
const { join, resolve } = require("node:path");

const packageRoot = resolve(__dirname, "..");
const required = [
  join(packageRoot, "dist", "lib", "musio-cli.jar"),
  join(packageRoot, "dist", "app", "backend-spring.jar"),
  join(packageRoot, "dist", "providers", "qqmusic-python-sidecar", "app", "main.py")
];

const missing = required.filter((path) => !existsSync(path));

if (missing.length > 0) {
  console.error("Musio npm package dist is incomplete.");
  for (const path of missing) {
    console.error("Missing: " + path);
  }
  console.error("Run `npm run build:dist` from packaging/npm before packing or publishing.");
  process.exit(1);
}
