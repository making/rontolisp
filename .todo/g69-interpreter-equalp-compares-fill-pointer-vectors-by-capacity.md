# g69: the interpreter's equalp compares fill-pointer vectors by capacity

Difficulty: Low

On the interpreter, `equalp` of two adjustable vectors with fill pointers returns NIL even
when their active elements are equal, unless the length happens to be 8 or 16 -- it looks
like it compares the underlying capacity (or the storage beyond the fill pointer) instead
of the active elements. SBCL returns T. Found 2026-10-23 in a unit test that built byte
buffers with `(make-array 0 :adjustable t :fill-pointer 0)` and `vector-push-extend`.

## Goal

`equal`/`equalp` (and `string=`/`mismatch`/`search` if they share the path) compare
vectors by their active elements, up to the fill pointer, on the interpreter, JVM and both
WASM backends, matching SBCL; pin it with a ci-spec case covering lengths other than the
growth steps.
