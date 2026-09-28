#!/usr/bin/env bash
set -e

echo "=================================================="
echo "  Starting mMirror Tesla Dev Simulation Server"
echo "=================================================="

cd "$(dirname "$0")/core-rust"
cargo run --bin mmirror-core
