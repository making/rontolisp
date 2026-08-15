# uiop/pathname

`uiop/pathname` is the pathname algebra — the portable layer libraries use to
build, take apart and compare pathnames without touching the file system.
**All 50 exports are implemented**, as pure computation over the pathname value,
so every one runs identically on all four backends — the interpreter, the JVM,
and both WASM outputs.

Every name is reachable through either spelling: `uiop:subpathname` and
`uiop/pathname:subpathname` are the same function
([The uiop Package](../uiop.md#sub-packages)).

A rontolisp [pathname](../data-types.md) carries one flat namestring, so the
component-wise algebra collapses onto namestring computation — with two
consequences worth knowing:

- **Logical pathnames do not exist** (no logical host can be defined), so
  `uiop:logical-pathname-p` answers `nil` for everything,
  `uiop:physical-pathname-p` is `pathnamep`, `uiop:physicalize-pathname` is the
  coercing identity, and `uiop:make-pathname-logical` signals
  `uiop:not-implemented-error`.
- **Nothing is absolutized**: `uiop:ensure-absolute-pathname` answers a relative
  path as itself where upstream signals — rontolisp resolves relative paths
  against the host working directory for the whole run, so the path as given is
  already the file's identity.

## Building and merging

| Function | What it does |
|----------|--------------|
| [`uiop:merge-pathnames*`](../functions/uiop-merge-pathnames-star.md) | the defaults-aware merge — an absolute `specified` wins, a relative one is appended to the defaults' directory |
| [`uiop:subpathname`](../functions/uiop-subpathname.md) | a relative subpath merged under a base pathname's directory |
| `uiop:subpathname*` | `nil` when the base is `nil`, otherwise `subpathname` with the base first put in directory form |
| `uiop:ensure-directory-pathname` | the pathname in directory form (a trailing `/`) |
| `uiop:ensure-absolute-pathname` | an absolute path passes through; a relative one is merged against the defaults (a pathname, or a function answering one) |
| `uiop:nil-pathname` / `uiop:*nil-pathname*` | the neutral defaults — the empty pathname `#P""` |
| `uiop:pathname-root` | the root of the pathname's host and device — `#P"/"`, the only root here |
| `uiop:pathname-host-pathname` | a pathname carrying only the host — `#P""`, since no host is modeled |
| `uiop:make-pathname*` | `make-pathname`, kept for callers of the deprecated spelling |
| `uiop:make-pathname-component-logical` | `:unspecific` becomes `nil`; everything else passes through |
| `uiop:normalize-pathname-directory-component` | a directory component in CLHS list form (`"foo"` → `(:absolute "foo")`) |
| `uiop:denormalize-pathname-directory-component` | the identity — the normalized form is the native one |
| `uiop:merge-pathname-directory-components` | the directory-list half of the merge, `:back` handling included |
| `uiop:*unspecific-pathname-type*` | `nil` — a component that is not present is `nil` here |

```lisp
(print (uiop:subpathname #P"/tmp/foo/" "bar/baz.txt"))
(print (uiop:subpathname* "/tmp/foo" "x.txt"))
(print (uiop:ensure-absolute-pathname "b.txt" "/tmp/"))
(print (uiop:merge-pathname-directory-components '(:relative :back "x") '(:absolute "a" "b")))
```

```
#P"/tmp/foo/bar/baz.txt"
#P"/tmp/foo/x.txt"
#P"/tmp/b.txt"
(:ABSOLUTE "a" "x")
```

## Predicates

`absolute-pathname-p`, `relative-pathname-p` and `file-pathname-p` answer the
parsed **pathname** when true (a generalized boolean, as upstream); the rest
answer `t`/`nil`. None of them touches the file system.

| Function | True when |
|----------|-----------|
| [`uiop:absolute-pathname-p`](../functions/uiop-absolute-pathname-p.md) | the namestring starts with `/` |
| [`uiop:relative-pathname-p`](../functions/uiop-relative-pathname-p.md) | it does not (the empty pathname included) |
| [`uiop:directory-pathname-p`](../functions/uiop-directory-pathname-p.md) | non-wild, with no name and no type — empty or ending in `/` |
| [`uiop:file-pathname-p`](../functions/uiop-file-pathname-p.md) | a name or type component is present |
| `uiop:hidden-pathname-p` | the name starts with a dot |
| `uiop:pathname-equal` | the two designators carry the same namestring |
| `uiop:logical-pathname-p` | never (no logical pathnames exist) |
| `uiop:physical-pathname-p` | the argument is a pathname |

```lisp
(print (list (uiop:absolute-pathname-p "/a/b") (uiop:relative-pathname-p "a/b")))
(print (list (uiop:directory-pathname-p "/a/b/") (uiop:file-pathname-p "/a/b")))
(print (list (uiop:hidden-pathname-p ".gitignore") (uiop:pathname-equal "/a/b" #P"/a/b")))
```

```
(#P"/a/b" #P"a/b")
(T #P"/a/b")
(T T)
```

## Directories

| Function | What it does |
|----------|--------------|
| [`uiop:pathname-directory-pathname`](../functions/uiop-pathname-directory-pathname.md) | the pathname's directory, name and type dropped |
| [`uiop:pathname-parent-directory-pathname`](../functions/uiop-pathname-parent-directory-pathname.md) | one directory level up (the root's parent is the root) |

```lisp
(print (uiop:pathname-directory-pathname #P"/a/b/c.txt"))
(print (uiop:pathname-parent-directory-pathname #P"/a/b/c.txt"))
```

```
#P"/a/b/"
#P"/a/"
```

## Parsing

| Function | What it does |
|----------|--------------|
| [`uiop:parse-unix-namestring`](../functions/uiop-parse-unix-namestring.md) | a Unix-syntax string as a pathname: `""` and `"."` components dropped, `:type` appended, `:ensure-directory` forcing directory form |
| [`uiop:unix-namestring`](../functions/uiop-unix-namestring.md) | the Unix-style namestring — which here *is* the namestring |
| [`uiop:split-name-type`](../functions/uiop-split-name-type.md) | two values, NAME and TYPE of a filename (the last dot separates them; a lone leading dot belongs to the name) |
| `uiop:split-unix-namestring-directory-components` | four values: `:absolute`/`:relative`, the directory components, the last component, and whether the string was a bare filename |

```lisp
(print (uiop:parse-unix-namestring "a//b/./c.txt"))
(print (uiop:parse-unix-namestring "foo/bar" :type "lisp"))
(print (multiple-value-list (uiop:split-name-type "foo.lisp")))
(print (multiple-value-list (uiop:split-unix-namestring-directory-components "/a/b/c.txt")))
```

```
#P"a/b/c.txt"
#P"foo/bar.lisp"
("foo" "lisp")
(:ABSOLUTE ("a" "b") "c.txt" NIL)
```

## Relative to a base

| Function | What it does |
|----------|--------------|
| [`uiop:subpathp`](../functions/uiop-subpathp.md) | when the first pathname sits under the second, the relative remainder that merges back onto it; `nil` otherwise |
| [`uiop:enough-pathname`](../functions/uiop-enough-pathname.md) | that remainder when there is one, the pathname itself otherwise |
| `uiop:call-with-enough-pathname` | calls a function on `enough-pathname`, with `*default-pathname-defaults*` bound to the base |
| `uiop:with-enough-pathname` | macro shorthand for the above — `(uiop:with-enough-pathname (p :defaults d) ...)` rebinds `p` |
| `uiop:with-pathname-defaults` | macro: run the body with `*default-pathname-defaults*` bound to the given form, or to `*nil-pathname*` when none is given |

```lisp
(print (uiop:subpathp #P"/tmp/foo/bar.txt" #P"/tmp/"))
(print (uiop:enough-pathname #P"/x/a.txt" #P"/tmp/"))
(let ((p #P"/tmp/a/b.txt"))
  (uiop:with-enough-pathname (p :defaults #P"/tmp/") (print p)))
(uiop:with-pathname-defaults (#P"/wpd/") (print *default-pathname-defaults*))
```

```
#P"foo/bar.txt"
#P"/x/a.txt"
#P"a/b.txt"
#P"/wpd/"
```

## Checking constraints

[`uiop:ensure-pathname`](../functions/uiop-ensure-pathname.md) is the constraint
machine the rest of uiop routes through: it coerces a designator (a string goes
through `parse-unix-namestring`), then applies the `:want-*` checks and
`:ensure-*` transforms in upstream's order. A failed check signals, or calls a
custom `:on-error` function.

```lisp
(print (uiop:ensure-pathname "a/b" :ensure-directory t))
(print (handler-case (uiop:ensure-pathname "/a/b" :want-relative t) (error () :err)))
```

```
#P"a/b/"
:ERR
```

Lite next to upstream, deliberately: a failed check reports
`Invalid pathname ~S: ~A` (not upstream's `~?` chain), `:want-logical` always
fails, and `:resolve-symlinks` / `:truenamize` are accepted and ignored (no
backend resolves a symlink); `:truename` answers what `probe-file` answers.

## Wildcards and translation

The `*wild*` family are namestring literals over the two wildcards the
[`directory`](../functions/directory.md) matcher reads (`*` and `?`), so a wild
constant and the matcher can never disagree.

| Name | Value / what it does |
|------|----------------------|
| `uiop:*wild*` | `"*"` |
| `uiop:*wild-file*` / `uiop:*wild-file-for-directory*` | `#P"*.*"` |
| `uiop:*wild-directory*` | `#P"*/"` |
| `uiop:*wild-inferiors*` | `#P"**/"` |
| `uiop:*wild-path*` | `#P"**/*.*"` |
| `uiop:wilden` | any file in any subdirectory of the pathname's directory |
| `uiop:translate-pathname*` | the output-translations wrapper over [`translate-pathname`](../functions/translate-pathname.md): a function destination is called, `t` answers the path, a relative destination is first merged with the root |
| `uiop:relativize-directory-component` | `(:absolute ...)` becomes `(:relative ...)` |
| `uiop:relativize-pathname-directory` | the pathname with its leading `/` dropped |
| `uiop:directory-separator-for-host` | `#\/` |
| `uiop:directorize-pathname-host-device` | the identity — a Unix-shaped physical pathname is already in that form |
| `uiop:*output-translation-function*` | `'identity` — no output translations run here |

```lisp
(print (uiop:wilden #P"/tmp/foo"))
(print (uiop:translate-pathname* #P"/src/a/b.lisp" #P"/src/**/*.*" #P"/out/**/*.*"))
(print (uiop:relativize-pathname-directory #P"/a/b/c.txt"))
```

```
#P"/tmp/**/*.*"
#P"/out/a/b.lisp"
#P"a/b/c.txt"
```

## Compile-time folding

Like `uiop:merge-pathnames*`, a `uiop:subpathname` whose arguments are literals
(or references to a top-level `defparameter` bound to one) is folded to a
pathname literal by the compile paths, so a bundled library's data-file path
costs nothing at run time.
