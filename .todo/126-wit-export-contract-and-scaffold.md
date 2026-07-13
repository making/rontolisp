# `rontolisp:wit-export`: implement-this-world contract checking + scaffolding

**Status:** open, unstarted. Step 2 of `.todo/124`. Depends on `.todo/125`
(`am.ik.wit` + `WitTypeMapper`).

## Goal

Turn an existing `.wit` **world** from something we emit into something we are
*checked against*: declare "this program implements this world", and let the
compiler verify every export's name, arity and types against it.

```lisp
(rontolisp:wit-export "wit/analyzer.wit" :world analyzer)

(defun analyze (text)            ; WIT: analyze: func(text: string) -> s32
  (length text))
```

No `:params '(:string) :returns :int` — the types come from the WIT. Today those
lists are hand-written next to a WIT we separately generate, so the two can drift
and nothing notices until `wasmtime --invoke` fails at runtime.

## What it must catch at compile time

The failure modes that currently only surface under wasmtime/jco:

- an export named in the world with **no matching defun** (or vice versa: a defun
  exported that the world does not declare)
- **arity** mismatch, and parameter/result **type** mismatch after `WitTypeMapper`
- a name that violates the component `label` grammar (lower-kebab-case) — today a
  runtime-ish constraint checked ad hoc in `WasmLispCompiler` via
  `COMPONENT_EXPORT_NAME`, fix-with-`:as`; a WIT world makes the *correct* name
  authoritative instead
- `"run"` collisions
- **`async func` in the WIT but no `:async t` on the defun** (and the reverse).
  This one is worth the whole todo on its own: `.kb/wasi-component.md` says an
  I/O-bearing sync-lifted export traps at runtime with "cannot block a synchronous
  task", and `:async` is deliberately *not* auto-detected. A WIT world states the
  answer declaratively, so the mismatch becomes a compile error instead of a trap.
- `:long`/`s64` used where the backend does not allow it (GC path rejects it; only
  `--no-gc --component` accepts) — the existing rule, now reported against the WIT
  line that asked for it.

## How it lowers

`wit-export` is a compile-time directive that expands into the existing
`rontolisp:wasm-export` `Decl`s — it is a **front-end for machinery that already
exists**, not a new export path. `WasmComponentBuilder.appendFuncExports` /
`NoGcWasmComponentBuilder` are untouched; the generated component must be
**byte-identical** to the one the equivalent hand-written `wasm-export` directives
produce today. That byte-identity is the acceptance test (same stash-dance proof
used for todo 92: base / http / sock / serve / P1 / `--no-gc` variants).

`--wit` then becomes a consistency check rather than a generator: emitting the
world we were handed must reproduce it (modulo the deliberate serve `use`-clause
deviation from `.todo/125`).

On the non-WASM backends the directive is inert (like `wasm-export` is today), but
it should still **type-check**, so an interpreter run catches the mismatch too.

## Scaffolding (the other half, cheap once the parser exists)

```
rontolisp --scaffold-wit wit/analyzer.wit -o analyzer.lisp
```

emits a runnable skeleton: `wit-export` directive, one `defun` stub per export with
the WIT doc comments carried over as Lisp `;;` comments (hence "preserve doc
comments" in `.todo/125`), the parameters named as the WIT names them, and a body
that signals "not implemented". Small, high demo value, and it is the natural
answer to "someone handed me a `.wit`, now what".

## Definition of done

- `wit-export` compiles a world's implementation on both WASM component paths,
  byte-identical to hand-written `wasm-export`s.
- Every mismatch above is a clear compile error naming the WIT file and line.
- `--scaffold-wit` produces a file that compiles unchanged (stubs signal at run
  time, not compile time).
- `examples/count-vowels/` migrates to it — it already has a checked-in
  `count_vowels_component.wit`, which becomes the *input* rather than the output.
  Its `.wit` must round-trip unchanged through the new pipeline.
- Four-backend + native E2E; docs (en/ja) for `wit-export` + `--scaffold-wit`;
  `.kb/wasi-component.md` updated.
