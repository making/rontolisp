# Readtables

A readtable is a value on all four backends: `*readtable*` holds one, [`copy-readtable`](../reference/functions/copy-readtable.md) copies one, and [`readtable-case`](../reference/functions/readtable-case.md), [`set-macro-character`](../reference/functions/set-macro-character.md), [`set-dispatch-macro-character`](../reference/functions/set-dispatch-macro-character.md), [`make-dispatch-macro-character`](../reference/functions/make-dispatch-macro-character.md) and [`set-syntax-from-char`](../reference/functions/set-syntax-from-char.md) change one. named-readtables' `defreadtable` builds one from these.

```lisp
(let ((*readtable* (copy-readtable nil)))
  (set-macro-character #\! (lambda (s c) (list 'not (read s t nil t))))
  (read-from-string "!done"))
; => (NOT DONE)
```

## Which reads honor it

Source is read form by form, each form with the readtable current when the read reaches it:

- On the interpreter, a program and every file it loads: a form that changes `*readtable*` -- named-readtables' `in-readtable`, or a `setf` of it -- changes how the rest of that file reads.
- On the compile paths (`-o`), the same reads, with the forms before evaluated as `compile-file` evaluates them: a change made in an `eval-when` with `:compile-toplevel` (what `in-readtable` expands to) reads the rest of the file, and the files of an ASDF system loaded earlier count as loaded. The reader macro functions run at compile time, and what one returns must be data a compiled program can carry: a closure or a hash table is a read error. A `defreadtable` the same compile selects goes in an `eval-when`, as named-readtables' documentation asks.

`load` binds `*readtable*` on every backend, so a change ends with the file. On the interpreter, `read-from-string`, and `read` and [`read-delimited-list`](../reference/functions/read-delimited-list.md) on a string stream, honor `*readtable*` too.

```console
$ cat bang.lisp
(eval-when (:compile-toplevel :load-toplevel :execute)
  (setf *readtable* (copy-readtable nil))
  (set-macro-character #\! (lambda (s c) (list 'not (read s t nil t)))))
(print !nil)
$ rontolisp bang.lisp -o bang.wasm && wasmtime run bang.wasm

T
```

A reader macro function receives a string input stream over the text being read, positioned behind its character, and a symbol it reads is interned in the current package. A function returning no values makes its character start a comment. Case conversion follows `readtable-case`.

A reader macro used inside a backquote template reads that template's commas, as in Common Lisp: the `read` its function makes starts inside the template, and the comma is evaluated when the template is.

```console
$ cat keep.lisp
(eval-when (:compile-toplevel :load-toplevel :execute)
  (setf *readtable* (copy-readtable nil))
  (set-dispatch-macro-character #\# #\`
    (lambda (s c n) (declare (ignore c n)) (list 'lambda '(%) (read s t nil t)))))
(defmacro keep-if (test xs) `(remove-if-not #`(funcall ,test % 2) ,xs))
(print (keep-if #'> '(1 2 3 4)))
$ rontolisp keep.lisp -o keep.wasm && wasmtime run keep.wasm

(3 4)
```

So libraries that read their own source through a readtable load: named-readtables, cl-interpol (`#?"Hello, ${name}"`) and cl-csv on all four backends, jonathan (through cl-syntax) on the interpreter and the JVM, and cl-annot (`@export`) and rutils on the interpreter.

## Not yet

- The compiled backends' run-time `read` and `read-from-string` ignore `*readtable*`.
- `read` from a file stream or standard input uses the standard syntax.
- `with-standard-io-syntax` does not rebind `*readtable*`.
- A standard dispatch function, such as `(get-dispatch-macro-character #\# #\()`, cannot be called directly; it can be compared and installed.
