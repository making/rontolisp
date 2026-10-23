# g78: lispresen guesses the width of most emoji instead of measuring it

Difficulty: Low

`wide-code-p` (`lispresen/src/metrics.lisp`) counts U+1F300-1F64F and U+1F900-1F9FF as
1 em and U+25A0-26FF as full width, but the rest of the emoji are "other proportional
characters", 800 milli-em: transport and map symbols (U+1F680-1F6FF, 🚀), dingbats
(U+2700-27BF, ✅ ✨ ❌), U+1FA70-1FAFF, regional-indicator flags (two code points for one
glyph) and ZWJ sequences (👨‍💻, several code points for one glyph, each counted). The
browser sets an emoji in the color emoji face (Apple Color Emoji, Noto Color Emoji,
Segoe UI Emoji), whose advance is about a full em or more, not 800 milli-em, and a
sequence as one glyph, not as its code points. Too narrow, the model can predict fewer
lines than the browser renders, which DESIGN.md (Text measurement) says never happens,
and the runtime check steps the slide down when the deck opens; too wide (a ZWJ
sequence counted per code point), a slide is fitted smaller than it needs. The widths
above are not measured yet: measure first, and close this item if the table turns out
to hold.

## Value

- For the user: emoji are common in Japanese tech talks (✅/❌ lists, 🚀 in titles), and a
  slide that changes size when it opens on the presenting machine is what lispresen's
  static fitting exists to prevent.
- Design: exact widths are the premise of the fit solver; this closes a hole in the
  table, nothing else changes. Shortcodes such as `:rocket:` are not needed: a deck is
  UTF-8 and the character can be written as is.
- Cost: measure, extend the ranges, count a cluster (variation selector U+FE0F, ZWJ,
  skin tone modifiers, flag pairs) as one glyph.

## Shape

- Measure emoji advances in Chrome and Safari on macOS (Apple Color Emoji), and in
  Chrome on Linux (Noto Color Emoji) if available, with `tools/calibrate.lisp` extended
  by an emoji sample; take the widest, plus the margin, as the text table does.
- Zero width for U+FE0F, U+200D joiners inside a sequence and U+1F3FB-1F3FF modifiers;
  regional indicators counted as one glyph per pair.
- Line breaking: never break inside a cluster (the phrase segmenter and the char-break
  fallback both).
- Record the numbers and date in DESIGN.md, Text measurement.

## Verification

- Extend `line-wrapping` in `lispresen/test/lispresen-test.lisp` with emoji widths and a
  ZWJ sequence that must stay on one line.
- The calibration page reports no emoji wider than the model in Chrome or Safari.
- `lispresen/check.sh`: byte-identical output on every backend.
