# m73. Java `@WitExport`: an `async func` export

Difficulty: Medium

`JavaWitExports.Checker` refuses an `async func` of the world ("is an async func, which the Java
subset does not implement yet"). Lisp's `rontolisp:wit-export` takes one on a wasm-GC component:
the export lifts with `:async t` (`doc/en/reference/functions/rontolisp-wit-export.md`), refused
only under `--no-gc --component` (m72). So the gap is the Java front end's.

The user's decision (2026-11-26): the Java side of the async component model, exports included.

- **The shape**: what a static method declares for an `async func` -- the value itself, the
  call's task waiting where it blocks (as m55 made an imported `async func` a method answering
  its value), or a `WitFuture<T>` (`rontolisp-java-api`, m55). Decide it from what the Lisp side
  lowers, not from a driver; a JDK test calls the method as it is.
- **A `stream<T>`/`future<T>` parameter or result** of an export: waits for m70 (compound types
  on the export path) and m68 (what the program hands the host) -- coordinate, do not duplicate.
- **Hosts**: wasmtime's invoke of an async-lifted export; jco's limit on async exports
  (`.kb/no-gc-scalar-wasm.md`, `.kb/wasi-component.md`).

Pinned by `JavaWitImportTest.aClassImplementsTheWorldAComponentExports` (extend it); `doc/{en,ja}/java/wit.md`.
