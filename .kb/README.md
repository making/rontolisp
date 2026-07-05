# Knowledge Base

Detailed, implementation-level notes on invariants referenced from `CLAUDE.md`.
Each file expands one bullet from CLAUDE.md's "Key Design Constraints" (or a
workflow) with full detail: internal class names, per-backend mechanics, edge
cases, and the tests that pin the behavior. Read the CLAUDE.md summary first;
come here only when you need the "why exactly" behind a constraint.

- [lisp2-namespaces.md](lisp2-namespaces.md) -- Lisp-2 function/variable namespace split across all backends
- [lambda-lists.md](lambda-lists.md) -- lambda list extensions (`&optional`/`&rest`/`&key`/`&aux`) desugared to "required + `&rest`"
- [do-return-block.md](do-return-block.md) -- `do`/`return` and the `%block` non-local exit boundary
- [defmacro-backquote.md](defmacro-backquote.md) -- `defmacro`, read-time backquote, compile-time macro expansion
- [gensym-macroexpand.md](gensym-macroexpand.md) -- `gensym`, `macroexpand`/`macroexpand-1`
- [defstruct.md](defstruct.md) -- `defstruct` expansion into plain defuns, tagged-list representation, setf accessor registry
- [packages.md](packages.md) -- the `cl`/`cl-user`/`rontolisp` package system
- [reader-features.md](reader-features.md) -- `#+`/`#-` feature conditionals, `*features*`, `#|...|#` block comments, `#.` handling
- [read-load-streams.md](read-load-streams.md) -- `read`/`load`/`read-line`/file streams runtime
- [load-inliner.md](load-inliner.md) -- compile-time `load` inlining (`LoadInliner`)
- [dynamic-late-binding.md](dynamic-late-binding.md) -- `--dynamic` late-binding fallback
- [wasi-component.md](wasi-component.md) -- `--component` WASI 0.3 component output
- [wasm-export-no-wasi.md](wasm-export-no-wasi.md) -- `rontolisp:wasm-export` + `--no-wasi` reactor mode
- [wasm-import.md](wasm-import.md) -- `rontolisp:wasm-import` (host functions callable from Lisp) + export `:as` aliases
- [optimize-dead-code-elimination.md](optimize-dead-code-elimination.md) -- `--optimize` tree-shaking (WASM + JVM)
- [no-gc-scalar-wasm.md](no-gc-scalar-wasm.md) -- `--no-gc` non-GC scalar WASM backend
- [time-environment-builtins.md](time-environment-builtins.md) -- time/environment built-ins
- [fetch-http.md](fetch-http.md) -- `rontolisp:fetch` outgoing HTTP
- [tcp-sockets.md](tcp-sockets.md) -- `rontolisp:tcp-*` TCP sockets and `rontolisp:tls-connect`/`tls-listen` (stream-handle integration + the wasi:sockets component variant)
- [json.md](json.md) -- `rontolisp:json-parse`/`json-stringify` Lisp-source library + splice
- [linalg.md](linalg.md) -- `linalg` package (numpy-style vector/matrix ops) Lisp-source library + the standard array functions
- [url.md](url.md) -- `rontolisp:url-*`/`query-param*` URL / query-string Lisp-source library
- [java-interop.md](java-interop.md) -- `java:` reflection interop bridge
- [template-class-embedding.md](template-class-embedding.md) -- when/how to embed a Java "template" class
- [eval-runtime.md](eval-runtime.md) -- runtime `eval` interpreter embedded in compiled output
- [hash-tables.md](hash-tables.md) -- hash table representation per backend
- [documentation-site.md](documentation-site.md) -- doc site layout, code-fence conventions, build/preview
- [asdf.md](asdf.md) -- `asdf:defsystem`/`asdf:load-system` limited ASDF subset (.asd parsed as data, LoadInliner splice + interpreter runtime)
