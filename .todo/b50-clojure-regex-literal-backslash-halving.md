# Clojure: regex literals halve `\\` runs (oracle keeps them)

Difficulty: Small (reader source handling; runtime parser untouched).

## Gap (verified 2026-10-02, oracle `clj` 1.12.6.1673)

The regex-literal reader collapses each `\\` pair in the source to one
`\`, where the oracle keeps the source verbatim:

| Probe | oracle 1.12.6 | rontolisp today |
|---|---|---|
| `(str #"\\d+")` | `\\d+` | `\d+` |
| `(re-find #"\\d" "\\d")` | `\d` (literal backslash + `d`) | `nil` (reads the digit class) |
| `(re-find #"\\\\" "a\\b")` | `nil` (two backslashes) | `\` (reads one backslash) |

So `#"\\d"` answers the digit class here and a literal backslash plus
`d` there. Single-backslash classes (`#"\d"`) agree on both sides,
which is what `doc/en+ja/clojure/reference/regex.md` now spells (with
the halving recorded as a deviation).

## Design sketch

- Teach the regex source reader (`ClojureReader.readRegexSource`) to keep
  backslashes verbatim like the oracle, or decide the halving is the
  deviation and keep it. Either way the runtime parser in `clojure.lisp`
  must agree with the reader on what a pair means (today they compose
  into the halving; find where).
- `str` of a pattern spells the source (already does); keep that
  round-trip in the pin.

## Acceptance

- `clojure-spec.yaml` or reader/lowering pins for the three probes above
  (whichever semantics is chosen, pinned against the oracle), green on
  all four backends (reader-only change: parity by construction, but run
  all four).
- `.kb/clojure-frontend.md` regex row updated; `regex.md` en+ja deviation
  line updated to match.

## Depends on

b21 (regex runtime). No dependency on b22-b49.
