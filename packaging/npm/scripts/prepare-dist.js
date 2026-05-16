#!/usr/bin/env node
const { spawnSync } = require("node:child_process");
const { cpSync, existsSync, mkdirSync, readdirSync, rmSync } = require("node:fs");
const { join, resolve } = require("node:path");

const packageRoot = resolve(__dirname, "..");
const repoRoot = resolve(packageRoot, "..", "..");
const distRoot = join(packageRoot, "dist");

run("npm", ["run", "build"], join(repoRoot, "frontend"));
run(mavenCommand(), ["-pl", "backend-spring", "-am", "-DskipTests", "package"], repoRoot);
run(mavenCommand(), ["-pl", "cli-java", "-am", "-DskipTests", "package"], repoRoot);

rmSync(distRoot, { recursive: true, force: true });
mkdirSync(join(distRoot, "lib"), { recursive: true });
mkdirSync(join(distRoot, "app"), { recursive: true });
mkdirSync(join(distRoot, "providers"), { recursive: true });

cpSync(
  join(repoRoot, "cli-java", "target", "musio-cli.jar"),
  join(distRoot, "lib", "musio-cli.jar")
);

cpSync(
  findJar(join(repoRoot, "backend-spring", "target")),
  join(distRoot, "app", "backend-spring.jar")
);

cpSync(
  join(repoRoot, "frontend", "dist"),
  join(distRoot, "app", "frontend"),
  { recursive: true }
);

cpSync(
  join(repoRoot, "providers", "qqmusic-python-sidecar"),
  join(distRoot, "providers", "qqmusic-python-sidecar"),
  {
    recursive: true,
    filter: (source) => {
      const normalized = source.replaceAll("\\", "/");
      return !normalized.includes("/.venv")
        && !normalized.includes("/.venv-win")
        && !normalized.includes("/__pycache__");
    }
  }
);

console.log("Musio npm dist prepared at " + distRoot);

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

function findJar(targetDir) {
  const jars = readdirSync(targetDir)
    .filter((name) => name.endsWith(".jar"))
    .filter((name) => !name.endsWith("-sources.jar"))
    .filter((name) => !name.endsWith("-javadoc.jar"))
    .filter((name) => !name.startsWith("original-"))
    .sort();

  const jar = jars.find((name) => name.startsWith("backend-spring-")) ?? jars[0];
  if (!jar) {
    throw new Error("No backend jar found in " + targetDir);
  }
  return join(targetDir, jar);
}
