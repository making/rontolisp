# 446. A runtime `load` answers `1` on the compile backends, `t` on the interpreter

Difficulty: Low

## The defect

```lisp
(print (load "lib.lisp"))
;; interpreter: T
;; JVM / WASM / component: 1
```

`doc/{en,ja}/reference/functions/load.md` says `load` returns `t`, and the
interpreter does. The compiled runtime helper returns the integer 1 instead
(`JvmReadRuntimeBuilder.buildLoad` ends `LCONST_1` + `Long.valueOf`; the WASM
`FUNC_LOAD` body is the same shape). Both values are truthy, so nothing that
merely tests the result notices -- which is why it survived.

Only a RUNTIME `load` is affected: a top-level literal one is spliced by
`LoadInliner` and has no value at all.

## Where it came from

Found while landing `.todo/439` (`load`'s keyword options), which made the value
easier to look at -- `(load p :if-does-not-exist nil)` now answers `nil` for a
missing file on all four backends, so the success value being `1` on three of
them is the only remaining disagreement in that expression.

## The fix

Return the backend's `t` from `_load` on both compile paths (a `Boolean.TRUE`
getstatic on the JVM, the i31/true singleton on WASM), and pin it with a
four-backend case -- the ci-spec case `computed-stream-options-439` already runs
a runtime `load`, so one `print` line extends it.

Watch: the emitted bytes of any program with a runtime `load` change (nothing
in `size-report/programs/` has one today -- re-check before assuming).
