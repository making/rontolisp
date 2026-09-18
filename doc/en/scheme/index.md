# Scheme (experimental)

**Experimental.** rontolisp reads a subset of R7RS-small -- `(scheme base)`,
`(scheme write)`, `(scheme read)`, `(scheme inexact)`, `(scheme cxr)`, `(scheme lazy)`,
`(scheme process-context)`'s `exit`, `(scheme eval)` and `(scheme repl)` -- just large
enough to run a
Scheme program on every backend. Conformance is partial by design and nothing here is a
compatibility promise. Use it to try a Scheme program on the JVM or WebAssembly; write
Common Lisp for anything you need to keep working.

A `.scm` file is read as Scheme; `--source-language scheme` says so for any other file.
The language is picked per file, so one program may mix the two.

```bash
rontolisp hello.scm                                # interpreter
rontolisp hello.scm -o Hello.class && java Hello   # JVM
rontolisp hello.scm -o hello.wasm && wasmtime run hello.wasm
rontolisp hello.scm -o hello-c.wasm --component && wasmtime run hello-c.wasm
rontolisp prog.txt --source-language scheme        # any extension
```

`--no-gc` is refused: that backend has no pairs, symbols or closures.

```scheme
(import (scheme base) (scheme write))

(define (count-up n)
  (let loop ((i 0) (acc '()))
    (if (= i n)
        (reverse acc)
        (loop (+ i 1) (cons (* i i) acc)))))

(define-record-type point (make-point x y) point? (x point-x) (y point-y))

(display (count-up 5)) (newline)
(write (list (point-x (make-point 3 4)) (if '() 'true 'false) #f "s")) (newline)
```

```
(0 1 4 9 16)
(3 true #f "s")
```

## This section

- [REPL](repl.md) -- the interactive session and script-runner mode.
- [Standards](standards.md) -- `--scheme-standard`: this implementation's dialect or
  strict R7RS.
- [Syntax](syntax.md) -- the reader, special forms and `import`.
- [Libraries](libraries.md) -- the nine importable R7RS libraries and what each
  provides.
- [*Structure and Interpretation of Computer Programs* (SICP) Compatibility](sicp.md) -- the
  MIT/SICP names visible with no `import`.
- [eval](eval.md) -- `(eval datum env)` and the environments it accepts.
- [Deviations](deviations.md) -- where this front end departs from R7RS, then what is
  not implemented yet.
- [Mixing with Common Lisp](common-lisp.md) -- calling a Scheme procedure from a Common
  Lisp file, and back.
- [Reference](reference.md) -- one page per procedure, constant and syntactic keyword.
