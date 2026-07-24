# Compile path: `return-from` across a lambda boundary is lambda-local (should be a non-local exit)

**Status:** open, unstarted. A **known compile-path deviation from CL**, currently
*pinned* (not hidden): `JvmLispCompilerTest.compileAndRunReturnFromInsideLambdaStaysLambdaLocal`
asserts the wrong-but-documented behavior, and assoc-utils' `alistp` is the real-world
victim. Solvable — this is the plan to close it.

## The deviation

`return-from` (and, symmetrically, a non-lexical `go`) is compiled as a **lexical**
goto/br target within a single compiled function (`.kb/do-return-block.md`, "The remaining
deviation from CL"). When the `return-from` sits inside a **lambda** that becomes a
*separately compiled method*, a goto/br cannot cross the method boundary, so it falls back
to the `%fn-block` and exits *that lambda*, not the outer defun.

```lisp
(defun probe (xs)
  (mapcar #'(lambda (x) (if (evenp x) (return-from probe :even) x)) xs))
(probe '(1 2 3))
;; interpreter (correct): :EVEN     -- return-from exits PROBE
;; compile paths (wrong): (1 :EVEN 3) -- exits only the lambda
```

The concrete user-visible casualty is assoc-utils' `alistp`, whose body does
`(mapl (lambda (tree) (unless ... (return-from alistp nil))) value)`. On the compile paths
the `return-from` is lambda-local, so `mapl` runs to completion and `alistp` reports `t`
for a value a real ASDF host would reject. The interpreter is correct (it throws
`BlockReturnSignal`, caught at the dynamically-enclosing `(block alistp ...)`).

Same root cause, same limitation: a **non-lexical `go`** (a `go` inside a lambda targeting
an enclosing `tagbody`) — `.kb/do-return-block.md` lines 85–87. Fold it into this work or
list it as an explicit follow-up, but don't fix `return-from` in a way that forecloses it.

## Why it is solvable

The interpreter already does the right thing with a **thrown signal** unwinding the stack to
the establishing block. The compile paths have every piece needed to do the same for the
cross-lambda case, without giving up the lexical fast path for the common same-function case:

- **JVM** has full exception machinery already in use for `handler-case`/`unwind-protect`
  (`OperandStack`, `StackMapAugmenter`, the escaped-cleanup/spill unwind in
  `JvmReturnCompiler.emitExit`). A cross-lambda `return-from` becomes: establishing block
  installs a `try/catch` for a dedicated non-local-exit throwable keyed by a **dynamic
  block-instance id**; the lambda closes over that id and `throw`s `(id, value)`; the catch
  matches on id and yields the value as the block's result.
- **WASM-GC** has wasm EH (`-W exceptions=y`) from the `handler-case` work (todo-129). Same
  shape: `throw`/`catch` a dedicated tag carrying `(id, value)`; escaped `unwind-protect`
  cleanups must run on the unwind path (they already inline for the lexical `return-from`).

The dynamic block-instance id (a fresh value per activation, closed over by the lambda) is
what makes it correct under recursion and lets an exit-after-block-returned surface as an
error instead of hitting the wrong frame — mirroring CL's dynamic extent.

## Design tensions to settle first

1. **Detection.** `containsReturnFrom` deliberately stops at nested `lambda`/`defun`
   boundaries. The enclosing function must learn that one of its blocks (`%fn-block`, a user
   `block`, or a `tagbody`) is targeted by a `return-from`/`go` from *inside* a lambda it
   builds, so it installs the catch + allocates the id. This is a new escape-analysis pass
   over the lambda bodies at compile time.
2. **Lexical fast path stays.** Only a target that actually crosses a compiled-function
   boundary should pay the EH cost; the same-function `return-from`/`go` must keep compiling
   to the current goto/br. Deny-by-default: choose EH only when detection proves a crossing.
3. **WASM byte-identity / flags.** EH mode is not byte-identical to the non-EH output and
   needs `-W exceptions=y` at run time. Gate it exactly like `handler-case` does today, so a
   program that does NOT use a cross-lambda exit stays byte-identical and flag-free. Update
   the "Verifying Output Manually" flag guidance in `CLAUDE.md` / `.kb` if the trigger set
   for EH mode widens.
4. **Cleanup interplay.** The throw must run escaped `unwind-protect` cleanups and restore
   `handler-case` spills on the way out (JVM: the existing catch/finally already do; WASM:
   the inline-cleanup strategy and its "throw from an inlined cleanup can re-enter its own
   handler" lite limit apply).
5. **Scope.** `--no-gc` has no `return-from` at all (never runs `desugarProgram`) — out of
   scope, keep it that way. The interpreter is already correct — do not touch it.

## Definition of done

- `(defun probe ...)` above returns `:EVEN` on the interpreter, JVM, WASM P1, and WASM
  component — all four backends agree with CL.
- **Rewrite the pinning test, don't delete it.** `compileAndRunReturnFromInsideLambdaStaysLambdaLocal`
  currently *asserts the bug*; flip it to assert the CL-correct result and rename it. (House
  rule: turning a pinned-wrong test green is a first-class finding, not a silent deletion.)
- assoc-utils' `alistp` reports `nil` for a non-alist on all four backends; extend
  `AssocUtilsE2eTest` to actually exercise `alistp` on a non-alist (it is skipped today).
- A focused regression: a cross-lambda `return-from` value returned mid-expression, plus a
  recursive case that proves the block-instance id targets the right frame.
- Non-lexical `go` either fixed the same way (preferred) or captured as an explicit,
  named follow-up with the reason it was split out.
- Four-backend + native `CiSpecE2eTest`. Add a ci-spec case for the cross-lambda exit.
- Update `.kb/do-return-block.md`: retire the "remaining deviation from CL" clause (or
  narrow it to whatever genuinely stays lexical-only), and record the EH-based crossing +
  its WASM flag trigger. Update the assoc-utils guide (en+ja) + `.kb/asdf.md` if the
  `alistp` limitation is fully lifted.
