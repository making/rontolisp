# Component imports (`canon lower`): the component stops being import-locked

**Status:** open, unstarted. Step 4 and the payoff of `.todo/124`. **The only step
with genuinely new encoder work.** Depends on `.todo/125` (DONE 2026-07-13, `.kb/wit.md`)
+ `.todo/127`.

## READ FIRST: you inherit unverified work from `.todo/127`

`.todo/127` (`rontolisp:wit-import`) is implemented and **pushed** (`21a0d87`), but its
verification was **deliberately deferred to the end of THIS todo** (user decision,
2026-07-14) -- because this todo adds a fourth lowering target to the very same
`WitImportInliner` and will likely move `ci-spec.yaml` again, so running the suite twice
buys nothing. **Your final verification run covers both todos**, and `.todo/127` is deleted
only when it is green:

- [ ] `./mvnw spring-javaformat:apply test` -- the suite was green (**3607 / 0 failures**)
      just before the `examples/wit/` directory move, and has NOT been run since. The move
      touched only `examples/examples.yaml`, the docs and comments, so `ExamplesE2eTest` and
      `DocExamplesTest` are the two that can break.
- [ ] `./mvnw -Pweb compile` -- green before the move; re-confirm (`src/web/java` compiles
      ONLY under this profile, so `./mvnw test` does not catch a break there).
- [ ] **native `CiSpecE2eTest`** -- `ci-spec.yaml` CHANGED in `21a0d87` (the
      `rontolisp:list-functions :rontolisp` expectation gained `wit-error-payload` and
      `wit-provide`). A plain `./mvnw test` SKIPS that test, so only the CI native-image job
      would catch a mistake. See CLAUDE.md, "Verifying the Native Image End-to-End".

If CI is red on `develop` when you start, that is the likely cause -- fix it first.

**Prerequisite SATISFIED (2026-07-14):** `result<T,E>` was settled as option (c)
— the error arm signals a condition catchable with `handler-case` on EVERY
backend — and the WASM catch mechanism it required landed with `.todo/129`:
the wasm-GC backends (Preview 1 + `--component`, incl. serve) compile
`handler-case`/`ignore-errors`/`unwind-protect` through the wasm
exception-handling proposal (`.kb/error-handling.md`, "WASM (todo-129)"). A
result-returning import stub can therefore signal with `%error-cond` and be
caught; programs that catch need `wasmtime -W exceptions=y` (37+). Only
`--no-gc` still rejects catching (by design).

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

### The target program already exists: `examples/wit/keyvalue/`

Written for `.todo/127` (DONE 2026-07-14) and **running today on the interpreter and
the JVM**, from one source, with the store swapped underneath it:

| file | what it is |
|---|---|
| `wit/keyvalue.wit` | the interface -- a faithful subset of real `wasi:keyvalue` 0.2 `store`: a `variant error`, a `resource bucket` with get/set/delete/exists/list-keys, `open: func(identifier: string) -> result<bucket, error>` |
| `memory-store.lisp` | a portable Lisp hash-table implementation; ends in one `(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'memory-store)` |
| `java-store.lisp` | the same interface over a real `java.util.LinkedHashMap` (`java:` interop); bound after, so it REPLACES the memory store on the JVM |
| `page-hits.lisp` | the program. It knows the WIT and nothing else -- no line of it says where the pairs live |

**The definition of done for this todo is that `page-hits.lisp` compiles to a component
and runs against a real host store WITHOUT A SINGLE CHARACTER CHANGING.** That is the
whole claim of `.todo/124`, made falsifiable. Do not write a new example for it; if the
existing one needs to change to work, the design is wrong.

What stops it today, verified 2026-07-14 (these are the two things this todo builds):

```console
$ rontolisp page-hits.lisp -o kv.wasm            # Preview 1
wit/keyvalue.wit:32: 'bucket-get': the WIT type of the result does not cross the
Preview 1 WASM import boundary (...). Its rontolisp representation is settled
(RESULT), and the interpreter and the JVM backend bind it today -- only the WASM
import boundary cannot marshal it yet

$ rontolisp page-hits.lisp -o kv.wasm --component
rontolisp:wit-import is not supported with --component yet: a component's imports
need the canonical-ABI lower, which is not implemented.
```

So the two halves are exactly: (1) the marshalling of the rich types
(`result`/`option`/`variant`/`list<u8>`) -- which also unblocks Preview 1, where a
FLAT interface (scalars/string/bool/handle only) already works today and is
byte-identical to a hand-written `wasm-import` block; and (2) `canon lower` itself.
Land (1) and Preview 1 keyvalue starts working on the way past.

Note `--no-gc` stays a clear refusal by design (its MVP module imports nothing), so
this example's backends will be interpreter / JVM / Preview 1 / component -- not five.

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
