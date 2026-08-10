#!/usr/bin/env bash
set -euo pipefail

example_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
installed_launcher="$example_dir/build/install/runtime-mcp-example/bin/runtime-mcp-example"

if [[ ! -x "$installed_launcher" ]]; then
    echo "prepare the MCP example with ./gradlew :runtime-examples:installDist" >&2
    exit 2
fi
if ! command -v xvfb-run >/dev/null 2>&1; then
    echo "the Linux MCP example launcher requires xvfb-run" >&2
    exit 2
fi

# Debian/Ubuntu xvfb-run merges child stderr into stdout. Preserve the caller's stderr on fd 3,
# then restore the installed Java launcher's stderr inside Xvfb so stdout remains JSON-RPC only.
exec xvfb-run -a sh -c 'exec "$1" 2>&3' sh "$installed_launcher" 3>&2
