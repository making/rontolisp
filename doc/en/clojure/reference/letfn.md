# letfn

`(letfn [(f [params...] body...)+] body...)`

Binds mutual local functions and evaluates the body, which may call them. Every name is
visible in every entry -- siblings call each other -- and in the body; an entry's name is
a function value too. Each entry lowers like a named-`fn` clause (parameters destructure
the same way, several arities dispatch the same way), so an entry may `recur` to itself
and the body calls each entry directly. An empty binding vector is just the body.

```clojure
(println (letfn [(fact [x] (if (zero? x) 1 (* x (fact (dec x)))))] (fact 5))) ; 120
(println (letfn [(even? [n] (if (zero? n) true (odd? (dec n))))
                 (odd? [n] (if (zero? n) false (even? (dec n))))]
           (even? 10))) ; true
(let [y 7]
  (println (letfn [(g [x] (+ x y))] (g 3)))) ; 10
```
