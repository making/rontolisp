# probe-file

`(probe-file pathname)`

Answers whether a file exists: the pathname when it does, `nil` when it does not. This is the only file operation that asks the question without opening anything, and the only one that does not fail on a missing path -- `open` (and therefore `with-open-file`) signals an error on every backend. Catching that error is a workable substitute, but a heavier one: `handler-case` puts the WASM backends into exception mode, and `--no-gc` rejects catching entirely, so a plain probe is the portable spelling. Works on all four backends. [`truename`](truename.md) is the signalling twin of this function.

The pathname answered on success is the file's truename, as SBCL's is: every symbolic link in the path resolved and every `.` and `..` taken out, the pathname [`truename`](truename.md) answers. With `d` a link to `x`, `(probe-file "d/f.txt")` is `#P"x/f.txt"`. Nothing is made absolute -- a relative path answers a path relative to the working directory, which a WASM module cannot name. A directory answers in directory form whatever the spelling: `(probe-file "d")` and `(probe-file "d/")` are both `#P"d/"`, and so is a link to `d`; a file spelled with a trailing `/` answers without it. A link whose target is missing answers the link itself, as SBCL's does. The argument takes either spelling (a pathname or a namestring). The path is interpreted exactly as `open` interprets it -- relative to the process working directory on the interpreter and the JVM, and to the preopened directories on WASM (a relative path against the first one, an absolute path against the preopened directory whose name is its longest prefix; run with `--dir`). A directory counts as existing. [`uiop:file-exists-p`](uiop-file-exists-p.md) answers the same for everything but a directory.

On WASM a link is resolved only where the module can read it, through a preopened directory: a link above every preopen stays as spelled (macOS's `/tmp` is a link to `/private/tmp`, so under `--dir /tmp` the answer for `/tmp/f.txt` is `#P"/tmp/f.txt"`, and under `--dir /` it is `#P"/private/tmp/f.txt"`), and a link whose target is absolute is not followed (the WASI host refuses it), so such a path is not there. Either way the answer is a path the module can open.

```console
(if (probe-file "config.lisp")
    (load "config.lisp")
    (print "no config"))
```

Here the file is loaded only when it is there; without `probe-file` the missing-file case would abort the program rather than take the `else` branch.
