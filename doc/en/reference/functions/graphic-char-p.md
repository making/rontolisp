# graphic-char-p standard-char-p

`(graphic-char-p character)` -- `(standard-char-p character)`

Character predicates. `graphic-char-p` is true for a printing character -- code points 32 to 126 and everything from 160 up -- so `#\Space` counts and `#\Newline` does not. `standard-char-p` is true for the 96 standard characters: the printing ASCII range plus `#\Newline`, which is the one character the two predicates disagree on.

```lisp
(list (graphic-char-p #\a) (graphic-char-p #\Space) (graphic-char-p #\Newline)
      (standard-char-p #\Newline) (standard-char-p #\Tab)) ; => (T T NIL T NIL)
```
