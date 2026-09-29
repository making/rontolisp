# `fli:` memory access cheap enough for a real-time loop on the interpreter and the native binary

Difficulty: High

## The problem

`examples/macos/audio.lisp` plays a Lisp render block live through an `AVAudioSourceNode`. The
block writes each sample with `(setf (fli:dereference samples :index i) x)`. At 44.1 kHz the
whole per-sample budget is ~22.7 us, and the instrument function has to fit in it too.

Measured 2026-09-29 (macOS 26, M4 Max; `.kb/objc.md`, "Measurements"):

| target | one `(setf fli:dereference)` | frames in 2 s live (88,200 due) |
|---|---|---|
| `java -jar` (interpreter) | ~35 us | 30,106-42,807 |
| native binary (interpreter) | ~35 us | 23,050-28,695 |
| JVM class / jar | ~0.7 us | 88,436 |
| `--native` | ~1.7 us | (live refused there: the block answers a value off thread 0) |

The interpreted cost breaks down as follows. `objc::%element` re-derives the element size on
every call: `fli:size-of` -> `objc::%fli-layout` -> `objc::%parse-type` of the encoding, ~15 us.
`objc::%store` then parses the same encoding again (`%parse-type` alone is ~8.5 us for `"f"`) and
runs `objc::%raw-arg` before `objc::%poke`. The host (`ObjcRuntime.poke`) looks the encoding
string up in its parse cache and opens a confined `Arena` for every write. Memoizing the two Lisp
parses brought one write down to 15-19 us. That is still at the deadline, so it was not landed.
Today the live render stutters on both interpreted targets, and the example prints its frame count.

`fli:dereference`, `fli:foreign-slot-value` and their `setf`s share `%element` / `%slot` /
`%load` / `%store` (`src/main/resources/am/ik/rontolisp/eval/objc.lisp`, FLI section), so all
four pay this cost. `%slot` also recomputes the C layout of every slot before the one it wants.

## What is needed

An `fli:` scalar access on the interpreter and the native binary that costs a small fraction of
the per-sample budget. Target: <= 2 us for one `(setf fli:dereference)` of a `:float`, so that a
live `audio.lisp` renders >= 87,000 of 88,200 frames on `java -jar` and on the native binary.
Keep behaviour identical on all targets: the objc corpora stay byte for byte, and the JVM class
and `--native` must not get slower.

Memoizing the parse is not the fix. Find out where the time goes, then remove the work instead of
caching it. Measure each candidate below before choosing; do not reason about them.

- **Resolve a type once, not on every access.** A pointer carries its FLI type and the pointee's
  encoding STRING (`fli::pointer`), and every access starts again from that string. Resolve the
  type once, when the pointer is made or the type is first named, into a descriptor: size,
  alignment, scalar kind, boolean flag, and slot offsets for a structure. Keep that descriptor on
  the pointer or in a per-type table. After that, an access is arithmetic plus one primitive
  call. `%slot`'s offsets belong in the same descriptor.
- **A scalar primitive that takes no encoding.** `%peek` / `%poke` take an encoding string, and
  every host parses it or looks it up (`ObjcRuntime.peek` / `poke` via `parsed`, `encoding.rs`
  on `--native`). A scalar access could pass a small kind code instead, with the host doing one
  `MemorySegment` get/set and no `Arena`. This is a primitive-layer change on three hosts
  (`eval/ObjcPrimitives`, `JvmObjcPrimitivesTemplate`, `objc-native-primitives.lisp` + `prim.rs`),
  plus `LispNames.OBJC_PRIMITIVES` and `ObjcClassTest`'s no-redefinition check.
- **The interpreter's own cost for this code.** Before this work, `objc.lisp` alone cost 12-23 us
  per send when interpreted and ~1 us on `--native`. The FLI path goes through keyword arguments,
  `multiple-value-bind`, and several defuns deep. Profile one `(setf fli:dereference)` on
  `java -jar` and see how much is argument parsing and call overhead, and how much is actual
  work. If the interpreter is the floor, the fix is there (for example, a faster path for a
  spliced library's defuns). That fix would also speed up every interpreted `objc:invoke`.
- **A bulk path, as an addition and not a replacement.** A block that fills a whole buffer could
  convert a packed float array into foreign memory with one `%write-octets` (the `objc:data`
  machinery already produces those bytes). Check first whether LispWorks' FLI has a spelling for
  this (`fli:replace-foreign-array`) before inventing one. Per-element `fli:dereference` must
  still become cheap: it is what the manual's forms and a `BOOL *stop` write use.

## Done when

- The table above has been re-measured with the method `.kb/objc.md` records, and the new numbers
  and date are written there. The `(setf fli:dereference)` line in that file's open items is
  gone or restated.
- `ObjcBaseTest`, `ObjcClassTest`, `ObjcBlockTest`, `ObjcExceptionTest`,
  `JvmObjcBaseCompilerTest` and `NativeObjcE2eTest` are green. The native binary (`-Pnative`)
  prints the base corpus byte for byte.
- `audio.lisp` played live on `java -jar` and the native binary renders >= 87,000 frames in 2 s,
  and its comment about the interpreter stuttering has been updated.
