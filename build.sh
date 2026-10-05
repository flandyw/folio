#!/usr/bin/env bash
# Builds a signed release APK using the keystore in .signing/.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_KEYSTORE_PATH="$PWD/.signing/folio-release.p12"
ANDROID_KEYSTORE_PASSWORD="$(tr -d '\r\n' < .signing/password)"
export ANDROID_KEYSTORE_PASSWORD

# Serialize the whole build/publish flow, because Gradle shares one release APK path.
mkdir -p .tooling
exec 9>.tooling/experimental-build.lock
if ! flock -n 9; then
    echo "Waiting for another experimental build to finish…"
    flock 9
fi
allocation=$(python3 tools/next-experimental-build.py 9>&-)
read -r allocated_commit experimental_build <<< "$allocation"
echo "Building experimental revision $experimental_build for commit count $allocated_commit"
./gradlew :app:assembleRelease "$@" \
    "-PfolioExperimentalBuild=$experimental_build" \
    "-PfolioExperimentalCommitCount=$allocated_commit" 9>&-
ls -1 app/build/outputs/apk/release/*.apk

if read -r -p "Publish this experimental build to the Folio server? [y/N] " publish_answer; then
    case "$publish_answer" in
        [Yy]|[Yy][Ee][Ss])
            FOLIO_EXPECTED_VERSION_CODE=$((allocated_commit * 10000 + experimental_build)) \
                bash ./release-server/publish.sh 9>&-
            ;;
        *) echo "Build complete; not published." ;;
    esac
else
    # A build without interactive input must not publish automatically.
    echo "Build complete; not published."
fi
