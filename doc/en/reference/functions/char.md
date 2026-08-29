# char schar

`(char string index)` -- `(schar string index)`

Returns the character at the 0-based `index` of `string`. `char` and `schar` behave identically here; in Common Lisp `schar` is the simple-string variant, but rontolisp treats them the same. The WASM backend indexes strings by byte, so indexing is well-defined for ASCII text only.

Both are also `setf` places: `(setf (schar s i) c)` / `(setf (char s i) c)` replaces the character at `i` and returns `c`. A string the running program allocated -- a [`make-string`](make-string.md) buffer, say -- is mutated in place. A string **literal** is never mutated on any backend: it is the constant in your source, shared by every evaluation of the form it appears in, so the write rebuilds the string and rebinds the place instead. That means the string expression must be a **variable** when it holds a literal (writing through `(setf (char "abc" 0) #\Z)`, or through a literal reached by an accessor, is an error), and an alias made before the write still sees the literal's own content. The compiled backends apply the rebuild-and-rebind to every immutable string, not only a literal, so an alias never sees such a write there.

```lisp
(char "hello" 1) ; => #\e
```
