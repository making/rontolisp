# char schar

`(char string index)` -- `(schar string index)`

Returns the character at the 0-based `index` of `string`. `char` and `schar` behave identically here; in Common Lisp `schar` is the simple-string variant, but rontolisp treats them the same. The WASM backend indexes strings by byte, so indexing is well-defined for ASCII text only.

Both are also `setf` places: `(setf (schar s i) c)` / `(setf (char s i) c)` replaces the character at `i` and returns `c`. A string the running program allocated -- a [`make-string`](make-string.md) buffer, say -- is mutated in place. A string **literal** is never mutated on any backend: it is the constant in your source, shared by every evaluation of the form it appears in, so the write rebuilds the string and rebinds the place instead. That means the string expression must be a **variable** when it holds a literal (writing through `(setf (char "abc" 0) #\Z)`, or through a literal reached by an accessor, is an error), and an alias made before the write still sees the literal's own content. On the compiled backends a string built by `copy-seq`/[`subseq`](subseq.md), `concatenate 'string`, the [`string-upcase`](string-upcase.md) family, `format nil`, [`with-output-to-string`](../macros/with-output-to-string.md) or [`read-line`](read-line.md) is mutable like a `make-string` buffer, so aliases of it see the write; the rebuild-and-rebind also applies to the few producers whose results are still immutable values there ([`princ-to-string`](princ-to-string.md), for example), where an alias never sees such a write.

```lisp
(char "hello" 1) ; => #\e
```
