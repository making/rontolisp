# i65. `warn` does not signal to a `handler-case` for a warning

Difficulty: Medium

Measured 2026-11-05: outside restart mode this prints `WARNING: w` and answers 1 on the interpreter, the JVM and both WASM
backends; SBCL 2.2.9 answers `WRN`.

```lisp
(print (handler-case (progn (warn "w") 1) (warning () 'wrn)))
```

`expandSignalDesignatorInner` lowers `warn` to the plain write `%warn` unless the restart runtime is on (`signalHook`); only
`signal` asks the established `handler-case`s, through `%signal-cond`. An eval'd `handler-case` already answers `WRN`, because
`%eval-handler-case` is written over `handler-bind` (`eval-macros.lisp`), so the two disagree today.

## Plan

Have the `warn` lowering ask what `signal` asks before it writes, on all four backends, with the instance `%signal-cond` builds.
The compile paths' probe is the `%hc-match-p` walk of the cluster stack, which `warn` does not carry now: size that against a program
that warns and has no `handler-case`, and keep such a program's bytes if the walk is not needed. Pin with a ci-spec case.

## Also seen

The interpreter's `(multiple-value-list (eval form))` drops a secondary value the compile paths and SBCL keep:
`(eval '(gethash 1 h))` is `(5)` for `(5 T)`, `(eval '(read-line s))` is `("hi")` for `("hi" T)`. And its
`(eval '(eval-when (:compile-toplevel) 1))` answers 1 where they answer NIL.
