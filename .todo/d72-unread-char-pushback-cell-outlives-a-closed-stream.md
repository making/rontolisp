# d72. The `unread-char` pushback cell outlives a closed stream

Difficulty: Medium

The handle-side pushback is ONE cell for every stream (`unread-char.lisp` on the compile
paths, `Environment`'s `pushbackStream`/`pushbackChar` on the interpreter;
`.kb/gray-streams.md`, "Handle-side pushback"). Nothing clears it when its stream is closed or
dropped, so a later `unread-char` on any OTHER stream signals "UNREAD-CHAR without an
intervening READ-CHAR". Measured 2026-10-06, all four backends:

```lisp
(with-input-from-string (s "abc") (unread-char (read-char s) s))
(print (with-input-from-string (s2 "xyz") (unread-char (read-char s2) s2) (read-char s2)))
```

SBCL prints `#\x`; every backend signals. The same with `:index` on the first form (SBCL `0`):
before `with-input-from-string`'s `:index` stopped draining the stream, the interpreter's drain
happened to take the parked character and this sequence passed there.

A per-stream cell (CL keeps the pushback on the stream) removes the cross-stream failure, but an
entry left by a dropped stream must not grow every later read's lookup -- so `close` has to drop
its stream's entry, and the closes the expression compilers synthesize (`with-input-from-string`,
`with-open-file`, `with-open-stream`) are invisible to `UnreadCharLibrary`'s call-site rewrite.
Decide where the cell lives (on the stream value, or a table the closes reach) on all four
backends together.
