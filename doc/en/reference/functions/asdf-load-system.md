# asdf:load-system

`(asdf:load-system name &rest options)`

Loads the named system: its `:depends-on` systems first (recursively), then its component files in their `:depends-on`/`:serial` order, each evaluated like [`load`](load.md). Returns the system name as a symbol. Loading the same system twice is a no-op (like [`require`](require.md)). The system definition comes from a prior [`asdf:defsystem`](asdf-defsystem.md), or from `NAME.asd` located by searching, in order: the directory of the loading file, the directories given with `--system-path`, and the directories in the `RONTOLISP_SOURCE_REGISTRY` environment variable (both accept several directories joined with the platform path separator, like `PATH`). For a secondary system name such as `"lib/tests"` the file searched is the primary system's (`lib.asd`).

Keyword options (`:verbose nil`, `:force t`, ...) are accepted and **ignored** on every backend: there is no `operate` machinery for them to drive and loading a system twice is already a no-op. They must still be `:keyword value` pairs, so a stray second system name is an error rather than a silently dropped load.

On the interpreter, `load-system` is an ordinary runtime function, so a computed name works (the name may also be the metaobject [`asdf:find-system`](asdf-find-system.md) answers). On the compile path (JVM/WASM), a **literal, top-level** `(asdf:load-system NAME)` is expanded at compile time: the dependency systems and component files are spliced into the program exactly like the compile-time [`load`](load.md)/[`require`](require.md) include, so the compilers see the definitions natively on every backend. A `load-system` nested inside another form or with a computed argument compiles to a runtime call that answers `nil` when the system was already spliced — the "load if missing" shape libraries guard with a [`find-system`](asdf-find-system.md) probe — and signals for any system the compiled program does not carry (nothing can be loaded at run time).

```console
;; my-lib.asd
(defsystem :my-lib
  :components ((:file "main" :depends-on ("package"))
               (:file "package")))

;; run.lisp
(asdf:load-system :my-lib)
(my-lib:greet)
```

`package.lisp` loads before `main.lisp` (the `:depends-on` constraint), then the program calls into the loaded system. See the [Systems guide](../../guides/asdf-systems.md) for the full project layout and the search-path details.
