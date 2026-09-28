# Compile-Time Warnings

A compile does not fail on a mistake the interpreter would only signal when the code
runs. It compiles that code to the same run-time condition and prints a `warning:`
line on standard error, at the position of the form:

- a call whose argument count or keyword arguments the callee rules out, which signals
  `program-error` when it runs (see [`defun`](../reference/special-forms/defun.md))
- a call to a function the program never defines
- a `defun` of a `COMMON-LISP` function whose call sites compile to the standard
  operator anyway
- a primitive a `--no-wasi` module reaches while it loads, an `http-handler` port a war
  ignores, and, with `--warn-java-reflection`, a `java:` call left to run-time reflection
- a `(warn ...)` a macro calls while it expands, placed at the macro call. A
  `style-warning` prints as `style-warning:` instead, and a warning a handler muffles
  prints nothing

```lisp
(defun add (a b) (+ a b))
(handler-case (add 1) (program-error () :caught)) ; => :CAUGHT
```

```console
$ rontolisp counts.lisp -o Counts.class
counts.lisp:3:9: warning: Function expects 2 arguments, got 1; compiled as a call-time program-error
```

## Failing the Compile (`--warnings-as-errors`)

`--warnings-as-errors` makes such a compile fail. Every warning is still printed, then
one `error:` line; the exit status is 1 and no output file is written.

```console
$ rontolisp counts.lisp -o Counts.class --warnings-as-errors
counts.lisp:3:9: warning: Function expects 2 arguments, got 1; compiled as a call-time program-error
counts.lisp:4:9: warning: CAR expects 1 argument, got 2; compiled as a call-time program-error
error: 2 warnings about the program's source, treated as errors (--warnings-as-errors)
```

It applies to every `-o` output (`.class`, `.jar`, `.war`, `.wasm` with or without
`--component` or `--no-gc`, and `--native`) and to `rontolisp test ... -o`. Without
`-o` it is refused: the interpreter warns about nothing before the program runs.

Only warnings about the program's own source count: the entry file, the files it
`load`s, an `-e` program, and the ASDF systems found beside it or on `--system-path`. A
warning about code a macro built counts at the macro call. These are printed but never
count:

- a warning in a library rontolisp splices in
- a warning in a system `ql:quickload` downloaded from a dist, which you cannot fix
- a line that is not about the source: the `:async t` and `--host-fetch` host
  obligations, and `warning: no JDK found`
- a `style-warning` a macro signals while it expands

The Maven plugin takes it as `<warningsAsErrors>true</warningsAsErrors>`
(`-Drontolisp.warningsAsErrors=true`), and an embedder as
`JvmSourceCompiler.warningsAsErrors(true)`, whose compile then throws
`WarningsAsErrorsException`.
