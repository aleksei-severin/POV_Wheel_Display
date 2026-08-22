#!/bin/bash
# Сборка APK. Тулчейн лежит в C:/Users/user/.povtools (JDK 17 + Android SDK 34 + Gradle 8.7).
set -e
export JAVA_HOME=/c/Users/user/.povtools/jdk
export ANDROID_HOME=/c/Users/user/.povtools/sdk
cd "$(dirname "$0")"
/c/Users/user/.povtools/gradle/bin/gradle "$@"
