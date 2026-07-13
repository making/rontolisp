# `am.ik.wit`: a WIT parser + printer, and the settled type mapping

**Status:** open, unstarted. Step 1 of `.todo/124` (read that first — the type
mapping table and the `result<T,E>` question live there).

## Goal

A language-independent WIT library, a sibling of `am.ik.jvm` / `am.ik.wasm` (no
rontolisp imports, no external dependencies), that can **read** a `.wit` file into
a model and **print** a model back to `.wit`. Everything downstream (`.todo/126`,
`127`, `128`) consumes this model; nothing downstream re-parses text.

## Why this step validates itself for free

`WitEmitter` today renders WIT by **splicing export lines into one fixed text
template per blob variant** (`component/wit/{base,http,sock,serve,serve-http,nogc,nogc-print}.wit`),
and `WitOracleE2eTest` byte-diffs the result against `wasm-tools component wit`
on the real bytes. So:

1. Build `WitParser` + `WitPrinter`.
2. Parse each of the seven templates and print them back — must be byte-identical
   (a pure round-trip test, no new oracle needed).
3. Re-implement `WitEmitter` as "build a `WitWorld` model, print it", deleting the
   template-splicing.
4. `WitOracleE2eTest` — which already exists and already passes — is now the
   regression test for the *new* implementation, unchanged.

If step 3 breaks byte-identity with `wasm-tools`, the existing test says so. This
is the cheapest possible way to get a trustworthy parser: the printer is proven
first, against a tool we do not control.

Keep the one deliberate deviation intact: the **serve** templates restore
`incoming-handler`'s `use types.{incoming-request, response-outparam};` clause,
which `wasm-tools` drops (printing WIT that does not re-parse). That deviation
must survive the migration, and now it is *also* a parser test case — the emitted
file must round-trip through our own parser.

## Scope of the parser

Parse the WIT the ecosystem actually ships, not the whole grammar at once:

- `package`, `world`, `interface`, `use`, `import`, `export`, `include`
- `func` (params, one result), `async func`
- types: the primitives, `list`, `option`, `result`, `tuple`, `record`, `variant`,
  `enum`, `flags`, `type` aliases, `resource` (+ methods / `static` / constructor)
- versions (`@0.3.0`), gated attributes (`@since`/`@unstable`) — at minimum parse
  and preserve; semantics can be ignored initially
- doc comments (`///`) — **preserve them**; `.todo/126`'s scaffolding wants to
  copy them into generated Lisp, and the printer needs them for round-trip

Corpus to test against: everything under `src/wasm-component/deps/**` (cli, clocks,
filesystem, http, io, random, sockets — both 0.2 and 0.3 versions) plus the seven
`WitEmitter` templates and `examples/count-vowels/count_vowels_component.wit`.
Round-trip each. `wasi:filesystem/types` and `wasi:http/types` are the hard ones
(resources, big variants) and are the real acceptance bar.

## Deliverable 2: settle the type mapping

`compiler/WitTypeMapper.java` — the single source of truth for
WIT type <-> rontolisp value, per `.todo/124`'s table. This class has no codegen
in it; it is the shared vocabulary that the JVM, interpreter and WASM binders each
consult, exactly as `WasmImportDirective` is shared today.

**Blocking decision, make it here:** `result<T,E>` (options (a)/(b)/(c) in
`.todo/124`). Recommendation: the error arm signals a condition on
interpreter/JVM; on WASM it traps with the message, because `handler-case` is a
compile-time error on every WASM backend (`.kb/error-handling.md`). Write the
decision and its rationale into `.kb/` when made — every later step depends on it,
and reversing it later is a breaking change to user code.

Second decision: `list<u8>` — string, or a list of ints? The fetch/socket code
already crosses bytes as strings; `wasi:keyvalue` values are `list<u8>`. Probably
"string, with an explicit bytes<->string helper", but pick and pin it.

## Definition of done

- `am.ik.wit` round-trips the whole `deps/**` corpus byte-identically.
- `WitEmitter` is a thin `WitPrinter` caller; `WitEmitterTest` +
  `WitOracleE2eTest` pass unchanged; the emitted `.wit` is still byte-identical to
  `wasm-tools component wit` on every non-serve variant.
- The template resources are deleted (or reduced to the fixed import lists), and
  the native-image `resource-config.json` entry shrinks accordingly.
- `WitTypeMapper` exists with the mapping settled and documented; the
  `result<T,E>` decision is written down in `.kb/`.
- Javadoc clean; `./mvnw test` green; no new module in the reactor (it is a package
  inside the main artifact, like `am.ik.wasm`).

## Non-goals

No Lisp-facing surface at all in this step. `wit-import` / `wit-export` are
`.todo/126`+ — this step ships a library and a refactor with zero user-visible
behavior change (the emitted bytes and text are identical before and after).
