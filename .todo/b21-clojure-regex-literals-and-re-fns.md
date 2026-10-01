# Clojure: regex literals + `re-*` + string regex overloads (shcloj4)

Difficulty: High (new regex runtime callable from lowered code on all four
backends; wasm string story involved. Largest item of this round -- do last).

## Gap (verified 2026-10-01, oracle `clj` 1.12.6.1673)

- Reader: `(re-find #"a+" "aaab")` -> rontolisp
  `error: regex literals are not supported yet` (reader refusal).
- `clojure.string/split` with regex: `(str/split text #"\W+")`
  (`exploring.clj` `indexable-words`, `ellipsize` with `#"\s+"`),
  `(filter #(re-find #"jar$" ...) ...)` (`utils.clj` `jar-urls`),
  `(re-matcher #"\w+" ...)` + `(re-find m)` loop (`sequences.clj`
  `demo-mutable-re`), `(str/split ...)` in hangman-adjacent code.
  Today's `split`/`replace` are literal-only by design (b08, pinned by
  `string-replace-and-split-stay-literal`).
- Oracle: `(re-find #"a+" "aaab")` -> `"aaa"`; `(re-seq ...)`, `re-matcher`
  stateful loop, `re-groups`, `re-pattern` string round-trip.

## Design sketch (decision needed in-item)

Options: (a) lower `#"..."` to a compiled-pattern value over a shared
regex runtime (JDK `java.util.regex` on interp/JVM; a WASM-side engine or
host import on wasm-GC/component -- measure first, `.kb/size-measurement.md`
rules: bytes moved vs total); (b) keep literals refused and only widen
`split`/`replace`/`re-find` to take pattern STRINGS compiled at run time
(half the corpus value, still needs the runtime). Either way `re-matcher`'s
mutability needs an atom-cell-like wrapper (the atom/set shape precedent).
`re-quote-replacement` already exists (literal side).

## Acceptance

- `clojure-spec.yaml`: literal compiles, `re-find`/`re-seq`/`re-matches?`
  (decide the fn set in-item: at least what the corpus uses),
  `str/split` + `replace` with pattern, `re-quote-replacement` interplay --
  identical on all four backends.
- Corpus pins: `exploring.clj` `indexable-words`/`ellipsize`,
  `sequences.clj` matcher demo output, `utils.clj` `jar-urls` filter.
- `.kb/clojure-frontend.md` regex row flipped from refusal to lowering
  (or narrowed refusal documented); cost numbers recorded like the b07
  print-cost precedent; doc pages en+ja.

## Depends on

b16 (harness). Independent of b17-b20/b22; scheduled last (blocks the
most corpus lines but costs the most).
