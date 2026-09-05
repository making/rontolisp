# Every emitted wasm-GC type must be `sub final` (0x4F)

**Invariant**: every type the WASM backend emits — rec-group structs/arrays via
`RecTypeDef.addSubFinal*` and the plain `0x60` function types — is FINAL; no emitted type
declares or permits subtypes.

- `RecTypeDef` writes the bare `comptype`, which the format defines as exactly the `sub final`
  shorthand (`subtype ::= 0x50 x* comptype | 0x4F x* comptype | comptype`), 2 bytes/type cheaper.
  0x4F = `sub final`, 0x50 = open `sub`. The explicit `0x4F` form must return the moment an
  emitted type needs a supertype; the shorthand cannot express one.
- Why finality is load-bearing: for `ref.cast`/`ref.test`/`call_indirect` on a concrete type
  Cranelift emits an inline type-id equality (`FuncEnvironment::is_subtype`). On INEQUALITY,
  wasmtime <= 46 falls back to the `is_subtype` host libcall under the engine-global
  `RwLock<TypeRegistryInner>` (`TypeRegistry::is_subtype_slow`), so concurrent serving degrades
  ~quadratically (46.0.1: 9,016 rps at c=1 -> 566 at c=16; 47.0.3: 10,042 -> 37,996).
  wasmtime 47+ (PR #13572) skips the libcall for a FINAL target, so no emitted check reaches it.
- Consequence: wasmtime floor for RUNNING a component stays 46, but concurrent load says
  **47+** (http-handler guide, `.github/docker/wasmtime/Dockerfile`). Declaring a supertype
  hierarchy would put every cast to the parent back on the libcall path even on 47+.
- Cross-refs: `.kb/wasm-shortest-encoding.md` (rule 3),
  `.kb/concurrent-served-requests.md` (interpreter/JVM side).

## Tests
`RecTypeDefTest` pins the emitted tag against the spec constants (not the enum) and that it is
never `0x50` — it once was, when `am.ik.wasm.Type` had `SUB`/`SUB_FINAL` swapped.
