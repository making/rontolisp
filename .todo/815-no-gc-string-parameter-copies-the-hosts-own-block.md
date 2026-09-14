# `--no-gc`: a `:string` export parameter allocates a second block and copies, when the host already wrote into ours

Difficulty: Medium

**Status:** open, measured 2026-09-14 against `44fce3277`.

## What happens

The host's side of a `:string` export parameter is: call `__ronto_alloc(n)`,
write `n` UTF-8 bytes into the block it returns, call the export with
`(ptr, n)`. The bytes are already in the module's own linear memory, in the
module's own arena.

The wrapper then allocates a SECOND block and copies them:

```wat
(func (;10;) (type 5) (param i32 i32)   ;; (ptr, len) from the host
  global.get 0      ;; save the heap mark
  local.set 2
  local.get 1
  local.set 3       ;; len
  i32.const 4
  local.get 3
  i32.add
  call 11           ;; __alloc(4 + len)      <-- a second block
  local.tee 4
  local.get 3
  i32.store         ;; write the [len] header
  local.get 4
  i32.const 4
  i32.add
  local.get 0
  local.get 3
  call 12           ;; __memcpy(dst+4, hostPtr, len)   <-- the copy
  local.get 4
  call 4            ;; the internal function, with a header pointer
  local.get 2
  global.set 0)     ;; restore the heap mark
```

It has to, today: an internal string is a pointer to `[len:i32 LE][bytes]`
(`.kb/no-gc-scalar-wasm.md`, "Strings"), and the host handed over bytes with no
header in front of them. The only place to put a header is a fresh block.

## What it costs

`size-report/programs/dom_reactor/dom_reactor.lisp` at
`--no-gc --no-wasi --optimize=size`, plus ONE export that takes a runtime string:

```lisp
(defun log-dynamic (msg) (emit-log msg))
(rontolisp:wasm-export 'log-dynamic :as "LogDynamic" :params '(:string) :returns nil)
```

| | before | after | delta |
| --- | ---: | ---: | ---: |
| total | 847 | **1,153** | **+306 (+36%)** |
| code | 191 | 391 | +200 |
| export | 69 | 141 | +72 |
| global | (none) | 7 | +7 |
| data | 440 | 441 | +1 |

The +200 in code is the wrapper, `__alloc` and `__memcpy`; the +72 in exports is
`__ronto_alloc` / `__ronto_alloc_mark` / `__ronto_alloc_reset` becoming part of
the module's surface; the global is the heap pointer. **It is a one-time cost** --
a second and third `:string` parameter add almost nothing -- but a module that
takes ONE string in pays all of it, and it is the largest single step this
backend has.

## The proposal

`__ronto_alloc(n)` reserves four bytes AHEAD of the pointer it returns. The
wrapper then writes the length at `ptr - 4` and passes `ptr - 4` to the internal
function. No second allocation, no `__memcpy`, and (since nothing is staged) no
heap-mark bracket around the call either.

The block the host filled becomes the string, in place.

## What has to be settled first

- **Who may pass what pointer.** Writing at `ptr - 4` is only safe when `ptr`
  came from `__ronto_alloc`. A host that passes a literal's address, or an
  interior pointer into a larger buffer, would have four bytes of something else
  overwritten -- where today's copy is robust to any pointer at all. Either the
  boundary contract says the pointer must come from `__ronto_alloc` (and
  `.kb/wasm-export-no-wasi.md` says so in the host-facing terms it already uses),
  or the wrapper bounds-checks `ptr` against the arena and falls back to the copy
  outside it. The check is a compare; the copy is a loop.
- **`--reentrant`.** `.kb/no-gc-scalar-wasm.md` already records that a
  memory-typed RESULT cannot sit at the un-advanced heap pointer under
  `--reentrant`, because another overlapped call can run before the host decodes
  it. Work out whether the parameter direction has the mirror problem -- the
  host's block stays live for the call either way, but the header write is new.
- **The auto-reset bracket.** `compileWrapperBody` snapshots and restores heap
  global 0 around a non-memory scalar return. With nothing staged there may be
  nothing to reclaim on this path; check before removing it, since the
  `Mem.allocates()` gate is what decides whether a wrapper carries it at all.
- **Alignment.** Reserving four bytes keeps the header at the alignment the block
  had. The `i32.load align=2` that reads a header is a hint in wasm, so this is a
  tidiness question, not a correctness one.
- **The arena API's own shape.** `__ronto_alloc` returning a pointer four bytes
  into its block changes what `__ronto_alloc_mark` / `__ronto_alloc_reset`
  bracket. Nothing outside the module should notice, but the tests that drive the
  arena by hand will.

## What this is NOT

Two other representations would make the boundary free, and both cost more than
they save here:

- **A NUL terminator instead of a header** removes the conversion entirely, but
  `length` becomes an O(n) scan, and a string can no longer contain `#\Nul` --
  which is a character this backend otherwise handles exactly
  (`.kb/characters-code-points.md`).
- **A `(ptr, len)` pair as the internal value** is better on every axis --
  boundary free, `length` O(1), `subseq` a view rather than a copy -- but this
  backend's value model is one wasm value per Lisp value, and a string is one
  `i32` local today. Making it two touches locals, parameters, results and the
  type section everywhere. That is a redesign, not an optimization, and it is not
  what this item asks for.

Keeping the header and reusing the host's block gets most of the size back
without touching either.

## Verify

- A host round trip for `:string` parameters at `--optimize=off` and `=size`:
  empty string, a UTF-8 string (the length is in BYTES), the same buffer passed
  twice, and a call that allocates again between two calls.
- `WasmStringParamBoundaryE2eTest` drives an import's memory-typed PARAMETERS
  through the same raw `(ptr,len)` ABI and is the pattern to copy for the export
  direction.
- The interpreter is the oracle: compile the probe WITH wasi, run under
  `wasmtime`, diff stdout against `java -jar ... probe.lisp`. This backend has no
  `format`, so take strings in as `:string` export parameters rather than
  building them.
