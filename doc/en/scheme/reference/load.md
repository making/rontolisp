# load

`(load filename)`

Evaluates the file named `filename` -- read as Scheme, like every file -- at run time,
its top-level forms in order in the global environment. A definition in the loaded file
is the loader's, visible to the forms after the `load`; a relative name resolves against
the loading file's directory. `load` answers the unspecified object. Under
[`--scheme-standard r7rs`](../standards.md) the loaded file obeys the same
begins-with-`import` rule as every other file.

A top-level `load` of a literal file name is inlined at compile time on the compiled
backends, the file's forms compiled in place; any other `load` loads at run time.

```scheme
(with-output-to-file "cfg.scm" (lambda () (display "(define mode 1)")))
(load "cfg.scm")
mode ; => 1
(delete-file "cfg.scm")
```
