# `objc`: class definition on the new base -- any callback shape, super sends, CLOS

Difficulty: High

Depends on .todo/a71 (the new base, its ownership and object representation). Same rule: nothing
here calls the old verbs or the old bridge.

## Scope

The LispWorks 8.1 `OBJC` definition half: `define-objc-class`, `define-objc-method`,
`define-objc-class-method`, `standard-objc-object` (CLOS integration),
`objc-object-var-value`, `objc-object-copied`, `objc-object-destroyed`, `current-super`,
`define-objc-struct`, `define-objc-typedef`, `define-objc-protocol`, the `invoke-into`
dispositions that need them, and `COCOA`'s `add-observer`/`remove-observer`. It replaces
`objc:define-class`, which .todo/a75 deletes.

## What the old base cannot do, and is replaced rather than extended

- **Callback shapes are a closed set of six** (`v@:`, `v@:@`, `v@:@@`, `B@:@`, `@@:@`, `q@:@`:
  `am.ik.objc.ObjcClasses.SHAPES`, one static upcall each, the same six in `foreign.upcalls` of
  `META-INF/native-image/am.ik.rontolisp/rontolisp-objc/reachability-metadata.json`).
  `define-objc-method` needs integer, floating-point and structure-by-value arguments and results,
  e.g. `areaOfWidth:height:` over two `(:unsigned :int)`, or a method answering `NSRange`.
- **`--native`**: the runner's single IMP (`rontolisp-native/runner/src/objc`) serves
  `(self, _cmd, a, b) -> x0` only. Floating-point arguments arrive in `d0-d7`, a structure over
  16 bytes by reference, a structure result through `x8`; `call.rs` classifies these for the
  downcall direction and is the model.
- **No super send**: `current-super` needs `objc_msgSendSuper` (`_stret` on x86_64, never
  exercised here).

## Decide

- Upcall shapes on the JVM: under `java -jar` an FFM upcall stub can be made for any shape at run
  time; only a native image needs them registered. A generated grid (as the 144-entry variadic grid
  in `ObjcNativeImageForeignConfigTest`), or run-time stubs plus a documented native-binary subset
  that refuses naming the registration to add.
- How a `standard-objc-object` instance relates to .todo/a71's object representation, and when
  `objc-object-destroyed` runs relative to the `Cleaner`.

## Done when

The manual's class-definition examples (section 1.4's `MyObject` with `areaOfWidth:height:`, a
method answering a structure, a super send, an observer) run unchanged on the interpreter, JVM
class output and `--native`; the native binary runs them or refuses by name.
