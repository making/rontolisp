# reset-vals!

`(reset-vals! atom v)`

`[old new]`、つまり `v` が置き換えた値と `v` を返す `reset!` です。validator が先に `v` を検査し、watch は格納の後に走ります。関数値として動きます。

```clojure
(def a (atom 1))
(println (reset-vals! a 5))
(println @a)
```

```
[1 5]
5
```
