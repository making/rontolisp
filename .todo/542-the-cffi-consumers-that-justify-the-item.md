# 542. The CFFI consumers that justify the item, and the docs

Difficulty: Medium

Part of `.todo/537`. Needs `.todo/539`; `.todo/541` decides which backends each consumer
is claimed on. A foreign function interface nobody's library uses is worth nothing -- this
item is where the claim "the ecosystem's C bindings run here" is made true and pinned.

## The consumers, in the order they are worth doing

1. **cl-sqlite** (`sqlite`) -- the demonstration. Pure CFFI, no grovelling, a real database
   in one `ql:quickload`, and rontolisp has no SQLite of its own (`.kb/mito.md` /
   `.kb/dists.md` route everything through postgres). This is the new capability, not a
   replacement for anything.
2. **static-vectors** -- small, no grovel, and a dependency of the fast-io family; a cheap
   second consumer that exercises `with-pointer-to-vector-data`, which the copy-in/copy-out
   backends get wrong most often.
3. **cl+ssl, the real one** -- a probe, NOT a migration. The `cl+ssl` shim over
   `rontolisp:tls-upgrade` (`.kb/tcp-sockets.md`) stays the default: it works on the WASM
   component backend, where CFFI never will, and it needs no OpenSSL on the machine.
   Loading upstream cl+ssl is worth doing once, as the honest measure of how far the
   backend reaches (it is a large, old, `defcvar`-and-callback-heavy binding), and the
   result belongs in the probe table below whichever way it goes.

`cffi-grovel` consumers stay out (`.todo/537`); the failure message is `.todo/539`'s.

## A probe table, the `.todo/152` shape

One row per CFFI consumer tried, saying loads / loads-and-runs / why not, in
`.kb/cffi.md`. That file is also where the invariant goes: **the portable half of upstream
CFFI is not ours** -- it is loaded verbatim, and the only rontolisp-authored pieces are the
`cffi-sys` backend, the `strings.lisp` substitution and the `.asd` replacement. Anyone
tempted to "fix" a cffi file edits the wrong thing.

## Docs and examples

- `doc/{en,ja}/guides/cffi.md`: what loads, what a `defcfun` costs, the native-binary shape
  registration rule (`.todo/541`) stated as a user-facing limit, and the three things that
  are not supported (grovel, `:long-double`, pinning).
- The `ffi:` verbs get their per-operator reference pages (H1 = name, signature, one
  runnable example with `; => value`), a `_catalog.yaml` entry and a row in
  `reference/functions.md` -- runnable means headless and platform-free: `strlen` and
  `getpid`, never a library that may not be installed.
- A row in `doc/{en,ja}/guides/asdf-systems.md`'s built-in-systems table for `cffi`.
- `examples/jvm/cffi-sqlite.lisp` -- a table created, rows inserted and read back through
  cl-sqlite. In `examples.yaml` with the backends `.todo/541` allows, gated on the library
  being present the way the mac-only examples are gated on the platform.

## Acceptance

`ExamplesE2eTest` green on the new example on every backend it declares,
`AsdfSystemsTest` covering the `cffi` system's resolution, a `ci-spec.yaml` case for a
`defcfun` against a symbol the process always has (so the case runs on the backends that
carry it and the refusal is what the others assert), and the probe table filled in from
actual runs rather than from expectation.
