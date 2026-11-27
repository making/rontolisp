# m99. Examples for the rontolisp-java-api WIT types

Difficulty: Medium

`rontolisp-java-api` now has `WitResult`, `WitStream`, `WitFuture` and `WitException` beside
`WitImport` / `WitExport`, but no example uses them: `examples/wit/shapes/Shapes.java` is the
only example touching the newer mappings. Add runnable examples a reader can copy, one idea
each, covering:

- `WitResult<T,E>`: an import answering `result` (both spellings: `WitResult` and the ok arm +
  `WitException`) and an export returning one.
- `stream<u8>` / `stream<T>`: the host's stream read as `InputStream` / `WitStream<T>`, and a
  program-handed `stream<u8>` written through `Consumer<OutputStream>`.
- `WitFuture<T>` and `async func`, import (`:blocking t` on the Lisp side, a plain method in Java)
  and `@WitExport` export.
- `@WitExport` compound types (record, variant, enum, flags, option, list, tuple) through the
  generated `<Holder>$WitExport`.

Pin each one in `examples/examples.yaml` so `ExamplesE2eTest` runs it (`.kb/running-backends.md`,
"Examples Suite"), against a host that really answers the WIT (wasmtime, jco, or a Rust
component as `examples/java/java-calls-rust` does). Prefer extending an existing example
(`java-calls-rust`, `rust-calls-java`, `examples/wit/*`) over a new directory. Link each from
`doc/{en,ja}/java/wit.md` at the section it illustrates. Every API shape follows
`doc/{en,ja}/java/wit.md` and the checkers (`JavaWitTypes`, `JavaWitImports`,
`JavaWitExports`); when an example needs something the checkers refuse, file a todo instead
of working around it.

Start after m89 (program-handed `WitFuture`) and m91 (exported resources) land, and use what
they add.
