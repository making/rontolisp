# uiop:subpathname

`(uiop:subpathname pathname subpath &key type)`

Merges `subpath` under the DIRECTORY of `pathname` -- the portable way a library
names a file relative to a base. An absolute pathname OBJECT passes through
unchanged; anything else is parsed as a relative Unix namestring (given `type`,
the whole last component becomes the NAME and `type` the type) and merged. An
absolute STRING subpath is an error (`:want-relative`).

```lisp
(uiop:subpathname #P"/tmp/foo/" "bar/baz.txt")   ; => #P"/tmp/foo/bar/baz.txt"
```

`uiop:subpathname*` is the same with a nil-tolerant base: `nil` answers `nil`,
and a non-nil base is first put in directory form.

## Backend support

Works on all four backends (Lisp source, `uiop-pathname.lisp`). Like
[`uiop:merge-pathnames*`](uiop-merge-pathnames-star.md), the compile paths fold
a call over literal arguments to a pathname literal.
