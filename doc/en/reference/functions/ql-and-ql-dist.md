# ql and ql-dist Package Functions

The `ql` package is a limited, API-compatible subset of Quicklisp:
`quickload` downloads a system from the real Quicklisp distribution into a local
cache and then loads it through the `asdf` subset (`quicklisp` is a built-in
nickname). `ql-dist` holds the distribution machinery, of which the one member a
program writes is `install-dist`: it adds another Quicklisp-format distribution —
[Ultralisp](https://ultralisp.org/), or any distinfo URL — to the dists
`quickload` searches, in installation order. Neither package is **part of Common
Lisp**; reference their symbols with the `ql:` / `ql-dist:` qualifier. The names
below link to their own pages; the [Systems
guide](../../guides/asdf-systems.md#downloading-with-quickload) covers the cache
layout and limitations.

| Function | Example | Result |
|----------|---------|--------|
| `ql:quickload` | `(ql:quickload "split-sequence")` | download a system (and its dependencies) from the installed dists, cache it under `~/.rontolisp/<dist>`, and load it; returns the list of loaded system names |
| `ql-dist:install-dist` | `(ql-dist:install-dist "ultralisp")` | add a Quicklisp-format distribution (a known name or a distinfo URL) to the dists `quickload` searches; returns the dist name |
| `ql:update-dist` | `(ql:update-dist "ultralisp")` | drop a dist's cached indexes so the next `quickload` sees its newest releases; returns the dist name |

