# `objc` / `cocoa`: a new base on the LispWorks interface -- design and the call layer

Difficulty: High

The `objc` package is rebuilt on a new base whose public vocabulary is the LispWorks 8.1
*Objective-C and Cocoa Interface User Guide and Reference Manual*: `OBJC` (42 symbols) and `COCOA`
(11). The current ten names (`class`, `send`, `define-class`, `on-main`, `string`, `data`, `bytes`,
`address`, `objectp`, `object`) and the bridge behind them are retired, not layered on. This item
is the design and the call half; class definition is .todo/a72, blocks .todo/a73, exceptions
.todo/a74, porting the libraries and deleting the old code .todo/a75.

## The rule while both exist

**New code never calls the old verbs or the old bridge**: not `objc:send` and friends, not
`eval/ObjcBridge`, `ObjcCaller`, `LispObjcObject`, `codegen/jvm/JvmObjcTemplate`,
`JvmObjcHandle`, nor the verbs of `eval/objc-native.lisp`. Both surfaces are exported until
.todo/a75 deletes the old one; nothing new may make that deletion harder.

## What is kept (platform substrate, not vocabulary)

Reused as is, or changed only where the new design needs it: thread 0 hand-over (`MainThread`,
`RontoLispCli.main`, `JvmSizedMainBuilder`); `TypeEncoding` and the runner's `encoding.rs`;
`call.rs` (Apple AArch64 argument classification); `VariadicSelectors`; native-image registration
(`reachability-metadata.json`, `ObjcNativeImageForeignConfigTest`); class-file embedding
(`JvmObjcRuntimeBuilder`); the `Cleaner` releasing on thread 0. Rationale and traps for each:
`.kb/objc.md`.

## Design first (land the decisions in `.kb/objc.md` before code)

- **One implementation of the semantics.** Today `ObjcBridge` and `JvmObjcTemplate` are hand-kept
  twins ("KEEP THE TWO IN SYNC") and `objc-native.lisp` is a third. Aim for a thin per-backend
  primitive layer (send by encoding, raw values in and out) with argument/result conversion written
  ONCE, in Lisp spliced for every target, the way `appkit.lisp` travels. Measure what that costs
  per send on `java -jar` (today 7.4-8.1 us, dominated by the thread-0 hop) and on `--native`
  (0.20-0.66 us) before deciding.
- **Ownership.** In the manual an `id` result is a raw pointer retained/released by hand. The
  current base gives each wrapper one retain released by a `Cleaner` on thread 0
  (`.kb/objc.md`, "Ownership"), which is safe. Decide what `objc-object-pointer`, `retain`,
  `release`, `autorelease`, `retain-count` mean so that manual-style code cannot double-release.
- **One object representation across the four targets** (today a record, a JVM handle and a wasm
  defstruct, each with its own equality/type arms). Equality by address and `type-of`/`typep`
  behaviour must stay what `.kb/objc.md`, "--native", pins.
- **Value shapes follow the manual**: `ns-range` answers `(6 . 5)`, `ns-rect` a vector; in
  LispWorks 8.1 the four Foundation structures are 64-bit (doubles, 64-bit integers) whatever the
  manual's reference pages say. Where the manual is ambiguous, measure a running LispWorks 8.1
  (Personal edition suffices) and commit the recorded answers as test data.

## Scope of this item

- `OBJC`: `ensure-objc-initialized`, `invoke`, `invoke-bool`, `invoke-into` (dispositions needing
  no Lisp-defined class), `can-invoke-p`, `alloc-init-object`, `description`,
  `trace-invoke`/`untrace-invoke`, `coerce-to-objc-class`, `objc-class-name`, `coerce-to-selector`,
  `selector-name`, `objc-class-method-signature`, `retain`/`release`/`autorelease`/`retain-count`,
  `make-autorelease-pool`/`with-autorelease-pool`, `ns-string-to-string`/`string-to-ns-string`,
  `objc-object-pointer`, `objc-object-from-pointer`, the type descriptor symbols. Variadic sends
  take `:variadic-num-of-fixed`.
- Additions with no LispWorks counterpart, rebuilt on the new base under their current names:
  `on-main`, `data`, `bytes`, `objectp`.
- `COCOA` as a new built-in package: `ns-point`, `ns-size`, `ns-rect`, `ns-range`, the four
  `set-ns-...*` setters, `ns-not-found` (`add-observer`/`remove-observer` land with .todo/a72).
- Free the name: `examples/macos/cocoa.lisp` defines a `cocoa` package (the board grid of
  `minesweeper-macos.lisp` and `life-macos.lisp`); rename it (e.g. `board`).
- `(asdf:load-system :objc)` answers from the built-in (`BuiltinSystems` / `ShimLibraries`,
  `.kb/asdf.md`).

Checklists for new names: `.kb/adding-primitives.md`.

## Done when

The manual's call-side examples (invoking, strings, memory management, the Foundation
structures) run unchanged on the interpreter, JVM class output, the native binary and `--native`;
the recorded LispWorks answers are asserted by a test; `.kb/objc.md` describes the new base.
