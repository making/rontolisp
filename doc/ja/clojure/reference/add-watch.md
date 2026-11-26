# add-watch

`(add-watch reference key f)`

atom、ref、agent、var が変わるたびに、変えたスレッドで `(f key reference old new)` を呼び、reference を返します。atom の watch は `swap!`、`reset!`（同じ値でも）、成功した `compare-and-set!`、`-vals` の動詞のたびに、ref の watch はそれを書いたトランザクションのコミットごとに1回（`old` はトランザクション前の値）、agent の watch は各アクションの後に（`await` のマーカーでも。`old` と `new` は等しい）、var の watch は `alter-var-root` の後に走ります。同じキーの2つ目の watch は最初のものを置き換え、複数の watch は Clojure の watch マップの順に走ります。例外を投げた watch は、値を格納した後の動詞から投げます。`remove-watch` で外します。関数値として動きます。

```clojure
(def a (atom 0))
(add-watch a :log (fn [k r old new] (println k old "->" new)))
(swap! a inc)
(reset! a 10)
(remove-watch a :log)
(swap! a inc)
(println @a)
```

```
:log 0 -> 1
:log 1 -> 10
11
```

後の `def`・`defn`・`defmacro`・`deftest` や `with-redefs` が var のルートを置き換えたときも、その var の watch はオラクルの `bindRoot` 同様に走ります（[仕様との差異](../deviations.md)）。
