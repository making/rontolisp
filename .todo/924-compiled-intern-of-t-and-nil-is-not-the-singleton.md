# The compiled backends' `(intern "T")` is not `t`

Difficulty: Medium

Split out of `.todo/917` (2026-09-20), which fixed the interpreter half and left
the compiled one as a divergence.

## What it is

`t` and `nil` are singletons (`LispTrue` / `LispNil` on the interpreter, their
own representations on the JVM and both WASM backends), not symbols spelled
`"T"` / `"NIL"`. Every operator that ANSWERS a symbol by name on the interpreter
now maps those two spellings back to the singletons
(`LispEvaluator.symbolOfSpelling`: `find-symbol`, `intern`, the enumerations), so
`(eq (intern "T") t)`, `(eq (find-symbol "NIL" :cl) nil)` and
`(some #'not (find-all-symbols "NIL"))` hold there -- the last one is what the
ANSI `find-all-symbols.3`-`.9` rows read.

The compiled backends still answer the string symbol: the JVM's 1-arg `intern`
is a quote strip (`JvmSymbolApiCompiler.compileIntern` -> `emitStripQuotes`), the
WASM one interns the bytes through `_intern_sym`, and neither knows the two names
are special; `%do-symbols-list` materializes the baked `cl` externals through the
same `intern`, so a `do-external-symbols` over `cl` yields a `T` that prints like
`t` and is not `eq` to it. `.kb/symbol-runtime-api.md`, "The spelling-identity
model", records the split.

## Plan

One rule per backend at the symbol-building rail: when the runtime string is
exactly `T` or `NIL`, answer the singleton (the JVM emission has the string in
hand; the WASM `_intern_sym` can compare the content range before the table
lookup, the way the keyword rail tests for a leading colon). Then the 2-arg
lowerings (`expandInternInPackage`, `computedQualifiedSpelling`) need nothing:
they build `PKG:NAME` spellings, and a bare `T`/`NIL` only arises through the
`cl`/`cl-user` arm, which already lowers to the 1-arg `intern`. Pin with the
`symbol-runtime-api` ci-spec case (`(eq (intern "T") t)`, `(eq (find-symbol "NIL"
"CL") nil)`), which then runs on all four backends, and drop the interpreter-only
caveat from `intern.md` / `find-symbol.md`.
