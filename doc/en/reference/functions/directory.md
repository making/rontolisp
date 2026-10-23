# directory

`(directory pathspec &key (resolve-symlinks t))`

The truenames of the files matching `pathspec`, each once, sorted with `string<`
so the same program prints the same answer on every backend whatever order the
host hands entries back in. `nil` when nothing matches -- it never signals.

A **wild name component** lists the directory and keeps what the pattern matches.
`*` stands for any sequence of characters, `?` for exactly one. Each answer is the
entry's truename, as [`truename`](truename.md) would answer it, so each is directly
openable and a directory carries a trailing `/`:

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

A **symbolic link** is matched by its own name and answered by its truename, so two
names for one file come back once; a link whose target is missing answers itself.
`:resolve-symlinks nil` keeps the matched names instead: a link to a directory still
carries the trailing `/`, and a link whose target is missing is left out. Given a
directory `t/` holding the directory `d/` (with `a.txt` in it), `ln` a link to `d`,
`al` a link to `d/a.txt` and `dang` a link to nothing:

| call | answer |
|---|---|
| `(directory "t/*")` | `(#P"t/d/" #P"t/d/a.txt" #P"t/dang")` |
| `(directory "t/*" :resolve-symlinks nil)` | `(#P"t/al" #P"t/d/" #P"t/ln/")` |

A truename takes out `.` and `..`, so `(directory "./*.txt")` answers `#P"a.txt"`,
not `#P"./a.txt"`; nothing is made absolute.

A **wild directory component** expands the prefix before the name is matched.
`*` walks exactly one level and `**` walks the whole subtree -- and, as in Common
Lisp, `**` matches ZERO levels as well as many, so the base directory's own files
come back too:

| pathspec | answer |
|---|---|
| `"d/*/*.lisp"` | the `.lisp` files one level below `d/` |
| `"d/**/*.lisp"` | every `.lisp` file in `d/` and anywhere below it |
| `"d/**/"` | `d/` itself and every directory below it |

Every expectation above is the same answer SBCL gives for the same tree, except that
SBCL makes each answer absolute.

## Backend support

All four backends, through one primitive each: the source-loader abstraction on
the interpreter (so a host without a filesystem, such as the browser playground,
answers `nil` rather than failing), `java.io.File.list` on the JVM, and WASI
`fd_readdir` on both WASM backends -- Preview 1 binds the real host function,
`--component` an adapter over `wasi:filesystem`'s `read-directory`. A WASM module
resolves the path against its preopened directories -- a relative path against the
first one, an absolute path against the preopened directory whose name is its
longest prefix -- so run it with `--dir`; without one nothing matches.

The `.` and `..` self/parent entries are never returned on any backend. On WASM a
link is resolved only where the module can read it through a preopened directory, as
for [`probe-file`](probe-file.md).

`uiop:directory-files`, `uiop:subdirectories` and `uiop:collect-sub*directories`
are defined in terms of this one function, with `:resolve-symlinks nil` as upstream
UIOP asks of SBCL.
