# 810: reproducing the dead literal length headers

`reactor.lisp` -- four host DOM imports taking `:string`s, four thin forwarders, a
recursive `fib`, four exports, eleven literals crossing out. The literal TEXT is
placeholder prose written to the SAME BYTE LENGTHS as the program the item was measured
on, so every section size is identical to the quoted figures.

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR reactor.lisp -o reactor.wasm --no-gc --no-wasi --optimize=size
node dsect.mjs reactor.wasm          # per-section payloads, the type list, the data walk
node host.mjs reactor.wasm           # the four DOM stubs; prints the observed calls as JSON
```

Baseline: raw 930, `gzip -9 -c < reactor.wasm` 650, type 36, import 78, func 6, memory 3,
export 69, code 230, data 484. Measure gzip through `<` -- `gzip -9 -c file` stores the
filename in the header and a longer name reads as a bigger module.

## The hand verification, without building anything

`strip.mjs` does to the emitted binary exactly what the item proposes: it removes the
eleven four-byte headers from the data segment and rewrites the fourteen address constants
in the code section. Every LEB128 immediate keeps its width, so the code section does not
move.

```bash
node strip.mjs reactor.wasm stripped.wasm    # -> 930 to 886, data 484 to 440
wasm-tools validate stripped.wasm
node host.mjs stripped.wasm | diff - <(node host.mjs reactor.wasm) && echo identical
```

## The prototype

`prototype.diff` is the working implementation the spike built (against `c972efa5d`):
`planMemory`'s literal-laying half split into `layoutData`, an all-headered plan handed to
`chooseFoldedImports`, then `withHeaderFree` re-laying the same literal order. Two maps
instead of one -- content-address-for-every-literal and header-address-for-the-headered
ones -- so a value-position use of a header-free literal fails loudly rather than reading
a neighbour's bytes as a length. It also carries the one pinned test that genuinely
changes.

## The probe

`probe/` is the classification harness -- the thing `ng805` is NOT (every `ng805` site has
a runtime argument somewhere, so the fold is declined and its module never exercises this
path at all).

```bash
JAR=/abs/path/to/rontolisp-0.1.0-SNAPSHOT-exec.jar probe/probe.sh
```

It compiles `wasm.lisp` at `--optimize=off` and `=size` with WASI on, runs each under
`node:wasi`, and diffs stdout against the interpreter running `interp.lisp` -- the same
body with the host imports replaced by printing `defun`s. `dumpdata.mjs` walks the data
segment so the classification itself can be read off: which spellings came out headered
and which did not. `nofold.lisp` is the control -- an import with a runtime argument, whose
layout must not move at all.
