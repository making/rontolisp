# ql-dist:install-dist

`(ql-dist:install-dist name-or-url &rest options)`

Installs a Quicklisp-format distribution beside the Quicklisp dist that
[`ql:quickload`](ql-quickload.md) downloads from. The argument is a known dist
name — `"quicklisp"` or `"ultralisp"` — or the URL of a distinfo
(`"http://dist.ultralisp.org/"`, the URL [Ultralisp](https://ultralisp.org/)
itself tells you to install, serves its distinfo directly). The return value is
the installed dist's name as a string; installing the same dist twice is a
no-op. Keyword options (`:prompt nil`, ...) are accepted and ignored — nothing
here prompts before downloading.

Only Quicklisp is installed by default, so Ultralisp is **opt-in**: this call,
or the CLI `--dist` option / the `RONTOLISP_DISTS` environment variable for
invocations with nowhere to put a form. The dists are searched **in the order
they were installed**, per system: `ql:quickload` takes each system (and each
dependency) from the first dist that lists it, so an added dist supplies the
names Quicklisp does not have without changing where anything else comes from.
A dist's indexes are downloaded only when a lookup actually reaches it. Each
dist caches under `~/.rontolisp/<dist>/` (`RONTOLISP_DIST_HOME` overrides the
base; `RONTOLISP_QUICKLISP_HOME` still overrides the quicklisp one).

Like `ql:quickload`, it takes effect at **interpret time or compile time** (on
the Java side, not inside the compiled program). On the compile path (JVM/WASM)
a **literal, top-level** call configures the dists the `quickload` forms below
it download from while the program is being spliced, and is then consumed; a
call nested inside another form, or with a computed argument, is a compile
error. On the interpreter it is an ordinary runtime function, so a computed URL
works.

```console
$ rontolisp
> (ql-dist:install-dist "http://dist.ultralisp.org/" :prompt nil)
"ultralisp"
> (ql:quickload "split-sequence")
(split-sequence)
```

See the [Systems guide](../../guides/asdf-systems.md#adding-a-dist-ultralisp)
for the search order, the cache layout and
[`ql:update-dist`](ql-update-dist.md), which refreshes a dist's index.
