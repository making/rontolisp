# Scheme sample programs

Programs for the **experimental** Scheme front end: a `.scm` file is read as a subset of
R7RS-small plus a few SICP-compatibility names, as described in the
[Scheme guide](../../doc/en/guides/scheme.md). Each runs identically on the interpreter,
the JVM, WASM and `--component`, and each is checked in [`examples.yaml`](../examples.yaml).

| Program | Shows |
| --- | --- |
| [`differentiation.scm`](differentiation.scm) | Symbolic differentiation over quoted expressions, with simplifying constructors, `case` and exact rationals |
| [`queens.scm`](queens.scm) | The eight queens puzzle in the map / filter / flatmap style, and a drawn board |
| [`huffman.scm`](huffman.scm) | Huffman code trees as `define-record-type` records: a code table, encoding and decoding |
| [`streams.scm`](streams.scm) | Infinite streams with `cons-stream`: Fibonacci numbers defined by themselves, the prime sieve, promises forced once. No `(import ...)`, since an import list hides the SICP names |
| [`evaluator.scm`](evaluator.scm) | A metacircular evaluator running a quoted program: closures, recursion, `set!`, `cond` and `let` as derived forms |
| [`collatz.scm`](collatz.scm) | Collatz chains memoised in a vector: named `let` and `do` loops a million iterations long, padded table output, `call/cc` to stop a search |

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR examples/scheme/queens.scm                                     # interpreter
java -jar $JAR examples/scheme/queens.scm -o Queens.class && java Queens     # JVM
java -jar $JAR examples/scheme/queens.scm -o queens.wasm && wasmtime run queens.wasm
java -jar $JAR examples/scheme/queens.scm -o queens-c.wasm --component && wasmtime run queens-c.wasm
```

The programs stay inside what the front end supports today; the guide's "Deviations"
section lists what it does not (no `syntax-rules`, escape-only `call/cc`, proper tail calls
only in loops). `rontolisp format` indents Common Lisp, not Scheme, so these files are
indented by hand.
