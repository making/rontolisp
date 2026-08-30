# asdf Package Functions

The `asdf` package is a limited, API-compatible subset of ASDF for loading
multi-file systems from `.asd` definitions. It is **not part of Common Lisp**;
reference its symbols with the `asdf:` qualifier. Each name below links to its
own page; the [Systems guide](../../guides/asdf-systems.md) gives a full project
layout and the search-path details.

| Function | Example | Result |
|----------|---------|--------|
| `asdf:defsystem` | `(asdf:defsystem :my-lib :components ((:file "main")))` | define a system (name, `:depends-on`, `:serial`, `:components`) for a later `load-system` |
| `asdf:load-system` | `(asdf:load-system :my-lib)` | load a system: its dependency systems first, then its component files in order (a literal, top-level form on the compile path) |
| `asdf:test-system` | `(asdf:test-system "my-app")` | load the system, follow its `:in-order-to` test-op chain, then run its recorded `:perform (test-op ...)` body — the standard `.asd` test entry point |
| `asdf:find-system` | `(asdf:find-system :my-lib nil)` | the system's metaobject, a real `asdf:system` CLOS instance memoized per name (`eq` across calls); nil for an unknown name when `error-p` is nil |
| `asdf:registered-systems` | `(asdf:registered-systems)` | the downcased names of every registered system, in registration order |
| `asdf:system-relative-pathname` | `(asdf:system-relative-pathname :my-lib "data/tlds.dat")` | the namestring of a path resolved against the system's source directory (folded to a literal on the compile path) |
| `asdf:component-pathname` | `(asdf:component-pathname (asdf:find-system :my-lib))` | a system's source directory with a trailing `/`, or a source-file child's resolved path; accepts the metaobject or a name designator |
| `asdf:component-name` | `(asdf:component-name (asdf:find-system :my-lib))` | reader: the component's downcase-canonical name |
| `asdf:component-version` | `(asdf:component-version (asdf:find-system :my-lib))` | reader: the declared `:version` string, or nil when the `.asd` declared none as a plain string (a computed spelling is never evaluated) |
| `asdf:component-children` | `(asdf:component-children (asdf:find-system :my-lib))` | reader: a system's component files in load order, one `asdf:cl-source-file` per file |
| `asdf:component-sideway-dependencies` | `(asdf:component-sideway-dependencies (asdf:find-system :my-lib))` | reader: the system's `:depends-on` names (package-inferred sub-system names included) |
| `asdf:component-parent` | `(asdf:component-parent child)` | reader: the parent component — the system for a source file, nil for a system |
| `asdf:component-system` | `(asdf:component-system child)` | the system a component belongs to (walks `component-parent` up) |

