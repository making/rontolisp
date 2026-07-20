# provide

`(provide module-name)`

Marks a module as loaded, so a later [`require`](require.md) of the same name returns without loading any file. Returns the module name as a symbol; `module-name` is a designator (keyword, symbol, or string), and providing an already-provided name is a no-op. A file loaded by `require` is expected to call `provide` itself, conventionally as its first form (which also lets mutually requiring files terminate).

The backend split mirrors `require`: an ordinary runtime function on the interpreter, and a literal, top-level compile-time directive on the JVM/WASM compile path (a nested or computed `provide` is a compile error). The Common Lisp `*modules*` variable is not available.

```lisp
(provide :my-module) ; => MY-MODULE
```

```lisp
(provide :my-module)
(require :my-module) ; => MY-MODULE
```

The `require` returns immediately because the module was already provided in the same program — no `my-module.lisp` file is looked up.
