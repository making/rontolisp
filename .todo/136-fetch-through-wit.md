# `rontolisp:fetch` through WIT — delete the biggest hand-written blob

**Status:** IN PROGRESS 2026-07-14. **Large, and the biggest single blob win.** Selected
ahead of `.todo/135` (user decision 2026-07-14). `.todo/133` landed, so
`set-method(method)` / `set-scheme(option<scheme>)` cross the component import boundary
(verified against wasmtime's real `wasi:http` host —
`WasmLispCompilerIntegrationTest.componentImportLowersAVariantParameter`). The
user-facing API does not change at all.

## READ THIS FIRST: the type tiers were never the blocker (survey, 2026-07-14)

The framing below ("the blocker that WAS") is right but **incomplete**, and acting on it
alone walks straight into a wall. What the survey established:

- **Every one of the 21 WIT functions a Lisp `fetch` needs already passes the
  `--component` type gate**, `error-code`'s 41 cases included. `fields.set` /
  `fields.from-list` are the only refusals (`list<list<u8>>` params), and `fields.append`
  replaces them exactly as this file predicted. `WitCanonicalAbi.flatSig` was hand-checked
  against the WAT adapter's core arities on all 21 and agrees on every one.
- **The real blockers are two STRUCTURAL gaps, and neither is in this file.** Both are
  shared with `.todo/135`, so they are this todo's to build.

**Blocker 1 — `canon resource.drop` is unreachable from Lisp.** `wit-import` binds an
interface's funcs / constructors / methods / statics; a drop is a canonical built-in, not a
WIT-declared function, so `WitResolver.functions` never yields one and nothing in
`appendUserImports` emits `ComponentWriter.canonResourceDrop` (which exists, and is called
only by the hardcoded blob builders). This is **not just a leak**: `wasi:http`'s
`types.wit:518-521` says the `output-stream` from `outgoing-body.write` is a CHILD resource
that must be dropped before the parent is finished, *"otherwise the `outgoing-body` drop or
`finish` will trap"* — the WAT adapter duly calls `drop-out` immediately before
`body_finish` (`adapter-http-client.wat:392-393`). **Without drops a Lisp fetch cannot even
send a request body.** Resources fetch must drop: `pollable`, `output-stream`, `fields`,
`input-stream`, `incoming-body`, `incoming-response`, `future-incoming-response`.

**Blocker 2 — cross-interface resource types are not unified.** `wasi:http/types` does not
DEFINE `input-stream` / `output-stream` / `pollable`; it `use`s them from `wasi:io/streams`
and `wasi:io/poll`. `WitComponentTypeEncoder` resolves a named type through
`WitResolver.resolveType`, which follows `use` clauses transparently, and then declares a
**fresh nominal `(export "input-stream" (type (sub resource)))` inside the http/types
instance type**. Two consequences, either fatal: the host's real `wasi:http/types` instance
has no `input-stream` export to match it, and the handle `incoming-body.stream()` returns
would index a different handle table than `wasi:io/streams.blocking-read` expects. Resources
are NOMINAL in the component model; structural re-declaration is not equivalence.

### The target encoding — confirmed by `wasm-tools print` on today's real fetch component

Do not re-derive this; it is measured (`rontolisp f.lisp -o f.wasm --component`, then
`wasm-tools print`). Imports go in DEPENDENCY ORDER, each `use`d type is aliased OUT of the
providing instance into the outer type space and then pulled back IN with an **`alias
outer`** inside the dependent's instance type:

```wat
(import "wasi:io/error@0.2.0" (instance (;10;) (type 13)))
(alias export 10 "error" (type (;14;)))              ;; -> outer type 14
(type (;15;) (instance                               ;; wasi:io/streams
    (export (;0;) "output-stream" (type (sub resource)))
    (alias outer 1 14 (type (;1;)))                  ;; <- the SAME error resource
    (export (;2;) "error" (type (eq 1)))
    (type (;4;) (variant (case "last-operation-failed" 3) (case "closed")))
    (export (;5;) "stream-error" (type (eq 4)))      ;; defined HERE -> declared, not aliased
    (export (;6;) "input-stream" (type (sub resource)))
    ...))
(import "wasi:io/streams@0.2.0" (instance (;11;) (type 15)))
(alias export 11 "output-stream" (type (;16;)))
(alias export 9  "pollable"      (type (;17;)))
(alias export 11 "input-stream"  (type (;18;)))
(type (;19;) (instance                               ;; wasi:http/types
    (export (;0;) "fields" (type (sub resource)))    ;; defined here
    ...
    (alias outer 1 16 (type (;14;)))                 ;; used from io/streams
    (export (;15;) "output-stream" (type (eq 14)))
    (alias outer 1 17 (type (;32;)))
    (export (;33;) "pollable" (type (eq 32)))
    (alias outer 1 18 (type (;38;)))
    (export (;39;) "input-stream" (type (eq 38)))
    ...))
(import "wasi:http/types@0.2.0" (instance (;12;) (type 19)))
```

`wasm-tools` outer-aliases every `use`d type, resource or not (`outgoing-handler`'s
instance type aliases the `error-code` VARIANT too). Only resources strictly REQUIRE it
(non-resource component types are structural), but following the tool exactly is the shape
to aim at.

## The pieces of new machinery, in order

- **A. `am.ik.wit`: `WitResolver` must report a resolved type's OWNER.** `resolveType`
  returns the defining item and throws the owning interface away — precisely the
  information the encoder needs. Add an owner-returning sibling; keep it
  language-independent.
- **B. `am.ik.wasm`: `ComponentWriter.instanceDeclAliasOuterType(count, outerTypeIndex)`**
  — the instance-type declaration `0x02` (alias) / sort `0x03` (type) / target `0x02`
  (outer). It appends to the instance type's local type index space, like
  `instanceDeclType`. Pin it with a reference probe against `wasm-tools`, per the
  `.kb/wit.md` rule.
- **C. `appendUserImports` + `WitComponentTypeEncoder`: dependency-ordered imports with
  outer-aliased `use`d types**, keyed by `(defining interface's canonical id, type name)` so
  identity holds across separate `wit-import` directives and even separate WIT files. A
  foreign RESOURCE whose owning interface is not also wit-imported is a compile error naming
  the WIT line ("also `rontolisp:wit-import` <id>").
- **D. resource `drop` binding** (`<resource>-drop`, bound only when textually referenced so
  every existing artifact stays byte-identical; a provider member on interpreter/JVM; a
  no-op defun on Preview 1; `canon resource.drop` on `--component`).

Only then **E**: a hand-written Lisp fetch, E2E against wasmtime's real `wasi:http` — that
is the proof, and it needs no `fetch.lisp` and no blob deletion. Then **F**: `fetch.lisp` +
its splice. Then **G**: delete the blobs.

## Progress, 2026-07-14 (uncommitted, working tree)

**A, B, C DONE. E PROVEN.** A fetch written entirely in Lisp over wit-imported
`wasi:io/{poll,error,streams}` + `wasi:http/{types,outgoing-handler}` prints `200` and the
response body against **wasmtime's real `wasi:http` host**
(`wasmtime run -W gc=y -W exceptions=y -S http=y`). Full suite 3653/0. A component with no
user imports is byte-identical (rebuilt a fetch component before/after and diffed).

- **A** — `WitResolver.resolveOwned` (the defining interface of a resolved type) and
  `resolveOwnedDeep`.
- **B** — `ComponentWriter.instanceDeclAliasOuterType`, pinned by
  `ComponentWriterTest.instanceTypeAliasesAUsedTypeFromTheEnclosingComponent`: the 258
  golden bytes are the REAL `wasi:io/streams` instance type, lifted out of a fetch component
  with `wasm-tools dump`.
- **C** — `WitComponentTypeEncoder` runs twice (collect foreign resources -> emit with their
  outer indices), `WasmComponentImportCompiler.inDependencyOrder` sorts the imports once
  where they are collected (so the wiring, the core instances and the instantiation args
  cannot disagree), `WasmComponentBuilder.appendUserImports` interleaves
  alias-out / type / import per interface, and `userImportTypes` replaces `userIfaces` in
  the three `appendFuncExports` type cursors.

Three things the work forced, all of which turned out to be right:

- **A type-only interface import is legal under `--component`.** `wasi:io/error` is imported
  purely to own the `error` resource that `wasi:io/streams`' `stream-error` carries; a real
  fetch component's instance type for it is one resource and ZERO functions. So
  "the program calls none of its functions" no longer fires on the component path — it
  defers to `appendUserImports`, which is the only place that sees every import and can
  tell an unused interface from a type provider. Both the encoder and `WitImportWorldEmitter`
  take the resources they must DECLARE (an instance type can only be projected from for a
  name it exports; a package block whose `use` clause points at nothing is not a document).
- **A TYPE ONLY MEANS SOMETHING WITH ITS SCOPE, and the scope CHANGES as a walk descends.**
  Follow a `use` clause into another interface and you are looking at ITS types, whose
  internal references the starting scope never imported: `outgoing-handler` uses
  `error-code`, whose `DNS-error` case carries a `DNS-error-payload` it has never heard of.
  A latent bug that keyvalue (self-contained) never hit. **Fully threaded** —
  `WitResolver.resolveOwned` reports the OWNER; `WitCanonicalAbi` carries a scope and hands
  out `scopedTo` siblings, and `VariantInfo`/`RecordInfo` carry the `abi` their payload /
  field types are written in (a consumer that gets those types back HAS to keep walking
  them, and doing that against the original scope is how a layout silently comes out
  wrong); the gate, the type encoder, the import codegen and `WitImportWorldEmitter` all
  thread it. Resolution stays STRICT — an earlier round shipped a nearest-wins
  `resolveOwnedDeep` search instead, and it is **deleted**: strict resolution is what caught
  the one consumer that had been missed (`WitImportWorldEmitter`, which runs on EVERY
  component compile because `componentWit()` is recorded there, not just under `--emit-wit`).
- **`--emit-wit`'s import side prints `use` clauses now**, rather than copying a foreign
  type into the wrong package block — which for a `resource` would have printed a document
  claiming two unrelated types where the component has one. A type ALIAS the bound surface
  reaches is printed too (`type headers = fields`): transparent at the ABI boundary is not
  transparent in the document, and a signature can name it.

Gotcha for whoever writes `fetch.lisp`: the `scheme` variant's cases are `HTTP` / `HTTPS`,
so the keyword is `:HTTP`, not `:http` (keywords are case-preserving, and one naming no case
traps `unreachable`).

### Left to do (F and G; D is done, below)

- **F** — `fetch.lisp` + `FetchLibrary` (component-path-only splice, inline WIT on the
  classpath; the ordering trap is that `WitImportInliner` runs BEFORE the library splices).
  Everything it needs now exists: the POST probe in the scratchpad IS `fetch.lisp`, modulo
  the splice mechanism and the `rontolisp:fetch` promise API (`fetch-start` / `fetch-await`
  back a `rontolisp:await`-able promise — preserve that, do not quietly make fetch
  synchronous).
- **G** — delete the blobs, the `H_*` constants, `buildHttp`; regen the WIT fixtures; the
  `rontolisp` package function roster in `ci-spec.yaml` (fetch becomes a Lisp defun). Also
  the `--emit-wit` ORACLE (`WitOracleE2eTest`) has never been run against a cross-interface
  import: the world we print is a coherent document and re-parses, but whether it is
  BYTE-identical to `wasm-tools component wit` on the same bytes is unmeasured. Measure it
  before shipping, and if it differs, decide deliberately (the fixed-variant fixtures are
  byte-diffed; nothing forces the user-import side to be).

## D — resource `drop`: DONE (2026-07-15). Design below; what it cost, first

**PROVEN**: a POST written entirely in Lisp over wit-imported `wasi:io/{poll,error,streams}`
+ `wasi:http/{types,outgoing-handler}` sends a request BODY and reads the response back from
wasmtime's real `wasi:http` — the case a GET could dodge, because `outgoing-body.finish`
traps unless the child `output-stream` is dropped first. Every artifact that existed before
is byte-identical (a 0-import component, keyvalue, a serve component and the Lisp-fetch
component all hash the same), because nothing names a `-drop`. Interpreter and JVM dispatch
`"bucket-drop"` to the provider; a dropped handle then answers `no-such-store` while the
STORE survives and the next `open` reads every key.

Three things it cost that are not in the design below:

- **A wrapper's first parameter is local slot 1, not 0.** Every compiled function carries an
  implicit closure environment in slot 0 (`WasmLispCompiler`: *"Slot 0 = env (unused for
  defuns), params start at slot 1"*). The first drop wrapper read slot 0 and trapped casting
  the null env to an i31 — a `cast failure` with no other clue.
- **`examples/wit/keyvalue/memory-store.lisp` hung its DATA off the handle**, so a naive
  `(remhash handle ...)` would have deleted the store. It now keys the data by store
  IDENTIFIER with the handle table a separate indirection, and hands out a FRESH handle per
  `open` (what a real host does). Dropping a handle releases the reference, not the store —
  which is the thing the example is now there to teach.
- **Measured, and it contradicts a comment that was in the tree:** wasmtime's own
  `-S keyvalue=y` provider hands each `open` an INDEPENDENT snapshot — a write through one
  bucket is invisible to a later `open` there (a seeded key still reads back, so it is not a
  plumbing failure). It is an in-memory convenience, not a store. The old comment claiming
  "two opens of the same store see each other's writes (the host's rule)" was simply false.

Steps A-C landed as commit `a74421f`. **D is what a request BODY needs**
(Blocker 1 above: `wasi:http` makes `outgoing-body.finish` TRAP unless the child
`output-stream` is dropped first, `types.wit:518-521`; the WAT adapter duly calls `drop-out`
immediately before `body_finish`, `adapter-http-client.wat:392-393`). The GET-only probe got
away with leaking. Resources a fetch must drop: `pollable`, `output-stream`, `fields`,
`input-stream`, `incoming-body`, `incoming-response`, `future-incoming-response`
(`outgoing-request` and `outgoing-body` are consumed by `handle` / `finish` as `own`
transfers).

**Name it `<resource>-drop`** (`kv:bucket-drop`), symmetric with the existing `bucket-new`
for a constructor — both are rontolisp spellings of something WIT does not name as a
function. `WitResolver.functions` does NOT enumerate resources, so `WitImportDirective` has
to walk `iface.items()` for `WitItem.ResourceDef` itself. Feed the synthetic name through
the existing `allMembers` set so the current "binds 'x' twice" check catches an interface
that really declares a method called `drop`.

**Bind it ONLY when the program textually references the name — on every backend.** A drop
is not a WIT function, so it is exempt from the "Preview 1 binds every function" convention,
and this one rule is what keeps every existing artifact byte-identical (nothing references a
`-drop` name today, so nothing is emitted). `WitImportInliner.referencedNames` already
computes the set; today it is only passed as the component `memberFilter`, so pass it
separately as a drop filter on all backends. `--no-prune` / `--dynamic` bind them all.

Per backend:

| | |
|---|---|
| interpreter / JVM | the existing `providerDefun` — `(defun kv:bucket-drop (self) (rontolisp::%wit-call "<iface>" "bucket-drop" self))`. Zero new machinery. Do NOT hardcode a no-op: the core knows the provider MECHANISM and no concrete interface, so what a drop MEANS is the provider's to decide (a Java store closes a connection; a Lisp store evicts a handle). Document that a provider with nothing to release just returns nil. |
| Preview 1 WASM | a **no-op defun** `(defun kv:bucket-drop (self) self nil)`. Emit NO `wasm-import`: the WIT declares no drop function, so importing `[resource-drop]bucket` would invent a host function the interface never named — breaking both the byte-identity-with-a-hand-written-import-block property and the browser demos' hand-written JS import objects. A P1 handle is an opaque integer the host handed over; there is nothing on the guest side to release. |
| `--component` | core side = an ORDINARY core import (`module` = the canonical iface id, `field` = `"[resource-drop]bucket"`, type `(func (param i32))`), so the existing `PLACEHOLDER_FUNC_BASE + ordinal` / `WasmImportInjector` machinery is reused unchanged and the wrapper body is the simplest there is (unbox the i31 handle to i32, call, return nil — no memory, no staging, no lift). Outer side = a SECOND emission kind in `appendUserImports`: `aliasInstanceType(ownerInstance, resource)` (consumes a component TYPE index) + `canonResourceDrop(thatType)` (consumes a CORE FUNC index, and NO component func — `canon resource.drop` produces a core function directly, with no alias and no lower). The two sides meet BY NAME through `coreInstanceFromFuncs`, so their orders are independent. `WasmServeComponentBuilder:210-217` is the working precedent, inside the same `SEC_CANON` vec as the lowers. |
| `--no-gc` | unchanged — `wit-import` is already rejected there. |

**THE ONE REAL TRAP — split `userImportFuncs` into three counters.** It is used today with
two different meanings, and a drop breaks the tie:

- `canonLift(22 + userFuncs, T_RUN_FUNC)` — a **core** func index → must count decls **+ drops**
- `componentInstanceFromFunc("run", 11 + userFuncs)` — a **component** func index → decls **only**
- `appendFuncExports(..., T_RUN_FUNC + 1 + userTypes, ...)` — the first free **TYPE** index →
  already `userImportTypes` (interfaces + projected resources), and each dropped resource
  that is not already projected adds one more

So: `userImportCoreFuncs` (decls + drops) / `userImportFuncs` (decls, the component side) /
`userImportTypes` (interfaces + projected + dropped). Sites: `WasmComponentBuilder` around
560 / 673 / 677 / 683, 788 / 975 / 979 / 985, 1054 / 1193 / 1197 / 1203, and
`WasmServeComponentBuilder` 134 / 266 / 270, 348 / 541 / 545. Get one wrong and you get
either an invalid component or — worse — one that VALIDATES while lifting the wrong core
function.

**The encoder declares resources lazily** (only when a bound function's signature mentions
one), so `WitComponentTypeEncoder.encode` must be told to force-declare a resource that is
only DROPPED and never otherwise reached. The `provided` parameter added in step C is
exactly that hook — reuse it. Placing the forced declarations after the function walk keeps
the bytes unchanged when the resource was already reached (the keyvalue shape).

**The keyvalue example leaks today, and fixing it is the point.** `page-hits-server.lisp`
opens a bucket PER REQUEST; `wasmtime serve` rebuilds the instance per request so the handle
table dies with it, but an instance-reusing host grows it without bound and pins the host's
bucket resources. `examples/wit/keyvalue/memory-store.lisp` hangs its DATA off the handle in
`*kv-buckets*`, so a naive `(remhash handle *kv-buckets*)` would delete the store's contents:
re-key the data by store identifier and keep the handle table as a separate indirection. That
change teaches the right thing — **dropping a handle is not deleting the store** — which is
worth having in a readable example.

**Nothing existing changes**: no program references a `-drop` name today, so every artifact
stays byte-identical, the emitted `--emit-wit` world is unchanged (a drop is a canonical
built-in, not a WIT function), and `WitOracleE2eTest` is untouched.

Two things todo 133 leaves you:

- `fields.set(name, value: list<list<u8>>)` and `from-list` do NOT cross (a `list<T>`
  argument is still a compile error). Build headers with `fields.append(name,
  value: list<u8>)`, which does. `fields.entries()` — a `list<T>` RESULT — DOES lift (into
  a list of 2-element lists), so response headers can be read.
- The collision guard (`WasmComponentBuilder.rejectAdapterImportCollisions`) rejects a
  `wit-import` of an interface the blob already imports. **Measured: there is no collision
  on the plain `wasmtime run` path** — a WIT-driven fetch has no `rontolisp:fetch` call
  site, so `usesHttp` is false and the **base** blob is selected, whose surface is WASI 0.3
  only (`uni.wit` carries no `wasi:io/*` or `wasi:http/*`). **Serve + fetch is the
  exception**: the serve blob's fixed surface really does import `wasi:http/types`, so a
  spliced `fetch.lisp` collides there. Keep the serve+fetch WAT variant selected as-is
  until `.todo/135` turns the serve blob's own `wasi:http` imports into user imports too —
  that is when `http-server-client` finally collapses, and it is why 135's definition of
  done claims the collapse while this file's does too. Neither can do it alone.

## What goes away

| artifact | size | what it is |
|---|---|---|
| `adapter-http-client.wasm` | 5.1 KB | hand-written WAT implementing `fetch-start` / `fetch-await` over `wasi:http@0.2` + `wasi:io@0.2` |
| `import-block-http-client.bin` | 5.3 KB | captured component type/import sections for those interfaces |
| `mem-http-client.wasm` | 132 B | the 16-page memory variant fetch needs |
| the `H_*` wiring constants in `WasmComponentBuilder` | ~30 hand-derived indices | re-read out of `wasm-tools dump` every time the blob changes |

That is **~10.5 KB of the ~12 KB of component blobs**, plus a whole `regen.sh` variant and
the `buildHttp` branch. What replaces it is a `fetch.lisp`.

## The shape: a Lisp-source library, exactly like `usocket.lisp`

**Users write nothing new.** `rontolisp:fetch` stays a built-in; there is no `wit-import`
in user code. The precedent is `json.lisp` / `url.lisp` / `usocket.lisp` / `linalg.lisp`:
the compiler notices the program references `rontolisp:fetch` and splices the library in.

```lisp
;;; fetch.lisp -- spliced only when the program calls rontolisp:fetch.
(rontolisp:wit-import <wasi:http/types@0.2.0>)
(rontolisp:wit-import <wasi:http/outgoing-handler@0.2.0>)
(rontolisp:wit-import <wasi:io/streams@0.2.0>)
(rontolisp:wit-import <wasi:io/poll@0.2.0>)

(defun rontolisp:fetch (url &rest options)
  ...)                                  ; the logic now in adapter-http-client.wat
```

The WIT text for a BUILT-IN library cannot be a file path (there is no user file to point
at). Use the internal `rontolisp::%component-import` form, which **already carries the WIT
text inline** — it was built that way for the browser playground (no filesystem), and it
serves this case unchanged.

Scope: the **WASM component leg only**. The interpreter and the JVM keep their
`HttpClient` implementation (`.kb/fetch-http.md`); Preview 1 WASM has no fetch at all
today. So the splice is component-path-only, like `VecLibrary`'s `--no-gc` exclusion.

## The blocker that WAS (`.todo/133`, landed 2026-07-14)

Read function by function against `src/wasm-component/deps/http/types.wit`, the entire
fetch surface crosses the component import boundary **today** — handles, `option<handle>`
(`outgoing-handler.handle`'s `options`), `list<u8>` (`blocking-write-and-flush`), `string`
(`fields.append`, `set-path-with-query`), and the deeply nested
`option<result<result<incoming-response, error-code>, _>>` that
`future-incoming-response.get` returns (results lift recursively).

The exceptions were `outgoing-request.set-method(method)` and `set-scheme(option<scheme>)`,
whose arguments are **variants** — `.todo/133`, and nothing else. Both cross now, verified
against wasmtime's real `wasi:http` host.

## The real risk, and how to retire it

fetch **works today**. This rewrite changes every http-client component's bytes and could
regress a shipped feature. So:

- keep the WAT adapter path alive behind the existing `emitHttpImport` selection while the
  Lisp path is built;
- the oracle is **output parity** against the current adapter across the existing fetch
  E2E (`WasmLispCompilerIntegrationTest`'s fetch cases, the `RONTOLISP_HTTP_E2E=1`
  opt-ins, `examples/net/http-*`), plus the promise API (`fetch-start`/`fetch-await` back
  a `rontolisp:await`-able promise — preserve that, do not quietly make fetch synchronous);
- only then delete the blobs, the `H_*` constants, the `buildHttp` branch and the
  `src/wasm-component` sources.

Also watch the **`--component` + serve + fetch** combination (`http-server-client`), which
exists today as its own blob variant and would collapse into "serve + the fetch library".

## Why it is worth it

This is the **self-hosting test of the whole IDL bet**: `.todo/124` claims a new host
interface should cost a `.wit` file rather than core code. fetch is core code implementing
a host interface. If rontolisp can re-implement its own built-in over its own WIT pipeline,
the claim is demonstrated rather than asserted — and every future host interface arrives
the same way, with no blob, no `regen.sh`, and no hand-derived indices.
