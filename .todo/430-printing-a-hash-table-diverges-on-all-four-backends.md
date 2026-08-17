# 430. Printing a hash-table diverges on all four backends

Difficulty: Medium

```lisp
(let ((h (make-hash-table :test 'equal)))
  (setf (gethash "a" h) 1)
  (princ (princ-to-string h)))
```

| | output |
| --- | --- |
| interpreter | `#<HASH-TABLE>` |
| JVM | `{"a"=[Ljava.lang.Object;@3fee733d}` |
| WASM Preview 1 | **`wasm trap: call stack exhausted`** |
| WASM component | same trap |
| SBCL, for reference | `#<HASH-TABLE :TEST EQUAL :COUNT 1 {1003FD6CC3}>` |

`print`, `princ`, `prin1`, `format ~A` and `format ~S` all behave the same way.
Three separate defects in one expression:

1. **The JVM backend leaks the host.** `LispHashTable.print()` answers
   `#<HASH-TABLE>` (`LispHashTable:103`), which is what the interpreter emits,
   but the compiled JVM path never reaches it: the value is a plain Java map by
   then, so `toString` runs and prints Java's own rendering -- container syntax,
   the raw `Object[]` bucket, and an IDENTITY HASH. That last part also breaks
   `.kb/emitted-output-determinism.md`: the same program prints different text
   on two runs.
2. **The WASM backends recurse until the stack dies.** `_print_val`
   (`WasmRuntimeBuilder`) has arms for i31, bignum, ratio, float, character,
   string, symbol, cons and the array family, and no arm for the hash-table
   struct, so it falls through to a branch that re-enters itself. The trap is
   unrecoverable -- no condition, no message, the buffered stdout of everything
   printed before it is lost too.
3. **Nobody agrees with anybody**, which is the governing-rule problem: this is
   ordinary printing, so all four must produce identical text.

## Why it matters

Found by the cl-mustache spike (`.todo/425`). Mustache's `{{.}}` prints
whatever the current section variable holds, and a nested context IS a
hash-table, so the spec suite's `sections` block prints one -- which is what
kills both WASM backends there, taking 34 unrelated assertions with them. The
same two spec cases fail on SBCL and on the interpreter (upstream's own
limitation), so they are cases we merely have to SURVIVE, not pass.

More generally: a hash-table reaching a printer is not exotic. It is what a
debug `(print x)`, an error report and a `format` of a decoded JSON object all
do.

## What to print

`#<HASH-TABLE>` is the existing interpreter answer and the one already pinned
elsewhere, so it is the cheap target: make the JVM and WASM paths reach it.
Whether to widen it toward SBCL's `:TEST`/`:COUNT` detail is a separate call --
if taken, take it on all four at once, and keep the identity hash OUT, because
`.kb/emitted-output-determinism.md` forbids run-varying text.

Check the neighbours in the same pass: whatever other value type reaches the
JVM's `toString` fallback or has no `_print_val` arm has both bugs already.

## Definition of done

Printing a hash-table produces byte-identical text on all four backends, with
no trap and no run-varying content, through `print` / `princ` / `prin1` /
`princ-to-string` / `format ~A` / `~S`. A `_print_val` arm on WASM, the
`LispHashTable.print()` route on the JVM. Pinned in `LispEvaluatorTest` /
`JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest` plus a `ci-spec.yaml`
case (which is what makes the four agree by construction), and recorded in
`.kb/hash-tables.md` beside the existing print-syntax note.
