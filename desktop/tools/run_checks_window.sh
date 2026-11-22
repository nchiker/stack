#!/bin/sh
# Opened by "Run Stack checks.desktop" in a terminal window: runs every automatic check, then waits for Enter.
cd "$(dirname "$0")/../.." || exit 1
python3 -u desktop/tools/run_all_checks.py
echo
printf 'Press Enter to close this window. '
read -r _x
