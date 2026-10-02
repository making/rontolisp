# b68. `(scheme load)`: the `load` procedure

Difficulty: Low

The last missing R7RS-small library name. R7RS exports one procedure,
`(load filename)` -- evaluate the file's forms as if in the REPL environment
(top-level, in order), returning unspecified.

The machinery exists: `eval/load-streams.md` `load`, the compile path's `LoadInliner`
(`.kb/load-inliner.md`), and `SchemeFiles` for `include`. What is missing is the Scheme
spelling: add `load` to `SchemeBuiltins` (library `load`) and to
`SchemeLowering.IMPORTABLE_LIBRARIES`, lowering to the Common Lisp `load` of the
mangled filename -- a `.scm` loaded this way must be read as Scheme
(`eval/SourceLanguage.SCHEME` is per file, so this may already hold; verify, do not
assume -- `.kb/source-language.md`).

Questions the pin must answer:

- The returned value: unspecified object, as everywhere.
- A loaded file's top-level `define` lands in the global environment, visible to the
 loader -- same as CL `load`.
- `exit` inside a loaded file: the loaded file's own wrapper ends the process
 (`.kb/scheme-frontend.md`, "Residual") -- keep and pin, or fix here if trivial.
- Under `--scheme-standard r7rs`, does the loaded file itself have to begin with
 `import`? Follow the same per-file rule as every other file.

## Oracle

```console
$ gosh -r7 -e '(load "t.scm")(display x)(newline)'   # t.scm defines x
```

Pin: define-then-use across the load, load order, error on a missing file
(`file-error?`), the no-op on a program that never calls `load` (byte-identical
output).

## Acceptance

A `scheme-spec.yaml` case on all four backends; the interpreter/JVM legs plus the
compile-path inline in the same case; libraries doc table row.
