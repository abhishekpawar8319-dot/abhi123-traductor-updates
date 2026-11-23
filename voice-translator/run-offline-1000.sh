#!/usr/bin/env bash
# Runs the offline 1,000-sentence check and prints the report.
# Fix what it finds, then run it again until every check is 1000 / 1000.
set -u
cd "$(dirname "$0")"
python3 tools/make_offline_sentences.py
./gradlew -q testDebugUnitTest --tests 'com.abhi.vozconversa.Offline1000Test' --rerun-tasks >/dev/null 2>&1
status=$?
cat app/build/reports/offline-1000/report.txt
exit $status
