#!/usr/bin/env node
const { existsSync } = require("node:fs");
const { join, resolve } = require("node:path");

const packageRoot = resolve(__dirname, "..");
const bin = join(packageRoot, "bin", "musio.js");

if (!existsSync(bin)) {
  console.error("Missing Musio bin entry: " + bin);
  process.exit(1);
}
