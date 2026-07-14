# WIT as rontolisp's universal IDL (roadmap anchor)

**Status:** ROADMAP COMPLETE 2026-07-14 (all four steps shipped); kept open as the
anchor for the follow-on frontier (`.todo/133`-`136`, `.todo/132`) — see the roadmap
table. **step 1 (`.todo/125`) DONE 2026-07-13** — `am.ik.wit` shipped,
`WitEmitter` migrated onto it, the type mapping settled in `compiler/WitTypeMapper`
and recorded in `.kb/wit.md` (todo file deleted on completion). **Step 2
(`.todo/126`) DONE 2026-07-14** — `rontolisp:wit-export` (contract check + lowering
into `wasm-export`, byte-identical components) and `--scaffold-wit`, recorded in
`.kb/wit.md` (todo file deleted on completion). Raised 2026-07-13, immediately after
`--wit` closed todos 92+93. Anchor todo: the design and the type mapping live here;
the remaining executable steps are `.todo/127` and `.todo/128` (the import side).

## The idea

`--wit` made rontolisp *emit* WIT. The interesting direction is the reverse:
**consume an existing `.wit` as the single description of a foreign boundary**,
and let every backend bind that same description to whatever it can reach.

Today the "call something outside the program" vocabulary is split three ways and
none of the three know about each other:

- `java:` interop — reflection, interpreter + JVM only (`.kb/java-interop.md`).
- `rontolisp:wasm-import` — Preview 1 core modules only; `--component` rejects it
  outright with an `UnsupportedOperationException` (`.kb/wasm-import.md`).
- Hand-written per-feature surfaces: `rontolisp:http-handler`'s request/response
  plists, `rontolisp:fetch`, `rontolisp:tcp-*`, `examples/browser/webgl-common/gl.lisp`'s
  31 hand-declared imports.

A component compiled today therefore **cannot import anything but the fixed WASI
blob surface** (`src/wasm-component/uni.wit`), which is the single biggest hole in
the component story — it is why `.todo/52` (wasi:keyvalue) and `.todo/53`
(wasmCloud) are both blocked on plumbing rather than on ideas.

## Why this is not a new concept — the seams already exist

1. **`compiler/WasmImportDirective.java` is already a backend-shared directive**
   whose JVM lowering is *a synthesized defun stub that signals an error*. The
   whole proposal is: keep the shape, make the stub resolve to an implementation.
2. **An import is already a defun.** `.kb/wasm-import.md`: because a
   `wasm-import` registers a synthetic defun in Pass 1, `#'name` / `funcall` /
   `mapcar` / dispatch / `eval` work with zero extra wiring. Anything WIT-derived
   inherits that for free.
3. **`LoadInliner` / `UserMacroExpander` already read files at compile time** and
   splice AST. A WIT-reading macro is the same motion as `(require :gl "gl.lisp")`.
4. **Every WIT type already has a house representation** (below). We are naming
   seven existing conventions, not inventing them.

## The type mapping (the load-bearing table — settle it in `.todo/125`)

| WIT | rontolisp | Precedent already in the tree |
|---|---|---|
| `s32`/`u32` | int | `wasm-export` `:int` |
| `s64`/`u64` | int (bignum-safe) | `:long`, today valid only on `--no-gc --component` |
| `f32`/`f64` | float | `wasm-export` `:float` (VT_F64) |
| `bool` | `t`/`nil` | `wasm-export` `:bool` |
| `string` | string | `wasm-export` `:string`, canonical UTF-8 ABI (todo 92 Tier 2) |
| `char` | character | reader's character type |
| `list<u8>` | string OR byte list | the fetch/socket string<->linear-memory marshalling |
| `list<T>` | proper list | `java:` interop's `marshalSequence` |
| `record` | keyword plist | **`rontolisp:http-handler`'s request/response plists** |
| `enum` | keyword | `:async`-style keyword args throughout |
| `variant` | `(tag . payload)` tagged list | the defstruct/CLOS tagged-list value model |
| `option<T>` | value or `nil` | Lisp itself |
| `tuple<A,B>` | list | — |
| `result<T,E>` | ok value; error arm signals a condition on EVERY backend | **SETTLED 2026-07-13: option (c)** — decision record in `.kb/wit.md`; WASM catch = prerequisite of `.todo/128` |
| `resource` | opaque integer handle | streams/sockets share one handle space (`.kb/read-load-streams.md`) |
| `flags` | list of keywords | — |

**`result<T,E>` is SETTLED (2026-07-13, user decision in `.todo/125`): option (c)**
— condition on every backend, catchable with `handler-case`; on WASM, where
`handler-case` is still a compile-time error, signaling traps with the message as a
*temporary limitation, not a contract*, and a WASM catch mechanism became a
prerequisite of `.todo/128`'s result-returning imports. `list<u8>` = byte string
(fetch/socket convention). Full rationale incl. why (a) multiple-values was rejected
despite being implementable today over `%mv-spill`: `.kb/wit.md`; the machine-checked
table: `compiler/WitTypeMapper` + `WitTypeMapperTest`.

## The Lisp-facing surface (sketch)

```lisp
;;; one file, four backends
(rontolisp:wit-import "wit/keyvalue.wit"
                      :interface "wasi:keyvalue/store@0.2.0"
                      :package kv)

(defun cache-get (bucket key)
  (handler-case (kv:get bucket key)
    (kv:error (e) (format t "miss: ~a~%" (kv:error-message e)) nil)))
```

and, symmetrically, WIT as a contract to *implement*:

```lisp
(rontolisp:wit-export "wit/analyzer.wit" :world analyzer)

(defun analyze (text)          ; WIT says: analyze: func(text: string) -> result<stats, string>
  (list :words 42 :chars 100)) ; arity/type mismatch => compile error
```

This subsumes `wasm-export`'s hand-written `:params '(:string) :returns :int` —
the types stop being maintained in two places.

## Package layout

```
am/ik/wit/                     # NEW, language-independent — same tier as am.ik.jvm / am.ik.wasm
  WitParser.java               # .wit text -> model
  WitPrinter.java              # model -> .wit text (WitEmitter migrates onto this)
  WitType.java                 # sealed: Prim | List | Record | Variant | Enum | Option | Result | Tuple | Flags | Resource
  WitFunc / WitInterface / WitWorld / WitPackage   # records

am/ik/rontolisp/compiler/
  WitDirective.java            # backend-shared parse result — sits next to WasmImportDirective
  WitTypeMapper.java           # the table above; the ONE source of truth for marshal/unmarshal

codegen/wasm/WasmWitImportCompiler.java   # canon lower -- the only heavy new codegen (.todo/128)
codegen/jvm/JvmWitImportCompiler.java     # bind to a provider object via the JavaBridge marshaller
eval/WitProviders.java                    # same, interpreted
```

`am.ik.wit` must obey the same rule as its siblings: **no rontolisp imports**,
no external dependencies.

## Roadmap

| Step | Todo | Weight | Unlocks |
|---|---|---|---|
| 1 | ~~`.todo/125`~~ **DONE 2026-07-13** — `am.ik.wit` parser/printer + settled mapping; `WitEmitter` on `WitPrinter`; variants renamed (`http-client`/`http-server`/`http-server-client`/`sockets`); `.kb/wit.md` | medium | self-validated via `WitOracleE2eTest` |
| 2 | ~~`.todo/126`~~ **DONE 2026-07-14** — `wit-export`: implement-this-world contract checking (compile path + interpreter) lowered into `wasm-export` (byte-identical components), `wasm-export :param-names`, `--scaffold-wit`; `.kb/wit.md` | small | killed the `:params`/`:returns` double-maintenance |
| 3 | ~~`.todo/127`~~ **DONE 2026-07-14** — `wit-import` on interpreter + JVM (provider binding); the core ships NO provider for any interface | medium | one Lisp source, host impl per backend |
| 4 | ~~`.todo/128`~~ **DONE 2026-07-14** — component imports (`canon lower`): instance import + per-function lower, rich results, member pruning, `--emit-wit` import side | **large** | wasi:keyvalue against wasmtime's REAL host, component composition |

**THE ROADMAP IS COMPLETE (2026-07-14).** All four steps shipped; `.kb/wit.md` is the
record. `.todo/52` (wasi:keyvalue) was the designated proof of step 4 and it landed —
`examples/wit/keyvalue` runs one source against a Lisp store (interpreter), a Java map
(JVM) and **wasmtime's own `wasi:keyvalue` implementation** (component), with identical
output.

**What remains of this file is the "absorbed afterwards" list below — and it is now the
actionable frontier**, with the blocker identified. Concretely:

| | |
|---|---|
| `.todo/133` | `variant`/`enum`/`result` as a component-import PARAMETER. **The keystone** — the only thing blocking 135 and 136, both verified function-by-function |
| `.todo/134` | serve mode accepts user WIT imports (small; an HTTP server with a real store) |
| `.todo/135` | serve's HTTP glue through WIT — the "http-handler becomes a world" bullet below |
| `.todo/136` | `rontolisp:fetch` through WIT — deletes ~10.5 KB, the biggest hand-written blob |
| `.todo/132` | the WebGL demos adopt `local:webgl/gl.wit` — the gl.lisp bullet below |

The blobs that CANNOT be externalized, so nobody re-proposes it: the **base adapter**
(the core's Preview-1-identical `wasi_snapshot_preview1` import layout is what every
`FUNC_*` constant rests on — and every program uses it), the **serve preview1 bridge**
(the same thing in miniature), the **`--no-gc` print micro-adapter** (a different backend;
`wit-import` is rejected there by design), and **`wasi:sockets`** (its 0.3 surface is
fundamentally `stream`/`future`-based, which has no rontolisp value on ANY backend until
language-level async — a wall, not a gap).

## What gets absorbed afterwards (the follow-on prize, not a step)

- `rontolisp:http-handler` becomes "a program implementing the
  `wasi:http/incoming-handler` world", with the request plist **derived** from the
  WIT `record` instead of hand-shaped differently per backend.
- `gl.lisp`'s 31 `wasm-import` directives become a `local:webgl/gl.wit`, from which
  the demos' **JS import object can also be generated** — today the handle-table
  bindings in each `index.html` are hand-written against a Lisp-side declaration
  with nothing checking the two agree.
- `wasm-import`/`wasm-export` stay as the low-level, WIT-free escape hatch (a JS
  import object is not a component and never has a `.wit`), exactly as
  `LoadInliner` coexists with ASDF.

## References

`.kb/wasm-import.md`, `.kb/wasi-component.md` (the `--wit` section — `WitEmitter`,
`WitOracleE2eTest`), `.kb/java-interop.md`, `.kb/error-handling.md` (the WASM
catch prohibition that shapes `result<T,E>`), `.kb/fetch-http.md`,
`.todo/52` (wasi:keyvalue), `.todo/53` (wasmCloud gaps), `.todo/02`
(the `wasi:http@0.2` island — a precedent for a temporary mixed-version import).
