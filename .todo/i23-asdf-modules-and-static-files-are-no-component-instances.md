# i23. A `:module` and a `:static-file` are no component instances

Difficulty: Medium

A system's children are its source files, flat and in load order (`.kb/asdf.md`, "Flat model
limits"), so a program walking the real tree gets something else. Measured against SBCL 2.2.9's
ASDF 3.3.1 on `(:file "one") (:module "m" :components ((:file "two")))` under `:pathname "src"`:

| Call | SBCL | Here |
| --- | --- | --- |
| `(mapcar #'component-name (component-children s))` | `("one" "m")` | `("src/one" "src/m/two")` |
| `(component-name (find-component s "m"))` | `"m"` | signals (nil) |
| `(component-name (find-component s '("m" "two")))` | `"two"` | `"src/m/two"` |
| `(component-find-path c)` | `("sys" "m" "two")` | `("sys" "src/m/two")` |
| `(slot-value s 'relative-pathname)` | `.../src/` | the system's directory |

Consumers: djula's `list-asdf-system-templates` finds a module by name and keeps its
`asdf:static-file` children (`src/template-store.lisp:117`); swank-asdf, serapeum's `docs.lisp` and
eclector's tests walk `component-children`/`module-components`.

Needed: the record carries the tree (`AsdfSystems.orderComponents` has it while it flattens), and
`component-children` answers real instances: `module`, `cl-source-file`, `static-file`, with real
names, `parent` and `component-pathname`. `%asdf-perform-component` keeps the flat load order by a
walk or a list of its own, so the fixed plan is unchanged. rove's `core/suite/file.lisp` reads the
children, and `AsdfMetaobjectsE2eTest`, `PackageInferredSystemE2eTest` and `LoadContextE2eTest` pin the
flat shape, which is what moves.
