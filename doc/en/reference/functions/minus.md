# -

`(- number &rest numbers)`

With one argument, returns its negation. With several, subtracts the rest from the first, left to right. The result type follows the usual numeric contagion: integers stay integers, but any float argument makes the result a float, and ratios are kept exact. Integer results promote to big integers on overflow, on every backend. The subtraction runs one pair at a time, so the arguments ahead of the first float subtract exactly and only their difference turns into a float: `(- 1/10 -1/5 0.0)` is `0.3`, as in SBCL. A complex argument folds the same way: `(- z z 1.5)` over an exact complex `z` is `-1.5`.

```lisp
(- 10 3) ; => 7
```

```lisp
(- 5) ; => -5
```
