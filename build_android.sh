#!/usr/bin/env bash
set -e

# mMirror Android NDK & APK Build Script
echo "=================================================="
echo "  mMirror for Tesla - Android Build Script"
echo "=================================================="

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_RUST_DIR="$PROJECT_DIR/core-rust"
ANDROID_DIR="$PROJECT_DIR/android"
JNI_LIBS_DIR="$ANDROID_DIR/app/src/main/jniLibs/arm64-v8a"

mkdir -p "$JNI_LIBS_DIR"

echo "1. Checking Cargo NDK..."
if command -v cargo-ndk &> /dev/null; then
    echo "Using cargo-ndk to compile Rust core for Android (arm64-v8a)..."
    cd "$CORE_RUST_DIR"
    cargo ndk -t arm64-v8a build --release
    cp "target/aarch64-linux-android/release/libmmirror_core.so" "$JNI_LIBS_DIR/"
    echo "✓ Copied libmmirror_core.so to $JNI_LIBS_DIR"
else
    echo "Note: 'cargo-ndk' is not installed yet."
    echo "To install cargo-ndk and build for Android:"
    echo "  cargo install cargo-ndk"
    echo "  rustup target add aarch64-linux-android"
    echo "  export ANDROID_NDK_HOME=/path/to/android-ndk"
    echo ""
    echo "Building host library for testing..."
    cd "$CORE_RUST_DIR"
    cargo build --release
fi

echo ""
echo "2. Building Android APK..."
if [ -f "$ANDROID_DIR/gradlew" ]; then
    cd "$ANDROID_DIR"
    ./gradlew assembleDebug
    echo "✓ APK built successfully: android/app/build/outputs/apk/debug/app-debug.apk"
else
    echo "To generate Gradle wrapper:"
    echo "  cd android && gradle wrapper"
fi

echo "=================================================="
echo "  Build process completed!"
echo "=================================================="
