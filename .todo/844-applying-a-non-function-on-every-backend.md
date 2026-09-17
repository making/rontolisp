# Applying a non-function: one report on every backend

Difficulty: Medium

Found while working `.todo/836` (2026-09-17). A value that is not a function, applied
through `funcall` -- what a Scheme variable call lowers to, `(define h 3) (h 1)` ->
`(FUNCALL |h| 1)` -- fails four different ways:

| backend | report |
|---|---|
| interpreter | `Unhandled condition: Not a function: 3` (a symbol value: `The function #f is undefined`) |
| JVM | `Unhandled condition: class java.lang.Long cannot be cast to class [Ljava.lang.Object;` plus a Java stack trace |
| wasm, component | `wasm trap: wasm 'unreachable' instruction executed`, exit 134 |

`.todo/836` gave the Scheme INTERPRETER REPL its own wording through
`eval/LispApplyException` (`#f is not a procedure; operands: (2 3)`), which carries the
value and the arguments. Still open:

1. The compiled backends signal nothing a program or a reader can use. Decide the
   condition (CL: `type-error` for a non-designator, `undefined-function` for a symbol) and
   raise it on the JVM and both wasm backends, with a cross-backend case pinning the
   message. Measure the cost on a `funcall`-heavy loop first: the check belongs where the
   backends already dispatch on the callee's representation, not in front of every call.
2. A Scheme FILE on the interpreter still reports the Common Lisp wording
   (`RontoLispCli.failureLine` knows no language). Once 1 settles the message, make file
   mode and the REPL say the same thing.

`.kb/scheme-frontend.md` ("A session", applying a non-procedure) records the current state.
