# case

`(case expr test result... default?)`

Evaluates `expr` once and answers the result of the first clause whose test constant
equals it. Test constants are not evaluated: a symbol is itself, and a list lists the
clause's alternatives (`(1 2 3)` matches any of the three; `'x` is the list
`(quote x)`, so it matches `quote` and `x`). A lone trailing form is the default.
Without one, no matching clause throws `IllegalArgumentException`
`No matching clause: <value>`. A constant listed twice is refused when the program
lowers (`Duplicate case test constant`).

Constants compare like Clojure's `case`: a number matches only its own category
(`1N` is `1`, `1.0` is not, `-0.0` is not `0.0`, `##NaN` matches nothing), a character
never matches its code, and a vector, map or set constant matches by `=`, so a vector
constant matches a list or lazy seq of the same members.

Deviation: `1M` and `1` are the same constant, because decimal literals are exact
rationals.

```clojure
(defn kind [x]
  (case x
    0 :zero
    (1 2 3) :small
    "one" :string
    [1 2] :pair
    :other))
(println (map kind [0 2 "one" '(1 2) 9])) ; (:zero :small :string :pair :other)
(println (try (case 5 1 :a) (catch IllegalArgumentException e (ex-message e)))) ; No matching clause: 5
```
