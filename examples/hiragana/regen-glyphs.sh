#!/usr/bin/env bash
# Regenerate the reference glyphs for the hiragana demo.
#
#   examples/hiragana/regen-glyphs.sh
#
# Runs the offline Java glyph renderer (glyphgen/GlyphGen.java) and rewrites the
# generated artifacts in this directory:
#   prototypes.lisp        the trainer's reference glyphs
#   glyphs.js              GLYPHS/KANA/ORDER for index.html
#   samples/<romaji>.txt   each template flattened to a stdin bitmap
#
# Run this ONLY when changing the font, resolution (GRID), or class set in
# GlyphGen.java -- the artifacts above are committed, and the normal build
# (gen.sh) consumes them as-is.  Requires a JDK with the configured font
# installed (macOS ships "Hiragino Maru Gothic ProN").
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
java "$here/glyphgen/GlyphGen.java" "$here"
