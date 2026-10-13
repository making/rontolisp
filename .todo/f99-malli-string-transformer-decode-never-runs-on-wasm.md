# f99. malli string-transformer decode never runs on wasm

Difficulty: Medium

Found re-running the malli probe for `.todo/f96` (2026-10-13): `(m/decode :int
"1" mt/string-transformer)` answers `1` on the oracle, the interpreter and the
JVM, but `"1"` on both wasm backends, and `(m/decode [:map [:x :int]] {:x "1"}
...)` leaves `:x` undecoded the same way. The decoder is `-string->long` over
`(Long/parseLong x)`, a host static the wasm backends refuse at call time (the
`JAVA:STATIC` warning), so the `catch` keeps the input. Reproduced on the
pristine tree without f96's changes.

The `.kb/clojure-frontend.md` malli row calls the 12-line probe line-for-line
on wasm, but the decode lines print bare (`println`), under which an undecoded
`"1"` reads as `1`; `pr-str` shows the gap. Either the row or the behavior is
wrong.

## What decides the design

- The oracle's grammar is `Long/valueOf`'s; the front end already owns a
  portable one (`%clojure-parse-long`, `clojure.core/parse-long`). Whether
  decode should read through it, and what a malformed string answers there, is
  the design question -- not just wiring the static.
- Out of scope: the f96 refusal paths (untouched).

## Plan

1. The 12-line malli probe is the oracle's line for line under `pr-str` on all
   four backends (or documents why it cannot).
2. clojure-spec rows where the behavior is observable, four backends.
