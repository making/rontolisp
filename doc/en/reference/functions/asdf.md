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
| `asdf:operate` | `(asdf:operate 'asdf:load-op :my-lib)` | perform an operation on a system: `load-op` is `load-system`, `test-op` is `test-system`; any other operation signals |
| `asdf:perform` | `(defmethod asdf:perform :around ((o asdf:load-op) (c asdf:cl-source-file)) ...)` | generic: every action of a system load (`prepare-op`, `compile-op`, `load-op` on each file and on the system), so a library's method runs around each component load |
| `asdf:oos` | `(asdf:oos 'asdf:load-op :my-lib)` | the old name of `operate`. `load-systems`, `load-systems*`, `require-system` and `make` are the other spellings of a load |
| `asdf:load-asd` | `(asdf:load-asd "vendor/old/my-lib.asd")` | load the definitions in one `.asd` file |
| `asdf:clear-system` | `(asdf:clear-system :my-lib)` | forget a system: the next `find-system` searches again |
| `asdf:map-systems` | `(asdf:map-systems #'print)` | call a function with every registered system. `already-loaded-systems`, `component-loaded-p` and `registered-system` answer about them |
| `asdf:find-component` | `(asdf:find-component :my-lib "main")` | a component by name or by a list of names down the tree, or nil. `module-components` is `component-children` |
| `asdf:system-description` | `(asdf:system-description (asdf:find-system :my-lib))` | what the `defsystem` declared, with the other `system-` readers, `component-property` and `system-source-file` |
| `asdf:*central-registry*` | `(push "vendor/my-lib/" asdf:*central-registry*)` | directories searched for `NAME.asd` ahead of the search path |
| `asdf:initialize-source-registry` | `(asdf:initialize-source-registry '(:source-registry (:tree :here) :inherit-configuration))` | configure the search: `:directory`, `:tree`, `:here`, what it inherits. `clear-source-registry` undoes it |
| `asdf:*system-definition-search-functions*` | `(push 'vendor-search asdf:*system-definition-search-functions*)` | the functions a search calls in order, with the name, until one answers a pathname or a system |
| `asdf:find-system` | `(asdf:find-system :my-lib nil)` | the system's metaobject, a real `asdf:system` CLOS instance memoized per name (`eq` across calls); nil for an unknown name when `error-p` is nil |
| `asdf:registered-systems` | `(asdf:registered-systems)` | the downcased names of every registered system, in registration order |
| `asdf:system-relative-pathname` | `(asdf:system-relative-pathname :my-lib "data/tlds.dat")` | the namestring of a path resolved against the system's source directory (folded to a literal on the compile path) |
| `asdf:component-pathname` | `(asdf:component-pathname (asdf:find-system :my-lib))` | a system's or module's directory with a trailing `/`, or a file's resolved path; accepts the metaobject or a name designator |
| `asdf:component-name` | `(asdf:component-name (asdf:find-system :my-lib))` | reader: the name the definition gives the component, a symbol downcased |
| `asdf:component-version` | `(asdf:component-version (asdf:find-system :my-lib))` | reader: the declared `:version` string, or nil when the `.asd` declared none as a plain string (a computed spelling is never evaluated) |
| `asdf:component-children` | `(asdf:component-children (asdf:find-system :my-lib))` | reader: a system's or module's children in definition order, instances of `asdf:module`, `asdf:cl-source-file`, `asdf:static-file` |
| `asdf:component-sideway-dependencies` | `(asdf:component-sideway-dependencies (asdf:find-system :my-lib))` | reader: the system's `:depends-on` names (package-inferred sub-system names included) |
| `asdf:component-parent` | `(asdf:component-parent child)` | reader: the parent component — the system or module that holds it, nil for a system |
| `asdf:component-system` | `(asdf:component-system child)` | the system a component belongs to (walks `component-parent` up) |

Every other name real ASDF 3.3.1 exports reads and has its definition on every backend,
so a library that names one loads: a class is a class, a generic takes a method, a variable
is `nil`, and calling a function signals `uiop:not-implemented-error` naming it. The bundle
and program operations are classes `asdf:operate` refuses by name.
