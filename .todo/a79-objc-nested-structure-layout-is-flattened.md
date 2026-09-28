# objc: a nested structure is laid out flattened, not by the C rule

Difficulty: Medium

Every host lays a structure out over its scalar LEAVES as if they were one flat member list
(`am.ik.objc.TypeEncoding`, `runner/src/objc/encoding.rs` `Type::layout`, and `objc.lisp`'s
`objc::%type-size` over a parsed type). That equals the C layout only while no nested structure
has tail padding. `{Outer={Inner=dc}c}` is 24 bytes in C (`Inner` is 16: `d`, `c`, 7 bytes of
padding; the outer `c` at 16), but 16 flattened (the outer `c` at 9). A send, a method or block
argument and result, `%peek` / `%poke` and an ivar of such a type all read and write the wrong
bytes.

`fli:size-of`, `fli:foreign-slot-value` and a `define-objc-class` ivar declared with a
`define-objc-struct` type already use the C rule (`objc::%fli-layout`, over the named slots), so
for such a structure they disagree with the hosts.

## Done when

- The parsed type keeps the member nesting (or its offsets), and all three hosts compute offsets,
  size and alignment by the C rule; the AArch64 classification (HFA, > 16 bytes by reference) is
  unchanged for structures without tail padding.
- A corpus line passes and answers a structure with tail-padded nesting through a method defined
  in Lisp, identical on the interpreter, a JVM class and `--native`.
