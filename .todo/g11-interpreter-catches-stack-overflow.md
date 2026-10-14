# g11: the interpreter catches StackOverflowError in handler-case (shcloj4 functional suite)

Status 2026-10-14: `examples.test.functional` is green on the JVM, ends the
interpreted program (`error: stack overflow`, exit 1), and traps on WASM. The
oracle reads `Ran 7 tests containing 19 assertions. 0 failures, 0 errors.`

## Half out of scope: tail-fibo

`(thrown? StackOverflowError (tail-fibo 1000000N))` overflows only where a named
self call keeps a frame. Every backend here runs it as a jump in constant stack
(`.kb/jvm-self-tail-calls.md`) and computes the millionth number instead.
Matching the oracle means killing that optimization: not done. The full 7-test
file never passes; the target is the trimmed 6-test copy (without the tail-fibo
assertion), as in `ClojureProjectNamespacesTest`.

## What to change: stack-consuming-fibo on the interpreter

`evalHandlerCase` (`eval/LispEvaluator.java`) catches only `LispEvalException`,
so a raw `StackOverflowError` from deep `eval` recursion blows through the
in-program `thrown?`. The JVM leg is green because its landing catches any
`Throwable` and the clause takes the host object by its class ("Catching").
Catch `StackOverflowError` beside `LispEvalException`, synthesize a condition
carrying the host chain (the existing shape: raw Java failures become
`LispEvalException` at the boundary, e.g. the HTTP server and reader paths),
and run the same clause dispatch. Unwinding frees the stack before the catch
runs, so the handler needs only a few frames; 1M deep overflows on every JIT
(C1-fattest ~5.5k calls on 16 MiB), so the pin is deterministic. No guard
counter: frame size varies ~3x with JIT warmth (`.kb/interpreter-stack.md`),
so a fixed guard would kill legit deep programs on warm JVMs.

Risks to handle in the item: `finally` blocks running mid-unwind with no stack
left can overflow again (the documented REPL fragility); control state may come
back inconsistent (the `controlState`/`restore` machinery exists for the REPL --
decide what an in-program catch restores). Rethrow the original error when no
clause matches. WASM stays trapping (a trap is not catchable): per-backend
difference, documented where the functional deviation already is.

Acceptance:
- trimmed functional driver (`Ran 6 tests containing 9 assertions. 0 failures,
  0 errors.`) green on the interpreter and the JVM, wasm still traps.
- `ClojureProjectNamespacesTest#aDeepNonTailCallInAThrownAssertionEndsTheInterpretedProgram`
  updated: the interpreted program now runs the catch instead of dying.
- unit + spec pins for a caught overflow (keep them shallow and fast: an
  exponential descent overflows in milliseconds); `.kb` updates
  (`clojure-frontend.md` functional section, `interpreter-stack.md` if the
  mechanics change).
- full suite green (merge `origin/develop` right before the run, one maven run).
