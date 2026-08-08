# Wasm size comparison

How big is a rontolisp program once it is a `.wasm` file?

The two programs here, the flag matrix and the reference numbers all follow
[wado-lang/wado `wasm-size/`](https://github.com/wado-lang/wado/tree/main/wasm-size),
a cross-language Wasm binary size comparison (C, Rust, Zig, Moonbit, Wado). That
directory has the other languages' sources; this one adds the rontolisp side of
the same two programs, so the numbers can be read next to each other.

## The programs

| Directory | Program |
| --- | --- |
| [`hello_world/`](hello_world) | Write `Hello, World!` to stdout, and nothing else |
| [`pi_approx/`](pi_approx) | Approximate pi with the Leibniz series, 1,000,000 terms, printed to 15 decimal places |

Each directory holds two sources: the plain one, and a `-nogc` companion for the
`--no-gc` backend (see [The `--no-gc` floor](#the---no-gc-floor)).

## Usage

```bash
./build.sh          # build every variant, run it, check its output, print the table
./build.sh clean    # remove out/
```

It uses `target/rontolisp` when the GraalVM native binary is built and the
executable jar otherwise (override with `RONTOLISP=/path/to/rontolisp`).
`wasmtime` is optional -- without it the modules are still built and measured,
only the output checks are skipped.

## Results

rontolisp 0.1.0-SNAPSHOT, measured 2026-08-08, validated on wasmtime 47.0.2. The
sizes are toolchain- but not host-dependent, so `./build.sh` reproduces them.

| Artifact | Flags | Size (bytes) |
| --- | --- | ---: |
| hello_world | (none) | 124,307 |
| hello_world | `--optimize` | 518 |
| hello_world | `--optimize=size` | 518 |
| hello_world | `--component --optimize=size` | 1,672 |
| hello_world | `--no-gc --optimize=size` | 406 |
| pi_approx | (none) | 124,507 |
| pi_approx | `--optimize` | 2,781 |
| pi_approx | `--optimize=size` | 2,781 |
| pi_approx | `--component --optimize=size` | 3,908 |
| pi_approx | `--no-gc --optimize=size` | 1,042 |

## Cross-language context

The non-rontolisp rows are quoted from the upstream README (measured there on
2026-08-03 with wasi-sdk 25.0, rustc 1.97.1, Zig 0.15.2, Moonbit 0.1.20260803) --
they were **not** re-measured here, so treat them as context rather than as a
controlled benchmark. Every language is built with its own size-optimization
flags; rontolisp's are `--optimize=size` for the Preview 1 command and
`--component --optimize=size` for the component.

### hello_world

| Language | WASI | Size (bytes) |
| --- | --- | ---: |
| rontolisp | Preview 1 | 518 |
| rontolisp | Preview 3 (component) | 1,672 |
| wado | Preview 3 (component) | 1,974 |
| c | Preview 1 | 3,829 |
| zig | Preview 1 | 4,449 |
| moonbit | Preview 1 | 9,227 |
| rust | Preview 1 | 40,365 |

### pi_approx

| Language | WASI | Size (bytes) |
| --- | --- | ---: |
| rontolisp | Preview 1 | 2,781 |
| rontolisp | Preview 3 (component) | 3,908 |
| wado | Preview 3 (component) | 6,034 |
| zig | Preview 1 | 10,608 |
| c | Preview 1 | 18,105 |
| moonbit | Preview 1 | 22,986 |
| rust | Preview 1 | 59,753 |

## Reading the numbers

**`--optimize` is not optional.** Without it a rontolisp module carries the
whole prelude: 124 KB for `hello_world`, 99.6% of which nothing in the program
reaches. `--optimize` is the dead-code tree-shaker -- keep only what `_start`
and the exports reach -- and it is what turns 124,307 bytes into 518. Every
number worth comparing on this page is a tree-shaken one.

**`hello_world` is 518 bytes and imports one function.** A rontolisp command
module imports `wasi_snapshot_preview1.fd_write` and nothing else:

```console
$ wasm-tools print out/hello_world_size.wasm | grep '(import'
  (import "wasi_snapshot_preview1" "fd_write" (func (;0;) (type 0)))
```

That is smaller than the C and Zig builds mostly because it is not the same kind
of module: rontolisp's default WASM backend is **wasm-GC**, so strings and
objects are host-managed GC types and the module ships no allocator, no `malloc`
and no linear-memory bookkeeping. The other Preview 1 rows all carry their own
heap. The like-for-like linear-memory row is `--no-gc` below; the like-for-like
component row is the 1,672-byte one.

**`pi_approx` is now dominated by the arithmetic tower, and both the loop and the
printing are nearly free.** All four rows are the same program at `--optimize`:

| variant | bytes |
| --- | ---: |
| the million-iteration `dotimes` alone, empty body | 203 |
| the loop alone, `(princ "done")` at the end | 1,770 |
| the loop + `(format t "pi = ~,2F~%" ...)` | 2,781 |
| the loop + `(format t "pi = ~,15F~%" ...)` -- the real program | 2,781 |
| the loop + `(princ <the f64 result>)` | 2,292 |

Read off it: the fixed-decimal directive costs **1,011 bytes** (the literal text
included), the digit count does not move it at all, and printing the same float
through `princ` is **489 bytes LESS** than printing it to 15 decimal places -- so
`~,15F` is now the more expensive of the two, by exactly the renderer it carries.

`pi_approx` was 17,012 bytes when this page was first written, more than half of it
the five-line program's own function body. Four findings account for the whole
difference:

- **`~,nF` used to expand INLINE into eight ordinary Lisp forms** -- scale by
  `10^15`, `round` to an integer, `princ-to-string` it, then punch in a decimal
  point with `subseq` and `%string-concat`. It now lowers to one call to a routine
  that builds its digits straight out of an unboxed `f64` (`%fixed-decimal`, 457
  bytes of runtime shared by every site).
- **every numeric coercion used to inline a five-way type ladder**, ~80 bytes at
  each operand of each float operation. This module carried 26 copies, 43% of its
  code section; they are one shared 80-byte function now
  (`.kb/wasm-shared-coercion.md`).
- **the counted loop used to carry a boxed induction variable** -- a generic `<`
  call per iteration whose `t`/nil answer was immediately tested for nullness, plus
  a re-box of the counter per step. A `dotimes` over a literal bound is now a bare
  `i64` counter (`.kb/wasm-counted-loops.md`); the empty loop above went 1,987 ->
  203 bytes, and a 100-million-iteration integer accumulation went 3.49 s -> 0.36 s.
  It is the same at `--optimize=size`, which is why the two levels now agree.
- **`princ` of a value the compiler could not type kept the WHOLE printer
  reachable.** Printing one float cost 3,777 bytes, of which the float printer
  itself is 379 -- the rest was the generic value dispatch, the character-vector
  normalizer in front of it, and the bignum / ratio / character / cons / array
  printers reachable only from there. An argument whose type is decidable at
  compile time now goes straight to its renderer, and that row above is 522 bytes
  over the loop instead of 3,777 (`.kb/optimize-dead-code-elimination.md`).

Together the program's own body went **8,607 -> 224 bytes**, and the whole module
is smaller than the loop alone used to be. What is left is the arithmetic tower
(`_fixed_dec` 457, `_write_stream_str` 253, the shared `_as_f64` 80, and ~1,400
bytes of rational/float helpers behind every `+` and `/` in the loop).

**The component costs about 1.1 KB.** `--component` re-frames the module as a
WASI 0.3 component: the canonical-ABI adapters and the type section are the
whole difference (1,672 vs 518, 3,908 vs 2,781). Its imports are stated as a
WIT world rather than as `fd_write`:

```console
$ wasm-tools component wit out/hello_world_component.wasm
package root:component;

world root {
  import wasi:cli/types@0.3.0;
  import wasi:cli/stdout@0.3.0;

  export wasi:cli/run@0.3.0;
}
...
```

(the interface definitions the world refers to follow)

### The `--no-gc` floor

`--no-gc` emits a plain MVP core module -- no wasm-GC, no `-W gc` needed, a
linear memory it manages itself. It is the closest rontolisp gets to what the C
and Zig rows are: **406 bytes** for `hello_world` and **1,042 bytes** for
`pi_approx`, the smallest artifacts on this page by a wide margin.

Two caveats, which is why these live outside the main table:

- **They are reactors, not commands.** `--no-gc` accepts only `(defun ...)` and
  `rontolisp:wasm-export` at top level, so there is no `_start` and the host
  calls the export by name (`wasmtime run --invoke approx-pi pi_approx_nogc.wasm`).
  The other languages' programs are `_start` commands.
- **`pi_approx-nogc` prints differently.** `format` is outside the `--no-gc`
  subset, so it prints with `princ`, which gives rontolisp's default float shape
  (`3.141591`) instead of 15 decimal places. Same loop, less output -- but that
  is not where the gap to the 2,781-byte wasm-GC build comes from: printing to
  15 places costs 1,011 bytes there against `princ`'s 522. What `--no-gc` is not
  carrying is the GC value model and the generic numeric tower behind every `+`
  and `/` in the loop.

## Flags

| Flag | What it does |
| --- | --- |
| `--optimize` | Dead-code-eliminate: keep only what `_start` and the exports reach |
| `--optimize=size` | The above, plus trade speed for size -- drops fused integer trees and unboxed locals. Both programs here are byte-identical at the two levels, but it costs up to 6x the runtime on integer-heavy code |
| `--component` | Emit a WASI 0.3 component instead of a Preview 1 module |
| `--no-gc` | Emit a plain MVP core module: no wasm-GC, numeric subset only, exports rather than `_start` |
