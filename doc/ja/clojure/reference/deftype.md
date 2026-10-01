# deftype

`(deftype Name [fields...] Protocol (method [target & args] body...) ...)`

deftype を定義します。マップ動詞には不透明な、レコード形の値です。不透明な
`:C%TYPE` タグでレコードと同じ4要素形を共有します。読みは外れ（`get` は既定値を
答え）、書きと `seq`・`count`・`empty?` はシグナルを上げ、`=` は同一性です
（いずれもオラクル同様）。位置指定コンストラクタ `->Name` のみ lower されます
（オラクルは deftype に `map->Name` を定義しません）。`(Name. ...)` は書き換わり
ます。インラインのメソッド本体には `defrecord` 同様フィールドがローカルとして
見えます。名前はファイル全体の事前走査に参加します。

```clojure
(deftype T [a])
(def t (T. 1))
(println (= t t))          ; true
(println (= t (T. 1)))     ; false
(println (get t :a))       ; nil
(println (instance? T t))  ; true
```
