# rontolisp

A minimal Common Lisp subset implemented in Java. It supports three execution modes:

- **Interpreter** -- Tree-walking evaluation with REPL support
- **JVM compiler** -- Compiles Lisp to `.class` bytecode runnable on any JRE
- **WASM compiler** -- Compiles Lisp to `.wasm` binary using wasm-GC and WASI Preview 1

Try it right here -- the examples on these pages run in your browser via the same
WebAssembly build as the [playground](../../index.html):

```lisp
(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
(print (fact 10))
```

Press **Run** above (the rontolisp runtime loads on the first run).
