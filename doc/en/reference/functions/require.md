# require

`(require module-name [pathname])`

Loads the file for a module unless the module was already registered by [`provide`](provide.md), then returns the module name as a symbol. `module-name` is a designator: a keyword (`:util`), a symbol (`'util`), or a string (`"util"`). Without an explicit `pathname` the file `<name>.lisp` is resolved relative to the requiring file, exactly like [`load`](load.md); an explicit second argument overrides that mapping. The required file is expected to `(provide <name>)` itself, conventionally as its first form — that is what marks the module, so a second `require` of the same name is consumed without loading. This makes `require` the tool for the diamond dependency (`a.lisp` and `b.lisp` both requiring `utils.lisp` loads it once), where `load` would evaluate the file twice.

On the interpreter, `require` is an ordinary runtime function. On the compile path (JVM/WASM), a **literal, top-level** `(require ...)` is expanded at compile time: the module file is spliced into the program like the compile-time `load` include, so the compilers see its definitions natively. Unlike `load`, a `require` nested inside another form or with a computed argument is a compile error — it cannot be deferred to the compiled runtime reader. The Common Lisp `*modules*` variable is not available.

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
