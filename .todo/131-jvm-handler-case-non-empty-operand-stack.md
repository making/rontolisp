# JVM: `handler-case` / `ignore-errors` in argument position emits an unverifiable class

**Status:** open, unstarted. A **correctness bug**, pre-existing since `.todo/116`
(the error-handling foundation). Found 2026-07-14 while implementing `.todo/127`,
which is unrelated to it -- but which makes it easier to hit, because the settled
WIT mapping says a `result<T, E>`'s error arm signals a condition you catch with
`handler-case`.

## The bug

A `handler-case` (or `ignore-errors`) evaluated where the **operand stack is not
empty** -- i.e. in an argument position -- compiles to a class the JVM verifier
rejects. There is no compile error: the `.class` is written, and `java Prog` dies
with a `VerifyError` naming an arbitrary unrelated method.

```lisp
(defun risky () (error "boom"))
(print (list "result:" (handler-case (risky) (error (e) e "caught"))))
```

```console
$ rontolisp hc.lisp                      # interpreter
("result:" "caught")

$ rontolisp hc.lisp -o Hc.class && java Hc
Error: Unable to initialize main class Hc
Caused by: java.lang.VerifyError: Expecting a stackmap frame at branch target 7
Exception Details:
  Location: Hc._lispToString(Ljava/lang/Object;)Ljava/lang/String; @1: ifnonnull

$ rontolisp hc.lisp -o hc.wasm && wasmtime run -W gc -W exceptions=y hc.wasm
("result:" "caught")
```

## Exact scope (probed on all backends, 2026-07-14)

| form | position | interpreter | JVM | wasm-GC |
|---|---|---|---|---|
| `handler-case` | statement (empty stack) | ok | ok | ok |
| `handler-case` | `let` init | ok | ok | ok |
| `handler-case` | **argument** | ok | **VerifyError** | ok |
| `ignore-errors` | **argument** | ok | **VerifyError** | ok |
| `unwind-protect` | argument | ok | ok | ok |

So it is specific to the two **catching** forms on the **JVM** backend.
`unwind-protect` (whose compiler lays out its own protected region) is fine, and
both WASM catching paths (todo 129's `try_table`) are fine.

## Why

Entering a JVM exception handler clears the operand stack -- the handler starts
with only the thrown exception on it. Any values already on the stack when the
protected region begins are therefore gone on the exception path, so the two
control-flow edges into the merge point disagree about the stack, which is exactly
what the verifier reports. `JvmUnwindProtectCompiler` gets this right; the
`handler-case` layout does not.

Class version 50 (the lenient, StackMapTable-free verifier this whole backend
relies on, `CLAUDE.md`) does not rescue it: HotSpot's failover runs the inference
verifier, that verifier also rejects the method, and the message you see is the
*split* verifier's -- which is why it names a stackmap frame and an unrelated
method, and reads as nonsense.

## The fix

Spill the live operand stack to fresh locals before the protected region and
reload it after the merge (the shape `JvmReturnCompiler` already needs for a
non-local exit, `.kb/do-return-block.md`: "`return` ... only works where the
operand stack is empty" -- same root cause, and `return` chose to *forbid* the
position rather than spill). Two candidate outcomes:

1. **Spill and restore** -- `handler-case` works everywhere, no user-visible
   limit. Needs `JvmExprCompiler` to know the operand-stack depth at the call
   site; check whether `Ctx` already tracks it (`JvmUnwindProtectCompiler` may
   already have what is needed).
2. **Reject it at compile time** -- a clear error naming the form and the
   position, matching `return`'s existing restriction. Much cheaper, but a
   `handler-case` in an argument position is *ordinary* Lisp (the WIT `result`
   mapping produces it naturally), so this is a real language limit and would
   need documenting.

Prefer (1).

## Definition of done

- A regression test in `JvmLispCompilerTest` that COMPILES AND RUNS the repro
  above (a compile-only test would pass today -- the bad class is written without
  complaint; the test must load it).
- The same shape in a nested argument (`(f (g (handler-case ...)))`), inside a
  `mapcar` lambda, and as a non-first argument.
- `ignore-errors` covered too (it is sugar over `handler-case`, but pin it).
- Four-backend + native E2E; `.kb/error-handling.md` updated (it currently claims
  the JVM path is full).
