# d25. A negative `end` given to a compiled `subseq` is read as "omitted"

Difficulty: Medium

`(subseq "hello" 0 -1)` with `-1` computed at run time refuses in the interpreter
(`SUBSEQ: invalid bounds 0, -1 for string of length 5`, a `type-error`, as SBCL does) but
answers the whole string on the JVM, and on wasm for a literal string (a built string
refuses). `(write-string "hello" s :end -1)` lowers onto it and writes `hello` on the three
compiled backends.

Cause: the JVM lanes encode "end omitted" as the int `-1`
(`JvmSubseqCompiler.unboxEnd` / `emitResolveEnd`, `_subseqEnd`, `_subseqCore`,
`_subseqCv`), so a GIVEN negative end is indistinguishable from nil. Use a sentinel no
index can take (`Integer.MIN_VALUE`) and let `emitBoundsTest` see the real negative. Then
sweep the wasm literal-string lane (`_subseq`'s string branch) for the same read.

Pin it on every representation in `SubseqBoundsFixture` (a `-1` end beside the existing
start probes) and in ci-spec `subseq-refuses-a-bad-range-in-every-representation`
(`(ssb-probe s 0 -1)`). `.kb/subseq-runtime.md`, "Bounded string operators", lists it.
