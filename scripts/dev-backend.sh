#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! command -v java >/dev/null 2>&1; then
  echo "java was not found in PATH. Install JDK 21+ in this environment or use scripts/dev.ps1 from Windows PowerShell." >&2
  exit 1
fi

if ! command -v mvn >/dev/null 2>&1; then
  echo "mvn was not found in PATH. Install Maven in this environment or use scripts/dev.ps1 from Windows PowerShell." >&2
  exit 1
fi

cd "$ROOT_DIR/backend-spring"
exec mvn spring-boot:run
