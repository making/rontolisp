# m91. `wit-export`: a resource the world exports

Difficulty: High

An exported interface that declares a `resource` is refused at the WIT line
(`WitExportDirective.lowerInterfaceMembers`), as is `own` / `borrow` of one anywhere in an
export (`refusedCanonical`).

- The component half: `canon resource.new` / `resource.rep` / a destructor export
  (`[dtor]name`), the methods exported as `[method]r.m`, `[constructor]r`, `[static]r.f`
  (`WitEmitter.resourceExportName` spells them for the import side).
- A Lisp value for the representation: the import side's handle is an opaque integer
  (`.kb/wit.md`, "Name mapping and the two type tiers").
- **A resource of another interface (`use`)** is nominal, so the structural copy
  `am.ik.wit.WitUses` makes of a value type does not apply: such a name stays a `use`
  clause and is refused. What `wasm-tools component new` 1.263.0 does for a used type
  (measured on value types, the same wiring a resource needs): an interface the world does
  not export becomes an instance IMPORT of the component declaring the used types, each
  aliased out (`alias export`); one the world exports is exported first, whatever the
  world's order, and the type aliased out of the EXPORTED instance; the using interface's
  shim imports the foreign type (`import-type-<name>`), then its local name
  (`import-type-<name>0` when the two collide) and exports the foreign import under the
  local name. A world-level `use` always imports the instance. An instance import breaks
  `--component --no-wasi`'s promise of importing nothing, and collides with a
  `wit-import` of the same interface.
