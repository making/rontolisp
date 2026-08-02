# A user `defmethod`/`defun` on a BUILT-IN name: the interpreter loses the built-in, the compile paths lose the definition

Difficulty: 中〜大 overall, but the two halves are NOT the same size and the
first is worth shipping on its own -- see "Fix direction" for the mechanics.

- **Half 1, interpreter: 小.** ~50-80 lines. Telling a built-in from a user
  definition is a type test (`LispFunction` = Java-backed builtin, `LispLambda`
  = user `defun`), and the rest is a `generateDispatcher` fallback overload
  threaded into its TWO body builders. One real trap: the dispatcher is
  regenerated on EVERY `defmethod`, so the stash of the built-in must happen
  exactly once or the second method captures the dispatcher itself and the
  fall-through recurses forever. Plus variadic forwarding, so `close`'s
  `&key abort` reaches the built-in through the dispatcher's `%gf-rest`.
- **Half 2, compile paths: 中〜大, and it is the bulk.** The method is known --
  `GrayStreamsLibrary.process` is the same shape -- so the cost is not the
  design. It is that the name set is COMPUTED (every registered generic whose
  name collides with a compiler-lowered built-in) rather than the fixed list
  Gray rewrites, that it needs a new whole-program walker whose rules are
  exactly where the Gray pass got it wrong twice (`.kb/gray-streams.md` records
  both), that it has to compose with `BuiltinFunctionWrappers` (a first-class
  `#'close`) and `LibraryDefunPruner`, and that `with-open-file`'s own expansion
  starts routing through the dispatcher. Then the 3-backend matrix + ci-spec.

Do NOT narrow half 2 to a whitelist of the names fast-io happens to method
(`close`, `open-stream-p`, `input-stream-p`, `output-stream-p`,
`stream-element-type`). That is smaller and it is the bypass wiring CLAUDE.md's
working principles warn about; the contract is "any built-in name".

Found while closing `.todo/236` (fast-io / circular-streams). `.todo/236` billed
"No applicable method: CLOSE on INTEGER" as a symptom of its `with-slots` bug --
it is not. It is an independent, general defect that has nothing to do with
fast-io, measured 2026-08-02 on all four backends.

## The two halves, measured

```lisp
(defclass mycls () ())
(defmethod close ((s mycls) &key abort) (declare (ignore abort)) :closed)
(print (close (make-instance 'mycls)))                       ; the user method
(with-open-file (f "bytes.bin" :element-type '(unsigned-byte 8))
  (print (read-byte f)))                                     ; the BUILT-IN
```

| | interpreter | JVM / WASM |
| --- | --- | --- |
| `(close <instance>)` -> the user method | `:CLOSED` | **ClassCastException** |
| `(close <stream handle>)` -> the built-in | **"No applicable method: CLOSE on INTEGER"** | works |

- **Interpreter**: `LispEvaluator.evalDefmethod` installs the generated
  dispatcher with `eval(LispMacroExpander.generateDispatcher(...))`, an ordinary
  `defun` of the generic's name -- which SHADOWS the built-in `LispFunction` in
  `globalEnv`. The dispatcher's fall-through is `noApplicableMethod(...)`, so
  every non-instance argument now errors. In SBCL these are generic functions
  whose standard methods stay; adding one never removes the built-in behavior.
- **Compile paths**: `(close X)` is a compiler-lowered call
  (`Jvm/WasmExprCompiler` `case LispNames.CLOSE` -> `Jvm/WasmCloseCompiler`), so
  the generated dispatcher defun is dead and the user method is silently
  ignored. Not `defmethod`-specific: a plain `(defun close (x) ...)` is ignored
  the same way (measured), which makes this the wider "a user definition of a
  built-in name" gap.

## Why it matters (severity)

`(ql:quickload "fast-io")` POISONS `close` for the whole image on the
interpreter: fast-io's `gray.lisp` defines `close` methods for its two stream
classes, so any later `with-open-file` -- anywhere in the program, on an
unrelated file -- dies with "No applicable method: CLOSE on INTEGER". The
`.todo/236` acceptance exercise had to be written around it
(`FastIoCircularStreamsE2eTest` closes no stream), and that is the shape to
watch for: a test that avoids the call rather than a fix.

The same fast-io file also methods `open-stream-p`, `input-stream-p`,
`output-stream-p` and `stream-element-type` -- every one of them a CL built-in
here.

## Fix direction

Both halves want the same statement: **a built-in whose name a program defines a
method on becomes that generic's DEFAULT METHOD.**

1. **Interpreter** (small): in `evalDefmethod` / `evalDefgeneric`, before
   installing the dispatcher, if `globalEnv.lookupFunctionOrNull(name)` is still
   a `LispFunction` (Java-backed = built-in; a user defun is a `LispLambda`),
   stash it under an internal name ONCE and generate the dispatcher with that
   name as the fall-through instead of `noApplicableMethod`. Needs a
   `generateDispatcher(name, registry, fallbackFunctionName)` overload threading
   the fallback into `simpleDispatchBody` AND `combinedDispatchBody`; the call
   must forward the dispatcher's own params (`apply` + `%gf-rest` for a variadic
   generic, so `close`'s `&key abort` tail survives).
2. **Compile paths** (the real work): the Gray-dispatch pattern is the model
   (`.kb/gray-streams.md`) -- when the program registers a generic whose name is
   a compiler-lowered built-in, emit the dispatcher under an internal name,
   rewrite the program's call sites onto it, and let the dispatcher's
   fall-through spell the ORIGINAL built-in call (which the compilers still
   lower). The walker must skip the dispatcher's own body, exactly like
   `GrayStreamsLibrary.DISPATCH_DEFUNS`.

Ordering note: half 1 alone is already a strict improvement (it makes the
interpreter agree with the compile paths for the built-in argument, and matches
SBCL); it does not create a new divergence, it shrinks the existing one to "the
compile paths ignore the user method".

## Acceptance

The table above answers identically on all four backends, pinned by a ci-spec
case, and `FastIoCircularStreamsE2eTest` regains a `with-open-file` around a
real stream after the fast-io load.
