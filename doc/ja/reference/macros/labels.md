# labels

`(labels ((name lambda-list body...)...) body...)`

[`flet`](flet.md) と同様ですが、定義同士が互いに見えるため、局所関数は自分自身や互いを呼び出せます（再帰と相互再帰）。

```lisp
(labels ((ev (n) (if (= n 0) t (od (- n 1))))
         (od (n) (if (= n 0) nil (ev (- n 1)))))
  (list (ev 10) (od 9))) ; => (t t)
```
