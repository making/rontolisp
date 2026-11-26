# m65. JVM: a `vector` call costs a store per element

Difficulty: Medium

On the JVM `(vector a b ...)` is still `LispMacroExpander.expandVector`: `(make-array n)` and an
`aset` per element (the index boxed, `_aset1`, the answer popped). Measured 2026-11-26 (defvars as
elements): some 30 B a element; `(coerce (list ...) 'simple-vector)` some 21. wasm-GC makes the
storage from the elements at once (`.kb/array-literals.md`, "A `vector` call").

What to decide: an `Object[]` of the elements (`anewarray`, then `dup`, index, element,
`aastore` each, some 5 B and the element) handed to one helper of `JvmArrayRuntimeBuilder` that
answers what `(make-array n)` and the stores would -- the packed `long[]` while every element is
a fixnum, the general list otherwise (`_arrayMake`'s two paths); a call past the method limit
(`.kb/jvm-method-size-limits.md`) and an element holding a loop (`.kb/jvm-osr-backedges.md`, the
array under it on the stack). Measure the Java examples (the shiori's `%jdk-types` and class
objects' interfaces are `vector` calls) and the corpus.
