# wasm: a closure or packed-array key of an `eq`/`eql` table still shares bucket 0

Difficulty: Low

Left over from `.todo/839` (2026-09-17). The identity-hash slot (`.kb/hash-tables.md`,
"The identity-hash slot") covers `TYPE_CONS`, `TYPE_CELL` and `TYPE_INSTANCE`; `_hash`
still answers 0 for a `TYPE_CLOSURE` and for a packed array (`TYPE_FARRAY`, the packed
integer vectors), so an `eq` table keyed by functions or by packed buffers is an n-entry
chain, as every aggregate key was before the slot. Nothing in the corpora keys by either,
which is why it was left.

## To do

1. `TYPE_CLOSURE {i32 funcId, eqref env}` can carry the slot the way the cons does (free
   on wasmtime, `.kb/wasm-gc-object-size.md`): an `emitNewClosure` in `WasmEmitHelper`
   that every closure site goes through, and a fourth arm in
   `WasmIdentityHashRuntimeBuilder`.
2. A packed array's storage is a wasm `array`, which has no field to add. `TYPE_FARRAY`
   is a struct and can carry the slot; the packed integer vectors are bare arrays and
   cannot. Decide whether they stay in bucket 0 (then say so in the kb) or get a struct
   wrapper, and what that costs `--simd`'s `TYPE_VBLOCK` path.
3. Pin with the shape of
   `WasmLispCompilerIntegrationTest.compileEqHashTableWithManyAggregateKeysStaysHashed`:
   100,000 closure keys and packed-vector keys in one `eq` table, a regression being a
   hang.
