# n07. `--scaffold-wit` generates Java

Difficulty: Medium

The user's request (2026-11-27): the WIT skeleton generation that Common Lisp has, for Java too.

`rontolisp --scaffold-wit world.wit -o impl.lisp` (`cli/WitScaffolder`) writes a
`rontolisp:wit-export` directive and one `defun` stub per export. There is no Java form: a
Java author writes the `@WitExport` class, its member types and every `@WitImport` interface
by hand from the mapping in `doc/{en,ja}/java/wit.md`.

- **Selecting Java**: by the output's extension (`-o Greeter.java`) and by
  `--source-language java` when printing to stdout. Lisp stays the default.
- **Exports**: one `@WitExport` class for the world, one stub method per exported function
  (each throwing until filled in, so the file compiles unchanged, as the Lisp stubs signal
  at run time), the WIT signature and the `///` doc comments as Javadoc.
- **Imports**: one `@WitImport` interface per imported interface (and the world's own
  imported functions), so a program can call them right away.
- **Types**: every type the world reaches, declared where the mapping says it is declared
  (member types of the interface or the `@WitExport` class): `record`, `variant`, `enum`,
  `flags`, `option`, `result` (both spellings: pick the one the mapping documents as the
  default), `tuple`, `list`, `stream`/`future`, `async func`, resources and `use`d types.
- **One source of truth**: generate from the same mapping the checkers apply
  (`JavaWitTypes`, `JavaWitImports`, `JavaWitExports`), not from a second table, so a type
  the checker refuses is not generated -- the generator says which item it skipped and why,
  as the checker would word it.
- **Pinned by round trip**: the generated file passes the checkers and compiles to a
  component whose `--emit-wit` matches the input world (`wasm-tools component wit`), on the
  example worlds under `examples/wit/*` (keyvalue, shapes, resource, sockets). Extend the
  existing `WitScaffolderTest` rather than adding a class.
- Doc: `doc/{en,ja}/guides/wit-contracts.md` ("Scaffolding an Implementation") and
  `doc/{en,ja}/java/wit.md`; the CLI help in `CliOptionTable`.
- Out of scope: Clojure (`.kb/clojure-frontend.md` says `--scaffold-wit` has no Clojure
  form); file a Todo if it falls out naturally.
