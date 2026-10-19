#!/usr/bin/env bash
set -euo pipefail
desktop_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
demo_root=$(mktemp -d /tmp/stack-desktop-demo.XXXXXXXX)
chmod 700 "$demo_root"
export XDG_DATA_HOME="$demo_root/data"
export XDG_CONFIG_HOME="$demo_root/config"
export XDG_CACHE_HOME="$demo_root/cache"
export XDG_STATE_HOME="$demo_root/state"
mkdir -p "$XDG_DATA_HOME" "$XDG_CONFIG_HOME" "$XDG_CACHE_HOME" "$XDG_STATE_HOME"
export STACK_PDFIUM_LIBRARY="${STACK_PDFIUM_LIBRARY:-$desktop_root/runtime/pdfium/lib/libpdfium.so}"
if [[ ! -f "$STACK_PDFIUM_LIBRARY" ]]; then
    printf '%s\n' 'Run python3 desktop/tools/setup_pdfium.py once before the demo.' >&2
    exit 1
fi
cd "$desktop_root"
cargo run --locked --offline -p stack-cli -- fixtures "$demo_root/fixtures"
printf 'Disposable demo data: %s\n' "$demo_root"
cargo run --locked --offline -p stack-desktop -- --demo "$demo_root/fixtures" "$@"
