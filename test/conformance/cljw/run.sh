#!/usr/bin/env bash
# test/conformance/cljw/run.sh — the ClojureWasm load matrix for this library.
#
# Requires every namespace listed in baseline.edn :must-load, one cljw process
# each, against the classpath in deps.edn. Exits non-zero when one of them
# stops loading; reports (without failing) any namespace that starts loading,
# which is the signal to widen the baseline.
#
# The binary comes from $HIVE_CLJW_BIN, else :runtimes :cljw :binary in
# ~/.config/hive-mcp/config.edn — never a hardcoded path. With no binary the
# matrix SKIPS, unless HIVE_REQUIRE_NATIVE_ARMS=1 makes an unresolvable
# runtime a failure.
set -uo pipefail
cd "$(dirname "$0")"

CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/hive-mcp/config.edn"
BIN="${HIVE_CLJW_BIN:-}"
if [ -z "$BIN" ] && [ -f "$CONFIG" ]; then
    BIN="$(grep -o ':cljw {:binary "[^"]*"' "$CONFIG" | head -1 | sed 's/.*"\(.*\)"/\1/')"
fi
if [ ! -x "${BIN:-}" ]; then
    if [ "${HIVE_REQUIRE_NATIVE_ARMS:-0}" = "1" ]; then
        echo "cljw-matrix: FAIL — no cljw binary (HIVE_CLJW_BIN / $CONFIG)" >&2
        exit 1
    fi
    echo "cljw-matrix: SKIP — no cljw binary (HIVE_CLJW_BIN / $CONFIG)"
    exit 0
fi

export CLJW_HOME="${CLJW_HOME:-$HOME/.cljw}"
echo "cljw-matrix: $("$BIN" --version)"

must_load="$("$BIN" -e '(->> (slurp "baseline.edn") clojure.edn/read-string :must-load (clojure.string/join " ") println)' 2>/dev/null | grep -v '^note:' | grep -v '^nil$')"
if [ -z "$must_load" ]; then
    echo "cljw-matrix: FAIL — baseline.edn declares no :must-load namespaces" >&2
    exit 1
fi

probe() {  # ns -> 0 when it loads
    "$BIN" -e "(require '$1)" >/dev/null 2>&1
}

fails=0; n=0
for ns in $must_load; do
    n=$((n + 1))
    if probe "$ns"; then
        echo "  LOAD    $ns"
    else
        echo "  BLOCKED $ns"
        "$BIN" -e "(require '$ns)" 2>&1 | grep -v '^note:' | grep -v '^nil$' | head -2 | sed 's/^/          /'
        fails=$((fails + 1))
    fi
done

# Namespaces present in src but absent from the baseline: report only.
for f in $(cd ../../../src && find . -name '*.clj' -o -name '*.cljc' | sed 's|^\./||' | sort); do
    ns="$(echo "$f" | sed 's|\.cljc*$||; s|/|.|g; s|_|-|g')"
    case " $must_load " in
        *" $ns "*) continue ;;
    esac
    if probe "$ns"; then
        echo "  NEW     $ns loads and is not in baseline.edn — widen it"
    fi
done

echo "cljw-matrix: $((n - fails))/$n baseline namespaces load"
[ "$fails" -eq 0 ]
