# 222. `make-pathname` has no runtime form on the compile paths

Difficulty: Low (what is left is ONE gap, and it is not about pathnames)

## Status (2026-08-03, done inside `.todo/249`)

**Items 1-4 are DONE.** `make-pathname` is prelude Lisp
(`LispPreludeLibrary.MAKE_PATHNAME`) on top of the shared `%pathname-split`
rule, so the shapes the folder declines -- a computed `:defaults` or `:name` --
run on all four backends instead of compiling to a call-time error. The Java
`Environment` entry is gone (one definition, four backends); the compile-time
fold stays, and `LispPreludeLibraryTest#thePreludeMakePathnameAgreesWithPathnameOps`
pins the two renderings against each other, SBCL-checked. `pathname-name` and
`pathname-type` landed with it (item 4), plus `namestring`; details and the two
load-bearing rules are in `.kb/directory-listing.md`.

Two corrections came out of that work and are recorded there and in
`.kb/declarations-type-checks.md`:

- `:defaults` defaults COMPONENT-WISE and is not a merge (the first cut dropped
  the defaults' TYPE whenever only `:name` was supplied).
- `pathname` stopped being an EMPTY type -- a namestring IS a pathname here --
  with a yield rule so a `typecase` that also has a catch-all still
  discriminates a path from string CONTENT.

## What is left: item 5, and it is NOT a pathname problem

The E2E this todo named -- the ZERO-argument
`(local-time:reread-timezone-repository)` -- still works on the interpreter only.
Verified 2026-08-03 with the runtime `make-pathname` in place:

```
interpreter: *default-timezone-repository-path*
             = ".../local-time-20260101-git/zoneinfo/"   -> Asia/Tokyo resolves
JVM:         = NIL -> "The value of TIMEZONE-REPOSITORY is NIL,
                       which is not of type (OR PATHNAME STRING)"
```

`make-pathname` is no longer the reason. local-time computes the defvar
(`src/local-time.lisp:100-116`) from

```lisp
(when (find-package "ASDF")
  (eval (read-from-string
          "(let ((system (asdf:find-system :local-time nil)))
             (when system (asdf:component-pathname system)))")))
```

with `#.(or *compile-file-truename* '*load-truename*)` as the fallback. On the
compile paths BOTH are empty: there is no ASDF system registry at run time in a
compiled artifact (the systems are a compile-time notion, `.kb/asdf.md`), and
`*load-truename*` is nil because the load was inlined away.

So item 5 is really "an artifact cannot ask ASDF where its system's source
directory was". The honest options, in order of preference:

1. Have `LoadInliner` FOLD the `(asdf:component-pathname (asdf:find-system ...))`
   shape the way `CompileTimePathnameFolder` already folds
   `asdf:system-source-directory` -- it declines here only because the call is
   wrapped in `(eval (read-from-string "..."))`. Folding through a literal
   `read-from-string` + `eval` of a constant string is the narrow, checkable fix.
2. Or bind `*load-truename*` at compile time to the inlined file's namestring,
   which is what the `#.` fallback wants.

Until one of them lands, the caveat in `doc/{en,ja}/guides/asdf-systems.md`
stays: on the compiled backends pass the repository explicitly,
`(local-time:reread-timezone-repository :timezone-repository "zoneinfo/")`.
