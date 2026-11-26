# Scheme sample programs

Programs for the **experimental** Scheme front end: a `.scm` file is read as a subset of
R7RS-small plus a few SICP-compatibility names, as described in the
[Scheme section](../../doc/en/scheme/index.md). Each runs identically on the interpreter,
the JVM, WASM and `--component`, and each is checked in [`examples.yaml`](../examples.yaml).

| Program | Shows |
| --- | --- |
| [`differentiation.scm`](differentiation.scm) | Derivatives by forward-mode automatic differentiation: dual numbers as `define-record-type` records over quoted expressions, `case` and exact rationals |
| [`queens.scm`](queens.scm) | The n-queens puzzle by depth-first search with an explicit stack, symmetry classes by rotation and reflection, and a drawn board |
| [`huffman.scm`](huffman.scm) | Canonical Huffman codes as in DEFLATE: code lengths from merging groups, codes from the lengths, encoding, decoding and the Kraft sum |
| [`streams.scm`](streams.scm) | Infinite streams with `cons-stream`, all from one generator: Pascal's triangle, look-and-say, filtered and mapped views, cells computed once. No `(import ...)`, since an import list hides the SICP-compatibility names |
| [`evaluator.scm`](evaluator.scm) | An interpreter for a Forth/Joy-style stack language: a record for the machine, built-in words as closures, quotations as code, `guard` around each line |
| [`collatz.scm`](collatz.scm) | Collatz chains memoised in a vector: named `let` and `do` loops a million iterations long, padded table output, `call/cc` to stop a search |

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR examples/scheme/queens.scm                                     # interpreter
java -jar $JAR examples/scheme/queens.scm -o Queens.class && java Queens     # JVM
java -jar $JAR examples/scheme/queens.scm -o queens.wasm && wasmtime run queens.wasm
java -jar $JAR examples/scheme/queens.scm -o queens-c.wasm --component && wasmtime run queens-c.wasm
```

The programs stay inside what the front end supports today. They are indented with
`rontolisp format`, which applies the Scheme rules to a `.scm` file.
