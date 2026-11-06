# i85. Reader: a package marker inside `|...|` is a constituent

Difficulty: Medium

`|a:b|` is one symbol named `"a:b"` in the current package (CLHS 2.3.5: a colon inside a
multiple escape is not a package marker). Measured 2026-11-06:

    (symbol-name '|a:b|)        ; interpreter: error "No such package: a"
    (read-from-string "(|?:| . 12)")   ; prints (?:|| . 12)

Consumer: sycamore 20260101 (`src/cgen.lisp:144`, `(|?:| . 12)` in an operator-precedence table)
stops with `No such package: ?` on the interpreter and at `cgen.lisp:101:1` on the JVM compile.
With the symbol renamed by hand sycamore loads and its tree sets / tree maps answer what SBCL
2.2.9 answers.

Fix in the frontend lexer and in every runtime reader (`JvmReadRuntimeBuilder`,
`WasmReadRuntimeBuilder`, the prelude `read` scanner); the printer must also print such a symbol
back with bars. ci-spec case reading and printing `|a:b|`, `|:x|`, `a::|b:c|`.
