# A literal `:string` argument round-trips linear memory -> GC array -> linear memory

**Status:** open. Measured 2026-09-13 on `64f8cf44e`.

Difficulty: Medium

## The measurement

`789`'s reactor -- two host imports (one taking two `:string`s), a fixnum recursion, two
exports -- `--no-wasi --optimize=size`: **1,658 bytes**, code 1,305 / 21 functions.

Read out of the module, by function:

| Bytes | What |
| ---: | --- |
| 527 | the bignum LIMB tier: `_limb_addsub` 136, `_limb_cmp` 139, limb normalize 162, limb get 49, 41 |
| 203 | `+` 74, `-` 68, compare 47, `<=` 14 |
| 182 | **the literal-string round trip: `_str_build` 69 + `_str_to_mem` 113** |
| 154 | the two import wrappers, 34 and 120 (the two-`:string` one carries 788's staging) |
| 130 | the program itself: `fib` 46, `init-app` 32, the two export wrappers 44, `run-computation` 8 |
| 73 | `_int_new` 35, `_int_val` 38 |
| 11 | `_initialize` (the GC heap pre-grow, `.kb/wasm-gc-heap-pregrow.md`) |

**The program is 10% of its own module.** 41% is the limb tier, which is semantics
(`+` on two fixnums can exceed 2^63 and Common Lisp says it must still be exact) and not
this item.

## The waste this item is about

`(host-log "module initialized")` compiles to, at the call site:

```wat
i32.const 1163    ;; the literal's address, in LINEAR MEMORY, put there by a data segment
i32.const 20
call 4            ;; _str_build: allocate a GC byte array and copy the bytes INTO it,
                  ;;             one i32.load8_u per byte, out of linear memory
call 19           ;; the import wrapper, which calls _str_to_mem: copy the GC array back
                  ;;             OUT to linear memory at HEAP_PTR, one array.get per byte
```

The bytes start in linear memory and end in linear memory, unchanged, having been walked
byte-by-byte into a GC array and byte-by-byte back out. Both loops are in helpers that
exist only for this: in this module `_str_build`'s only caller is `init-app`, and
`_str_to_mem`'s only callers are the two import wrappers.

Linear-memory-to-linear-memory is `memory.copy` -- ONE instruction.

## What to do

**Specialize the call site when a memory-typed argument is a literal.** The compiler knows
the literal's data address and length; the boundary wants `(ptr, len)` of a copy in the
staging region. So emit the staging bump plus `memory.copy` and call the import directly,
instead of building a GC string to hand to a wrapper that immediately unbuilds it.

- **Copy, do NOT pass the data segment's own pointer.** That is
  [`795`](795-no-gc-string-argument-read-only-contract.md)'s hazard and the stated reason
  `789`'s item 2 was rejected: identical literals are deduplicated, so a host write-back
  through such a pointer would corrupt every use of that spelling. A copy into staging has
  none of that -- the saving here is not the copy, it is the two byte-loops and the GC
  array between them.
- The wrapper still has to exist for `#'name`/`funcall`/`mapcar`/dispatch. This is a
  call-site lowering; the shaker drops the wrapper only when no site needs it.
- Second, smaller, and independent: even where a GC string IS wanted, `_str_build`'s loop
  is `array.new_data` -- one instruction -- if the literal lives in a PASSIVE data segment.
  Active segments are dropped after instantiation and `array.new_data` traps on a dropped
  one, so this is a data-section change as well as an emitter one.

## What it is worth, and what that would settle

**Measured**: `_str_build` 69 + `_str_to_mem` 113 = 182 bytes, plus the two import wrappers
154 if every site of both bypasses them. **Estimated, not measured**: the inline
replacement costs roughly 10-15 bytes per site, so the ceiling is around -250 and the floor
around -150.

That range matters because of where 1,658 sits. `wasm-opt -Oz` over the finished module
reaches **1,495** -- so an external optimizer, given everything, gets to within a few bytes
of a hand-written non-GC toolchain's output for the same program, and **the remaining win
has to come from what the emitter emits, not from tidying it afterwards**. This item is the
largest single piece of that which is pure round trip rather than semantics.

For scale: the same program on `--no-gc` is 528 bytes, because that backend has neither the
boxed value model nor the limb tier. The wasm-GC number is what it is for reasons; this
particular 182 bytes is not one of them.

## Touch points

- `codegen/wasm/WasmImportCompiler.java` (`emitStagedMemoryParam`, and the call-site
  decision that currently is not there)
- the expr compiler's user-call path, which needs to see that the callee is an import and
  what its parameter types are -- the `importWrappers` map is local to
  `WasmLispCompiler.compile` today
- `codegen/wasm/WasmStringRuntimeBuilder.java` (`_str_build`, `_str_to_mem`)
- the string table / data section for the passive-segment half
- `.kb/wasm-gc-strings.md`, `.kb/wasm-import.md`
