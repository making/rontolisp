# 523. The array gate is forced on by the injected wrappers too, on every compile

Difficulty: Low (the shape of the fix is `.todo/519`'s, already landed one gate over)

Sibling of `.todo/519`, which fixed the same defect on the EVAL gate.

## The defect

`JvmLispCompiler` decides `usesArrays` before Pass 1; the finished class is then
scanned for own-class calls it never declared, and an unresolved one throws
`GateUnderpredicted` and re-runs the whole compile with `GROUP_ARRAYS` forced on.
The trigger is the injected built-in wrappers, exactly as it was for `eval`:

```
$ echo '(let ((s 0)) (print s))' | ... -o T.class   # -Drontolisp.debug.gate=true
[gate] underpredicted=[arrays] unresolved=[
  _aset1(...)      (called from FILL, COERCE, VECTOR, READ-SEQUENCE),
  _charVecMake(...) (called from FILL),
  _arrayMake(...)  (called from COERCE, VECTOR),
  _aref1(...)      (called from SVREF, WRITE-SEQUENCE),
  _arrayDims(...)  (called from ARRAY-RANK, ARRAY-DIMENSION,
                    ARRAY-TOTAL-SIZE, ARRAY-ROW-MAJOR-INDEX)]
```

`BuiltinFunctionWrappers.ARRAY_FILL_POINTER_FUNCTIONS` already gates the wrappers
whose bodies call the array runtime -- but it does not name these ten, so every
program pays a second compile pass, and any program whose class keeps the
dispatch ladder alive keeps the array runtime with it. Measured 2026-08-25:
`(print (mapcar (lambda (x) (* x x)) '(1 2 3)))` is 13,654 B, of which the array
runtime (`_arrayMake`/`_aref1`/`_aset1`/`%SEQ-TO-VECTOR`/...) is most; the same
program without the ladder is 6,710 B.

## What to build

Add the missing names to the array-gated wrapper set (or a set beside it), the
way `.todo/519` added `APPLY_USING_FUNCTIONS` to the eval-gated one, so the
wrapper and the runtime it calls are decided by one scan. The list above is what
one trivial program surfaces; find the rest by compiling with
`-Drontolisp.debug.gate=true` over `examples/` and `ci-spec.yaml` and collecting
every `arrays` report.

`GateUnderpredicted` must stay -- this removes a spurious trigger, not the
mechanism.

## Acceptance

- No program in `examples/` or `ci-spec.yaml` reports `underpredicted=[arrays]`
  unless its own source names an array operator.
- `(print (mapcar (lambda (x) (* x x)) '(1 2 3)))` declares no `_arrayMake` /
  `_aref1` / `_aset1`.
- A program that DOES use an array still gets the full runtime, and
  `.kb/adjustable-arrays.md`'s pinning tests stay green.
- `ci-spec.yaml` and `ExamplesE2eTest` byte-identical on all four backends.
