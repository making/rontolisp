# 441. `make-pathname` rejects wild directory components

Difficulty: Medium

Child of `.todo/436` (read it first). Wave 1.

## The defect

```lisp
(make-pathname :directory '(:absolute "a" :wild-inferiors) :name :wild :type "lisp")
;; => MAKE-PATHNAME: unsupported :directory component :WILD-INFERIORS
```

`:wild` as a `:name` / `:type` already works; only the `:directory` components
are missing. With them, `wild-pathname-p` and `translate-pathname` have to
honour the components -- that trio is what recursive directory traversal is
built on (upstream uiop's `directory-files` / `subdirectories` /
`collect-sub*directories` are all `translate-pathname` over
`*wild-inferiors*`).

Read `.kb/pathnames.md`, `.kb/directory-listing.md` -- `(directory "sys/*.*")`
already answers correctly, so this is about the pathname OBJECT model more than
about the filesystem.

## Watch

- Decide the namestring round trip (does a wild pathname print as something
  `#p"..."` reads back?) and write it into the `.kb` file.
- Building a pathname is filesystem-INDEPENDENT and must stay working on the
  targets that have no filesystem (browser playground, `--no-wasi`).
- Check what `cli/CompileTimePathnameFolder` does when a wild pathname reaches
  it.

## Acceptance

Construction, `wild-pathname-p`, `translate-pathname` and a recursive
`directory` walk agree across all four backends; a ci-spec case
(`wild-pathnames-441`).
