# 構文と定義

束縛と制御のフォームです。定義はファイル全体の事前走査で決まるので、定義は後で定義される名前を使えます。実関数を持つ値束縛の head 位置呼び出しは値セルの funcall に、それ以外の変数は prelude ディスパッチャ経由になります（実関数は funcall し、コレクションは呼び出します）。

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
| `binding` | `(do (def ^:dynamic *d* 1) (binding [*d* 2] *d*))` | `2` |
| `time` | `(time (+ 1 2))` | `3` |
