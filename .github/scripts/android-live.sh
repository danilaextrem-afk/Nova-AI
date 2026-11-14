#!/usr/bin/env bash
set -euo pipefail

cd "$GITHUB_WORKSPACE/android"
url="$(tr -d '[:space:]' < nova-api-url.txt)"
test -n "$url"
expected="${EXPECTED_COMMIT:-$GITHUB_SHA}"
[[ "$expected" =~ ^[a-fA-F0-9]{7,40}$ ]]
expected="${expected,,}"

collect_screenshots() {
  adb pull /sdcard/Android/data/ai.nova.app/files/screenshots "$GITHUB_WORKSPACE/android-live-screenshots" || true
}
trap collect_screenshots EXIT

gradle :app:connectedDebugAndroidTest --no-daemon --stacktrace \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=ai.nova.app.NovaLiveAuthTest \
  "-Pandroid.testInstrumentationRunnerArguments.novaLiveUrl=$url" \
  "-Pandroid.testInstrumentationRunnerArguments.novaLiveCommit=$expected"

python3 - <<'PY'
from pathlib import Path
from xml.etree import ElementTree

required = {
    "registerSignOutSignInAndDeleteOnLiveServer",
    "liveCreatorConversationMemoryImageAndWallet",
}
passed = set()
for report in Path("app/build/outputs/androidTest-results").rglob("TEST-*.xml"):
    for test in ElementTree.parse(report).iter("testcase"):
        if test.get("classname") != "ai.nova.app.NovaLiveAuthTest":
            continue
        if any(test.find(status) is not None for status in ("failure", "error", "skipped")):
            raise SystemExit(f"Android live case did not pass: {test.get('name')}")
        passed.add(test.get("name"))
if passed != required:
    raise SystemExit(f"Missing Android live results: {sorted(required - passed)}")
print(f"Verified {len(passed)} live Android cases executed and passed.")
PY
