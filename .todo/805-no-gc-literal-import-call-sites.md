# `--no-gc`: a literal `:string` argument is two constants, and worth nothing until the forwarder is gone

**Status:** open. Measured 2026-09-13 on `fcb0e8738`.

Difficulty: Medium

Twin of [`801`](801-literal-string-argument-round-trips-through-the-gc-heap.md) on the
other backend, with a different mechanism, a different prize, and a prerequisite `801` does
not have.

## The finding that matters most

**On its own this is worth ZERO bytes.** Specializing a call to a `wasm-import` whose
memory-typed arguments are all literals produced a module byte-identical to the baseline,
because on a program written the way a host-facing module tends to be written, **the
literals are not at the import call site**. They are one level up:

```lisp
(defun emit-log (msg) (js-log msg))          ; the import call site: the argument is a PARAMETER
(defun init-app () (emit-log "…"))           ; the literal is here
```

[`800`](800-single-call-site-inliner.md)'s single-call-site inliner is a **prerequisite**,
not a companion. Measured ladder on the browser reactor of
[`804`](804-no-gc-module-surface.md) (four DOM imports, four exports, seven literals):

| | total | code (functions) | types |
| --- | ---: | ---: | ---: |
| baseline | 1,383 | 491 (20) | 129 (24) |
| this item alone | **1,383** | 491 (20) | 129 |
| `800` alone (forwarders inlined) | 1,321 | 457 (16) | 106 |
| `800` + this item | **1,244** | 407 (12) | 83 (16) |

So: `800` is worth 62 bytes here, and this item is worth **77 more on top of it** -- which
is 77 that `800` cannot reach by itself, because inlining the forwarder only moves the
literal to the import wrapper's call site; something still has to notice it is a literal.

## What disappears, measured

Eight functions: the four import wrappers (16 + 26 + 26 + 26 = **94 bytes**) and the four
forwarders (6 + 8 + 8 + 8 = **30**, `800`'s share). A wrapper body is generic address
arithmetic that is constant-folded away for a literal:

```wat
(func (;14;) (param i32 i32) (result i64)   ;; one of three like it
  local.get 0  i32.const 4  i32.add  local.get 0  i32.load     ;; (ptr+4, [ptr])
  local.get 1  i32.const 4  i32.add  local.get 1  i32.load
  call 1
  i64.const 0)
```

Against that, the call sites grow **48 bytes** (14 string arguments go from one constant to
two). Net on the code section -84, types -46, function section -8.

The spike left `i64.const 0` on every `:void` return even where the caller immediately
drops it; a version that looks at statement position (see
[`806`](806-no-gc-internal-void.md)) measures an estimated 1,232.

## The sound version is cheap ON THIS BACKEND

`801` says the call site cannot see that the callee is an import because `importWrappers`
is local to `WasmLispCompiler.compile`. **That is the wasm-GC backend; it is not true
here.** `NoGcWasmCompiler.imports` is already a field (`Map<String,
WasmImportCompiler.Decl>`, kept for `collectCallsCons`). Only `importOrdinals` is a local
of `compile()`; the spike needed one more field. What is left:

- statement-vs-value position, so a `:void` call does not push and drop.
- The wrapper keeps being emitted as an internal function; `--optimize` shakes it when
  nothing else needs it (`#'name`, `funcall`, dispatch). Under `--optimize=off` this is a
  pure 48-byte loss -- gate it, or accept that `off` means what it says.

**No aliasing question arises here, unlike `801`.** The pointer handed to the host is the
module's own permanent literal block -- the same pointer the wrapper computes today -- so
this changes no contract (`.todo/795` still describes it exactly). `801`'s copy into
staging is required on the GC side for a reason that does not exist on this one.

The shared part with `801` is the PREDICATE ("are this call's memory-typed arguments all
literals?"), which belongs on `WasmImportCompiler.Decl`. The emission cannot be shared.

## Sequencing, and an honest note on its worth

`804` landed first (2026-09-13), as planned; then `800`, then this. Two interactions, both
measured:

- `804` item 1's type deduplication therefore collected its full 68 bytes (69 as emitted).
  Had this item and `800` landed first it would have been 33 -- eight fewer functions means
  eight fewer duplicate type entries. Whichever lands second collects less; the baseline
  this item's 139 bytes were measured against is now the post-`804` module (1,382 -> 1,090
  on the reactor), so re-measure before believing the figure below.
- **117 of this item's 139 bytes are bytes an external optimizer already finds.** `-Oz`
  takes the baseline from 1,383 to 1,118; between two `-Oz`ed modules this item is worth
  22. rontolisp ships no optimizer, so all 139 are real shipped bytes -- but by the rule in
  `.kb/optimize-dead-code-elimination.md` this is the *weaker* kind of win, and `801`'s 182
  bytes on the GC side (a round trip no optimizer can see as one) are the stronger kind.
  Rank accordingly if the queue is ever short of time.

The spike material -- benchmark, JS host, size scripts, and the measured `.wat`/diff --
is in [`artefacts/805-no-gc-literal-import-call-sites/`](artefacts/805-no-gc-literal-import-call-sites/).

## Touch points

- `codegen/wasm/NoGcWasmCompiler.java` (`imports`, `importOrdinals`, the call-site
  lowering, statement position)
- `codegen/wasm/WasmImportCompiler.java` (the shared all-literal predicate)
- `.kb/no-gc-scalar-wasm.md`, `.kb/wasm-import.md`
