#!/usr/bin/env node
const { readFileSync, writeFileSync } = require("node:fs");
const { join, resolve } = require("node:path");

const version = process.argv[2];

if (!version) {
  console.error("Usage: node packaging/scripts/set-npm-version.js <version>");
  process.exit(1);
}

const packagingRoot = resolve(__dirname, "..");
const packageNames = [
  "linux-x64",
  "linux-arm64",
  "darwin-x64",
  "darwin-arm64",
  "win32-x64",
  "win32-arm64"
];

const platformPackages = Object.fromEntries(
  packageNames.map((target) => [`@mindforge-x/musio-${target}`, version])
);

updatePackageJson(join(packagingRoot, "npm", "package.json"), (pkg) => {
  pkg.version = version;
  pkg.optionalDependencies = platformPackages;
});

for (const target of packageNames) {
  updatePackageJson(join(packagingRoot, "platforms", target, "package.json"), (pkg) => {
    pkg.version = version;
  });
}

console.log("Updated Musio npm package versions to " + version);

function updatePackageJson(path, updater) {
  const pkg = JSON.parse(readFileSync(path, "utf8"));
  updater(pkg);
  writeFileSync(path, JSON.stringify(pkg, null, 2) + "\n");
}
