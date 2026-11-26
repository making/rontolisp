# m80. Java `@WitExport` carries compound types

Difficulty: High

A Lisp world's export carries records, variants, enums, lists, options, tuples and results on a
component (`.kb/wit.md`, "Compound types across an export"). A Java world's does not:
`WitExportDirective.describe`, which `JavaWitExports.Checker` reads the world through, still
answers the flat set alone ("not supported at this front end's export boundary yet"), and
`doc/{en,ja}/java/wit.md` says so.

- **The mapping is `@WitImport`'s, in the other direction** (`.kb/java-frontend.md`, "WIT
  imports"; `JavaWitImports.Writer`'s converters): a record a Java record, a variant a sealed
  interface of records, an enum an enum, `list<T>` a `List`, `option<T>` an `Optional`,
  `list<u8>` a `byte[]` (the directive's `:octets t`), a `result` a
  `am.ik.rontolisp.wit.WitResult` -- or, at the top of an answer, the ok arm with a
  `WitException` thrown, as an import's method declares either. A static method takes the
  Java value, so the `<function>%wit` defun the lowering already writes for unsigned integers
  converts the Lisp shape in and the Java value out.
- **To make**: `describe` answering the whole `WitTypeMapper.Shape` of an export (as
  `WitImportDirective.describe` does: aliases followed, nested types resolved, `definition`),
  the checker's type table, the converters, the directive's `:octets t`.
- **The envelope**: `lower` wraps a top-level `result` in `<f>%wit-export`, which catches
  `rontolisp:wit-error`. A Java export either answers the envelope itself (then the wrapper
  must not wrap: a `:names` table's `rawOnly` precedent on the import side) or throws a
  condition the wrapper maps. Decide with `JavaWitImports.thrown` in view.
- `--no-gc` stays the flat set (no value for a record there); a `--no-wasi` module waits for
  m79.
- Pins: `JavaWitImportTest.aClassImplementsTheWorldAComponentExports`,
  `examples/java/rust-calls-java`; `doc/{en,ja}/java/wit.md` ("The types", "Limitations").
