#!/usr/bin/env bash
# =============================================================================
# Runs supabase/tests/*.sql against the linked project.
#
#   bash scripts/run-db-tests.sh            every test file
#   bash scripts/run-db-tests.sh 01         the files whose names contain "01"
#
# Each file is run with _helpers.sql in front, in one transaction, and ends by
# raising 'ALL PASSED' so that whatever it wrote is rolled back: the tests use
# the project's real data and must leave it as they found it. A file passes
# when that is the error it stops on; any other error is its failure.
# =============================================================================
set -u
cd "$(dirname "$0")/.."

filter="${1:-}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

passed=0
failed=0
for file in supabase/tests/[0-9]*.sql; do
    name="$(basename "$file")"
    [[ -n "$filter" && "$name" != *"$filter"* ]] && continue

    cat supabase/tests/_helpers.sql "$file" > "$tmp/run.sql"
    out="$(supabase db query --linked -f "$tmp/run.sql" 2>&1)"

    if grep -q "ALL PASSED" <<< "$out"; then
        echo "PASS  $name"
        passed=$((passed + 1))
    else
        reason="$(grep -o 'FAIL: [^"\\]*' <<< "$out" | head -1)"
        [[ -z "$reason" ]] && reason="$(grep -i 'error' <<< "$out" | head -1)"
        echo "FAIL  $name  ${reason:-$out}"
        failed=$((failed + 1))
    fi
done

echo "-- $passed passed, $failed failed"
[[ $failed -eq 0 ]]
