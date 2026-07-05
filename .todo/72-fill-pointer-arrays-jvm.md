# 72: Fill-pointer arrays on the JVM backend

Split from `.todo/71`. The interpreter already has fill-pointer / `:adjustable`
support (`.kb/adjustable-arrays.md`); this ports the fill-pointer sub-step to the
JVM compiler.

## Scope

Compile-path support for: `make-array :fill-pointer/:adjustable`,
`fill-pointer`(+setf `%set-fill-pointer`), `array-has-fill-pointer-p`,
`adjustable-array-p`, `array-element-type`, `vector-push`, `vector-pop`,
`vector-push-extend`. `length` and the `#(...)` printer must clamp to the fill
pointer.

## Design (from `.kb/adjustable-arrays.md`)

Today a JVM array is a `java.util.ArrayList`: slot 0 = `Object[]` of `Long`
dims, slots `1..` = data (`JvmArrayRuntimeBuilder`). Wrap slot 0 in a 3-element
header `Object[]{ dimsInner, fillPointer(Long|null), adjustable(Boolean|null) }`
so the data offset (`1 + flat`) is untouched:

1. `JvmArrayRuntimeBuilder._arrayMake` -- build the wrapper; grow the signature
   to `(dims, init, fillPointer, adjustable)`.
2. `JvmQuoteCompiler.compileQuotedArray` -- the OTHER producer of the header
   (`#(...)` literals; always fill-pointer=null) must build the wrapper too.
3. Readers that treat slot 0 as dims gain one `aaload`: `emitFlat2`,
   `emitFlatN`, `_arrayDims`, `buildToString` (both prin1/princ variants).
4. `buildToString` + `JvmLengthRuntimeBuilder` clamp the element count to the
   fill pointer.
5. New static helpers `_fillPointer`/`_setFillPointer`/`_arrayHasFillPointer`/
   `_adjustableArrayP`/`_vectorPush`/`_vectorPop`/`_vectorPushExtend`
   (push-extend grows the ArrayList + the inner dims Object[]); wire cases in
   `JvmArrayCompiler` + `JvmExprCompiler.compileCons`.
6. `BuiltinFunctionWrappers` entry per name so `#'vector-push` etc. work.
7. `--optimize` (`JvmClassShaker`): keep the new helpers reachable when used.
8. Watch `JvmArraypCompiler` / `JvmEvalRuntimeBuilder` -- any other slot-0
   reader.

## Acceptance

`JvmLispCompilerTest` cases mirroring the interpreter tests; cross-backend
output matches the interpreter. Then ci-spec + native E2E once WASM lands too.
