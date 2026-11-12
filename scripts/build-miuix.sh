#!/usr/bin/env bash
set -euo pipefail
commit=ea1b935ddba7bb25de816342b2e7a4ad2113bb46
version=0.9.4-ea1b935-SNAPSHOT
if [[ -f "$HOME/.m2/repository/top/yukonga/miuix/kmp/miuix-glass/$version/miuix-glass-$version.pom" ]]; then exit 0; fi
work="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/baiye-miuix"
if [[ ! -d "$work/.git" ]]; then git clone https://github.com/compose-miuix-ui/miuix.git "$work"; fi
cd "$work"
git checkout "$commit"
git config core.abbrev 7
test "$(git rev-parse HEAD)" = "$commit"
chmod +x gradlew
# The official preview computes its Maven version from this commit.
printf '\nnetworkTimeout=120000\nretries=3\n' >> gradle/wrapper/gradle-wrapper.properties
tasks=()
for module in miuix-core miuix-shader miuix-squircle miuix-ui miuix-blur miuix-glass; do
  tasks+=(":$module:publishKotlinMultiplatformPublicationToMavenLocal" ":$module:publishAndroidPublicationToMavenLocal")
done
./gradlew "${tasks[@]}" --no-daemon --no-parallel --no-configuration-cache --stacktrace
