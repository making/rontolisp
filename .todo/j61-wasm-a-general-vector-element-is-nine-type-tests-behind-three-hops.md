# j61. wasm: a general vector's element is nine type tests behind three hops; what a list element still costs

Difficulty: High

`.kb/adjustable-arrays.md`, "What an element access costs", and
`.kb/starlark-frontend.md`, "Against CPython": after j55 a Starlark program of list reads
and writes is 2.1 s on wasm where it was 4.8 and CPython is 1.2. What was measured there
(wasmtime 49): a type test that passes is about 1 ns, a call 2-5 ns with the references
live across it, and the guest profiler misattributes both.

A general array is `cell -> header cons (dims . (meta . data)) -> buckets`, every field
`(ref null eq)`, every local `(ref null eq)`: the inline arm of a rank-1 access is nine
tests (cell, header twice, dims twice, `dims[0]`, the subscript, `(meta . data)`, the
data), about 110 B a site, and zlib pays the bytes for nothing.

What a Starlark `xs[i]` pays now, per element: the call of `%starlark-index`, a boxed
read of the loop variable (`_ub_read`, a call), the list test and the items read (five
tests), `length` of the items (`_seq_len`, a call and seven tests), the bounds
comparison, the arm's nine tests.

## Do

- The array header as a struct of typed fields (the dims and the data as `(ref
  $buckets)`, the fill pointer and the offset as i32), or the cell holding the data
  directly for a rank-1 array with storage of its own: a rank-1 read is then the cast to
  it, the subscript's i31 test, the bound and `array.get`. Every array emitter and runtime
  body reads the header (`WasmArrayCompiler`, `WasmArrayRuntimeBuilder`, the string
  runtime's character vectors, the printer); measure the arm's size and time first on a
  hand-edited module.
- The instance's slots field as `(ref $buckets)` (`TYPE_INSTANCE` field 1): one cast less
  per slot read and store.
- `length` of a general vector at a site that knows it is one (a declaration, the
  structure slot's `:type`): the fill pointer or `dims[0]` inline, as a packed integer
  vector's is.
- The Starlark loop variable: `for i in range(n)` copies the counter into a variable the
  body may assign, a dual local, and every `xs[i]` boxes it through `_ub_read`
  (`StarlarkLowering`, the counted loop; `.kb/wasm-unboxed-locals.md`).
- Re-run `time.sh` over the eleven programs and record the table in
  `.kb/starlark-frontend.md`; the goal there is each of them under CPython on wasm.
