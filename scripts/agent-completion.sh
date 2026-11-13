#!/usr/bin/env bash
# Full quality gate, fail-fast, in order. This is THE completion gate: work is
# not done until this passes. Runs the same in a terminal and in CI.
#
#   1. spotless:apply        format sources
#   2. checkstyle:check      static analysis (any warning fails)
#   3. clean install         compile with Error Prone + NullAway, package
#   4. verify                unit tests -> integration tests (Docker) -> spotless:check
#                            -> checkstyle -> JaCoCo merged line coverage >= 80%
#   5. suppressions guard    checkstyle-suppressions.xml must be unchanged
#
# Integration tests use Testcontainers, so Docker must be running.
set -euo pipefail

cd "$(dirname "$0")/.."

step() {
    printf '\n=== Step %s ===\n' "$1"
}

fail() {
    printf '\nFAILED at: %s\n' "$1" >&2
    exit 1
}

step "1/5: spotless:apply"
./mvnw -B -q spotless:apply || fail "spotless:apply"

step "2/5: checkstyle:check"
./mvnw -B -q checkstyle:check || fail "checkstyle:check"

# Tests are skipped here only because step 4 runs every suite for real.
step "3/5: clean install -DskipTests (compile + NullAway)"
./mvnw -B -q clean install -DskipTests || fail "clean install (compile/NullAway)"

step "4/5: verify (unit tests, integration tests, JaCoCo >= 80%)"
./mvnw -B verify || fail "verify (tests/coverage)"

step "5/5: checkstyle-suppressions.xml unchanged"
if git rev-parse --verify --quiet HEAD >/dev/null; then
    git diff --exit-code HEAD -- checkstyle-suppressions.xml || fail "checkstyle-suppressions.xml was modified"
else
    # No commits yet: compare the working copy against what is staged.
    git diff --exit-code -- checkstyle-suppressions.xml || fail "checkstyle-suppressions.xml was modified"
fi

printf '\n=== All completion gates passed ===\n'
