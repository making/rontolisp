# probe-file

`(probe-file pathname)`

Answers whether a file exists: the pathname when it does, `nil` when it does not. This is the only file operation that asks the question without opening anything, and the only one that does not fail on a missing path -- `open` (and therefore `with-open-file`) signals an error on every backend. Catching that error is a workable substitute, but a heavier one: `handler-case` puts the WASM backends into exception mode, and `--no-gc` rejects catching entirely, so a plain probe is the portable spelling. Works on all four backends. [`truename`](truename.md) is the signalling twin of this function.

A pathname is its namestring in rontolisp, so the "truename" returned on success is the argument string itself: no backend resolves symbolic links or makes the path absolute. The path is interpreted exactly as `open` interprets it -- relative to the process working directory on the interpreter and the JVM, and to the first preopened directory on WASM (run with `--dir`). A directory counts as existing. `uiop:file-exists-p` is the same operation under its ASDF/UIOP name.

```console
(if (probe-file "config.lisp")
    (load "config.lisp")
    (print "no config"))
```

Here the file is loaded only when it is there; without `probe-file` the missing-file case would abort the program rather than take the `else` branch.
