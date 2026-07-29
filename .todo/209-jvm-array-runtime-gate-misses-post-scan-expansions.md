# The JVM array-runtime gate is a SOURCE scan, so post-scan expansions escape it

Found 2026-07-29 while closing `.todo/208`. `JvmLispCompiler.programUsesAnyArrayOp`
decides whether `JvmArrayRuntimeBuilder`'s helpers (`_arrayMake`, `_aset1`,
`_rmGet`/`_rmSet`, `_strv`, ...) are emitted at all, and it decides it by
scanning the SOURCE program for a list of operator names. Several lowerings
introduce an array primitive AFTER that scan has run, during `compileExpr`.
When one of those is the only array use in the program, the compiler emits an
`invokestatic` to a method that was never generated, and the program dies at
run time -- JVM method resolution is lazy, so it survives verification and
fails only if that branch is actually taken:

```lisp
(let ((s "abc")) (setf (elt s 0) #\z) (print s))
```

```console
$ rontolisp elt.lisp -o Elt.class && java Elt
Exception in thread "main" java.lang.NoSuchMethodError:
  'java.lang.Object Elt._aset1(java.lang.Object, java.lang.Object, java.lang.Object)'
```

The interpreter prints `"zbc"`; WASM prints `"zbc"` (it emits the array
builtins inline, so it has no gate to miss). The gate's own comment names the
mechanism -- "vector/svref/coerce/... expand into make-array/aref/%aset ...
during compileExpr, after this scan runs, so the derived names gate the helpers
too" -- and the list is maintained by hand, so it is right up to whichever
lowering was added last without a matching entry.

## Two entries were missing and were added in the .todo/208 pass

- `make-string` -- lowers to `(make-array n :element-type 'character ...)`.
- `make-sequence` -- `(make-sequence 'vector n)` lowers to `make-array`,
  `(make-sequence 'string n)` to `make-string`.

Both are now in `programUsesAnyArrayOp` on the JVM and on WASM (WASM only
needs them for the wrapper-group exclusion). That closes those two, and leaves
the mechanism intact.

## What is still open

`(setf (elt seq i) v)` is the one confirmed hole left: `LispMacroExpander.expandSetf`'s
`ELT` branch yields `(%aset seq i v)`, and neither `SETF` nor `ELT` is a gate name
(adding `ELT` outright would pull the whole array runtime into every program
that merely READS `(elt list i)`, which is most of them). A LIST target happens to
survive because the runtime `consp` dispatch never reaches the `_aset1` call, so
only a string / vector target fails -- which is why no test caught it.

That is one instance found by inspection, not a survey. The point of this item
is the mechanism, not the instance.

## Goal

The gate stops being a hand-maintained name list that can silently disagree
with the lowerings. Options, in the order they are worth trying:

- **Scan what is actually compiled.** The lowerings are pure AST rewrites in
  `LispMacroExpander`; if the gate scan ran over the expanded program (or the
  emission were driven by what `compileExpr` actually referenced, e.g. by
  collecting requested helper names during a first pass and emitting the union
  afterwards) the list would not exist at all. `Ctx` already threads
  `usesArrays` into the per-site normalizers, so the ordering is the whole
  difficulty: emission currently happens before the bodies are compiled.
- **Emit lazily.** Record every `invokeHelper` target while compiling method
  bodies, then generate exactly that set. This is the same shape as the WASM
  backend's "emit inline" answer, and it makes the gate a consequence rather
  than a prediction.
- **Failing both, make the list checkable**: a test that compiles a minimal
  program per lowering-that-introduces-an-array-primitive and RUNS it, so a new
  lowering without a gate entry fails in CI rather than in a user's program.

The same question applies to the sibling gates built the same way
(`programUsesAnyHashOp`, `usesSeqString`, the reader/parse-integer wrapper
exclusions) -- they are smaller surfaces but the failure mode is identical.

## Acceptance

- `(let ((s "abc")) (setf (elt s 0) #\z) (print s))` prints `"zbc"` on all four
  backends, pinned by a ci-spec case.
- A new lowering that introduces an array primitive after the scan cannot ship
  without either being covered automatically or failing a test.
- `.kb/adjustable-arrays.md` records which answer was taken and why (its
  "The JVM array gate now includes `make-string`" bullet is the current state).

Code: `JvmLispCompiler.programUsesAnyArrayOp` (~2176) and its `usesArrays`
consumer (~742), `WasmLispCompiler.programUsesAnyArrayOp` (~4428),
`LispMacroExpander.expandSetf`'s `ELT` branch (~3145),
`JvmArrayCompiler.emitStrvNormalize`.
