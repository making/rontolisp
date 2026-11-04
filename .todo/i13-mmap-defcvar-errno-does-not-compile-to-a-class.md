# i13. mmap's `(cffi:defcvar errno :int)` does not compile to a class

Difficulty: Medium

Measured 2026-11-04 on the Quicklisp `mmap-20260101` sources, before and after the change that
lets `macroexpand` read symbol macros through `&environment`.

```lisp
(ql:quickload "mmap")   ; -o M.class
```

`error: while compiling defun MMAP::CHECK-POSIX: Cannot compile symbol reference: MMAP::ERRNO`.
`posix.lisp:95` defines it and `:169` reads it. On the interpreter mmap loads and 3bz, which needs
it, decompresses a deflate stream correctly.

`CffiSystemTest#defcvarReadsAndWritesARealCGlobal` covers the string-named shape. Find whether a
bare symbol name, or the file order under `asdf`, loses the `define-symbol-macro`
(`.kb/symbol-macrolet.md`, `.kb/cffi.md`). Consumer: 3bz and pngload on a compiled class, after i12.
