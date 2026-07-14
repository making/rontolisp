# Component imports: `variant` / `enum` / `result` as a PARAMETER

**Status:** open, unstarted. Small-to-medium. **The keystone of the blob-externalization
line** (`.todo/134`-`136`): both remaining hand-written adapter blobs that COULD go away
are blocked by this one missing capability, and by nothing else.

## The gap

The component import boundary (`.kb/wit.md`, "Component imports") lifts **results**
recursively — a `record`, `variant`, `enum`, `option`, `list<T>`, nested `result` all cross
— but lowers **parameters** flat: a scalar, `bool`, `string`, `list<u8>`, a handle, or an
`option` of those. Anything else is a clear compile error
(`WitImportDirective.validateComponentParam`).

That asymmetry was a deliberate v1 cut, and it is fine for `wasi:keyvalue`. It is NOT fine
for `wasi:http`, where the two most important calls in the whole surface are:

```wit
// the ONE call that sends a serve-mode response  (deps/http/types.wit:368)
set: static func(param: response-outparam,
                 response: result<outgoing-response, error-code>);

// the ONE call that sets an outgoing request's method  (fetch)
set-method: func(method: method);          // `variant method { get, head, post, ... }`
```

Everything else in `wasi:http/types` + `wasi:io` already crosses today (measured by reading
the vendored WIT against the type tiers): handles, `option<handle>`, `list<u8>`, `string`,
`result<_, header-error>` results, the nested `option<result<result<...>>>` of
`future-incoming-response.get`.

## Why it is a modest extension, not a wall

The blocking variants are all **flat-payload**: each case carries a handle (i32), an enum
(i32), or nothing. Their canonical flattening is therefore just
`(discriminant i32, joined-payload i32)` — the exact shape `WasmComponentImportCompiler`'s
`emitLowerOptionParam` already emits (an `option<T>` IS a `variant { none, some(T) }` after
despecialization; the code even computes the payload flats through `WitCanonicalAbi`
already).

So the work is: generalize `emitLowerOptionParam` from "2 cases, one payload" to "N cases,
per-case payload", driven by `WitCanonicalAbi.variantInfo` (which exists and is tested).
**No `record` / `list<T>` / `tuple` parameter support is needed** — do not build the
general memory-spilling lowering; that is a much bigger job (params beyond 16 flats spill
to a caller-allocated area) and nothing in sight needs it.

## The Lisp side

A `variant` parameter arrives as its settled representation — a keyword (`:get`) for a
payload-less case, a tagged list (`(:other . "PATCH")`) when the case carries a payload;
an `enum` is a keyword; a `result` argument is... **decide this**. The result mapping says
a result RETURN is unwrapped (ok = the value, error = a signal), but a result ARGUMENT has
no such precedent. Two candidates:

- the envelope cons the WASM import lowering already uses internally: `(:ok . V)` /
  `(:error . E)` — consistent with `%wit-result`, and the caller is a rontolisp-authored
  library (`.todo/135`/`136`), not a user;
- a tagged list like any other variant (`result` despecializes to
  `variant { ok(T), err(E) }`), i.e. `(:ok . V)` / `(:err . E)`.

They are nearly the same; pick one, pin it in `WitTypeMapper`'s doc and `.kb/wit.md`, and
say so on the doc pages. **This is a user-facing mapping cell — settle it once.**

## Definition of done

- `variant` / `enum` / `result` parameters whose cases are payload-less or carry a flat
  payload cross the `--component` import boundary; a case with a non-flat payload (a
  `record`, a `string`, a `list`) is still a clear compile error naming the WIT line.
- The Lisp-side representation of a `result` parameter is settled and documented.
- Pinned in `WasmComponentImportCompilerTest`; an E2E that actually calls such an import
  (the cheapest real one: `wasi:http/types`'s `outgoing-request.set-method` — but that
  needs `.todo/136`'s scaffolding, so a hand-written probe interface is fine).
- Docs: the `--component` column of the type table in
  `doc/{en,ja}/reference/functions/rontolisp-wit-import.md`, and the "params lower flat"
  paragraph in `doc/{en,ja}/compiling/wasm.md` + `.kb/wit.md`.

## Why this is the keystone

`.todo/135` (serve's HTTP glue in Lisp) and `.todo/136` (fetch in Lisp) delete ~7 KB of the
~12 KB of hand-written adapter blobs, and **both are blocked by exactly this and nothing
else** — verified by reading their WIT surfaces function by function. Land this and both
become ordinary work.
