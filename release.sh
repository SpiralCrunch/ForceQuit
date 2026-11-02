#!/usr/bin/env bash
# Build a signed release APK and publish it as a GitHub Release (picked up by Obtainium).
# Bump versionCode + versionName in app/build.gradle.kts before running.
set -euo pipefail
cd "$(dirname "$0")"

version=$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts)
tag="v$version"
apk="dist/ForceQuit-$tag.apk"

if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
    echo "Tag $tag already exists — bump versionCode/versionName in app/build.gradle.kts first." >&2
    exit 1
fi
if [[ -n $(git status --porcelain) ]]; then
    echo "Working tree has uncommitted changes — commit them first so the release matches the code." >&2
    exit 1
fi

./gradlew --quiet clean assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "$apk"
echo "Built $apk"

git tag -a "$tag" -m "ForceQuit $tag"
git push origin HEAD "$tag"

if command -v gh >/dev/null; then
    gh release create "$tag" "$apk" --title "ForceQuit $tag" --generate-notes
else
    echo "gh CLI not installed — create the release for $tag on GitHub and attach $apk manually."
fi
