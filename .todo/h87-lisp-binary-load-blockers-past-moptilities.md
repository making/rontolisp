# h87. lisp-binary: the load blockers past moptilities

Difficulty: Medium

Re-probed 2026-10-31 (`.kb/clos.md`, "Out of scope / known gaps", lisp-binary): its dependency
moptilities loads on all four backends now. lisp-binary's own load then stops, in order, at:

1. `simple-bit-stream.lisp:8` `(defclass bit-stream (fundamental-binary-stream
   fundamental-input-stream fundamental-output-stream) ...)`: trivial-gray-streams'
   `fundamental-binary-stream` and `fundamental-character-stream` are not classes here (the
   protocol has the binary/character input and output leaves only, `.kb/gray-streams.md`).
2. `lisp-binary.asd`: `asdf:*asdf-version*` (and `*asdf-revision*`) is not defined, so the `.asd`
   computes "1.0", pushes `:lisp-binary-upgrade-asdf` and loads its bundled `asdf.lisp`, which
   signals "ASDF is not supported on your implementation". A `(push
   :lisp-binary/never-use-own-asdf *features*)` before the quickload did not avoid it -- measure
   which feature set the `.asd` read sees.
3. `utils.lisp` `struct-like-defclass` is `(defparameter ,name (defclass ,name ...))`: it needs
   `defclass` to answer the class OBJECT (CLHS; it answers the name here), because
   `bind-class-slots` hands that value to moptilities' `slot-names` at macro-expansion time, and
   `slot-names` of a SYMBOL is moptilities' own `nyi` off sbcl/ccl/lispworks/allegro. On the
   compile paths the `defclass` is also not top-level.

Not measured past (3). Each step on all four backends.
