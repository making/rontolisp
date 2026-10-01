# Syntax and definition

The binding and control forms. Definitions are decided by a pre-scan of the whole file, so a definition may use one below it; a head-position call to a value binding is a `funcall` of the value cell.

| Name | Example | Result |
|---|---|---|
| `def` | `(do (def x 5) nil)` | `nil` |
| `defn` | `(do (defn sq [x] (* x x)) (sq 7))` | `49` |
| `fn` | `((fn [a b] (+ a b)) 1 2)` | `3` |
| `let` | `(let [x 1 y x] y)` | `1` |
| `loop` | `(loop [i 0] (if (= i 3) i (recur (inc i))))` | `3` |
| `recur` | `(loop [a 0] (if (= a 2) a (recur (inc a))))` | `2` |
| `if` | `(if (< 1 2) :yes :no)` | `:yes` |
| `when` | `(when true 1 2)` | `2` |
| `cond` | `(cond (= 1 2) :one (= 1 1) :two :else :other)` | `:two` |
| `do` | `(do 1 2 3)` | `3` |
| `and` | `(and 1 2 3)` | `3` |
| `or` | `(or nil nil 3)` | `3` |
| `not` | `(not nil)` | `true` |
| `quote` | `(quote (a b c))` | `(a b c)` |
| `comment` | `(comment (anything at all))` | `nil` |
| `declare` | `(declare later)` | `nil` |
