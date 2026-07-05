# 70: Byte-field ops (`byte`/`byte-size`/`byte-position`/`ldb`/`dpb`)

Split off from `.todo/65-cl-utilities-support.md` (cl-utilities stdlib residue,
step 2 -- one of the two "heaviest" items). Single-session sized but nontrivial.

## Goal

The CL byte-specifier family:

- `(byte size position)` -- a byte specifier (a `(size . position)` pair in CL;
  choose a representation, e.g. a tagged 2-elem list or a cons).
- `(byte-size bytespec)` / `(byte-position bytespec)` -- accessors.
- `(ldb bytespec integer)` -- extract the `size`-bit field at `position` (right-
  shifted so the field's LSB is bit 0). `(ldb (byte 8 0) 255)` = 255,
  `(ldb (byte 4 4) 255)` = 15.
- `(dpb newbyte bytespec integer)` -- deposit the low `size` bits of `newbyte`
  into the `position` field of `integer`, others unchanged.
  `(dpb 0 (byte 4 0) 255)` = 240.

(`ldb`/`dpb` are also setf places in CL, but that is out of scope here -- function
forms only, matching rotate-byte's usage.)

## Why

cl-utilities `rotate-byte` uses `byte`/`ldb`/`dpb`. This is the piece that makes
`rotate-byte` runnable once `integer-length` (.todo/68) also lands.

## Current state

None exist. Bit-op precedent as in `.todo/68` (`logand`/`ash`). `byte` returns a
compound value, so pick a representation that all three backends can carry (a
2-element list is simplest and needs no new runtime type; keep it internal --
programs pass the result straight to `ldb`/`dpb`).

## Plan

1. Decide the byte-spec representation (recommend: a plain 2-elem list
   `(size position)` built by `byte`, read by `byte-size`/`byte-position`/
   `ldb`/`dpb` -- no new runtime type, works everywhere `list`/`car`/`cadr` do).
2. `LispNames` + `PackageRegistry.CL_FUNCTIONS` (count bump: see the pin list in
   `.todo/68`).
3. Interpreter (`Environment`, `BigInteger` shifts/masks) -> `LispEvaluatorTest`.
4. JVM -> `JvmLispCompilerTest`. Could implement `ldb`/`dpb` as
   `LispMacroExpander` lowerings over existing `ash`/`logand`/`logior`/`lognot`
   to avoid new codegen -- evaluate whether that keeps all backends in sync (the
   defstruct/multiple-values "lower over primitives" precedent).
5. WASM -> `WasmLispCompilerIntegrationTest`; same i31/i64/bignum caveat as
   `.todo/68` -- scope to the fixnum range + document if a full bignum path is
   impractical.
6. `BuiltinFunctionWrappers`, ci-spec, docs.

## Acceptance

The `ldb`/`dpb`/`byte` examples above on all four backends (or documented WASM
range limit). With `.todo/68` done, cl-utilities `rotate-byte` runs. Native E2E
green.
