# -

`(- number &rest numbers)`

With one argument, returns its negation. With several, subtracts the rest from the first, left to right. The result type follows the usual numeric contagion: integers stay integers, but any float argument makes the result a float, and ratios are kept exact. Integer results promote to big integers on overflow, on every backend. With a complex argument the subtraction still runs one pair at a time, so an exact step stays exact whatever float follows: `(- z z 1.5)` over an exact complex `z` is `-1.5`, as in SBCL.

```lisp
(- 10 3) ; => 7
```

```lisp
(- 5) ; => -5
```
