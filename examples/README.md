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
| [`sieve.lisp`](sieve.lisp) | Sieve of Eratosthenes: boolean `make-array` as the sieve, `aref`/`(setf (aref ...))`, list accumulation via `push`/`reverse`, and prime factorization with `sqrt`/`ceiling` |
| [`hanoi.lisp`](hanoi.lisp) | Tower of Hanoi: classic recursive puzzle solver with both a printing variant and a list-returning variant (`&optional` argument), plus `expt` for the move count formula |
| [`roman.lisp`](roman.lisp) | Roman numeral encoder/decoder: bidirectional conversion (1↔I, 4↔IV, …, 3999↔MMMCMXCIX) with association-list lookup tables, `concatenate`, and a full 3999-value round-trip correctness check |
| [`word-frequency.lisp`](word-frequency.lisp) | Word frequency counter: hash-table accumulation, `string-downcase`, `alpha-char-p`-based tokenization, `sort` with a custom comparator, and `maphash` iteration |
| [`contact-book.lisp`](contact-book.lisp) | Contact book using `defstruct`: tagged-list structs with `setf`-able accessors, `&key` lambda lists, hash-table storage, and `maphash`-based lookup |
| [`l-system.lisp`](l-system.lisp) | L-system (Lindenmayer system) fractal generator: string-rewriting via hash-table rule dispatch, `&rest` variadic arguments, and character-frequency analysis (Sierpinski triangle, Koch curve, Dragon curve) |

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
| [`webgl-triangle/`](webgl-triangle) | The WebGL hello world and the smallest `rontolisp:wasm-import` program: Lisp compiles two shaders and draws one colored triangle through ten imported host functions -- no exports, no frame loop; the page just calls `_initialize()` |
| [`webgl-cube/`](webgl-cube) | Hello 3D: a rotating cube whose perspective projection and rotation matrices are computed in Lisp every frame (4x4 matrix math on `make-array`s); bulk floats (geometry, the mat4 uniform) cross the boundary through a small staging array |
| [`webgl-galaxy/`](webgl-galaxy) | A spiral galaxy whose WebGL pipeline is driven entirely from Lisp: the GLSL shaders live in the Lisp source, and Lisp compiles, links, buffers and issues every draw call through 34 `rontolisp:wasm-import` host functions (even `Math.sin`/`Math.cos`) — JavaScript is one-line bindings and the HUD |

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
