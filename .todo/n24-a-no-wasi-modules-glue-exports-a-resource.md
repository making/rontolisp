# n24. A `--no-wasi` core module's glue exports a resource

Difficulty: Medium

A core module defines no resource type, so `rontolisp:wit-export` refuses a world exporting a
resource there (`WitExportDirective.destructorForms`), on both backends. Under `--no-wasi
--emit-js-glue` the glue lifts and lowers the canonical ABI in JavaScript
(`.kb/wasm-export-no-wasi.md`); it could keep the handle table too: an object per handle with
the representation, `Symbol.dispose` calling the destructor.

- Measure first: the glue's size for the table, against what a host gains over passing the
  integer (`--no-gc`) or the slot index itself.
- The core module's side is the component's (`ExportHandles`, `ExportedResources`): the
  built-ins become glue functions under the same import names.
