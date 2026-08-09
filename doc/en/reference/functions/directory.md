# directory

`(directory pathspec)`

The pathnames matching `pathspec`, sorted with `string<` so the same program
prints the same answer on every backend whatever order the host hands entries
back in. `nil` when nothing matches -- it never signals.

A **wild name component** lists the directory and keeps what the pattern matches.
`*` stands for any sequence of characters, `?` for exactly one, and the answers
keep the pathspec's own directory prefix so each is directly openable, with a
subdirectory carrying a trailing `/`:

```lisp
(directory "no-such-directory/*.*")   ; => NIL
```

Given a directory holding `a.txt`, `b.txt` and the subdirectories `sub/` and
`empty/`:

| pathspec | answer |
|---|---|
| `"d/*.*"` | `(#P"d/a.txt" #P"d/b.txt" #P"d/empty/" #P"d/sub/")` — everything |
| `"d/*.txt"` | `(#P"d/a.txt" #P"d/b.txt")` |
| `"d/?.txt"` | `(#P"d/a.txt" #P"d/b.txt")` |
| `"d/*"` | `(#P"d/empty/" #P"d/sub/")` — a wild name with NO type, so only untyped entries |
| `"d/a*"` | `NIL` — same rule: `a.txt` has a type |

A **non-wild pathspec designates itself**, as in Common Lisp: `"d/a.txt"` answers
`(#P"d/a.txt")` when the file exists, and a directory answers itself in directory
form -- both `"d"` and `"d/"` give `(#P"d/")`. **Listing a directory is
`"d/*.*"`, not `"d/"`.**

The DIRECTORY components are never wild: a rontolisp pathname carries a flat
namestring, with no structured directory list to walk, so `"src/*/f.lisp"`
matches nothing.

Every expectation above is the same answer SBCL gives for the same tree.

## Backend support

All four backends, through one primitive each: the source-loader abstraction on
the interpreter (so a host without a filesystem, such as the browser playground,
answers `nil` rather than failing), `java.io.File.list` on the JVM, and WASI
`fd_readdir` on both WASM backends -- Preview 1 binds the real host function,
`--component` an adapter over `wasi:filesystem`'s `read-directory`. A WASM module
resolves the path against its first preopened directory, so run it with `--dir`;
without one nothing matches.

The `.` and `..` self/parent entries are never returned on any backend.

Everything else in the family -- `uiop:directory-files`, `uiop:subdirectories`,
`uiop:collect-sub*directories` and `uiop:directory-exists-p` -- is defined in
terms of this one function.
