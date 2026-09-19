# Scheme: tail calls through a procedure value

Difficulty: High

Follow-up of `.todo/897`. A tail call through a procedure value -- an argument
(`(self self (- n 1))`), a variable, `apply`, CPS continuations -- is an ordinary call.
Largest passing depth, default stacks (2026-09-19): JVM `java Prog` 1,716, wasm and
component 2,693, interpreter 15,234 (`.kb/scheme-frontend.md`, "Measurements").

Measured options (same file, "Tail-call groups"):

- A trampoline everywhere: `fib 32` about neutral on the JVM, +20-50% on wasm, 2.2x on the
  interpreter; every bounced tail call 15-20x slower. Not acceptable as a general
  mechanism.
- wasm `return_call` / `return_call_ref` (wasmtime 47 enables `tail-call`): proper tail
  calls on the two wasm targets for every call in tail position, cost not measured yet;
  the JVM has no counterpart, so the backends would differ in depth (they already do).
  Measure emitted bytes and speed, and whether a `funcall` through
  `%scheme-ensure-procedure` can be a tail call at all.
- The JVM: a larger stack for compiled output's `main` (the CLI already runs the
  interpreter on a 16 MiB worker, `.kb/interpreter-stack.md`) raises the ceiling without
  making it unbounded; it is a Common Lisp-wide change.
- The JVM's 1,716 is low because one Scheme-level call through a value is several JVM
  frames (`funcall`, `%scheme-ensure-procedure`, the closure); fewer frames per call would
  raise it for every program.
