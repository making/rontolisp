# compile-file

`(compile-file input-file &key ...)`
`(compile-file-pathname input-file &key ...)`

The two standard names that exist so a program mentioning them loads, and
signal an error when they are actually called.

`compile-file` and `compile-file-pathname` have nothing to name. rontolisp has no
file compiler: a program is compiled **whole** in one pass and a `load`ed file is
spliced into it, so no fasl is ever produced and no pathname names one -- which is
also why `*compile-file-pathname*` and `*compile-file-truename*` are permanently nil. Both signal rather than answering a
fabricated pathname for a file that will never exist. To compile, run the compiler:
`rontolisp prog.lisp -o Prog.class`, `-o prog.wasm`.

```console
$ rontolisp -e '(compile-file "x.lisp")'
Unhandled condition: compile-file is not supported (no file compiler: a program is compiled whole)
```

## Backend support

Both behave identically on all four backends.
