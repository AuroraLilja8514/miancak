#!/usr/bin/env bash

# Re-enter under a finite watchdog; nested drivers share the remaining budget.
MIANCAK_ENTRY="$(cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")"
if [ "${MIANCAK_TIMEOUT_SCRIPT:-}" != "$MIANCAK_ENTRY" ]; then
  exec python3 "$(dirname -- "$MIANCAK_ENTRY")/../../scripts/run_with_timeout.py" --script "$MIANCAK_ENTRY" -- "$@"
fi

set -e
cd "$(dirname $0)"

DRAWABLE_DIR=../../res/drawable
ANDROID_LIB=$ANDROID_SDK_ROOT/tools/lib

first () { echo "$1"; }
JAVA_ARGS=(
  -classpath
  "$(first $ANDROID_LIB/sdk-common-*.jar):$(first $ANDROID_LIB/common-*.jar)"
)
svg_to_vector ()
{
  java "${JAVA_ARGS[@]}" SvgToVector.java "$@"
}

TMP=`mktemp -d`
trap "rm -r '$TMP'" EXIT
set -x

inkscape doc_key.svg -o "$TMP/doc_key_g.svg" -C --export-page 1 --export-plain-svg --export-text-to-path
inkscape doc_key.svg -o "$TMP/doc_key_u.svg" -C --export-page 2 --export-plain-svg --export-text-to-path

svg_to_vector "$TMP/doc_key_g.svg" "$DRAWABLE_DIR/doc_key_g.xml"
svg_to_vector "$TMP/doc_key_u.svg" "$DRAWABLE_DIR/doc_key_u.xml"
