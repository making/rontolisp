# Component imports (`canon lower`): the component stops being import-locked

**Status:** open, unstarted. Step 4 and the payoff of `.todo/124`. **The only step
with genuinely new encoder work.** Depends on `.todo/125` (DONE 2026-07-13, `.kb/wit.md`)
+ `.todo/127`.

**New prerequisite from the `.todo/125` decision:** `result<T,E>` was settled as
option (c) — the error arm signals a condition catchable with `handler-case` on
EVERY backend, and the WASM trap is documented as a temporary limitation, not a
contract. Therefore a **WASM catch mechanism is a prerequisite of the
result-returning imports in this step** (wasi:keyvalue returns `result`
everywhere). Budget it as part of this todo or as its own predecessor todo; the
"landmines" section below already anticipated this.

## The hole this fills

A `--component` build today can import **nothing** outside the fixed WASI blob
surface (`src/wasm-component/uni.wit`): `rontolisp:wasm-import` throws
`UnsupportedOperationException` under `--component` (`.kb/wasm-import.md`), and
every new host interface so far (fetch, sockets, http) was added by **hand-building
another blob variant** under `src/wasm-component/` — which is why there are seven
of them (`core`, `core-http-client`, `core-sockets`, `core-http-server`, ...) and why each new
interface costs a `regen.sh` round plus re-derived wiring constants.

That does not scale, and it is what blocks `.todo/52` (wasi:keyvalue), `.todo/53`
(wasmCloud), and any composition with a component someone else wrote.

## What is new

`am.ik.wasm.ComponentWriter` today encodes the **lift** side (exports): sync
`canon lift`, the stackful-async variant (functype tag 0x40 vs 0x43), the canonical
string options (memory/realloc/utf8/post-return). It does **not** encode the
**lower** side: taking a component-level import and making it callable from the
core module.

So this step adds, roughly:

- component **type** encoding for arbitrary WIT types (records, variants, options,
  results, lists, resources) — today only flat scalars and `string` exist, because
  that is all `wasm-export` allows
- `canon lower` with canonical options (memory, realloc, string encoding,
  post-return / `cabi_post` on the *import* side)
- import instance + alias wiring in the component sections
- the **lowered marshalling** in the core module: WIT value <-> our value
  representation, driven by `WitTypeMapper` — the counterpart of
  `WasmExportRuntimeBuilder`, which already does exactly this for the lift side
  and is the template to follow

## What is NOT new (lean on it hard)

- **Index stability.** `.kb/wasm-import.md`'s placeholder trick already solves the
  "new imports shift every `FUNC_*` constant" problem: an import is a synthetic
  defun that emits `call (1<<27 + ordinal)`, and `WasmImportInjector` rewrites the
  finished module, remapping every `call`/`ref.func` and shifting export/start
  indices. It was built for Preview 1 core imports; the component path needs the
  same rewrite over a component's core module. **Reuse it, do not reinvent it.**
- **`--optimize`** already tree-shakes unused imports (that is why `gl.lisp` can
  declare a 34-entry union for free). Verify it composes here — a WIT interface
  with 40 functions of which a program calls 2 must not cost 38 imports.
- **The blob approach stays** for the WASI surface itself. This is additive: an
  import-free component must stay **byte-identical** to today's output (the same
  stash-dance proof used for todo 92/93). Do not attempt to re-derive the existing
  adapter through the new path.

## Killer app: do `.todo/52` (wasi:keyvalue) *through* this, not around it

`.todo/52` currently proposes hand-rolled `rontolisp:kv-*` built-ins plus a bespoke
blob. Retarget it: `wasi:keyvalue` is a plain `wit-import` of a published `.wit`,
with `bucket` as a resource handle (`.todo/127`), and the interpreter/JVM parity
comes from a provider rather than from a parallel hand-written implementation.
If that works, **it is the proof the whole IDL bet was right**: a new host
interface costs a `.wit` file, not a blob variant.

Second target once it lands: composing with a component written in another
language (`wac plug` a Rust component's exports into a rontolisp component's
imports) — the "Lisp as glue" demo, and the thing that makes rontolisp interesting
to people who do not write Lisp.

## Landmines

- **`result<T,E>`** is unavoidable here (every `wasi:keyvalue` function returns
  one) and WASM cannot catch conditions (`.kb/error-handling.md`). `.todo/125`
  decided option (c) — condition on every backend — so this todo is what forces
  the real catch mechanism on the WASM backend (see the prerequisite note at the
  top). The decision record: `.kb/wit.md`.
- **Resources** need a lifetime story (`resource.drop`). Handles in the stream
  space give us `close`; make sure a dropped handle cannot be reused.
- **0.2 vs 0.3 mixing.** `wasi:keyvalue` is a 0.2 interface; the component's own
  surface is 0.3 (with `wasi:http@0.2` already a deliberate temporary island,
  `.todo/02`). Establish the rule now: which versions may coexist in one component,
  and where it is enforced (today `WasmExprCompiler` enforces the fetch+tcp mixing
  rule ad hoc).
- **The `--wit` output must keep telling the truth** — a component with imports now
  has import lines to emit, which is a direct test of `.todo/125`'s printer against
  `wasm-tools component wit`.

## Definition of done

- A component can import a user-supplied WIT interface and call it; verified under
  `wasmtime` with a real host providing it.
- Import-free components are byte-identical to today.
- `.todo/52` (wasi:keyvalue) lands on this pipeline, running on all four backends.
- `--optimize` shakes unused imports; `--wit` round-trips the imports.
- `.kb/wasi-component.md` + `.kb/wasm-import.md` updated; docs (en/ja); four-backend
  + native E2E.
