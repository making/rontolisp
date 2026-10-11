# f49. Clojure regex: `\p` properties, `(?U)` and the other constructs the engine refuses

Difficulty: Medium

The regex engine (`clojure.lisp` "Regular expressions", `.kb/clojure-frontend.md` "Regex") is
`java.util.regex` ported, but refuses as `unsupported regex` what needs data or machinery it does
not carry: `\p{..}`/`\P{..}` (POSIX names `\p{Alpha}` `\p{Punct}`, general categories `\p{L}`
`\p{Lu}`, scripts `\p{IsLatin}`, blocks `\p{InGreek}`, `\p{javaLowerCase}`), `\d \s \w \b`
under `(?U)` (Unicode Nd, White_Space, Alphabetic), `\G` (where the previous match ended),
`\N{name}`, `\X` and `\b{g}` (grapheme clusters), a class under `(?c)` (NFC). `\b` also takes
no non-spacing mark after a letter as a word character, where the oracle asks
`Character.getType`.

Measured against clj 1.12.6 (2026-10-11):

| program | oracle | here |
|---|---|---|
| `(re-seq #"\p{L}+" "héllo wörld 42")` | `("héllo" "wörld")` | refused |
| `(re-find #"(?U)\w+" "日本語!")` | `"日本語"` | refused |
| `(re-seq #"\G\d" "12a3")` | `("1" "2")` | refused |
| `(re-seq #"\b\w+\b" "été")` | `nil` | `("e" "t")` |

## What decides the design

- A table is wasm every regex program carries once an op that tests it is reachable: the case
  tables `(?iu)` needs are ~16.7 KB already. Generate property tables from the JDK like
  `clojure/ClojureCaseMapping` and decode them on first use; measure what each costs a program
  that names none.
- `\G` reads the end of the matcher's previous match, which `%clojure-re-match` does not see:
  the search would pass it in.

## Plan

1. `\p`/`\P`: the POSIX and `java*` names, general categories, then scripts and blocks; `(?U)`'s
   classes and `\b` over the same tables; `\b`'s non-spacing marks.
2. `\G`.
3. `\N{..}`, `\X`, `\b{g}`, `(?c)` classes -- or keep refusing them by name, the reason recorded
   in the KB.
4. Extend `ClojureRegexTest`'s random corpus with what lands; pin on all four backends.
