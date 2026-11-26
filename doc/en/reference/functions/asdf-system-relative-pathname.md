# asdf:system-relative-pathname

`(asdf:system-relative-pathname system relative)`

Returns the namestring of `relative` resolved against the source directory of the named `system` — the one-call form of merging a relative path onto `(asdf:system-source-directory system)`. This is how a library names a data file it bundles next to its `.asd`. `system` is a string, keyword or symbol designator (or a value returned by [`asdf:find-system`](asdf-load-system.md)); a system that is not registered is an error.

`relative` takes either spelling — a namestring, or the pathname `#P"data/list.dat"` denotes. The answer stays a namestring (the ASDF locators are compile-time facts here, not pathname producers).

On the compile path (JVM/WASM) the call is folded to that literal namestring while the program is being built, so a `with-open-file` over the result can be inlined into the artifact and the compiled program needs neither the system registry nor the file at run time.

```console
$ cat my-lib.asd
(defsystem :my-lib :components ((:file "main")))

$ cat main.lisp
(print (asdf:system-relative-pathname :my-lib "data/tlds.dat"))

$ rontolisp run.lisp --system-path .
"/home/me/my-lib/data/tlds.dat"
```
