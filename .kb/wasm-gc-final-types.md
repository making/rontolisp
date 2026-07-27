# Every emitted wasm-GC type must be `sub final` (0x4F)

**Invariant**: every type the WASM backend emits -- the rec-group structs/arrays via
`RecTypeDef.addSubFinal*` and the plain `0x60` function types (final by spec
shorthand) -- is FINAL. No emitted type declares or permits subtypes; the module uses
no wasm-level subtyping at all.

**Why it is a performance invariant, not just hygiene**: wasmtime's Cranelift lowers
`ref.cast` / `ref.test` / the `call_indirect` signature check to a single inline
type-index equality ONLY when the target type is final
(`FuncEnvironment::is_subtype` in `crates/cranelift/src/func_environ/gc.rs`: "a final
type cannot be the supertype of any other type"). For a NON-final target it emits a
fallback `is_subtype` libcall, and that libcall's per-store cache
(`subtype_check_cache`, a bounded map in `crates/wasmtime/src/runtime/store/gc.rs`)
degrades to the engine's RwLock-protected type registry once full. The practical
effect scales with how much code is merely loaded: in the todo-188 PBKDF2 benchmark
the libcall was 20% of cycles with ironclad alone and 52% with cl-ppcre also
quickloaded, because the loaded stack's type-pair traffic evicted the hot pairs.

**The bug this file pins**: `am.ik.wasm.Type` had `SUB`/`SUB_FINAL` swapped
(`SUB_FINAL` wrote 0x50, the spec's OPEN `sub`), so every rec-group type shipped
non-final and every hot-path cast took the libcall. The spec mapping is 0x4F =
`sub final`, 0x50 = `sub`; `RecTypeDefTest` asserts the emitted opcode against the
spec constants, not against the enum, so a re-swap cannot pass.

Both browsers and wasmtime validate final and open types alike (the module declares
zero supertypes either way), so nothing but the cast lowering ever notices --
which is exactly why the swap survived until it was profiled.
