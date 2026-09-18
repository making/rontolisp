# `replace` of overlapping regions of ONE sequence copies forward

Difficulty: Medium

CLHS `replace`: when sequence-1 and sequence-2 are the same object and the regions
overlap, the result is as if the whole source region were copied first. Every backend
copies element by element forward instead, and the interpreter's string path differs
from the compiled ones (found 2026-09-18 writing Scheme's `bytevector-copy!`):

```lisp
(let ((v (vector 1 2 3 4 5))) (replace v v :start1 1 :end2 3) v)
;; want #(1 1 2 3 5); every backend: #(1 1 1 1 5) (also a packed (unsigned-byte 8) vector, a list)
(let ((v (copy-seq "abcde"))) (replace v v :start1 1 :end2 3) v)
;; want "aabce"; interpreter "aabce", JVM and wasm "aaaae"
```

Copying backward (or through a temporary) when `(eq seq1 seq2)` and `start1 > start2` is
the fix; the paths are `Environment`'s `replace`, `LispMacroExpander.expandReplace` and
its runtime wrappers (`REPLACE_RUNTIME`, `REPLACE_ARRAY_RUNTIME`, `REPLACE_BULK`).
Failing ci-spec case first (all four backends, general vector, packed vector, string, list).

Scheme's `bytevector-copy!` works around it (`%scheme-bytevector-copy!` in `scheme.lisp`
copies the source region out when both are one bytevector); drop the workaround once
this lands.
