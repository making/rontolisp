# A condition re-signalled by a second `await` names the first one's line

Difficulty: Medium

When a future's condition is re-signalled by an `await` a handler catches, and then again by a
second `await` that nothing catches, the report's hop line names the FIRST await:

```lisp
(rontolisp:async-defun job ()
  (error "job failed"))

(let ((f (job)))
  (handler-case (rontolisp:await f)
    (error () (print :caught)))
  (rontolisp:await f))
```

```
Unhandled condition: job failed
  at t.lisp:2
  in JOB (async), awaited at t.lisp:5
```

Line 5 is the await whose signal was handled; the one the condition escaped from is line 7. The
interpreter records the hop's await site once per crossing on the condition's trace, which the
second await rethrows unchanged (`eval/ConditionTrace`); the JVM backend reproduces that on purpose
(`JvmUncaughtHandler.buildAsyncAwaited`, pinned by
`UncaughtReportParityTest#aSecondAwaitOfAFailedFutureKeepsTheFirstAwaitAsTheBoundarysSite`). A
wasm-GC `--component` module with `--report-locations=line` says line 7, Preview 1 the line of the
call (todo 991, item 4).

Goal: decide the rule -- the await that re-signalled the condition that escaped looks right -- and
make the interpreter, the JVM backend and wasm-GC print it, the test above changing with them.

Read first: `.kb/error-handling.md` ("Interpreter async", "JVM async", "Location lines on
wasm-GC"), `.kb/async-await.md`.
