# `rontolisp:wit-import` on the interpreter + JVM: one WIT, a provider per backend

**Status:** open, unstarted. Step 3 of `.todo/124`. Depends on `.todo/125`.
Can proceed in parallel with `.todo/126`.

## Goal

`(rontolisp:wit-import "wit/kv.wit" :interface "wasi:keyvalue/store@0.2.0" :package kv)`
declares a foreign boundary once, and **the same Lisp source binds to a different
implementation per backend**. This step does the two backends that need no new
encoder work (interpreter, JVM); `.todo/128` does the component.

The point is not "call Java from Lisp" — `java:` already does that. The point is
that a program written against `wasi:keyvalue` can be *developed and tested on the
JVM* against a `HashMap` and then compiled to a component that talks to a real
host, with **zero source changes**. Today `.todo/52` has to invent that parity by
hand ("back it with either an in-memory HashMap or a file store"); with a WIT the
parity is structural.

## How it lowers

Reuse the `wasm-import` shape wholesale (`.kb/wasm-import.md`): each WIT function
becomes a **synthetic defun**, registered in Pass 1, so `#'kv:get` / `funcall` /
`mapcar` / `eval` all work with no extra wiring. `compiler/WitDirective` is the
shared parse result (sibling of `WasmImportDirective`), and the name is
package-resolved exactly like `wasm-import`'s quoted name is today
(`PackageResolver.resolveWasmDirective`) so `(in-package kv)` behaves.

The difference from `wasm-import` is only **what the defun body resolves to**:

- **Preview 1 WASM**: literally today's `wasm-import` — module/field, `--preload`
  or a JS import object. `wit-import` becomes a typed front-end for it.
- **Interpreter** (`eval/WitProviders.java`) and **JVM**
  (`codegen/jvm/JvmWitImportCompiler`): a call into a registered **provider
  object**, marshalled through `WitTypeMapper` on top of the existing
  `JavaInterop` / `JavaBridgeTemplate` machinery — which already does cost-based
  overload selection, sequence marshalling and callback proxying. Today these two
  backends synthesize a defun stub that *signals an error*; this step gives that
  stub somewhere to go.

## The provider question (decide before coding)

How does the JVM know what implements `wasi:keyvalue/store`? Three candidates:

- (a) **Explicit in Lisp**, using existing interop:
  `(java:bind-wit "wasi:keyvalue/store" (java:new "com.example.RedisStore" url))`.
  Zero new infrastructure, honest about being a JVM-only line, and it keeps the
  *program* portable while the *binding* is per-backend (guard with `#+jvm`).
- (b) A Java **SPI** (`WitProvider` interface, `ServiceLoader`) keyed by interface
  name — nothing in the Lisp source at all, but invisible magic and a native-image
  reflection problem.
- (c) A **built-in provider set** for the WASI interfaces we already implement
  natively (`wasi:cli/environment` -> our env built-ins, `wasi:clocks` -> our time
  built-ins, `wasi:random` -> `random`, `wasi:http/handler` -> `fetch`). This is
  not an alternative to (a) — it is what makes a WIT-importing program *run on the
  interpreter out of the box*, and it is where most of the value is.

Recommendation: **(c) + (a)**. Ship built-in providers for the WASI interfaces
rontolisp already implements (so a `wasi:keyvalue` program can at least be *run*
on the interpreter against an in-memory bucket), plus an explicit escape hatch for
user code. Skip (b).

Note the native-image constraint from `.kb/java-interop.md`: the native binary can
*compile* `java:` programs but cannot *interpret* them (no reflection metadata).
A provider bound reflectively inherits that limit; a built-in provider (c) does not
— another reason (c) carries the weight.

## Resources and handles

`wasi:keyvalue`'s `bucket` is a WIT `resource`. Map it to an opaque integer handle
in the **existing stream/socket handle space** (`.kb/read-load-streams.md`), so
`close` already works on it and the whole thing needs no new value type. Pin this:
it is the pattern for every resource-bearing WIT (filesystem, http bodies, sockets).

## Definition of done

- `wit-import` works on interpreter + JVM + Preview 1 WASM; `--component` still
  gives the current clear "not supported" error (lifted by `.todo/128`).
- A `wasi:keyvalue`-shaped program runs on the interpreter against a built-in
  in-memory provider, and on the JVM against a user-supplied one, from one source.
- `examples/browser/webgl-common/gl.lisp` **spike** (do not migrate yet): can a
  hand-written `local:webgl/gl.wit` reproduce its 34 imports, and does `--optimize`
  still shake the unused ones? If yes, that is the proof the browser boundary is
  expressible and `.todo/124`'s follow-on prize is reachable.
- `#'kv:get` as a value, `funcall`, `mapcar` all work (inherited from the synthetic
  defun shape — pin it with a test, it is the property most likely to regress).
- Four-backend + native E2E; docs; `.kb/` note.
