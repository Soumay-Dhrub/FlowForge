#!/usr/bin/env bash
# Verification helper for concurrent work in this tree.
#
# Another agent is editing com.flowforge.report right now, and its half-finished state breaks
# testCompile. This copies the working tree to a scratch directory with the report package pinned to
# HEAD (a known-compiling state), so this task's code can be verified without touching their files.
#
# Usage: ./verify-isolated.sh <maven args...>
set -euo pipefail

REPO="/Users/soumaydhrub/Desktop/FlowForge"
SCRATCH="/tmp/ff-verify"

rm -rf "$SCRATCH"
mkdir -p "$SCRATCH"
rsync -a --exclude target --exclude verify-isolated.sh "$REPO/backend/" "$SCRATCH/"

rm -rf "$SCRATCH/src/main/java/com/flowforge/report" "$SCRATCH/src/test/java/com/flowforge/report"
mkdir -p /tmp/ff-head && rm -rf /tmp/ff-head/*
cd "$REPO"
git archive HEAD backend/src/main/java/com/flowforge/report backend/src/test/java/com/flowforge/report \
  | tar -x -C /tmp/ff-head
cp -R /tmp/ff-head/backend/src/main/java/com/flowforge/report "$SCRATCH/src/main/java/com/flowforge/"
cp -R /tmp/ff-head/backend/src/test/java/com/flowforge/report "$SCRATCH/src/test/java/com/flowforge/"

cd "$SCRATCH"
exec mvn "$@"
