# rontolisp:version

`(rontolisp:version)`

Returns build and version information about the running rontolisp, as a property
list with the keys `:version`, `:build-timestamp`, `:git-commit` and
`:git-branch`. It is the same information printed by `rontolisp --version`. The
values (timestamp and git revision) depend on the build, so the result varies
between builds and is not supported inside the compiled runtime `eval`/`load`.

```lisp
(getf (rontolisp:version) :version)
```

The call returns a plist such as `(:version "0.1.0-SNAPSHOT" :build-timestamp
"..." :git-commit "..." :git-branch "...")`; use `getf` to read a single field.
