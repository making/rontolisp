# The interpreter re-expands a user macro on every evaluation

`LispEvaluator.evalCons` calls `expandUserMacro` each time it reaches a call whose
operator is a `defmacro`, so a macro call inside a loop body is expanded once per
ITERATION -- the macro body is interpreted, a fresh expansion is consed, and only
then is anything evaluated. Nothing caches the result. The compile path expands
each call site exactly once (`UserMacroExpander`), so this is an
interpreter-only cost, and it is the largest one left in the interpreter.

## Measurement (2026-07-26)

Found while closing `.todo/179` phase 5 (compiler macros + `load-time-value`). That
work made the interpreter's `cl-ppcre:split` 7.2x faster and `scan` ~28x faster, but
the `do-*` iteration macros did not move, because their per-call-site memos never
hit: each iteration re-expands `do-matches-as-strings` into a FRESH `(scan "..." ...)`
cons, which is a new key for `compilerMacroExpansions`, whose expansion is a new
`(load-time-value (create-scanner ...))` cons, which is a new key for
`loadTimeValues`. So the scanner is recompiled every iteration anyway.

500 iterations of `(cl-ppcre:do-matches-as-strings (m "[0-9]+" line) ...)` on the
interpreter, against the same loop with a hoisted `create-scanner` control:

| | literal | hoisted control |
| --- | --- | --- |
| interpreter | 11,093 ms | 753 ms |
| JVM (`.class`) | 11 ms | 21 ms |
| WASM `--component` | 13 ms | 14 ms |

The three compiled backends are at parity (the literal is now the FASTER side --
the control pays for an extra closure variable). Only the interpreter lags, and by
14.7x.

## What to do

Memoize the expansion by call-site cons identity, the same shape
`LispEvaluator.compilerMacroExpansions` / `loadTimeValues` already use (an
`IdentityHashMap` bounded by `EXPANSION_MEMO_LIMIT`). Then a macro call inside a
loop expands once, and the two existing memos start hitting for free.

Correctness questions to settle before doing it:

- **Redefinition.** A `defmacro` re-evaluated at runtime must invalidate the memo,
  and so must `pushLocalMacro`/`popLocalMacro` (a `macrolet` changes the macro table
  for a dynamic extent, so an expansion cached inside its body must not survive it).
  Clearing the whole memo on any of those is the cheap, obviously-correct answer.
- **Expansion-time state reads.** A macro body may read a global while expanding
  (cl-who's `with-html-output` reads `*html-mode*`). Caching freezes the first
  answer. Note this is what the compile path ALREADY does -- it expands once, at
  compile time -- so caching moves the interpreter TOWARD cross-backend identity,
  not away from it. Say so in `.kb/defmacro-backquote.md` either way.
- **Built-in macros are the bigger prize and a separate question.** `loop`, `dolist`,
  `cond`, `setf`, ... are re-expanded per evaluation too (`evalCons` calls
  `LispMacroExpander.expand*` inline). Those expansions are pure functions of the
  form, so memoizing them needs no invalidation at all -- but the memo would be much
  hotter, so measure before assuming the lookup pays for itself.

Pin whatever lands with a counter-based test (a macro whose body increments a
special, called N times from a loop, must expand once) plus the existing
`macrolet` tests, and re-run the measurement above.
