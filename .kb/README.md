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
- [symbol-runtime-api.md](symbol-runtime-api.md) -- `symbol-name`/`intern`/`find-symbol`/`make-symbol`/`boundp`/`fboundp`/`symbol-value` (verbatim names, no intern table, global-only variable lookups)
- [defstruct.md](defstruct.md) -- `defstruct` expansion into plain defuns, tagged-list representation, setf accessor registry
- [clos.md](clos.md) -- static CLOS subset (`defclass`/`defgeneric`/`defmethod`/`make-instance`/`slot-value`): shared registry, dispatcher generation, cl-who expansion-time dispatch
- [packages.md](packages.md) -- the `cl`/`cl-user`/`rontolisp` package system
- [reader-features.md](reader-features.md) -- `#+`/`#-` feature conditionals, `*features*`, `#|...|#` block comments, `#.` handling
- [read-load-streams.md](read-load-streams.md) -- `read`/`load`/`read-line`/file streams runtime; component stdin (stdin.lisp)
- [load-inliner.md](load-inliner.md) -- compile-time `load` inlining (`LoadInliner`)
- [dynamic-late-binding.md](dynamic-late-binding.md) -- `--dynamic` late-binding fallback
- [wasi-component.md](wasi-component.md) -- `--component` WASI 0.3 component output
- [wit.md](wit.md) -- `am.ik.wit` WIT parser/printer library + `WitResolver`, the model-based `--emit-wit` emission (`WasiWitDefinitions` + fixtures + generator), the blob-variant renaming, the settled `WitTypeMapper` table (the `result<T,E>` = condition-everywhere decision record), `rontolisp:wit-export` (a world as the authoritative export list) and `rontolisp:wit-import` (calling a WIT interface: the per-backend lowering, the provider decision record, `wit.lisp`)
- [wasm-export-no-wasi.md](wasm-export-no-wasi.md) -- `rontolisp:wasm-export` + `--no-wasi` reactor mode
- [wasm-import.md](wasm-import.md) -- `rontolisp:wasm-import` (host functions callable from Lisp) + export `:as` aliases
- [optimize-dead-code-elimination.md](optimize-dead-code-elimination.md) -- `--optimize` tree-shaking (WASM + JVM)
- [library-defun-pruning.md](library-defun-pruning.md) -- default-on AST pruning of spliced library defuns (`LibraryDefunPruner`, `--no-prune` escape) + `am.ik.jvm` constant-pool deduplication
- [no-gc-scalar-wasm.md](no-gc-scalar-wasm.md) -- `--no-gc` non-GC scalar WASM backend
- [wasm-gc-strings.md](wasm-gc-strings.md) -- WASM GC-backend strings as `$str_bytes` arrays (HEAP_PTR as a stack pointer, `_str_fresh` counter ids, `_str_to_mem`/`_write_str_gc` bridges) -- retires the linear string heap leak
- [time-environment-builtins.md](time-environment-builtins.md) -- time/environment built-ins
- [fetch-http.md](fetch-http.md) -- `rontolisp:fetch` outgoing HTTP
- [async-await.md](async-await.md) -- async-defun/await, futures and asynchronous streams
- [error-handling.md](error-handling.md) -- unwind-protect (interpreter try/finally, JVM exception tables + return-escape cleanups), condition objects (define-condition over the CLOS subset, seeded hierarchy, error/signal/warn designators, with-slots) and handler-case/ignore-errors (typed catching, ThreadLocal condition/depth channels; WASM rejects catching)
- [tcp-sockets.md](tcp-sockets.md) -- `rontolisp:tcp-*` TCP sockets, `rontolisp:tls-connect`/`tls-listen` (stream-handle integration + the wasi:sockets component variant) and the `usocket` compatibility shim (usocket.lisp + the built-in ASDF system)
- [json.md](json.md) -- `rontolisp:json-parse`/`json-stringify` Lisp-source library + splice
- [linalg.md](linalg.md) -- `linalg` package (numpy-style vector/matrix ops) Lisp-source library + the standard array functions
- [vec.md](vec.md) -- `vec` package + the packed float-array type and its four `--simd` acceleration layers
- [linalg-simd.md](linalg-simd.md) -- `--simd` interception of the `linalg:` kernels (the declined-input protocol, the precision contract)
- [vec.md](vec.md) -- `vec:` package (packed-f64 vector kernels over the packed float-array type) + JVM `--simd` (jdk.incubator.vector) and `--no-gc` native `v128` acceleration
- [adjustable-arrays.md](adjustable-arrays.md) -- fill-pointer / `:adjustable` / displaced arrays + `vector-push`/`-pop`/`-push-extend` + `adjust-array`/`array-displacement` (all four backends)
- [url.md](url.md) -- `rontolisp:url-*`/`query-param*` URL / query-string Lisp-source library
- [java-interop.md](java-interop.md) -- `java:` reflection interop bridge
- [template-class-embedding.md](template-class-embedding.md) -- when/how to embed a Java "template" class
- [stackmap-augmenter.md](stackmap-augmenter.md) -- class version 61 via `am.ik.jvm.StackMapAugmenter` (offline StackMapTable computation over frame-free emitter output; pipeline order, merge rules, size cost, what v61 unlocks)
- [eval-runtime.md](eval-runtime.md) -- runtime `eval` interpreter embedded in compiled output
- [hash-tables.md](hash-tables.md) -- hash table representation per backend
- [documentation-site.md](documentation-site.md) -- doc site layout, code-fence conventions, build/preview
- [asdf.md](asdf.md) -- `asdf:defsystem`/`asdf:load-system` limited ASDF subset (.asd parsed as data, LoadInliner splice + interpreter runtime)
- [declarations-type-checks.md](declarations-type-checks.md) -- `declare`/`declaim`/`proclaim`/`the` no-ops, `eval-when` (+ top-level flattening), `check-type`/`assert`, shared type-specifier tests
- [flet-labels.md](flet-labels.md) -- `flet`/`labels` local functions via let-bound lambdas + Lisp-2 call-site rewrite (labels = nil-then-setq letrec)
- [dynamic-special-variables.md](dynamic-special-variables.md) -- dynamic (special) variable binding (`defvar`/`declaim special` + `let`/`let*`/`progv`): thread-scoped shallow binding on the interpreter, static-field/module-global save/restore on the compilers
- [multiple-values.md](multiple-values.md) -- `values`/`multiple-value-bind`/`-list`/`-call`/`nth-value` as a syntactic lowering (no runtime representation); floor-family + gethash secondary values, floor-family divisor
