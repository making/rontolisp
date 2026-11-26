# asdf:initialize-source-registry

`(asdf:initialize-source-registry [parameter])`

Configures where a search looks for `NAME.asd`
([The system search path](../../guides/asdf-systems.md#the-system-search-path)), scanning what
the configuration names now, as in real ASDF. It answers no value.

```console
(asdf:initialize-source-registry
 '(:source-registry (:tree :here) :inherit-configuration))
(asdf:load-system :my-lib)
```

The configuration holds exactly one of the last two directives:

| Directive | Meaning |
|---|---|
| `(:directory LOCATION)` | the `.asd` files directly in a directory |
| `(:tree LOCATION)` | the `.asd` files below a directory; of two with one name the shallowest wins, then the first by name |
| `(:exclude NAME...)`, `(:also-exclude NAME...)` | directory names a `:tree` after it does not enter, replacing or extending `.git`, `_darcs` and the like |
| `(:include LOCATION)` | the configuration in a file, or in the `.conf` files of a directory |
| `:default-registry` | real ASDF's `~/common-lisp/` and XDG data directories |
| `:inherit-configuration` | what the program inherits: the directory of the file asking, `--system-path` and `RONTOLISP_SOURCE_REGISTRY`, at this position |
| `:ignore-inherited-configuration` | none of those: only the directories named here |

A location is an absolute path, or a list of one and relative parts: `(:home "lisp/")`,
`(:here "vendor/")`. `:here` is the directory of the file being loaded, or of the configuration
file. A relative path is refused, as in real ASDF.

A string parameter is real ASDF's short form, directories joined by `:`: a trailing `//` makes a
`:tree`, an empty element is `:inherit-configuration`, and a string without one ignores it. A string
starting with `(` is read as a configuration, and nil inherits.

Unlike real ASDF, nothing here reads `CL_SOURCE_REGISTRY`, `~/.config/common-lisp/` or
`~/common-lisp/` unless the configuration says `:default-registry`: a program's search depends on the
machine only through `--system-path` and `RONTOLISP_SOURCE_REGISTRY`.

`asdf:clear-source-registry` undoes it, and the next search starts from the inherited configuration.
`asdf:compute-source-registry`, `asdf:ensure-source-registry` and
`asdf:process-source-registry` are real ASDF's. An invalid configuration signals
`asdf/source-registry:invalid-source-registry`, a `warning` signalled with `error`.

## Backend support

The interpreter runs the call as written. A compile path reads a top-level call with a literal
parameter while it splices, with the file holding the call as `:here`, as `load-system` reads only
a literal name; a computed one has no effect on what a compile path finds. The call stays in the
program too, so its variables and `sysdef-source-registry-search` answer at run time from what the
running program can list.
