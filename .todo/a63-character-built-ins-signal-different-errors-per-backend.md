# A non-character reaching char-code and the other character built-ins: a different error per backend

Difficulty: Medium

Measured 2026-09-27 (`(handler-case (FORM 1) (error (e) ...))`):

| form | interpreter | JVM | wasm-GC |
|---|---|---|---|
| `(char-code 1)`, `(char-upcase 1)`, `(alpha-char-p 1)`, `(digit-char-p 1)`, `(char-name 1)` | `simple-error` "CHAR-CODE expects a character, got: 1" | `type-error` "the value is not of the expected type", datum NIL (a `ClassCastException` at the unboxing's `checkcast`) | uncatchable trap (`ref.cast`) |

CL requires a `type-error` naming the datum and `CHARACTER`. The character comparisons already do
this on all four backends (`.kb/error-handling.md`, "One argument is still checked"): the
`CHARACTER`-typed rows in `compiler/OperandTypes`, the JVM's `_ckChr` under the operator's
wrapper, wasm's `_chr_code` (EH mode) and the interpreter's `OperandTypeException`.

Goal: the rest of the character built-ins (`char-code`, `char-upcase`, `char-downcase`,
`alpha-char-p`, `digit-char-p`, `upper-case-p`, `lower-case-p`, `both-case-p`, `alphanumericp`,
`char-name`, `graphic-char-p`, `standard-char-p`, `char-int`, ...) reach the same funnels and report
`OP: The value 1 is not of type CHARACTER` byte-identically -- the interpreter's
`Environment.requireChar` turned into the named `OperandTypeException`, the JVM's
`JvmEmitHelper.unboxCodePoint` sites through `_ckChr`, wasm's `WasmCharCompiler.pushCode` through
`_chr_code` in EH mode. Measure the Worker and `zlib` sizes as the comparisons' change did.
