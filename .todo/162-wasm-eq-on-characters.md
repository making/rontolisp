# WASM: (eq (code-char cp) (code-char cp)) returns NIL

Every backend now agrees on eql / equalp for characters ((eql #\A #\A) is T
everywhere), but the WASM backends return NIL for eq on two separately
allocated CHARACTERs where the interpreter and JVM compile path both return T:

    (eq #\A #\A)                          ; interp/JVM T, WASM NIL
    (eq (code-char 65) #\A)               ; interp/JVM T, WASM NIL
    (eq (code-char 128512) (code-char 128512))  ; interp/JVM T, WASM NIL

Common Lisp spec: "an implementation is permitted to make eq return true when
used on characters where char= would return true; conforming code cannot
rely on it." So both answers are conforming, but the cross-backend
divergence is a real byte-identical-output gap.

## Why the divergence

- **Interpreter** -- `LispChar` is a record whose derived `equals` is value
  based; the runtime `eq` walks through `equals` for `LispChar` operands.
- **JVM compile path** -- CHARACTER is a length-1 `int[]{cp}`. Todo 153's
  `_eqv` (the shared value-equality helper `eq` delegates to) now has an
  early-branch that compares two int[] operands by their sole slot,
  restoring the T that the pre-widening `Character.valueOf(char)` cache
  produced for BMP code units.
- **WASM (both backends)** -- CHARACTER is `TYPE_CHAR` (struct). Two
  `struct.new` allocations produce distinct GC references, so `ref.eq`
  (which drives `_isEq`) returns 0.

## Plan

Extend the WASM `_isEq` (and its private helper `WasmEvalRuntimeBuilder`
equivalent) to compare two TYPE_CHAR operands by their code-point field:

    if (ref.test $type_char a && ref.test $type_char b)
        return struct.get $type_char 0 a == struct.get $type_char 0 b

This mirrors the JVM's early int[] branch and matches the interpreter's
value-based equals. All three other _isEq operand shapes (i31, TYPE_STRING
symbols, structs, floats, ...) stay unchanged.

Alternative -- runtime intern of TYPE_CHAR values (a single canonical
struct per code point) would achieve the same ref.eq behavior without
changing `_isEq`; the branch is simpler and lifetime-neutral, so prefer it.

## Verification

- New ci-spec case pinning `(eq #\A #\A)` / `(eq (code-char cp) (code-char
  cp))` -> T on every backend, plus `(eq (code-char 128512) #\A)` -> NIL.
- `WasmLispCompilerIntegrationTest` per-mode (default, --optimize,
  --dynamic, --component, --simd).
