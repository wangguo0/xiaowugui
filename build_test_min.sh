#!/usr/bin/env bash
set -euo pipefail

INPUT="${1:-test.html}"
OUTPUT="${2:-test.min.html}"
CACHE_DIR="${NPM_CONFIG_CACHE:-/tmp/npm-cache}"

if [ ! -f "$INPUT" ]; then
  echo "Input file not found: $INPUT" >&2
  exit 1
fi

if ! command -v node >/dev/null 2>&1; then
  echo "node is required" >&2
  exit 1
fi

if ! command -v npx >/dev/null 2>&1; then
  echo "npx is required" >&2
  exit 1
fi

TMP_FILE="$(mktemp "${TMPDIR:-/tmp}/webhome-min.XXXXXX.html")"
cleanup() {
  rm -f "$TMP_FILE"
}
trap cleanup EXIT

echo "Minifying $INPUT -> $OUTPUT"
NPM_CONFIG_CACHE="$CACHE_DIR" npx --yes --cache "$CACHE_DIR" html-minifier-terser \
  --collapse-whitespace \
  --remove-comments \
  --remove-redundant-attributes \
  --remove-script-type-attributes \
  --remove-style-link-type-attributes \
  --use-short-doctype \
  --minify-css true \
  --minify-js true \
  --output "$TMP_FILE" \
  "$INPUT"

node - "$TMP_FILE" <<'NODE'
const fs = require("fs");
const file = process.argv[2];
const html = fs.readFileSync(file, "utf8");
const scriptRe = /<script(?![^>]*src=)[^>]*>([\s\S]*?)<\/script>/gi;
let count = 0;
for (const match of html.matchAll(scriptRe)) {
  count += 1;
  new Function(match[1]);
}
console.log(`Checked inline scripts: ${count}`);
NODE

mv "$TMP_FILE" "$OUTPUT"
chmod 644 "$OUTPUT"
trap - EXIT

bytes() {
  wc -c < "$1" | tr -d ' '
}

ORIGINAL_BYTES="$(bytes "$INPUT")"
MINIFIED_BYTES="$(bytes "$OUTPUT")"
awk -v original="$ORIGINAL_BYTES" -v minified="$MINIFIED_BYTES" 'BEGIN {
  saved = original - minified;
  pct = original ? saved * 100 / original : 0;
  printf("Original: %d bytes\nMinified: %d bytes\nSaved: %d bytes (%.1f%%)\n", original, minified, saved, pct);
}'

if command -v gzip >/dev/null 2>&1; then
  GZIP_BYTES="$(gzip -9 -c "$OUTPUT" | wc -c | tr -d ' ')"
  echo "gzip -9: $GZIP_BYTES bytes"
fi

if command -v brotli >/dev/null 2>&1; then
  BROTLI_BYTES="$(brotli -q 11 -c "$OUTPUT" | wc -c | tr -d ' ')"
  echo "brotli q11: $BROTLI_BYTES bytes"
fi

echo "Wrote $OUTPUT"
