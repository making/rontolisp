# m95. `wit-export`: a handle of a resource the component imports

Difficulty: High

An export carries a handle only of a resource an EXPORTED interface declares
(`.kb/wit.md`, "A resource the world exports"). Refused at the WIT line
(`WitExportDirective.refusedHandle`, `lowerInterfaceMembers`):

- an exported interface whose `use` clause names a resource of an interface the world does
  not export -- refused whether or not a function names it;
- a world's own `use` of a resource, and a `resource` the world declares, in a function of
  the world (the clause alone is left out, the component importing nothing for it).

Each is an IMPORT of the component: the host owns the resource, and its handle is the
opaque integer `wit-import` gives one (the `wasi:http/handler` shape, `handle: func(request:
own<request>)` over `wasi:http/types`).

- What `wasm-tools component new` 1.263.0 does for a used type (measured on value types): an
  interface the world does not export becomes an instance IMPORT of the component declaring
  the used types, each aliased out (`alias export`); the using interface's shim imports the
  foreign type (`import-type-<name>`), then its local name (`import-type-<name>0` when the two
  collide) and exports the foreign import under the local name. A world-level `use` always
  imports the instance.
- The instance import breaks `--component --no-wasi`'s promise of importing nothing, and
  collides with a `wit-import` of the same interface (one instance import an interface:
  `WasmComponentBuilder.appendUserImports`, `rejectDuplicateUserImports`).
- The marshaller already passes such a handle as its integer (`Gen.exported` answers null
  for a resource the world does not export); the directive's refusal and the component
  wiring are what is missing.
