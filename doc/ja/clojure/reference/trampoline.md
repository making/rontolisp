# trampoline

`(trampoline f args...)`

`f` の適用結果が thunk（0引数関数）の間、引数なしで呼び続け、非関数で止まった値を返します。
自己呼び出しループなので相互 thunk 連鎖も定数スタックです。値としては関数に続けて任意個の
引数を取ります。

```clojure
(defn b15-blast [x] (if (zero? x) :done (fn [] (b15-blast (dec x)))))
(println (trampoline b15-blast 50)) ; :done
```
