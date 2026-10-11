# f54. Clojure regex: `\X`, `\b{g}` and `\N{name}`

Difficulty: Medium

The regex engine (`clojure.lisp` "Regular expressions", `.kb/clojure-frontend.md` "Regex")
still refuses as `unsupported regex` what f49 left: `\X` and `\b{g}` (grapheme clusters) and
`\N{name}`. Measured against clj 1.12.6 (2026-10-11): `(re-seq #"\X" "éx")` is
`("é" "x")`, here refused; `(re-find #"\N{LATIN SMALL LETTER A}" "a")` is `"a"`, refused.

## What decides the design

- `\X` is `jdk.internal.util.regex.Grapheme.nextBoundary` (Unicode 16 rules, JDK 25): a
  break type per code point derived from the general category and `isExtendedPictographic`
  (both already in `clojure-unicode.lisp`'s combos) plus constants (CR, LF, Hangul L/V/T/LV/LVT,
  Prepend, the excluded spacing marks, regional indicators), and GB9c's Indic conjunct lists
  (`IndicConjunctBreak.isConsonant`/`isExtend`, six linkers). The two lists are internal API:
  generate them by probing public `Pattern` (`\X` over consonant + U+094D + U+0915, over
  U+0915 + e + U+094D + U+0915) into a runs table of their own, so the combos stay inside
  i31.
- `XGrapheme.study` adds one to the minimum length and nothing to the maximum (a look-behind
  holding `\X` never matches); `GraphemeBound` asks `nextBoundary` from `matcher.last`, the
  last match's end -- 0 after `find(int)`'s reset, where `\G` holds at the start it was given.
- `\N{name}` needs `Character.codePointOf`'s name list, far too big to ship. A regex literal's
  names can be resolved while lowering (every `\N{...}` of its source through
  `codePointOf`) and handed to the parse; a pattern built at run time keeps refusing.

## Plan

1. The grapheme break type and the conjunct table; `\X` and `\b{g}` nodes; a regex literal
   naming either makes `REGEX_UNICODE` (or a family of their own, measured).
2. `\N{..}` of a literal and of `re-pattern` of a literal string.
3. `ClojureRegexTest`'s Unicode corpus with both; pin on all four backends.
