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
| [`life.lisp`](life.lisp) | Conway's Game of Life: a glider on an 8x8 toroidal grid backed by a 2-D `make-array`, neighbour counting with wraparound, and per-generation ASCII rendering |
| [`sorting.lisp`](sorting.lisp) | Quicksort and merge sort over number lists, parameterized by a first-class comparator; cross-checked against the built-in `sort` |
| [`calc.lisp`](calc.lisp) | A tiny prefix-arithmetic interpreter: a recursive evaluator over an alist environment, cross-checked against the built-in `eval` |
| [`mandelbrot.lisp`](mandelbrot.lisp) | ASCII Mandelbrot set: floating-point arithmetic and nested loops (no transcendental functions) |
| [`line-numbers.lisp`](line-numbers.lisp) | A `cat -n` style file tool: `with-open-file`, `read-line`, `write-line`, `format nil`, line/character counts |
| [`parse-numbers.lisp`](parse-numbers.lisp) | Numeric-column parsing and character classification: `parse-integer`, `char`, `alpha-char-p`, `digit-char-p` over file lines |
| [`nn.lisp`](nn.lisp) | Feed-forward neural network learning XOR via backpropagation |
| [`mlp.lisp`](mlp.lisp) | Generalized multi-layer perceptron for 2-D circle classification |
| [`maze-rl.lisp`](maze-rl.lisp) | Tabular Q-learning that solves a grid maze: a hash-table Q-table keyed by `(row col action)`, idiomatic `random`-based epsilon-greedy exploration, and an ASCII rendering of the learned path. **Non-deterministic:** because `random` is unseeded and per-backend, the exact path and value count differ on each run and backend (the algorithm always converges to a valid route) |

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
