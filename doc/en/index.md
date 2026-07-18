# rontolisp

A minimal Common Lisp subset implemented in Java. It supports three execution modes:

- **Interpreter** -- Tree-walking evaluation with REPL support
- **JVM compiler** -- Compiles Lisp to `.class` bytecode runnable on any JRE
- **WASM compiler** -- Compiles Lisp to `.wasm` using wasm-GC, targeting either a WASI Preview 1 core module or a WASI 0.3 (Component Model) component

Try it right here -- the examples on these pages run in your browser via the same
WebAssembly build as the [playground](../../playground.html):

```lisp
(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
(print (fact 10))
```

Press **Run** above (the rontolisp runtime loads on the first run).
