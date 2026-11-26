# m94. doc: the WIT-Java mapping as one reference

Difficulty: Medium

The user's request (2026-11-27): the mapping between WIT and Java is written down thoroughly.

`doc/{en,ja}/java/wit.md` grew one Todo at a time (l26, l85, m55, m68, m69, m73, m80, m82):
the import table mixes kinds and directions ("`stream<u8>` the host answers", "... the program
hands the host"), the export section is "the table above, but ...", what each output takes
(component, `--no-wasi` module, Preview 1, `--no-gc`, JDK/interpreter/JVM) is prose in
Limitations, and the refusals are one long bullet. A reader cannot answer "can I use
`option<result<..>>` as an export parameter under `--no-wasi`?" from one place.

- **One table per question, each checked against the code and a test, not the old prose**:
  - WIT type -> Java type, every kind (scalars, `char`, `string`, `list<u8>`/`list<T>`,
    `option`, `result` both spellings, `tuple`, `record`, `variant`, `enum`, `flags`,
    resource/`own`/`borrow`, `stream<u8>`/`stream<T>`/resource streams, `future<T>`,
    `async func`, `use`d types, `error-context`) with WHERE the Java type is declared (member
    type of the interface / the `@WitExport` class / its member class, any record for a tuple).
  - Direction x position: import parameter, import result, export parameter, export result,
    inside another type -- supported, refused (with the reason in a few words), or
    "not yet" linking the open Todo's subject (never its number).
  - Output x feature: component, `--component --no-wasi`, `--no-wasi` module (+ JS glue),
    Preview 1, `--no-gc`, `--no-gc --component`, `--native`, JDK / interpreter / JVM.
  - Unsigned integers and `u64` past `Long.MAX_VALUE`, `null`, a closed resource: the
    boundary rules in one list.
- Keep the worked examples (keyvalue, shapes, pause); the tables link to them.
- en and ja in the same commit (`.kb/documentation-site.md`); the docs-tool build passes.
- Derive every cell from the checkers (`JavaWitTypes`, `JavaWitImports`, `JavaWitExports`,
  `WitExportDirective`, `WitImportDirective`) and their tests; where a cell has no test, add
  the case to an existing test (`JavaWitImportTest`) or mark the cell unverified and say so
  in the report.

Start after the WIT Todos in flight land (an async func of compound types, a resource a
world exports, flags on a `--no-wasi` module's glue, `--no-gc` async), so the tables are
written once against what they leave.
