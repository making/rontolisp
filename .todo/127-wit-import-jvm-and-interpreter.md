# `rontolisp:wit-import` on the interpreter + JVM: one WIT, a provider per backend

**Status:** IMPLEMENTED + PUSHED 2026-07-14 (`21a0d87`); **verification deliberately
deferred.** Step 3 of `.todo/124`. Full mechanics and every decision record: `.kb/wit.md`.

**Do not run the owed verification on its own** (user decision, 2026-07-14): `.todo/128`
adds a fourth lowering target to the very same `WitImportInliner`, and will likely move
`ci-spec.yaml` again, so the suite + the native E2E are run ONCE, at the end of `.todo/128`,
covering both. The checklist is in "Remaining" below and is repeated at the top of
`.todo/128`. Delete this file only when that run is green.

## What shipped

A compile-time front-end that **lowers into forms that already exist** -- the
`wit-export` design. No backend gained a codegen case.

| backend | `(rontolisp:wit-import "kv.wit" :interface "..." :package kv)` lowers to |
|---|---|
| Preview 1 WASM | one `rontolisp:wasm-import` per WIT func. **Measured byte-identical** to the hand-written block, and identical again under `--optimize` with a never-called import shaken out |
| interpreter / JVM | a synthesized `(defpackage kv ...)` + one ORDINARY `defun` per func calling `rontolisp::%wit-call`. Ordinary defuns, so `#'kv:get` / `funcall` / `mapcar` / `eval` work with no wiring |
| `--component` | clear error (needs `canon lower` -- `.todo/128`) |
| `--no-gc` | clear error (its MVP module imports nothing) |

New: `am/ik/wit/WitResolver.java`, `compiler/WitImportDirective.java`,
`eval/WitImportInliner.java`, `eval/WitLibrary.java` +
`resources/am/ik/rontolisp/eval/wit.lisp`. `WitImportInliner` runs **BEFORE**
`UserMacroExpander` (its synthesized `defpackage` must exist before any pass resolves a
`kv:get` call site) -- the opposite of `WitExportInliner`, and the thing a future reader
will get wrong.

## Decisions taken (all recorded in `.kb/wit.md`)

- **The core ships NO provider for any interface** -- this REVERSES this todo's own
  recommendation of option (c). rontolisp knows the provider *mechanism*, not what
  `wasi:keyvalue` is; a built-in store would pin one third-party spec's id, member names
  and version into a Lisp's core, contradicting `.todo/124`'s bet that a new host
  interface costs a `.wit` file rather than core code. A store is ordinary user code:
  `examples/wit/keyvalue/{memory-store,java-store}.lisp`.
- The escape hatch is `rontolisp:wit-provide` taking a **Lisp callable**, not the todo's
  `java:bind-wit` (JVM-only, un-interpretable in the native binary, and it would drag in
  the hand-synced `JavaInterop`/`JavaBridgeTemplate` pair). A Java-backed store is then a
  few lines of Lisp over the existing `java:` interop -- `java-store.lisp` is exactly that.
- `:field-style` defaults to `:camel` (the WIT label `create-shader` -> the Preview 1
  import field `createShader`, the JS convention and what jco produces).
- A WIT resource is an opaque integer handle allocated **by the provider** -- NOT the
  shared stream/socket handle space this todo sketched. `cl:close` does not apply to a WIT
  resource (a resource is released by its own interface's drop, which `wasi:keyvalue`'s
  store does not even expose). The shared space becomes relevant at the component ABI.
- The **`gl.lisp` spike is answered**: a hand-written `local:webgl/gl.wit` reproduces the
  boundary byte-for-byte and `--optimize` still shakes the unused imports. 28 of gl.lisp's
  31 imports are an exact kebab->camel match; the 4 that are not are cases where gl.lisp
  chose *different words* on the Lisp side. A WIT label is ONE name serving both sides, so
  it cannot keep them -- and the user decided the WIT-generated names win and `wit-import`
  gets **no** alias option. The migration is `.todo/132`.

## Found on the way (separate work, do not fold in)

- `.todo/131` -- a **pre-existing JVM bug**: `handler-case`/`ignore-errors` in ARGUMENT
  position (non-empty operand stack) emits a class the verifier rejects, with no compile
  error. Unrelated to this todo; keep `handler-case` at statement position meanwhile.
- `.todo/132` -- the WebGL demos adopt `gl.wit` (unblocked by the spike above).

## Remaining before this file can be deleted

- [ ] **Full `./mvnw test` re-run after the `examples/wit/` directory move.** The suite was
      green (3607 / 0 failures) BEFORE the move; the move touched `examples/examples.yaml`,
      the docs and comments only, so `ExamplesE2eTest` + `DocExamplesTest` are the two that
      can break -- but they have not been run since.
- [ ] **Native `CiSpecE2eTest`.** `src/test/resources/ci-spec.yaml` CHANGED (the
      `rontolisp:list-functions :rontolisp` expectation gained `wit-error-payload` and
      `wit-provide`), and CLAUDE.md says a `ci-spec.yaml` edit must be verified against the
      native binary -- a plain `./mvnw test` SKIPS that test, so only the CI native-image
      job would catch a mistake.
- [ ] `./mvnw -Pweb compile` was green before the move; re-confirm.
