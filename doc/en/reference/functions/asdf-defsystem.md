# asdf:defsystem

`(asdf:defsystem name &rest options)`

Defines a **system** — a named collection of source files with load-order constraints — for a later [`asdf:load-system`](asdf-load-system.md), and returns the system name as a symbol. This is a limited, API-compatible subset of ASDF's `defsystem`: the options are plain data and are never evaluated. `name` is a literal designator (a string `"my-lib"`, a keyword `:my-lib`, or a symbol). Supported options:

- Metadata — `:description`, `:long-description`, `:version`, `:author`, `:maintainer`, `:license` (also `:licence`), `:homepage`, `:bug-tracker`, `:source-control`, `:mailto` — accepted for `.asd` compatibility and ignored.
- `:depends-on (system...)` — systems loaded before this one, located through the same search path as `load-system`.
- `:serial t` — each component implicitly depends on the previous one.
- `:components (component...)` — the source files: `(:file "name" [:depends-on ("other"...)])` names `name.lisp`; `(:module "dir" [:serial t] [:depends-on (...)] :components (...))` prefixes its children with `dir/`; `(:static-file "name")` is accepted and contributes no source. Components are loaded in a stable topological order of their `:depends-on` constraints.

Any other option (`:in-order-to`, `:perform`, `:defsystem-depends-on`, `:if-feature`, a computed `:version`, ...) or component type is an error naming the unsupported clause. Normally the form lives in a `NAME.asd` file next to the sources; a `.asd` file is parsed as **data**, so it may contain only `defsystem` forms (bare or `asdf:`-qualified) and `in-package` forms (skipped). An inline top-level `(asdf:defsystem ...)` in a program also registers the system. Component paths resolve against the directory of the `.asd` file (or of the defining source).

```console
;; my-lib.asd
(defsystem :my-lib
  :description "A small example system"
  :version "0.1.0"
  :serial t
  :components ((:file "package")
               (:file "main")))
```

The system loads `package.lisp` then `main.lisp` (`:serial t`), both from the directory of `my-lib.asd`. See the [Systems guide](../../guides/asdf-systems.md) for a complete project walkthrough.
