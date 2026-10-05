# d26. `subseq` with a non-integer bound is not the `type-error` SBCL signals on the compile paths

Difficulty: Medium

`(subseq "hello" "a" 3)` and `(subseq (list 1 2 3) "a" 3)` (the bound read at run time):

| backend | answer |
|---|---|
| SBCL | `type-error`, datum `"a"`, expected `(MOD 4611686018427387901)` |
| interpreter | `simple-error` `SUBSEQ expects an integer index, got: "a"` |
| JVM | `type-error` with a nil datum and expected type (`the value is not of the expected type`) |
| wasm P1, component | `wasm trap: cast failure` (uncatchable) |

The bounded string operators (`write-string`, `write-line`, `string-upcase` family) lower
onto `subseq` and inherit all four answers for a non-integer `:start` / `:end`.

Give the bound an operand check like the other index operands (`AREF`'s
`OperandTypes` / `WasmOperandTypes` path): the interpreter's `requireIndex`, the JVM
unbox in `JvmSubseqCompiler.unboxIndex` / `unboxEnd`, and the wasm `castI31GetS` sites in
`WasmSubseqCompiler` / `WasmStringRuntimeBuilder`, signalling a `type-error` of datum the
value and expected type `(INTEGER 0 N)` (the class and datum are what SBCL's answer shares).
Measure the size cost on the EH modules first (`.kb/subseq-runtime.md` records the byte
budget of the bounds report).
