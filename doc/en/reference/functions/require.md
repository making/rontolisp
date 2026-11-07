# require

`(require module-name [pathname])`

Loads the file for a module unless the module was already registered by [`provide`](provide.md), then returns the module name as a symbol. `module-name` is a designator: a keyword (`:util`), a symbol (`'util`), or a string (`"util"`). Without an explicit `pathname` the file `<name>.lisp` is resolved relative to the requiring file, exactly like [`load`](load.md); an explicit second argument overrides that mapping. The required file is expected to `(provide <name>)` itself, conventionally as its first form — that is what marks the module, so a second `require` of the same name is consumed without loading. This makes `require` the tool for the diamond dependency (`a.lisp` and `b.lisp` both requiring `utils.lisp` loads it once), where `load` would evaluate the file twice.

`(require :asdf)` and `(require :uiop)`, how a script opens on a Lisp shipping ASDF, load nothing: both are always there.

On the interpreter, `require` is an ordinary runtime function. On the compile path (JVM/WASM), a **literal, top-level** `(require ...)` is expanded at compile time: the module file is spliced into the program like the compile-time `load` include, so the compilers see its definitions natively. A `require` nested inside another form (a function body, say), whose module name may then be computed, loads nothing when it runs: a module the program provided, or `asdf`/`uiop`, returns its name, and any other one signals an error naming the module. A library that loads an optional part on demand therefore compiles, and the call fails only if the part is missing. A top-level `require` with a computed argument, one a macro expands to, and `#'require` taken as a function value are compile errors. `*modules*` lists the provided module names as strings on the interpreter; on the compile path it stays `nil`, since `provide` is consumed at compile time.

```console
;; util.lisp
(provide :util)
(defun u-sq (x) (* x x))

;; main.lisp
(require :util)
(require :util)   ; already provided: no second load
(print (u-sq 7))  ; prints 49
```

The first `require` loads `util.lisp`, whose `provide` marks the module; the second is a no-op returning `util`.
