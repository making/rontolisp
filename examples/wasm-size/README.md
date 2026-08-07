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

rontolisp 0.1.0-SNAPSHOT, measured 2026-08-07, validated on wasmtime 46.0.1. The
sizes are toolchain- but not host-dependent, so `./build.sh` reproduces them.

| Artifact | Flags | Size (bytes) |
| --- | --- | ---: |
| hello_world | (none) | 318,599 |
| hello_world | `--optimize` | 518 |
| hello_world | `--optimize=size` | 518 |
| hello_world | `--component --optimize=size` | 1,672 |
| hello_world | `--no-gc --optimize=size` | 406 |
| pi_approx | (none) | 327,227 |
| pi_approx | `--optimize` | 17,012 |
| pi_approx | `--optimize=size` | 16,083 |
| pi_approx | `--component --optimize=size` | 17,265 |
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
| wado | Preview 3 (component) | 6,034 |
| zig | Preview 1 | 10,608 |
| rontolisp | Preview 1 | 16,083 |
| rontolisp | Preview 3 (component) | 17,265 |
| c | Preview 1 | 18,105 |
| moonbit | Preview 1 | 22,986 |
| rust | Preview 1 | 59,753 |

## Reading the numbers

**`--optimize` is not optional.** Without it a rontolisp module carries the
whole prelude: 318 KB for `hello_world`, 99.8% of which nothing in the program
reaches. `--optimize` is the dead-code tree-shaker -- keep only what `_start`
and the exports reach -- and it is what turns 318,599 bytes into 518. Every
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

**`pi_approx` is dominated by printing the answer, not by computing it.** The
whole million-iteration loop -- the f64 accumulate, the divide, the sign flip --
is 3,778 bytes with a `princ` at the end. Printing the result to 15 decimal
places is the other 13,234.

Almost all of that is one directive. A literal control string is parsed at
compile time, so no directive interpreter ships; instead `~,15F` *expands* into
eight ordinary Lisp forms (scale by `10^15`, `round` to an integer,
`princ-to-string` it, then punch in a decimal point with `subseq` and
`%string-concat`) which are emitted **inline into the caller**, each generic
operation carrying its own numeric type ladder. The result: half the module is
the five-line program's own function body. `~,2F` costs the same as `~,15F`, so
it is the shape of the lowering rather than the digit count. Replacing the
`format` call with `princ` -- same loop, same everything else -- drops the module
from 16,083 to 7,930 bytes.

**The component costs about 1.2 KB.** `--component` re-frames the module as a
WASI 0.3 component: the canonical-ABI adapters and the type section are the
whole difference (1,672 vs 518, 17,265 vs 16,083). Its imports are stated as a
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
  (`3.141591`) instead of 15 decimal places. Same loop, less output -- and, as
  the `format` finding above says, that accounts for most of the difference
  between 1,042 and 16,083 bytes.

## Flags

| Flag | What it does |
| --- | --- |
| `--optimize` | Dead-code-eliminate: keep only what `_start` and the exports reach |
| `--optimize=size` | The above, plus trade speed for size -- drops fused integer trees and unboxed locals. Free here (`hello_world` is unchanged; `pi_approx` is a float kernel, so it gains 5.5%), but it costs up to 4x the runtime on integer-heavy code |
| `--component` | Emit a WASI 0.3 component instead of a Preview 1 module |
| `--no-gc` | Emit a plain MVP core module: no wasm-GC, numeric subset only, exports rather than `_start` |
