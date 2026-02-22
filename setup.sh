#!/bin/bash
set -euo pipefail

# ============================================================
# Accessibility Guard - 初回セットアップスクリプト
#
# Gradle Wrapper を生成し、ビルド可能な状態にします。
# Android Studio で開く前に一度実行してください。
#
# 使い方:
#   chmod +x setup.sh && ./setup.sh
# ============================================================

GRADLE_VERSION="8.5"
WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
WRAPPER_JAR_URL="https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}/gradle/wrapper/gradle-wrapper.jar"

echo "=== Accessibility Guard Setup ==="
echo ""

# --- Java チェック ---
if ! command -v java &> /dev/null; then
    echo "❌ Java が見つかりません。JDK 17 をインストールしてください。"
    exit 1
fi

JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
echo "✅ Java $JAVA_VER detected"

# --- Gradle Wrapper 生成 ---
if [ -f "$WRAPPER_JAR" ]; then
    echo "✅ Gradle Wrapper は既に存在します"
else
    echo "📦 Gradle Wrapper を生成中..."

    # gradle がインストールされていれば使う
    if command -v gradle &> /dev/null; then
        gradle wrapper --gradle-version "$GRADLE_VERSION"
    else
        # gradle が無い場合は jar を直接ダウンロード
        echo "   gradle コマンドが見つからないため、jar を直接ダウンロードします..."
        mkdir -p gradle/wrapper

        if command -v curl &> /dev/null; then
            curl -fsSL -o "$WRAPPER_JAR" "$WRAPPER_JAR_URL"
        elif command -v wget &> /dev/null; then
            wget -q -O "$WRAPPER_JAR" "$WRAPPER_JAR_URL"
        else
            echo "❌ curl または wget が必要です"
            exit 1
        fi

        # gradlew が無ければ生成
        if [ ! -f "gradlew" ]; then
            # wrapper jar があれば gradlew を使って再生成
            java -cp "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain wrapper --gradle-version "$GRADLE_VERSION" 2>/dev/null || true
        fi
    fi

    chmod +x gradlew 2>/dev/null || true
    echo "✅ Gradle Wrapper を生成しました"
fi

# --- 動作確認 ---
echo ""
echo "🔨 ビルドテスト中..."
./gradlew --version

echo ""
echo "=== セットアップ完了 ==="
echo ""
echo "次のステップ:"
echo "  1. Android Studio でこのフォルダを開く"
echo "  2. ビルド: ./gradlew assembleDebug"
echo "  3. テスト: ./gradlew testDebugUnitTest"
echo "  4. APK:   app/build/outputs/apk/debug/app-debug.apk"
