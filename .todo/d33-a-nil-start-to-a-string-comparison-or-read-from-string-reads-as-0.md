# d33. A nil `:start` to a string comparison or `read-from-string` reads as 0

Difficulty: Medium

SBCL signals a `type-error` (datum `NIL`) for each call below; a nil `:end` is the length.
Measured with the nil read at run time:

| call | SBCL | interpreter | JVM / P1 / component |
|---|---|---|---|
| `(string= "abc" "abc" :start1 nil)`, `string-equal` | `type-error` | `T` | `type-error` |
| `(string< "abc" "abd" :start2 nil)` (the `%string-compare` family) | `type-error` | `2` | `2` |
| `(funcall #'string= "abc" "abc" :start1 nil)` | `type-error` | `T` | `T` |
| `(read-from-string "12" t nil :start nil)` | `type-error` | `12` | `12` |
| `(nstring-upcase (copy-seq "abc") :start nil)` | `type-error` | other error | other error |

Causes: `Environment.boundedStringArg` skips a nil bound; `%string-compare` defaults with
`(or start1 0)` / `(or start2 0)`; `BuiltinFunctionWrappers.stringEquality` reads `:start1` /
`:start2` with `getfKwOr` (the sequence operators moved to `getfKwDefault`). The sequence
operators' fix is the model (`.kb/sequence-bounding-keywords.md`, "A nil `:start` is no bound";
`SequenceBoundsFixture.NIL_START_PROGRAM`): one fixture and a ci-spec case on all four backends,
byte identity for calls with no `:start`, sizes of an affected program. `read-from-string` sits
in the reader runtimes (`JvmReadRuntimeBuilder`, `WasmReadRuntimeBuilder`, the interpreter
reader).
