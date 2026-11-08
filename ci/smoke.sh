#!/usr/bin/env bash
# Run a built jolt-metrics binary over a source tree and check every output
# format, so an embedded resource or a library the archive is missing fails
# here and not on a user's machine.  Usage: ci/smoke.sh ./jolt-metrics <dir>
# <dir> should be in a git checkout with history, for churn and unusual changes.
set -u
bin="$1"
dir="$2"
out=$(mktemp -d)
fail() { echo "smoke: $*"; exit 1; }

"$bin" --help | grep -q "Usage: jolt-metrics" || fail "--help printed no usage"

"$bin" "$dir" > "$out/report.md" || fail "the Markdown report failed"
head -1 "$out/report.md" | grep -q "^# Code Metrics Report" || fail "no Markdown report"
grep -q "^## Hotspots" "$out/report.md" || fail "no hotspots: git history wasn't read"
grep -q "^## Unusual changes" "$out/report.md" || fail "no unusual changes: git history wasn't read"
echo "smoke: Markdown report, with git history"

"$bin" "$dir" "$out/report.json" > /dev/null || fail "the JSON report failed"
grep -q '"schema_version":1' "$out/report.json" || fail "no JSON report"
echo "smoke: JSON report"

# the page inlines resources/metrics/html, which the binary has to carry
"$bin" "$dir" "$out/report.html" > /dev/null || fail "the HTML report failed"
grep -q "makeSortable" "$out/report.html" || fail "the HTML report has no script"
grep -q ":root" "$out/report.html" || fail "the HTML report has no stylesheet"
echo "smoke: HTML report, with its embedded script and stylesheet"

"$bin" "$dir" --format edn > "$out/report.edn" || fail "the EDN report failed"
head -c 2 "$out/report.edn" | grep -q "{:" || fail "no EDN report"
echo "smoke: EDN report"

"$bin" "$out/no-such-dir" > /dev/null 2>&1
[ $? -eq 2 ] || fail "a missing directory didn't exit 2"
echo "smoke: bad usage exits 2"
