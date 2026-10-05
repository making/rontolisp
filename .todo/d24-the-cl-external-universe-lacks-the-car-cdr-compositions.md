# d24. The cl external universe lacks the car/cdr compositions

Difficulty: Low

`do-external-symbols` over `cl` (and `do-symbols`, `find-all-symbols`, `apropos-list`,
`with-package-iterator`) lists 951 names on every backend; the standard's 978 plus `while`
is 979. The 28 car/cdr compositions (`caar` .. `cddddr`) are missing: `cl`'s `LispPackage`
external set is `PackageRegistry.CL_EXTERNALS`, which leaves them to
`LispNames.isCarCdrComposition`, and the enumerations (`PackageResolver.accessibleEntries`,
the `%baked-packages%` universes) read the set only.

```lisp
(let ((n 0)) (do-external-symbols (s :cl) (incf n)) (print n))   ; SBCL 978 / all four 951
(print (find-all-symbols "CADDR"))                                ; SBCL (CADDR) / all four NIL
```

`find-symbol` and the use-list inheritance already answer them
(`PackageRegistry.standardNames` holds the 979). Adding them to the set grows every baked
universe of a package using `cl` by 28 names; measure that against the package-walk
programs before choosing between the set and the enumeration.
