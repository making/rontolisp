# case

`(case expr test result... default?)`

`expr` を一度だけ評価し、テスト定数がその値に等しい最初の節の結果を返します。テスト定数は評価されません。シンボルはそのシンボル自身で、リストはその節の選択肢を並べたものです（`(1 2 3)` は三つのどれにも一致します。`'x` はリスト `(quote x)` なので、`quote` と `x` に一致します）。末尾に単独で残ったフォームはデフォルトです。デフォルトがなく、どの節にも一致しないときは `IllegalArgumentException` `No matching clause: <値>` を投げます。同じ定数を二度並べると、プログラムの変換時に拒否されます（`Duplicate case test constant`）。

定数は Clojure の `case` と同じ規則で比較します。数値は同じ種類の数値にだけ一致し（`1N` は `1` に一致し、`1.0` は一致しません。`-0.0` は `0.0` に一致せず、`##NaN` は何にも一致しません）、文字はその文字コードに一致しません。ベクタ、マップ、セットの定数は `=` で比較するので、ベクタの定数は同じ要素のリストや遅延シーケンスにも一致します。

仕様との差異: 10 進リテラルは正確な有理数なので、`1M` と `1` は同じ定数です。

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
