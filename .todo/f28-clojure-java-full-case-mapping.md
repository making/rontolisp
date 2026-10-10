# f28. Clojure: Java's full case mapping for `upper-case`, `lower-case` and `%S`

Difficulty: Medium

The Clojure front end upcases and downcases with CL `string-upcase`/`string-downcase`, a fold
per code point (`.kb/characters-code-points.md`, "Case fold is FULL Unicode, and PER CODE
POINT everywhere"). The oracle calls `String.toUpperCase`/`toLowerCase`, which apply
SpecialCasing (one code point to several) and Final_Sigma. Measured 2026-10-10, clj 1.12.6 vs
the interpreter:

| form | oracle | here |
|---|---|---|
| `(clojure.string/upper-case "straße")` | `"STRASSE"` | `"STRAßE"` |
| `(format "%S" "straße")` | `"STRASSE"` | `"STRAßE"` |
| `(.toUpperCase "ŉ")` | `"ʼN"` | `"ŉ"` |
| `(clojure.string/lower-case "ΑΣ")` | `"ας"` | `"ασ"` |

## What decides the design

- The CL fold must stay per code point (the `.kb` file above says why); this is a Clojure
  runtime helper of its own, not a change to `string-upcase`.
- Locale: the oracle's `toUpperCase()` uses the default locale (Turkish/Lithuanian rules aside);
  measure which locale the oracle runs under before choosing `Locale.ROOT` semantics.
- Size: the unconditional SpecialCasing upcase list is ~100 code points; a table on WASM.

## Plan

1. Measure every SpecialCasing row and Final_Sigma contexts on clj 1.12.6.
2. One Clojure runtime helper per direction behind the verbs above (`upper-case`,
   `lower-case`, `capitalize`, `.toUpperCase`, `.toLowerCase`, `%S`/`%B`/`%C`/`%X`'s upcase);
   pin on all four backends; drop the `%S` clause from `doc/{en,ja}/clojure/deviations.md`.
