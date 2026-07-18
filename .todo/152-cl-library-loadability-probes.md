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

## Deliverable

Per probe: either the library loads end-to-end (checklist complete), or a
concrete gap list feeding new todos. Track probe results in this file as
they happen.
