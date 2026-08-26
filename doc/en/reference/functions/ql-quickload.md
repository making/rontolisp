# ql:quickload

`(ql:quickload name-or-names &rest options)`

Downloads a system (and its dependencies) from the real [Quicklisp](https://www.quicklisp.org/) distribution into a local cache, then loads it exactly like [`asdf:load-system`](asdf-load-system.md). The argument is a single system-name designator (string, keyword or symbol) or a list of them; the return value is the list of loaded system names as symbols. `quicklisp` is a nickname for the `ql` package. Keyword options (`:silent t`, ...) are accepted and ignored, like [`asdf:load-system`](asdf-load-system.md)'s.

The download uses the Quicklisp dist metadata: `quicklisp.txt` (the distinfo) points at `systems.txt` (dependency resolution) and `releases.txt` (the tarball URL per project). Each release tarball is fetched, extracted and cached under `~/.rontolisp/quicklisp/` (override the location with the `RONTOLISP_QUICKLISP_HOME` environment variable), so a second `quickload` of the same system does no network I/O. Quicklisp is the only dist installed by default; [`ql-dist:install-dist`](ql-dist-install-dist.md) (or the CLI `--dist`) adds another Quicklisp-format distribution such as [Ultralisp](https://ultralisp.org/), and each system — and each dependency — is then taken from the first installed dist that lists it. After the sources are present, the extracted `.asd` directories are added to the system search path and loading proceeds through the `asdf` subset — so `ql:quickload` is `asdf:load-system` with an auto-download step in front of it. It is subject to the same limitations: many libraries use Common Lisp features rontolisp does not implement (the full CLOS protocol, the condition system) and will not load even once downloaded.

The download happens at **interpret time or compile time** (on the Java side, not inside the compiled program). On the interpreter, `quickload` is an ordinary runtime function, so a computed name works. On the compile path (JVM/WASM), a **literal, top-level** `(ql:quickload NAME)` is downloaded and its component files are spliced into the program at compile time — so the compiled program has the sources baked in and never fetches at runtime (the WASM `fetch` limitation does not apply). A `quickload` nested inside another form or with a computed argument is a compile error.

```console
$ rontolisp
CL-USER> (ql:quickload "split-sequence")
(split-sequence)
CL-USER> (split-sequence:split-sequence #\, "a,b,c")
("a" "b" "c")
```

The first call downloads and caches `split-sequence`; the second call into the loaded system runs it. See the [Systems guide](../../guides/asdf-systems.md) for the cache layout and the list of libraries that actually load.
