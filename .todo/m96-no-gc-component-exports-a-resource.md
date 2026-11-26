# m96. `--no-gc --component`: exporting a resource

Difficulty: Medium

`rontolisp:wit-export` of a world whose exported interface declares a `resource` is refused
under `--no-gc` (`WitExportDirective.destructorForms`: "only a component of the default
backend exports"). The default backend keeps each value in a slot store
(`wit-resource.lisp`); the scalar backend has integers only, so there the representation
can be the integer the constructor answers, with no store: an owned handle answered is
`canon resource.new` of it, a borrowed one received is the integer, an owned one received is
`resource.rep` then `resource.drop`.

- `NoGcWasmCompiler` exports the flat types alone: a handle is a new flat type (an `i32`
  with the built-in calls around it), and a handle inside a list or a record stays refused.
- The component half is `ExportedResources` (shim, resource type, built-ins, fixup), which
  `NoGcWasmComponentBuilder` does not call yet; its cursors are its own.
- The destructor: `<resource>-drop` called with the integer, unless the handle was one the
  export took in (the default backend marks the slot; here a flag in linear memory).
- A `--no-wasi` core module (no component) has no resource type at all: its JavaScript glue
  would have to make the handle table. Not part of this item unless measured cheap.
