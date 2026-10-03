# c08. On the JVM a lambda's tail call through a value keeps a frame

Difficulty: High

Measured 2026-10-03 (exec jar with the tail groups), compiled output run as `java Prog` on
the default 16 MiB worker:

- `(let ((f nil)) (setq f (lambda (n) (if (= n 0) :done (funcall f (- n 1))))) (funcall f
  1000000))`: `StackOverflowError`; the interpreter and wasm answer `:DONE`.
- A `labels` function calling itself or a sibling of its form jumps, and a `defun`'s tail
  through a value bounces (`.kb/jvm-self-tail-calls.md`, `JvmTailBounce`); a lambda's tail
  through a value -- a closure held in a variable, a continuation, a state machine of
  closures in a table -- is neither: two frames a call (the lambda and `_invoke_N`).

## Plan

- Extend the bounce to lambda bodies (`Ctx.tailBounce` for a lambda), the candidate the
  mutual tail call item left once tail groups covered `labels`. The obstacle:
  `hasTrampoline` is decided from the defuns before Pass 2, while a lambda is found during
  it, after its expansion; a lambda that bounces in a class without the trampoline needs a
  retry like `GateUnderpredicted`, or a scan of every lambda body before Pass 2.
- Measure what the unwrap at every dispatcher call site costs a program that never bounces
  (bench-report, size-report, the examples) before choosing the gate.

## Pin

- `ci-spec.yaml` (all four backends): the closure self-loop above 1,000,000 deep, and two
  closures in variables calling each other in tail position.
