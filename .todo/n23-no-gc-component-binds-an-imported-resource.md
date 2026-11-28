# n23. `--no-gc --component`: a resource the component imports

Difficulty: Medium

`--no-gc --component`'s `wit-import` binds no resource (`validateNoGcComponentFunc`), so an
export there refuses a handle of a resource the component imports too
(`WitExportDirective`, the `imported` visitor and `requireCanonical`). The two go together.

- A handle is an `i32` on this backend: the binding's constructor, methods and
  `<resource>-drop` are flat imports of the interface's instance, typed with the resource the
  instance type declares (`own`/`borrow` of it, not `u32`), so `NoGcWasmComponentBuilder`'s
  `instanceType` must declare the resource type and its handles.
- An export that borrows one releases it before it returns (`.kb/wit.md`, "A resource the
  component imports": wasmtime traps otherwise): on this backend a call of the binding's
  drop after the function's call, in the export's `%wit-export` defun.
- The exported resource's lowering (`WitExportDirective.ScalarHandles`) is the model:
  the conversions are Lisp around the function, the built-ins `wasm-import`s.
