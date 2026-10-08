# Syntax and definition

The binding and control forms. Definitions are decided by a pre-scan of the whole file, so a definition may use one below it; a head-position call to a value binding holding a real function is a `funcall` of the value cell, while any other variable goes through the prelude dispatcher (which `funcall`s real functions and invokes collections).

| Name | Example | Result |
|---|---|---|
| `def` | `(do (def x 5) nil)` | `nil` |
| `defn` | `(do (defn sq [x] (* x x)) (sq 7))` | `49` |
| `defn-` | `(do (defn- sq [x] (* x x)) (sq 7))` | `49` |
| `defonce` | `(do (defonce x 5) x)` | `5` |
| `fn` | `((fn [a b] (+ a b)) 1 2)` | `3` |
| `let` | `(let [x 1 y x] y)` | `1` |
| `letfn` | `(letfn [(f [x] x)] (f 1))` | `1` |
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
| `with-meta` | `(with-meta [1] {:a 1})` | `[1]` |
| `var` | `(do (def x 5) #'x)` | `#'user/x` |
| `var-get` | `(do (def x 5) (var-get #'x))` | `5` |
| `test` | `(do (defn ^{:test (fn [] nil)} t []) (test #'t))` | `:ok` |
| `comment` | `(comment (anything at all))` | `nil` |
| `declare` | `(declare later)` | `nil` |
| `when-let` | `(when-let [x 1] (+ x 10))` | `11` |
| `if-let` | `(if-let [x nil] 1 :none)` | `:none` |
| `when-not` | `(when-not false :ran)` | `:ran` |
| `if-not` | `(if-not nil :t :e)` | `:t` |
| `when-first` | `(when-first [x [1 2]] x)` | `1` |
| `if-some` | `(if-some [x false] [x] :none)` | `[false]` |
| `when-some` | `(when-some [x nil] :body)` | `nil` |
| `case` | `(case 2 1 :one (2 3) :few :many)` | `:few` |
| `condp` | `(condp < 5 10 :big 3 :mid)` | `:mid` |
| `while` | `(let [a (atom 0)] (while (< @a 3) (swap! a inc)) @a)` | `3` |
| `locking` | `(locking :k (+ 1 2))` | `3` |
| `with-redefs` | `(do (defn f [] 1) (with-redefs [f (fn [] 2)] (f)))` | `2` |
| `binding` | `(do (def ^:dynamic *d* 1) (binding [*d* 2] *d*))` | `2` |
| `time` | `(time (+ 1 2))` | `3` |
