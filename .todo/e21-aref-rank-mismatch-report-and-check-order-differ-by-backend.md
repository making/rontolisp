# e21. aref's rank-mismatch report and check order differ between the interpreter and the compiled backends

Difficulty: Medium

Measured 2026-10-07 (interpreter, JVM, WASM P1 / component in EH mode), each call under
`handler-case`, printing `(type-of e)` and the report:

| call | interpreter | JVM, WASM (every level) |
| --- | --- | --- |
| `(aref (make-array '(2 2)) 1)` | `PROGRAM-ERROR aref: expected 2 subscripts, got 1` | `SIMPLE-ERROR` with the same text |
| `(aref (make-array 3 :element-type '(unsigned-byte 8)) 0 0)` | `SIMPLE-ERROR AREF: a packed integer vector is rank 1` | `aref: expected 1 subscripts, got 2` |
| `(aref "abc" 0 0)` | `TYPE-ERROR AREF: The value "abc" is not of type ARRAY` | `aref: expected 1 subscripts, got 2` |
| `(aref (make-array '(2 2)) nil)` | `AREF: The value NIL is not of type INTEGER` | `aref: expected 2 subscripts, got 1` |
| `(aref (make-array '(2 2)) (+ nil 1))` | `+: The value NIL is not of type NUMBER` | `aref: expected 2 subscripts, got 1` |

- The interpreter evaluates and type-checks every subscript, then checks the array and its rank
  (`Environment` `AREF`: `subscriptValues`, then `requireArray`/`flatIndex`). The ordinary compiled
  emission checks the array and its rank before it evaluates the subscripts
  (`JvmArrayCompiler.compileAref`/`compileAset`, `WasmArrayCompiler.compileAref`/`compileAset`,
  `_arr_check_rank` first). A fused integer tree's aref already follows the interpreter, so the
  last two rows differ between the default and size levels on the JVM and WASM.
- The `#'aref` wrapper (`BuiltinFunctionWrappers`) signals a `simple-error` too.
- SBCL 2.2.9 is no oracle: it reads a rank-2 array row-major for one subscript and signals a
  `type-error` expecting `(ARRAY * (* *))` for more (`.kb/error-handling.md`).

Plan: decide the condition class and the texts for the packed-vector and string rows (one
contract, `.kb/error-handling.md`), then move the compiled `aref`/`%aset` rank check after the
subscripts on both backends, pinned on all four with a shared fixture.
