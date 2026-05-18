#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.musio/run"
if [ "$#" -gt 0 ]; then
  PORTS=("$@")
else
  PORTS=(18765 18766 18767)
fi
PROCESS_PATTERN='app\.main|spring-boot:run|vite|npm run dev|mvn'

stop_tree() {
  local pid="$1"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 0
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "pid=$pid is not running"
    return 0
  fi

  local child
  while read -r child; do
    [[ -n "$child" ]] && stop_tree "$child"
  done < <(pgrep -P "$pid" 2>/dev/null || true)

  kill "$pid" 2>/dev/null || true
  sleep 0.2
  if kill -0 "$pid" 2>/dev/null; then
    kill -9 "$pid" 2>/dev/null || true
  fi
  echo "Stopped pid=$pid"
}

stop_pid_files() {
  [[ -d "$RUN_DIR" ]] || return 0
  local file raw
  for file in "$RUN_DIR"/*.pid; do
    [[ -e "$file" ]] || continue
    raw="$(head -n 1 "$file" 2>/dev/null || true)"
    [[ "$raw" =~ ^[0-9]+$ ]] && stop_tree "$raw"
    rm -f "$file"
  done
}

socket_inodes_for_port() {
  local port="$1"
  local port_hex
  port_hex="$(printf '%04X' "$port")"
  awk -v port="$port_hex" '
    NR > 1 && $4 == "0A" {
      split($2, local_addr, ":")
      if (local_addr[length(local_addr)] == port) {
        print $10
      }
    }
  ' /proc/net/tcp /proc/net/tcp6 2>/dev/null || true
}

pids_for_socket_inode() {
  local inode="$1"
  local fd link pid
  for fd in /proc/[0-9]*/fd/*; do
    link="$(readlink "$fd" 2>/dev/null || true)"
    if [[ "$link" == "socket:[$inode]" ]]; then
      pid="${fd#/proc/}"
      pid="${pid%%/*}"
      echo "$pid"
    fi
  done
}

stop_ports() {
  local port inode pid
  declare -A stopped=()
  for port in "${PORTS[@]}"; do
    while read -r inode; do
      [[ -n "$inode" ]] || continue
      while read -r pid; do
        [[ -n "$pid" ]] || continue
        [[ -n "${stopped[$pid]:-}" ]] && continue
        stopped[$pid]=1
        stop_tree "$pid"
      done < <(pids_for_socket_inode "$inode")
    done < <(socket_inodes_for_port "$port")
  done
}

stop_known_project_processes() {
  local cmdline pid
  declare -A stopped=()
  for cmdline in /proc/[0-9]*/cmdline; do
    [[ -r "$cmdline" ]] || continue
    pid="${cmdline#/proc/}"
    pid="${pid%%/*}"
    [[ "$pid" == "$$" ]] && continue
    cmdline="$(tr '\0' ' ' < "$cmdline" 2>/dev/null || true)"
    if [[ "$cmdline" == *"$ROOT_DIR"* && "$cmdline" =~ $PROCESS_PATTERN ]]; then
      [[ -n "${stopped[$pid]:-}" ]] && continue
      stopped[$pid]=1
      stop_tree "$pid"
    fi
  done
}

stop_pid_files
stop_ports
stop_known_project_processes

echo "Musio local services stopped."
