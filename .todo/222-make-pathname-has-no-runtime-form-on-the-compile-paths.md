# 222. `make-pathname` has no runtime form on the compile paths

## Problem

`make-pathname` exists only as a COMPILE-TIME fold (`cli/CompileTimePathnameFolder`,
which reduces the literal-keyword shapes to a namestring) plus a real interpreter
function. On the JVM and both WASM backends a call the folder cannot reduce
compiles to a call-time error, with

```
warning: the function MAKE-PATHNAME is undefined; compiled as a call-time error
```

The folder cannot reduce a call whose `:defaults` is a runtime value -- a local
variable, a `flet` parameter, anything computed. That is not a rare shape: it is
what a library does when it builds a path from something it just looked up.

## Why it matters (the caller that surfaced it)

local-time computes `*default-timezone-repository-path*` at load time from the
system's own source directory:

```lisp
(flet ((try (project-home-directory)
         (when project-home-directory
           (ignore-errors
             (truename
               (merge-pathnames "zoneinfo/"
                                (make-pathname :name nil :type nil
                                               :defaults project-home-directory)))))))
  ...)
```

`project-home-directory` is a `flet` parameter, so the fold declines, the call
signals, `ignore-errors` swallows it and the defvar ends up `nil`. The
consequence is visible since the directory-listing work landed (`.todo/221`,
`.kb/directory-listing.md`): `(local-time:reread-timezone-repository)` now walks
the bundled `zoneinfo/` tree and resolves `"Asia/Tokyo"` on ALL FOUR backends --
but on the three compiled ones only when the caller passes the repository
explicitly:

```lisp
(local-time:reread-timezone-repository :timezone-repository "zoneinfo/")
```

The zero-argument call, which is the one every program actually writes, works
only on the interpreter. Documented in `doc/{en,ja}/guides/asdf-systems.md`.

## What to do if picked up

1. Decide the SURFACE first. A rontolisp pathname IS its namestring, so a runtime
   `make-pathname` is pure string assembly over `:directory` / `:name` /
   `:type` / `:defaults` -- the same rule `PathnameOps.makePathname` already
   implements for the folder. The obvious move is to make that ONE rule reachable
   at run time on every backend rather than write a second one.
2. Prefer prelude Lisp (`LispPreludeLibrary`) over four Java runtimes, the way
   the `.todo/221` family went: `merge-pathnames`, `pathname-directory`,
   `directory` and the `uiop:` walkers are all one Lisp definition each, and
   `LispPreludeLibraryTest#thePreludeMergePathnamesAgreesWithPathnameOps` is the
   existing pattern for pinning a Lisp rendering against the Java one.
   `make-pathname` takes keywords, which the prelude already handles
   (`&key` desugars on every backend).
3. Keep the compile-time fold. It is what makes an ASDF-located data directory a
   literal in the emitted artifact; the runtime form is the fallback for the
   shapes it declines, not a replacement.
4. `pathname-name` / `pathname-type` are the natural siblings and are likewise
   absent; `pathname-directory` landed with `.todo/221`.
5. Then the zero-argument `(local-time:reread-timezone-repository)` is the E2E
   case, and the `asdf-systems.md` caveat comes out.
