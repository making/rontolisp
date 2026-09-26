# Restart mode never narrows the condition runtime

Difficulty: Medium

`LispMacroExpander.conditionNarrowing` answers `ConditionNarrowing.none()` under restart mode
(and `WasmLispCompiler.usedLayoutTags` bakes every layout), so any `handler-bind` program whose
condition reaches a printer carries every registered condition class's report arm and the
`%fmt-render` renderer. The commit that introduced the bail (e3ee662a3) gives no reason beyond
"the world stays open".

Measured 2026-09-26 on wasm-GC, default optimize:

```lisp
(defun main ()
  (handler-bind ((error (lambda (c) (format t "saw ~a~%" c))))
    (car 5)))
(print (ignore-errors (main)))
```

- As shipped: 114,044 B. The same handler ignoring `c`: 17,961 B. The `handler-case` twin that
  prints its condition: 7,357 B.
- Dropping only the `restartMode` half of `conditionNarrowing`'s bail: 27,228 B, same output on the
  interpreter, JVM, wasm-GC and the component. Not run against the test suite.

Plan: find what in restart mode can construct a condition class or hand `%format-condition` an
unrendered control AFTER the scan (the restart runtime defuns, restart-mode `cerror`/`warn`
lowerings, `check-type`/`assert`) and cover those sites the way `END_OF_FILE_SITES` /
`FILE_ERROR_SITES` are covered, then drop the bail (and, separately measured, the `restartMode`
input of `usedLayoutTags`). `ci-spec restart-system` puts the whole corpus into restart mode, so
`CiSpecE2eTest` exercises the narrowed runtime on every case.

Read first: `.kb/error-handling.md` ("The condition floor is narrowed to what the program can
construct", "The routing gate asks whether a condition can be NAMED", "The restart-mode gate").
