# compile-file

`(compile-file input-file &key ...)`
`(compile-file-pathname input-file &key ...)`
`(remove-method generic-function method)`

The three standard names that exist so a program mentioning them loads, and
signal an error when they are actually called.

`compile-file` and `compile-file-pathname` have nothing to name. rontolisp has no
file compiler: a program is compiled **whole** in one pass and a `load`ed file is
spliced into it, so no fasl is ever produced and no pathname names one -- which is
also why `*compile-file-pathname*` and `*compile-file-truename*` are permanently nil. Both signal rather than answering a
fabricated pathname for a file that will never exist. To compile, run the compiler:
`rontolisp prog.lisp -o Prog.class`, `-o prog.wasm`.

`remove-method` has no method to remove. A method here is a registry row plus a
generated function, never a first-class object, and there is no `find-method` to
obtain one from -- so no caller can name the method it means.

```console
$ rontolisp -e '(compile-file "x.lisp")'
Unhandled condition: compile-file is not supported (no file compiler: a program is compiled whole)
```

## Backend support

All three behave identically on all four backends.
