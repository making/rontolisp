# Argument validation throws a raw Java exception that no Lisp handler can catch

Difficulty: High

An argument-shape error leaves the evaluator as an `IllegalArgumentException` /
`UnsupportedOperationException` / `IndexOutOfBoundsException` rather than as a
`LispEvalException` carrying a condition class, so it passes straight through
`handler-case`, `handler-bind` and `ignore-errors` and kills the program:

```lisp
(handler-case (remove 1 '(1 2 3) :bogus 4) (error (c) (list :caught c)))
;; => error: REMOVE expects keyword arguments :test/:test-not/:key, got: :BOGUS
;;    (the whole run ends; the handler never sees it)
```

`.kb/error-handling.md` states the catching contract for all four backends and
`LispEvalException.ofClass(ClosRegistry.TYPE_ERROR_CLASS_NAME, ...)` is the
mechanism already in place for a bad `car` and an out-of-bounds index -- these
sites simply do not use it. CL says a call with a bad keyword, a bad argument
count or a bad keyword-list shape signals `program-error`, and a wrong-typed
argument signals `type-error`; both are catchable conditions, not aborts.

## The sites

The two highest-volume ones are keyword-argument validation:

- `macro/LispMacroExpander:5413` -- the shared
  `name + " expects keyword arguments " + join(allowed) + ", got: "` throw
- `macro/LispMacroExpander:4780`, `:5779`, `:7754`;
  `eval/LispEvaluator:2693`, `:9376`, `:10484`

plus the arity messages (`X expects N arguments, got M`,
`Function expects 1 argument, got 2`) and the `UnsupportedOperationException`
family (`setf does not support place: X`, `map supports only the 'list, ...
result types`, `:displaced-to cannot be combined with ...`,
`make-sequence: unsupported result type X`).

Note that the `LispMacroExpander` sites throw at MACROEXPANSION time. Making
them signal a condition is only half the fix on the interpreter: the expansion
must also happen inside the dynamic extent of the enclosing `handler-case` (it
does today, since the interpreter expands per eval), and on the compile paths a
macroexpansion-time `program-error` is a compile-time diagnostic, not a runtime
condition -- that asymmetry is CL-conformant but must be written down.

## Why it is worth doing

Beyond the obvious ("an uncatchable error is not an error"), it is the
second-largest lever on the ANSI report. The suite tests these cases with

```lisp
(deftest remove.error.4 (signals-error (remove 'a nil 'bad t) program-error) t)
```

i.e. `signals-error` is a `handler-case`. rontolisp already REJECTS the call --
it just rejects it in a way the test cannot observe, so a test that would PASS
today is instead recorded as a lost top-level form. The two top rows of the
committed `results/interpreter.md` reason table are exactly this:

| count | reason |
|---:|---|
| 370 | `IllegalArgumentException: X expects keyword arguments :X, got: :X` |
| 299 | `IllegalArgumentException: X expects keyword arguments :test/:test-not/:key, got: :X` |

A second population hides behind the same throw and is a REAL gap rather than a
free pass: `(remove 'a '(a b c a d) :bad t :allow-other-keys t)` must RETURN the
filtered list, because `:allow-other-keys t` makes an unknown key legal. That
check has to land in the same pass as the condition class.

## How to do it

1. Route every argument-shape rejection through
   `LispEvalException.ofClass(...)`, with `PROGRAM-ERROR` for shape/arity/keyword
   and `TYPE-ERROR` for a wrong-typed argument. `PROGRAM-ERROR` needs a
   `ClosRegistry` constant beside `TYPE_ERROR_CLASS_NAME` /
   `ARITHMETIC_ERROR_CLASS_NAME` and a `seed(...)` row (`(error 'program-error)`
   already resolves, so the class exists; the constant and the throw sites do
   not).
2. Honour `:allow-other-keys t` (and the `:allow-other-keys` appearing anywhere
   in the keyword list, per CLHS 3.4.1.4) before rejecting an unknown key.
3. Same behavior on the JVM and both WASM backends -- `.kb/error-handling.md`
   is the file that says so, and it names the pinning test.
4. Re-run `ansi-test/measure.sh`. This item only becomes VISIBLE in the report
   once `.todo/681` lands: until the driver counts these as tests, the win shows
   up as fewer lost forms rather than as more passes.

Found while re-evaluating the ANSI measurement method (2026-09-03). Siblings:
`.todo/679` (quoted `pi` reads as a double) and `.todo/681` (driver accounting).
