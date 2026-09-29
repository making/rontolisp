# `fli:` bulk copy between a Lisp array and foreign memory

Difficulty: Medium

## The problem

An interpreted render block (`examples/macos/audio.lisp`) writes each sample with its own
`(setf (fli:dereference samples :index i) x)`. That write costs ~3 us interpreted (2026-09-29,
M4 Max, `.kb/objc.md`, "Measurements"), most of it the evaluator's per-form cost, not memory
access. Measured on the same machine, interpreted, for 512 floats:

| path | time |
|---|---|
| 512 `(setf fli:dereference)` | ~1,550 us |
| 512 `(setf aref)` into a `single-float` vector | ~234 us |
| one `objc::%write-octets` of that vector's `objc::%octets` | ~5 us |

So a block that computes a buffer into a Lisp array and copies it once spends ~0.47 us a sample
instead of ~3 us.

## What is needed

A public way to copy a whole buffer in one call, in both directions (Lisp array to foreign memory,
foreign memory to Lisp array), on the interpreter, the JVM class and `--native`, answering the
same everywhere.

- Use LispWorks' spelling if its FLI has one. `fli:replace-foreign-array` is the candidate: read
  its entry in the LispWorks FLI manual (argument order, `:start1 :end1 :start2 :end2`,
  `:allow-lisp-arrays`, what counts as a foreign array -- a pointer to `(:c-array ...)` or also a
  pointer to a scalar element type as `fli:allocate-foreign-object :nelems` makes) before
  deciding. Invent a name only if it has none.
- Build on what exists: `objc::%octets` (a packed array's bytes; Lisp on `--native`),
  `objc::%write-octets` / `%read-octets`. The foreign-to-Lisp direction needs bytes back into
  floats on `--native` too.
- Per-element `fli:dereference` stays as it is; this is an addition.
- User docs in `doc/en/**` and `doc/ja/**`; a corpus line pinned on the interpreter, the JVM
  class and `--native`; `audio.lisp` may use it once it exists.

## Done when

- The copy exists on all three targets with a corpus line each pins, docs in both languages.
- The table above is re-measured with the new call.
