# rontolisp examples

Practical, self-contained rontolisp programs. Unless noted otherwise, each one
runs identically on all three backends (interpreter / JVM / WASM).

Assuming the executable JAR has been built
(`./mvnw clean spring-javaformat:apply package`), set:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
```

| File | What it demonstrates |
| --- | --- |
| [`nqueens.lisp`](nqueens.lisp) | Backtracking search (N-Queens): recursion, list manipulation, functional accumulation, ASCII board output |
| [`life.lisp`](life.lisp) | Conway's Game of Life: a console front-end that `(load ...)`s the rendering-free core (`life-core.lisp`) -- a 30x24 toroidal grid backed by a 2-D `make-array`, neighbour counting with wraparound, and per-generation ASCII rendering |
| [`sorting.lisp`](sorting.lisp) | Quicksort and merge sort over number lists, parameterized by a first-class comparator; cross-checked against the built-in `sort` |
| [`calc.lisp`](calc.lisp) | A tiny prefix-arithmetic interpreter: a recursive evaluator over an alist environment, cross-checked against the built-in `eval` |
| [`mandelbrot.lisp`](mandelbrot.lisp) | ASCII Mandelbrot set: floating-point arithmetic and nested loops (no transcendental functions) |
| [`line-numbers.lisp`](line-numbers.lisp) | A `cat -n` style file tool: `with-open-file`, `read-line`, `write-line`, `format nil`, line/character counts |
| [`parse-numbers.lisp`](parse-numbers.lisp) | Numeric-column parsing and character classification: `parse-integer`, `char`, `alpha-char-p`, `digit-char-p` over file lines |
| [`nn.lisp`](nn.lisp) | Feed-forward neural network learning XOR via backpropagation: vectors as rank-1 arrays and weight matrices as rank-2 `make-array`s, with `aref`, `(setf (aref ...))` and `incf`/`decf` for in-place weight updates |
| [`mlp.lisp`](mlp.lisp) | Generalized multi-layer perceptron for 2-D circle classification, built on the same array-based vector/matrix representation as `nn.lisp` |
| [`maze-rl.lisp`](maze-rl.lisp) | Tabular Q-learning that solves a grid maze: a hash-table Q-table keyed by `(row col action)`, idiomatic `random`-based epsilon-greedy exploration, and an ASCII rendering of the learned path. **Non-deterministic:** because `random` is unseeded and per-backend, the exact path and value count differ on each run and backend (the algorithm always converges to a valid route) |

## Java interop / GUI (JVM only)

These drive real Java APIs through the `java:` interop package, so they run on
the **JVM only** — interpret them (`java -jar $JAR ...`) or compile them to a
`.class` (`-o Prog.class && java Prog`); not the WASM backend (which cannot
lower a java object) and not interpreted in the GraalVM native binary (which
has no reflection metadata for the interop classes) — and need a machine with a
display. See the
[Java interop guide](../doc/en/guides/java-interop.md).

| File | What it demonstrates |
| --- | --- |
| [`java-interop.lisp`](java-interop.lisp) | A minimal Swing window built directly through `java:new`/`java:call`/`java:field`/`java:proxy` -- a button whose `ActionListener` is a rontolisp lambda wrapped in a dynamic proxy |
| [`swing.lisp`](swing.lisp) | A small reusable Swing grid-window helper library, written entirely on top of `java:` (no bespoke Java class); reused as the rendering layer by the GUI demos |
| [`life-gui.lisp`](life-gui.lisp) | Conway's Game of Life animated in a Swing window: loads the same `life-core.lisp` as `life.lisp` plus `swing.lisp`, and steps the world on a `javax.swing.Timer` |
| [`minesweeper/minesweeper-swing.lisp`](minesweeper/minesweeper-swing.lisp) | Minesweeper on the desktop: loads the same `minesweeper-core.lisp` as the browser build (only the drawing differs) and paints a clickable Swing label grid via `swing.lisp` |

## Browser demos (compile to WASM, run in a page)

These are directories rather than single files: a Lisp program is compiled to
`.wasm` and driven from plain HTML/JavaScript via a tiny WASI shim.

| Directory | What it demonstrates |
| --- | --- |
| [`wasm-browser/`](wasm-browser) | Running a rontolisp-compiled `.wasm` in the browser from plain HTML + JavaScript, including feeding stdin from the page |
| [`minesweeper/`](minesweeper) | A playable Minesweeper: the rules (flood fill, win/lose) live in a shared `minesweeper-core.lisp` that both a browser build (compiled to a `--no-wasi` WebAssembly reactor, HTML rendering) and a [Swing build](minesweeper/minesweeper-swing.lisp) load -- only the drawing differs |
| [`hiragana/`](hiragana) | A 46-class handwritten-hiragana recognizer (the full gojuon): a small MLP trained offline in Lisp, baked into an inference `.wasm`, and driven from a `<canvas>` you draw on |
| [`webgl-galaxy/`](webgl-galaxy) | A spiral galaxy animated in WebGL whose physics run in Lisp: the page exposes `drawParticle` (and even `Math.sin`/`Math.cos`) as host functions via `rontolisp:wasm-import`, and Lisp calls them once per star per frame |

## Running

Each example can be run by the interpreter, compiled to a JVM `.class`, or
compiled to a `.wasm` module. Using `nqueens.lisp` as the example:

```bash
# 1. Interpreter
java -jar $JAR examples/nqueens.lisp

# 2. JVM (the class is named after the output file, so keep it path-free)
java -jar $JAR examples/nqueens.lisp -o Prog.class && java Prog

# 3. WASM (requires wasmtime 14+)
java -jar $JAR examples/nqueens.lisp -o nqueens.wasm && wasmtime run -W gc nqueens.wasm
```

`line-numbers.lisp` reads and writes files, so the WASM run needs a preopened
directory:

```bash
java -jar $JAR examples/line-numbers.lisp -o ln.wasm
wasmtime run -W gc --dir . ln.wasm
```
