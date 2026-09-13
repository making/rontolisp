# `--no-gc`: a literal `:string` argument is two constants, and worth nothing until the forwarder is gone

**Status:** open. Ladder re-measured 2026-09-13 after `804` and `800` landed; the prize
itself is not re-measured (it needs the spike again).

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

`800`'s single-call-site move is a **prerequisite**, not a companion -- and it has
LANDED (2026-09-13, `.kb/optimize-dead-code-elimination.md`, "The single-call-site move").
Re-measured on the same `bench.lisp`, after `804` and after `800`:

| | total | code (functions) | types |
| --- | ---: | ---: | ---: |
| after `804`, before `800` | 1,090 | 360 (17) | 57 |
| after `800` (**today's baseline**) | **1,011** | 300 (8) | 47 |

The pre-`804` ladder this item used to quote (1,383 / 1,383 / 1,321 / 1,244) is history in
every row; so is the claim that `804` item 1's share shrinks to 33 bytes, which was written
against a 129-byte type section that is now 47. **Measure the `805` rows against 1,011.**

What has NOT changed is the structural finding: **on its own this is still worth ZERO
bytes**, because the literals are still not at the import call site. What `800` did was put
them there for three of the seven, and the byte-level evidence is now in the module. `800`
inlined the `set-badge-color` chain all the way into `InitApp`, and its argument
substitution wrote the literals back at the wrapper's own address arithmetic:

```wat
i32.const 59  i32.const 4  i32.add  i32.const 59  i32.load    ;; 11 bytes, two constants' worth
i32.const 101 i32.const 4  i32.add  i32.const 101 i32.load
call 3   i64.const 0   drop
```

That is exactly the fold this item is about, sitting in plain sight in the emitted module.

## What is left to take

Three import wrappers survive `800`, because each now has two or three call sites rather
than one: `js_log`'s (16 B, called from `InitApp` and `RunComputation`), `js_set_text`'s
(26 B, twice from `InitApp`) and `js_append_text`'s (26 B, three times from
`AppendLogMessage`) -- 68 bytes of bodies plus their function and type entries. A call site
whose memory-typed arguments are all literals does not need the wrapper at all; when every
site of a wrapper is such a site the wrapper goes with them. The `i64.const 0; drop` pair on
each `:void` return is [`806`](806-no-gc-internal-void.md)'s, and `800` has multiplied it:
an inlined statement-position call now carries the pair inside its caller.

## What the spike measured, BEFORE 804 and 800 (kept for the mechanism, not the numbers)

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

- `804` item 1's type deduplication collected its full 68 bytes (69 as emitted), and `800`
  then took the type section from 57 to 47 over 8 surviving functions. Whichever lands last
  collects least, and this item is now last: the 139 bytes above were measured against a
  1,383-byte module with 20 functions and 24 type entries, and today's is 1,011 with 8 and
  12. **Re-measure; do not carry 139 forward.**
- **117 of this item's 139 bytes were bytes an external optimizer already found.** `-Oz`
  took the pre-`804` baseline from 1,383 to 1,118; between two `-Oz`ed modules this item was
  worth 22. rontolisp ships no optimizer, so all 139 are real shipped bytes -- but by the rule in
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
