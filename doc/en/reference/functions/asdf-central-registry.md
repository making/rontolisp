# asdf:\*central-registry\*

A list of directories searched for `NAME.asd` ahead of everything else
([The system search path](../../guides/asdf-systems.md#the-system-search-path)). Each
entry is evaluated when a search runs and its value is a pathname designator, as in real
ASDF, so an entry may be a string, a pathname or a form such as
`'*default-pathname-defaults*`. It starts out nil.

```console
(push "vendor/my-lib/" asdf:*central-registry*)
(asdf:load-system :my-lib)
```

## Backend support

The interpreter reads the variable at each search, so a computed push or a `let` binding
counts. A compile path reads only a top-level `push` or `pushnew` of a directory known at
compile time, as `load-system` reads only a literal name. The variable itself is an
ordinary one on all four backends.
