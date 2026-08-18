# CL library loadability probes — ecosystem enablement over bespoke libraries

Strategic decision (user, 2026-07-18): rontolisp's leverage is that real
Common Lisp libraries load verbatim (split-sequence, cl-who, cl-base64,
parse-number, cl-utilities, jzon in progress). For common needs — UUIDs,
hashing, compression, markdown, pattern matching, URL resolution, routing —
do NOT grow bespoke rontolisp-only libraries; instead, probe the widely-used
community library for each need, record what blocks it, and fix the
blocking INFRASTRUCTURE. Each fixed gap unlocks many libraries; a bespoke
library unlocks one need.

## Probe list (rough ascending expected difficulty; dependency claims need
verification against the actual Quicklisp dist at probe time)

| Need | Library to probe | Expected gates |
|---|---|---|
| UUID v4/v7 | `frugal-uuid` | small; good first probe |
| inflate/gzip | `chipz` | `(unsigned-byte 8)` typed arrays, declaim idioms |
| deflate | `salza2` | same family as chipz |
| URL parse + RFC 3986 relative resolution | `puri` | small pure CL |
| float bit encoding | `ieee-floats` | small; useful for later binary formats |
| utility substrate | `alexandria` | THE most-depended-on library; every symbol that fails is high-leverage |
| regex | `cl-ppcre` | large macro-generated dispatch; second-biggest unlock |
| pattern matching | `trivia` | `symbol-macrolet` (known gate, same as anaphora) + alexandria |
| markdown | `3bmd` | `esrap` (PEG parser, macro-heavy — a good stress test on its own) |
| crypto/hashing | `ironclad` | heavy typed 32-bit arithmetic idioms; farthest out |

## Method per probe

1. `(ql:quickload "<system>")` on the interpreter against the live dist.
2. Record the FIRST failure verbatim; classify: missing CL operator /
   numeric model (bignum, 64-bit) / CLOS-MOP / reader feature / declaration
   idiom.
3. Recurring gaps become their own todos (precedent: `symbol-macrolet` from
   anaphora, `tagbody`/`#.` compile path from jzon in `.todo/146`).
4. A library that loads follows the integration checklist: E2E test via
   `AsdfLibraryE2eSupport`, `guides/asdf-systems.md` entry (en+ja),
   `examples/asdf` demo + README row — then the interpreter -> JVM -> WASM
   wiring order for any language feature it forced.

## Known gates already on record (do not rediscover)

- `symbol-macrolet` — blocks anaphora, likely trivia.
- 64-bit/bignum numerics — blocks jzon's float parse/print path.
- `tagbody`/`go` + `#.` on the compile path — `.todo/146`.
- Gray streams — `GrayStreamsLibrary` exists (jzon work); flexi-streams
  would test its generality.

## Probe results

- **`alexandria` — DONE (2026-07-30).** It became loadable during the
  cl-postgres pass (`.todo/115`) and every dependent since (cl-postgres, s-sql,
  postmodern, quri) has ridden on it, but nothing pinned it on its own. The
  integration checklist above is now complete: the sources are vendored
  unmodified under `src/test/resources/alexandria`, `AlexandriaE2eTest` runs the
  public API of both packages on all four backends via `AsdfLibraryE2eSupport`,
  `examples/asdf/alexandria-demo.lisp` + the README row are the same program, and
  the guide (en+ja) carries the row with its limitation list. Full account:
  `.kb/asdf.md`'s alexandria entry.
  - One correctness bug fell out of it: **`#'mapcar` as a first-class value
    dropped every list but the first on both compile backends** (silently --
    `alexandria:mappend` is `(apply #'mapcar function lists)`). Fixed in the same
    pass (`BuiltinFunctionWrappers.mapcarWrapper`); the rest of the map family
    stays divergent, `.todo/218`.
  - Those four missing primitives (`coerce` to a computed result type,
    `(last list n)`, multi-sequence `every`, `read-sequence` into a character
    buffer) are CLOSED -- `.todo/219`, 2026-07-30. Only `type=` is still dark
    (`subtypep`'s secondary value, `.todo/214`).
- **`cl-ppcre`, `ironclad` — DONE** (see `.kb/asdf.md`); both are on the guide's
  loadable list.
- **`chipz` — DONE (2026-08-10).** The real 0.8 sources load and inflate gzip /
  zlib / deflate on all four backends; the CRC32-only `AsdOverrides` slice is
  retired (`.kb/asdf.md`, `.kb/mito.md`), `ChipzE2eTest` pins it,
  `examples/asdf/chipz-demo.lisp` + the guide row are in, and
  `size-report/programs/zlib` is built on it. Three infrastructure gaps fell out,
  each fixed for everyone rather than for chipz:
  - **`fill` did not exist.** A standard CL function; now a shared macro
    expansion over `%row-major-aset`/`rplaca` with the `replace` string
    deviation. salza2 wanted it too.
  - **`make-array :element-type` ignored a `deftype` alias**, so
    `:element-type 'octet` built a general array of `nil` instead of a packed
    vector of `0` (`.kb/packed-integer-vectors.md`). md5's `ub32` buffers and
    flexi-streams' `octet` buffers pack now as a side effect.
  - **The wasm Gray-streams pre-pass rewrote a defclass SLOT named after a
    stream built-in as if it were a call** (`.kb/gray-streams.md`), and **a
    `funcall` past `MAX_CALLABLE_ARITY` compiled to a call-time signal** instead
    of routing through `apply`'s spread dispatcher.
- **`jose` (JWT/JOSE) + `cl-json` — probed 2026-08-16, `.todo/419`.** jose is a
  `:package-inferred-system` and its deps load unpatched except cl-json, whose
  `.asd` wants three parse widenings (`.todo/420`). With those plus `logtest`
  (`.todo/421`), `jose:encode` runs byte-identically on all four backends
  (HS256/384/512 cross-checked against Python's `hmac`); `jose:decode`'s JSON
  half is unblocked now that `unread-char` works on a handle, and what is left
  is `logtest` (`.todo/421`, which `jose/base64` reaches through
  `trivial-utf-8`) and, on the compiled backends, `progv` (`.todo/423`, which
  cl-json's decoder forces on every program that loads it). RSA needs the ironclad slice widened
  (`.todo/424`) — the real public-key sources were verified to load and
  round-trip.
- **`salza2` (deflate) — verified loadable, deliberately not kept.** 2.1 gzips
  correctly on all four backends once the `deftype`-alias fix above is in (it was
  the library that surfaced it). It is not vendored: nothing in the repo consumes
  compression, and the size-report row wanted the DECOMPRESSOR to be comparable
  with the cross-language table. `(ql:quickload "salza2")` is all it takes to
  bring back; known limits at the time: `salza2:reset` needs `fill` (now present,
  unretested), and `stream.lisp`'s `compressing-stream` compiles with an
  undefined `stream-error-stream` (a condition accessor no part of the Gray
  protocol supplies, `.kb/gray-streams.md`; unowned).

## Deliverable

Per probe: either the library loads end-to-end (checklist complete), or a
concrete gap list feeding new todos. Track probe results in this file as
they happen.
