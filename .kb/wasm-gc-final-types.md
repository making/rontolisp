# Every emitted wasm-GC type must be `sub final` (0x4F)

**Invariant**: every type the WASM backend emits -- rec-group structs/arrays via
`RecTypeDef.addSubFinal*`, and the plain `0x60` function types (final by spec
shorthand) -- is FINAL. No emitted type declares or permits subtypes.

## Spelling
`RecTypeDef` writes the bare `comptype`, which the format defines as exactly the
`sub final` shorthand (`subtype ::= 0x50 x* comptype | 0x4F x* comptype | comptype`),
two bytes shorter per type. Spec mapping: 0x4F = `sub final`, 0x50 = open `sub`.
`RecTypeDefTest` pins the emitted tag against the spec constants (not the enum) and
that it is never `0x50` -- it once was, when `am.ik.wasm.Type` had `SUB`/`SUB_FINAL`
swapped and every rec-group type shipped non-final. The explicit `0x4F` form must
return the moment an emitted type needs a supertype; the shorthand cannot express one.

## Why finality matters: wasmtime's cast lowering
For `ref.cast`/`ref.test`/`call_indirect` against a concrete type, Cranelift emits an
inline null/i31 filter plus a type-id equality against the object header
(`FuncEnvironment::is_subtype`, `crates/cranelift/src/func_environ/gc.rs`). On
INEQUALITY:
- **wasmtime <= 46**: falls back to the `is_subtype` host libcall, taking the
  engine-global `RwLock<TypeRegistryInner>` read lock
  (`TypeRegistry::is_subtype_slow`); finality is never consulted and 45/46 have no
  per-store cache there. Every FAILING concrete `ref.test` -- the ordinary misses of
  the runtime's type-dispatch ladders -- pays libcall + global read lock. Succeeding
  checks, `(ref i31)` tests and abstract `struct`/`array`/`eq` tests were always
  inline-cheap. Since the lock is engine-global and `wasmtime serve` runs requests on
  parallel threads of one engine, CPU per request grows ~quadratically in in-flight
  requests (46.0.1: 9,016 rps at c=1 -> 566 at c=16; 47.0.3: 10,042 -> 37,996).
- **wasmtime 47+** (PR #13572): a FINAL target has no proper subtypes, so the equality
  IS the whole check -- no libcall either way. Given the invariant, no emitted check
  reaches the libcall on 47+; a ladder miss is one inline compare.

Both browsers and wasmtime validate final and open types alike, so nothing but the cast
lowering ever notices. Consequence: the wasmtime floor for RUNNING a component stays
46, but anything about serving concurrent load says **wasmtime 47+** (http-handler
guide throughput section; CI image pinned >= 47 in
`.github/docker/wasmtime/Dockerfile`). `.kb/concurrent-served-requests.md` is the
interpreter/JVM side; this file is the WASM side.

## Re-evaluation triggers
- **Declaring a supertype hierarchy** (e.g. a tagged common header struct) makes every
  cast to the non-final parent the libcall path even on 47+. Re-run the c=1/c=16 serve
  benchmark before shipping one.
- **Emitting fewer casts** (typed locals across a hot sequence, a typed internal calling
  convention) stays deliberately unimplemented: on 47+ the misses are inline compares
  and the 46->47 c=1 delta bounds all per-request check overhead at ~11%. Revisit only
  if a profile on a 47+ host shows the check sites hot.
