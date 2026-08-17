# ql:update-dist

`(ql:update-dist name &rest options)`

Drops a dist's cached indexes, so the next [`ql:quickload`](ql-quickload.md)
re-reads that distribution's `systems.txt` and `releases.txt` and sees the
releases published since the cache was written. The argument is an installed
dist's name (`"quicklisp"`, `"ultralisp"`, ...) as a string, keyword or symbol,
and the return value is that name; naming a dist that is not installed is an
error. Keyword options are accepted and ignored.

Without it a dist index is cached forever, which is what makes a repeated
`quickload` free — but a distribution rebuilt every few minutes
([Ultralisp](https://ultralisp.org/)) publishes releases the cached index cannot
name. Already-extracted sources are kept: a release directory is named after its
version, so a newer release extracts beside the old one rather than replacing
it.

Same timing as `ql:quickload`: interpret time, or compile time for a **literal,
top-level** call (which is then consumed — the compiled program downloads
nothing at run time). A nested or computed call is a compile error on the
JVM/WASM backends.

```console
$ rontolisp
> (ql:update-dist "ultralisp")
"ultralisp"
> (ql:quickload "split-sequence")
(split-sequence)
```

See the [Systems guide](../../guides/asdf-systems.md#adding-a-dist-ultralisp)
and [`ql-dist:install-dist`](ql-dist-install-dist.md).
