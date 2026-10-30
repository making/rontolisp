# Readtables

A readtable is a value on all four backends: `*readtable*` holds one, [`copy-readtable`](../reference/functions/copy-readtable.md) copies one, and [`readtable-case`](../reference/functions/readtable-case.md), [`set-macro-character`](../reference/functions/set-macro-character.md), [`set-dispatch-macro-character`](../reference/functions/set-dispatch-macro-character.md), [`make-dispatch-macro-character`](../reference/functions/make-dispatch-macro-character.md) and [`set-syntax-from-char`](../reference/functions/set-syntax-from-char.md) change one. named-readtables' `defreadtable` builds one from these.

```lisp
(let ((*readtable* (copy-readtable nil)))
  (set-macro-character #\! (lambda (s c) (list 'not (read s t nil t))))
  (read-from-string "!done"))
; => (NOT DONE)
```

## Which reads honor it

The interpreter's reader reads with the readtable current when it reads:

- A program and every file it loads are read form by form. A form that changes `*readtable*` -- named-readtables' `in-readtable`, or a `setf` of it in an `eval-when` -- changes how the rest of that file reads. `load` binds `*readtable*`, so the change ends with the file.
- `read-from-string`, and `read` and [`read-delimited-list`](../reference/functions/read-delimited-list.md) on a string stream.

A reader macro function receives a string input stream over the text being read, positioned behind its character, and a symbol it reads is interned in the current package. A function returning no values makes its character start a comment. Case conversion follows `readtable-case`.

So libraries that read their own source through a readtable load on the interpreter: named-readtables, cl-interpol (`#?"Hello, ${name}"`), cl-annot (`@export`), cl-syntax and jonathan.

## Not yet

- The compile paths (`-o`) read source with the standard syntax: a reader macro a program installs is not called there.
- The compiled backends' run-time `read` and `read-from-string` ignore `*readtable*`.
- `read` from a file stream or standard input uses the standard syntax.
- `with-standard-io-syntax` does not rebind `*readtable*`.
- A standard dispatch function, such as `(get-dispatch-macro-character #\# #\()`, cannot be called directly; it can be compared and installed.
