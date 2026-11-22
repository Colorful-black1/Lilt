#!/usr/bin/env bash
# Thin wrapper so Git Bash / WSL users can run ./package.sh <pc|apk|all>.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if command -v cygpath >/dev/null 2>&1; then
    script="$(cygpath -w "$here/package.ps1")"
elif command -v wslpath >/dev/null 2>&1; then
    script="$(wslpath -w "$here/package.ps1")"
else
    script="$here/package.ps1"
fi

exec powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$script" "$@"
