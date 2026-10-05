# d20. `subseq`'s bounds refusal is a `simple-error` where SBCL signals a `type-error`

Difficulty: Medium

Every backend refuses a bad `subseq` range with one text
(`SUBSEQ: invalid bounds S, E for KIND of length N`, `.kb/subseq-runtime.md`, "Bounds check"),
but as a condition-less error, which `handler-case` sees as `simple-error`. SBCL signals a
`type-error` (CLHS 17.1.1: a bad bounding index designator is a type error), measured with
`(handler-case (subseq s i e) (error (c) (list (type-of c) (typep c 'type-error))))`:

| call | SBCL | rontolisp, all four backends |
|---|---|---|
| `(subseq (list 1 2 3) -1)` | `(TYPE-ERROR T)` | `(SIMPLE-ERROR NIL)` |
| `(subseq (list 1 2 3) 1 5)` | `(SB-KERNEL:BOUNDING-INDICES-BAD-ERROR T)` | `(SIMPLE-ERROR NIL)` |
| `(subseq "abc" 2 1)`, `(subseq (vector 1 2 3) 2 1)` | same as the row above | `(SIMPLE-ERROR NIL)` |

A program catching `type-error` misses the refusal. Make it a `type-error` (datum the bad
index, expected type the valid range) on the interpreter, the JVM (`JvmSubseqCompiler.emitBoundsError`)
and both wasm backends (`WasmStringRuntimeBuilder.emitSubseqBoundsThrow`) together, keeping the
text, and pin `typep ... 'type-error` in the ci-spec case
`subseq-refuses-a-bad-range-in-every-representation`.
