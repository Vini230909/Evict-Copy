#!/usr/bin/env bash
# Checks the code against docs/CODE_RULES.md. Green = the rules hold. Red = not finished.
#
# Old files (everything that existed before the rules) are listed in check-baseline.txt
# with their line count. They are allowed to exist but never to grow. Every file NOT in
# that list is new and must follow every rule.
#
#   ./check.sh            run the check
#   ./check.sh --update   forget deleted old files and lower counts that shrank
#                         (it can only ever make the baseline smaller, never bigger)

set -u
cd "$(dirname "$0")"

ROOT=src/Extinction
BASELINE=check-baseline.txt
MAX_LINES=600
FAILS=0

fail() { echo "  RED  $1"; FAILS=$((FAILS + 1)); }

is_old()    { grep -q "^$1 " "$BASELINE"; }
old_lines() { grep "^$1 " "$BASELINE" | cut -d' ' -f2; }
lines()     { wc -l < "$1"; }

# --- Old files: may shrink, may vanish, may never grow -------------------------------
echo "Old files (baseline):"
while read -r file base; do
    if [ ! -f "$file" ]; then
        echo "  gone $file  (run --update to forget it)"
    elif [ "$(lines "$file")" -gt "$base" ]; then
        fail "$file grew: $base -> $(lines "$file") lines. Old code may not grow (rule 6)."
    fi
done < "$BASELINE"

# --- New files: every rule -------------------------------------------------------------
echo "New files:"
NEW=0
while read -r file; do
    is_old "$file" && continue
    NEW=$((NEW + 1))
    rel=${file#"$ROOT"/}
    depth=$(echo "$rel" | tr -cd '/' | wc -c)
    name=$(basename "$file" .java)
    dir=$(dirname "$rel")

    # Rule 1: flat - at most one subfolder level, at most MAX_LINES lines
    [ "$depth" -gt 1 ] && fail "$file is nested too deep (rule 1: at most one subfolder)."
    [ "$(lines "$file")" -gt "$MAX_LINES" ] && fail "$file has $(lines "$file") lines (rule 1: max $MAX_LINES)."

    # Rule 2: commands live only in commands/Player, commands/Admin, commands/Console
    case "$name" in *Command*)
        [ "$dir" = commands ] || fail "$file: commands belong in commands/ (rule 2)."
    esac
    if [ "$dir" = commands ]; then
        case "$name" in Player|Admin|Console) ;; *)
            fail "$file: commands/ may only hold Player, Admin, Console (rule 2)."
        esac
    fi

    # Rule 3: one config, no getters or setters in it
    case "$name" in *Settings*|*Config*)
        [ "$name" = Config ] || fail "$file: all settings go into one Config (rule 3)."
        acc=$(grep -nE '^\s*public .* (get|set)[A-Z][A-Za-z]*\(' "$file" | head -3)
        [ -n "$acc" ] && while read -r hit; do fail "$file:$hit  getter/setter in config (rule 3)."; done <<< "$acc"
    esac

    # Rule 4: no *Manager classes
    case "$name" in *Manager*) fail "$file: no *Manager classes (rule 4)." ;; esac

    # Rule 5: line 1 is a one-line // header; no comment longer than two lines
    head -1 "$file" | grep -q '^//' || fail "$file: line 1 must be a one-line // header (rule 5)."
    long=$(awk -v f="$file" '
        /^[[:space:]]*(\/\/|\/\*|\*)/ { run++; if (run == 3) print f ":" NR-2 "  comment longer than two lines (rule 5)."; next }
        { run = 0 }' "$file")
    [ -n "$long" ] && while read -r hit; do fail "$hit"; done <<< "$long"
done < <(find "$ROOT" -name '*.java' | sort)

# --- Summary ---------------------------------------------------------------------------
TOTAL=$(find "$ROOT" -name '*.java' | wc -l)
OLD=$(grep -c . "$BASELINE")
echo
echo "Files: $TOTAL total, $((TOTAL - NEW)) old, $NEW new. Target is about 30, all new."
echo "Rules and this script are edited only when the owner asks: docs/CODE_RULES.md, check.sh, $BASELINE."

if [ "${1:-}" = "--update" ]; then
    tmp=$(mktemp)
    while read -r file base; do
        [ -f "$file" ] || continue
        now=$(lines "$file")
        [ "$now" -lt "$base" ] && base=$now
        echo "$file $base"
    done < "$BASELINE" > "$tmp"
    mv "$tmp" "$BASELINE"
    echo "Baseline updated (only ever smaller)."
fi

echo
if [ "$FAILS" -eq 0 ]; then echo "GREEN"; exit 0; else echo "RED: $FAILS problem(s)"; exit 1; fi
