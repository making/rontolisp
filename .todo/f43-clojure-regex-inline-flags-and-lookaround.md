# f43. Clojure regex: inline flags and lookaround

Difficulty: High

The regex engine (`clojure.lisp` "Regular expressions", `.kb/clojure-frontend.md` "Regex")
refuses inline flags and lookarounds at construction (`unsupported regex: only (?:...) groups
are supported`). instaparse 1.5.0 builds its grammar of grammars from them: `regex-doc`
appends `(?x) #comment` to every terminal (`cfg.clj:40`), `inside-comment` is
`#"(?s)(?:(?!(?:\(\*|\*\))).)*(?x) #Comment text"` (`cfg.clj:47`), the ABNF grammar's
terminals carry `(?x)` comments (`abnf.clj:91`), and a user's grammar holds any Java regex
in `#'...'`. Found 2026-10-11 by removing the stops before it by hand in a copy of the library.

Measured against clj 1.12.6:

| program | oracle |
|---|---|
| `(re-find #"(?x) a b # comment" "xab")` | `"ab"` |
| `(re-find #"(?s)a.b" "a\nb")` | `"a\nb"` |
| `(re-find #"a(?!c)" "acab")` | `"a"` |
| `(re-find #"(?<=x)y" "ay xy")` | `"y"` |
| `(re-find #"(?i)abc" "xAbC")` | `"AbC"` |

## What decides the design

- The engine is one backtracking matcher every backend compiles, in continuation-passing
  shape (`%clojure-re-match`): a lookahead is a sub-match whose continuation answers at once,
  a lookbehind one tried backwards from the position; a flag changes how a later atom
  matches, so the parser carries the flag state into the opcodes it builds.
- Java's flag set: `i` (ASCII case unless `u`), `x` (whitespace and `#` comments outside a
  class), `s` (`.` takes line terminators), `m` (`^`/`$` at lines), `d`, `u`, `U`; scoped
  `(?i:...)` and turned off `(?-i)`.

## Plan

1. Parse and match `(?=...)`, `(?!...)`, `(?<=...)`, `(?<!...)` (bounded lookbehind first).
2. Inline flags, global and scoped, at least `i` `x` `s` `m`.
3. Re-probe instaparse; pin on all four backends.
