# labels

`(labels ((name lambda-list body...)...) body...)`

Like [`flet`](flet.md) but the definitions see each other, so the local functions can call themselves and each other (recursion and mutual recursion).

```lisp
(labels ((ev (n) (if (= n 0) t (od (- n 1))))
         (od (n) (if (= n 0) nil (ev (- n 1)))))
  (list (ev 10) (od 9))) ; => (T T)
```
