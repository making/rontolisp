# i10. A bare `&name` that is no lambda-list keyword is refused as a parameter

Difficulty: Low

Measured 2026-11-04, the same refusal on all four backends:

```lisp
(defun f (&a) (list &a))
(print (f 1))     ; SBCL (1)  rontolisp: Unsupported lambda-list keyword: &A
```

In any other package the name is homed (`PKG::&A`) and works, so only `cl-user` still refuses it:
the name stays bare there, and the code asks `startsWith("&")` to mean "a lambda-list keyword".
The test sits at about 45 sites in 19 classes. The exact list is `ClConstants.LAMBDA_LIST_KEYWORD_NAMES`.

Not every site is a CL lambda list: Clojure's `&` is lowered in `clojure/`, and `SchemeNames`
escapes a `&`-prefixed identifier because of this test. `&whole` and `&environment` stay refused where
they are refused today (`LispEvaluatorTest#evalDestructuringBindBindsWholeAndRejectsEnvironment`).

Pin in `ci-spec.yaml` with the two lines above, and drop the `cl-user` note in `.kb/packages.md`.
