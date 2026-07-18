# char schar

`(char string index)` -- `(schar string index)`

Returns the character at the 0-based `index` of `string`. `char` and `schar` behave identically here; in Common Lisp `schar` is the simple-string variant, but rontolisp treats them the same. The WASM backend indexes strings by byte, so indexing is well-defined for ASCII text only.

Both are also `setf` places: `(setf (schar s i) c)` / `(setf (char s i) c)` replaces the character at `i` and returns `c`. The interpreter mutates the string in place; the compiled backends rebuild the string and rebind it, so the string expression must be a **variable** there, and an alias made before the write still sees the old content.

```lisp
(char "hello" 1) ; => #\e
```
