# A pointer argument is its own carrier, and that doubles the shipped shape grid

Difficulty: Medium

Found while landing todo-632 (2026-09-02). `FfiRuntime`'s canonicalisation has FOUR
carriers per parameter -- `void*`, `jlong`, `jdouble`, `jfloat` -- and `void*` is one of
them only because FFM spells an address as `ValueLayout.ADDRESS` rather than
`JAVA_LONG`. On both platforms the linker serves, a pointer and a 64-bit integer are the
SAME thing to the ABI: one integer-class register (SysV) / one general register
(AAPCS64), same width, same alignment.

Native Image does not agree, and that is the measurement this item starts from:
`struct(jlong,jlong)(jlong,jlong)` was refused by a binary whose grid already carried
`struct(jlong,jlong)(void*,void*)` (the objc entry), so the two carriers are two stubs
there even though they are one calling convention.

## What collapsing them would buy

The grid's parameter dimension is `carriers^arity`. Merging `void*` into `jlong` takes
the pointer/integer tier from `2^arity` to `1`, i.e. 127 tuples at arity 0-6 to 7:

- the scalar grid, 1125 entries today, would fall to a few hundred;
- the by-value struct-RETURN family todo-632 added -- 70 return shapes x 45 parameter
  tuples = 3,150 entries, 444 KB of the checked-in
  `reachability-metadata.json` -- would fall by roughly the same factor, since its
  parameter tuples are the canonical ones.

The checked-in file is the only thing bounding either grid: 3,150 added entries moved the
image by 80 methods, ~30 KB of code area, and the binary size and build time not at all
(todo-632's measurements, in `.kb/ffi.md`). So the win here is the FILE and the shapes it
can then afford to carry, not the binary.

## What has to be true first

`toNativeArgument` hands FFM a `MemorySegment` for `:pointer` and `:string` today. Under
the collapse it would hand a `Long`, which means:

- **`:string` loses FFM's liveness tracking.** The confined arena a `:string` argument is
  copied into is alive across the `invokeWithArguments` (try-with-resources in
  `FfiRuntime.call`), so passing `segment.address()` is correct -- but it is correct by
  the call's own structure rather than by the API's checking. Say so where it is written.
- **A `void*` RETURN** comes back as a `Long` instead of a `MemorySegment`;
  `fromNativeReturn`'s `POINTER`/`STRING` cases both read the segment today, and the
  `STRING` case needs a segment to `getString` from (`MemorySegment.ofAddress(x)
  .reinterpret(...)` -- what `peek` already does).
- **Upcalls** take the same treatment in the other direction (`dispatch` /
  `toCallbackReturn`), or the two grids stop agreeing.
- The objc and gpu bindings build their OWN descriptors and are not touched; their
  `void*` entries stay.

## The measurement that decides it

Regenerate both grids with the merged carrier, then:

1. `FfiTest`, `CffiSystemTest` and `JvmFfiInteropCompilerTest` all pass -- a real
   `libsqlite3` call through a pointer is the case that matters.
2. Build the binary and run `examples/jvm/cffi-sqlite.lisp` plus the cffi guide's own
   transcript on it (todo-632 left a working list: strlen, getpid, gettimeofday, qsort
   through a callback, snprintf varargs, defcvar, div by value).
3. Record the new file size and entry count in `.kb/ffi.md` beside todo-632's numbers.

If a pointer argument turns out to need the ADDRESS layout for something (a `critical`
registration, a heap segment, an FFM check we depend on), that finding is the deliverable
and the four carriers stay -- write it into `.kb/ffi.md` and close this.
