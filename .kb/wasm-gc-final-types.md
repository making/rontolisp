# Every emitted wasm-GC type must be `sub final` (0x4F)

**Invariant**: every type the WASM backend emits -- the rec-group structs/arrays via
`RecTypeDef.addSubFinal*` and the plain `0x60` function types (final by spec
shorthand) -- is FINAL. No emitted type declares or permits subtypes; the module uses
no wasm-level subtyping at all.

**How it is SPELLED changed in todo-274, what it MEANS did not.** `RecTypeDef` used to
write the explicit `4F 00` ("sub final, zero supertypes") in front of every struct and
array; it now writes the bare `comptype`, which the format defines as exactly that
shorthand (`subtype ::= 0x50 x* comptype | 0x4F x* comptype | comptype`) and which is
two bytes shorter per type. The decoded type -- and therefore every claim below about
cast lowering -- is identical; `RecTypeDefTest` now pins the bare tag and, either way,
that the emitted byte is never the OPEN `0x50`. The explicit form comes back the moment
any emitted type needs a supertype, because the shorthand cannot express one -- and the
performance section below is the price list for doing that.

## What wasmtime actually lowers a concrete-type check to (verified per release)

For `ref.cast`/`ref.test`/`call_indirect` against a concrete type, Cranelift emits an
inline null/i31 filter plus a type-id equality against the object's header
(`FuncEnvironment::is_subtype`, `crates/cranelift/src/func_environ/gc.rs`). What
happens on INEQUALITY is what changed across releases:

- **wasmtime <= 46**: unconditional fallback to the `is_subtype` host libcall, which
  goes straight to the engine-global `RwLock<TypeRegistryInner>` read lock
  (`TypeRegistry::is_subtype_slow`). Finality is never consulted, and released 45/46
  have NO per-store cache in that path (the `subtype_check_cache` an earlier revision
  of this file described is PR #13860, merged 2026-07-10 to main only -- the todo-188
  session had read main, not the release it measured). So every FAILING concrete
  `ref.test` -- the ordinary misses of the emitted runtime's type-dispatch ladders --
  pays a libcall plus a global read lock. Succeeding checks were always inline-cheap,
  as are `(ref i31)` tests (a tag check) and abstract `struct`/`array`/`eq` tests (a
  header-kind check); the poison is exclusively the failing concrete test.
- **wasmtime 47+** (PR #13572): a FINAL target type has no proper subtypes, so the
  equality IS the whole check -- no libcall, no control-flow merge, in either
  direction. Because of the invariant above, none of our emitted checks can reach the
  libcall at all on 47+; a dispatch-ladder miss costs one inline compare. (47 also
  gained #13860's per-store cache for whatever still libcalls; with an all-final
  module that path is unreachable.)

## What <= 46 did to a served component (todo-277, measured 2026-08-09)

One `examples/net/httpbin.lisp` request executes **2,661 failing concrete tests**
(census: `WASMTIME_LOG=wasmtime::runtime::vm::libcalls=trace` on `wasmtime serve`,
every logged call `-> false`; ~90% of them test ONE hot value type against a dozen
expected types -- the shape of the runtime's dispatch ladders walking list structure). At one connection that is merely ~10% of throughput -- but the lock
is engine-global, `wasmtime serve` runs requests on parallel threads of ONE engine,
and contended rwlock acquisition degrades with waiter count, so CPU per request grows
~quadratically in in-flight requests. Measured on wasmtime 46.0.1 (M4 Max, 16 cores,
`ab` over loopback): 9,016 rps at c=1 collapsing to 566 rps at c=16, with
`TypeRegistry::is_subtype_slow` ~90% of `sample` top-of-stack. The SAME `.wasm` on
wasmtime 47.0.3: 10,042 rps at c=1, **37,996 rps at c=16** (67x), zero `is_subtype`
frames in the profile; `examples/net/http-handler.lisp` went 4,576 -> 40,080 rps at
c=16. Nothing changed in our emission -- the all-final invariant is exactly the shape
47's lowering rewards.

Consequences: the wasmtime floor for RUNNING a component stays 46, but everything
that talks about serving concurrent load says **wasmtime 47+** (the http-handler
guide's throughput section, the CI wasmtime image -- pinned >= 47 in
`.github/docker/wasmtime/Dockerfile`). `.kb/concurrent-served-requests.md` covers the
interpreter/JVM side of concurrent serving; this file is the WASM side.

## The bug this file pins

`am.ik.wasm.Type` had `SUB`/`SUB_FINAL` swapped (`SUB_FINAL` wrote 0x50, the spec's
OPEN `sub`), so every rec-group type shipped non-final. On wasmtime 47+ that would
today forfeit the inline lowering everywhere; when it shipped (measured in the
todo-188 PBKDF2 benchmark on 46: the libcall was 20% of cycles with ironclad alone,
52% with cl-ppcre also quickloaded) it meant every hot-path cast took the libcall.
The spec mapping is 0x4F = `sub final`, 0x50 = `sub`; `RecTypeDefTest` asserts the
emitted opcode against the spec constants, not against the enum, so a re-swap cannot
pass. Since the wrapper is no longer written, what the test rules out is a
re-introduced `0x50` in either position -- an emitter that starts declaring
supertypes has to use `0x4F`.

Both browsers and wasmtime validate final and open types alike (the module declares
zero supertypes either way), so nothing but the cast lowering ever notices -- which
is exactly why the swap survived until it was profiled.

## Re-evaluation triggers

- **Declaring a supertype hierarchy** (e.g. a tagged common header struct) makes
  every cast to the non-final parent an actual-!= -expected check, which is the
  libcall path even on 47+. Re-run the c=1/c=16 serve benchmark above before
  shipping any such hierarchy.
- **Emitting fewer casts** (typed locals across a hot sequence, a typed internal
  calling convention -- todo-277's "attack 2") stays deliberately unimplemented, by
  measurement: on 47+ the 2,661 misses are inline compares, and the 46->47 c=1 delta
  bounds the ENTIRE per-request check overhead at ~11%; the codegen complexity buys
  a few percent at most. Revisit only if a profile on a 47+ host shows the check
  sites themselves hot.
