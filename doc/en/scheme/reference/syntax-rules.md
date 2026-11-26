# syntax-rules

`(syntax-rules (literal...) (pattern template)...)` `(syntax-rules ellipsis (literal...) (pattern template)...)`

The transformer of a syntax definition. A use is matched against each `pattern` in turn (its first element, the keyword, is ignored), and the first that matches has its `template` instantiated with what the pattern variables matched. In a pattern, a `literal` matches only the same binding, `_` matches anything, and `...` (or the given `ellipsis`) repeats the subpattern before it -- nested, followed by more patterns, in a dotted tail or in a vector. In a template, `...` repeats the subtemplate before it and `(... ...)` stands for a literal `...`. A use no rule matches is an error when the file is read.

```scheme
(let-syntax ((first (syntax-rules () ((_ a b ...) 'a)))) (first x y z)) ; => x
(define-syntax my-if
  (syntax-rules (then else)
    ((_ c then t else e) (if c t e))))
(my-if #f then 'yes else 'no) ; => no
(define-syntax tagged
  (syntax-rules ::: ()
    ((_ x :::) '(x ::: ...))))
(tagged 1 2) ; => (1 2 ...)
```
