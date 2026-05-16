# Musio npm packaging

Musio uses a Codex-style npm layout:

- `@mindforge-x/musio` is the small global launcher package.
- `@mindforge-x/musio-<platform>-<arch>` packages contain the native runtime payload under `vendor/`.

The launcher keeps the command name short:

```bash
npm install -g @mindforge-x/musio
musio
```

## Build a platform package

Platform payloads are built natively because both `jlink` and PyInstaller are platform-specific.

```bash
cd packaging/platforms/linux-x64
npm run build:vendor
npm pack --dry-run
```

The generated `vendor/` directory contains:

```text
vendor/
  runtime/                 # jlink Java runtime
  lib/musio-cli.jar
  app/backend-spring.jar
  app/frontend/
  sidecar/qqmusic-sidecar
```

## Publish order

Publish all platform packages first:

```bash
cd packaging/platforms/linux-x64 && npm publish
cd packaging/platforms/linux-arm64 && npm publish
cd packaging/platforms/darwin-x64 && npm publish
cd packaging/platforms/darwin-arm64 && npm publish
cd packaging/platforms/win32-x64 && npm publish
cd packaging/platforms/win32-arm64 && npm publish
```

Then publish the launcher:

```bash
cd packaging/npm
npm publish
```

The launcher declares platform packages as optional dependencies. npm installs the package matching the user's OS and CPU.

## GitHub Actions release

Use the `npm release` workflow from GitHub Actions for cross-platform beta releases.

Recommended first run:

```text
version: 0.1.0-beta.0
tag: beta
dry_run: true
```

If all six platform jobs pass, rerun with:

```text
version: 0.1.0-beta.0
tag: beta
dry_run: false
```

Publishing supports either npm trusted publishing or an `NPM_TOKEN` repository secret. Trusted publishing requires configuring each npm package to trust this GitHub repository and workflow.
