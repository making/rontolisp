# f45. Clojure: instaparse's GLL parse is two orders of magnitude slower than the oracle

Difficulty: High

With f41-f44's stops removed by hand in a copy of instaparse 1.5.0 (2026-10-11), its GLL parser
runs verbatim on the interpreter and the JVM: a parse, a failure printed through its
`print-method`, a negative lookahead's `delay`. But it is far slower than the oracle:

| step | oracle (clj 1.12.6) | interpreter | JVM class |
|---|---|---|---|
| `(cfg/ebnf "S = 'a'+ !'c' 'b'")` | 19 ms | 1,877 ms | 322 ms |
| `(gll/parse grammar :S "aab" false)` | 0.85 ms | 57 ms | 7 ms |
| `(cfg/ebnf ...)` of 1 / 2 / 5 / 8 rules, interpreter | -- | 1.1 / 2.1 / 7.1 / 16.5 s | -- |

`instaparse.abnf` builds its ABNF parser while it loads (`(def abnf-parser (... (cfg/ebnf
...)))`, about 30 rules), which did not finish within 300 s on the interpreter, so
`instaparse.core`, which requires it, does not load in practice.

## What decides the design

- Measure before choosing: the suspects are a node key `[index parser]` hashed structurally
  (a parser is a nested combinator map; the oracle caches a map's hash), the per-terminal
  `(re-matcher re (sub-sequence text i))` that copies the rest of the text through the
  CharSequence view, and `AutoFlattenSeq`'s hash.
- `.kb/clojure-frontend.md` "Structural keys", "Hashes", "Host interfaces" (CHAR_SEQUENCE).

## Plan

1. Profile `cfg/ebnf` of a small grammar on the interpreter and the JVM; record where the time
   goes in the KB.
2. Fix what the profile names, then load `instaparse.core` and parse a grammar of its README
   on the interpreter and the JVM against the oracle's output.
