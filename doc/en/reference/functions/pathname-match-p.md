# pathname-match-p

`(pathname-match-p pathname wildcard)`

Whether `pathname` matches `wildcard`, component by component, by the rule
[`translate-pathname`](translate-pathname.md) matches with: `*` and `?` stay
within one component, a `**` directory component matches any run of levels
(zero included), a component missing from `wildcard` matches anything, and `*`
matches a missing component.

```lisp
(list (pathname-match-p "x/y/a.lisp" "x/*/*.lisp")
      (pathname-match-p "x/a.lisp" "x/*/*.lisp")
      (pathname-match-p "a" "*.*")
      (pathname-match-p "sub/b.lisp" "*.lisp"))
; => (T NIL T T)
```

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
