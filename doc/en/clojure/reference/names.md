# Names and keywords

Keywords spell their name verbatim behind a colon (`:a/b` stays whole, split
only by `name`/`namespace`); symbols mangle behind the `c%` prefix, so they print
demangled and compare whole. `name` reads the part past the first `/`,
`namespace` the part before it.

| Name | Example | Result |
|---|---|---|
| `name` | `(name :foo/bar)` | `bar` |
| `namespace` | `(namespace :foo/bar)` | `foo` |
| `keyword` | `(keyword "a" "b")` | `:a/b` |
| `find-keyword` | `(find-keyword "a")` | `:a` |
| `symbol` | `(symbol "a" "b")` | `a/b` |
