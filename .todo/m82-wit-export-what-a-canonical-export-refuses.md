# m82. `wit-export`: what a canonical export still refuses, and the hosts not measured

Difficulty: High

`WitExportDirective.requireCanonical` refuses, at the WIT line, what the component export path
does not carry (`.kb/wit.md`, "Compound types across an export"). Each is its own piece; take
them in the order a program asks.

- **A type of another interface** (`use other.{t}` in an exported interface or in the world).
  wasm-tools makes the other interface an IMPORT of the component unless the world exports it
  too, and the shim takes the type from that instance (probe `wasm-tools component new` first,
  as `WitExportTypes` did). Common in published worlds, so likely the first asked for.
- **`flags`**: no Lisp shape crosses either way yet (`WitTypeMapper`: a keyword list); the
  import side refuses it too, so both directions land together (`emitLowerParam` /
  `emitLiftAt`, one i32 per 32 flags). `WitExportTypes` already declares an unused one.
- **An `async func` of compound types**: how a flat async export delivers its value is
  `.kb/wasi-component.md`, "`:async t` I/O exports"; a result in memory has to outlive the
  wrapper there too. The serve handler's `task.return` lowering (`TaskReturn`) is the
  precedent for lowering a compound result mid-task. m73 is the Java side.
- **A resource the world exports** (`resource` with methods, `own` / `borrow` of it):
  `resource.new` / `resource.rep` / a destructor export, and a Lisp value for the
  representation. Today an interface's resource members are skipped without a word
  (`lowerInterfaceMembers` takes `FuncKind.PLAIN` alone) -- at least say so at the line.
- **A scope whose exports are all flat declares none of its types**: its bytes are the
  hand-written export's, so a world with an unused `record`, or one reached only through an
  alias of a flat type, exports no type a consumer could name. Decide whether a component
  target lowers such a scope canonically (conformance to the world) or stays byte-identical.
- **Hosts not measured**: jco (transpile and call; `.kb/wasi-component.md` on its export-lift
  reading) and a `bindgen` host. Measured so far: wasmtime 51's WAVE `--invoke`, a composed
  Lisp importer and, by hand, a Rust importer (`.kb/wit.md`, "Compound types across an
  export").
