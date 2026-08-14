# `uiop/version`: version comparison and the deprecation conditions

Difficulty: Low

Depends on `.todo/353`, and on `lexicographic<` / `lexicographic<=` from
`.todo/354` (upstream's `version<` is written over them).

The smallest of the twelve: 15 externals, one present (`with-deprecation`,
already a macro here). The **14** missing, plus `uiop/backward-driver`'s two,
which belong in the same batch because one of them is a version predicate and
the other is a two-line pathname alias:

```
uiop/version         DEPRECATED-FUNCTION-CONDITION DEPRECATED-FUNCTION-ERROR
                     DEPRECATED-FUNCTION-NAME DEPRECATED-FUNCTION-SHOULD-BE-DELETED
                     DEPRECATED-FUNCTION-STYLE-WARNING DEPRECATED-FUNCTION-WARNING
                     NEXT-VERSION PARSE-VERSION UNPARSE-VERSION
                     VERSION< VERSION<= VERSION= VERSION-DEPRECATION *UIOP-VERSION*
uiop/backward-driver COERCE-PATHNAME VERSION-COMPATIBLE-P
```

All pure; all four backends.

## Notes

- **`parse-version`** takes a `&optional error-handler` and calls it with a
  format string for a malformed version instead of signalling. Keep that shape
  -- `version<` on garbage must stay non-signalling.
- **`*uiop-version*`** is a string. Pin it to the version this port targets
  (`"3.3.7"`) and say in `.kb/uiop.md` that it is the CONTRACT version, not a
  claim of completeness -- a library that version-gates on it gets the answer
  that matches the API it will find.
- **The deprecation family** is five condition classes in a hierarchy plus
  `version-deprecation`, which maps a version pair to `:style-warning` /
  `:warning` / `:error` / `:delete`. `with-deprecation` already exists as a
  macro; rewire it onto the real classes rather than leaving two mechanisms.
- **`coerce-pathname`** is the deprecated alias of `parse-unix-namestring`
  (`.todo/357`); order this item after 357 or leave that one name for it.

## Gate

`UiopCoverageTest` reports `uiop/version 15/15` and `uiop/backward-driver 2/2`.
`LispEvaluatorTest` pins the comparison table (`"1.2" < "1.10"`, equal-prefix,
malformed input) and one `with-deprecation` expansion that actually signals the
class its version pair selects.
