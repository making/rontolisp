# deftype

`(deftype Name [fields...] Protocol (method [target & args] body...) ...)`

deftype を定義します。マップ動詞には不透明な、レコード形の値です。不透明な
`:C%TYPE` タグでレコードと同じ形（クラス名を含む）を共有します。読みは外れ（`get` は既定値を
答え）、書きと `seq`・`count`・`empty?` はシグナルを上げ、`=` は同一性です
（いずれもオラクル同様。ただし、その関数が読むインタフェースを本体が実装していれば、それを
通します。後述）。位置指定コンストラクタ `->Name` のみ lower されます
（オラクルは deftype に `map->Name` を定義しません）。`(Name. ...)` は書き換わり
ます。インラインのメソッド本体には `defrecord` 同様フィールドがローカルとして
見えます。`^:unsynchronized-mutable` または `^:volatile-mutable` を付けたフィールドは
それらのメソッド専用で、メソッドは [`set!`](set-bang.md) で代入します。インスタンス
呼び出しはインラインのメソッドと不変フィールドに届きます（[`.name`](dot-name.md)）。名前は
ファイル全体の事前走査に参加します。

```clojure
(deftype T [a])
(def t (T. 1))
(println (= t t))          ; true
(println (= t (T. 1)))     ; false
(println (get t :a))       ; nil
(println (instance? T t))  ; true
```

本体は [reify](reify.md#host-interfaces) と同じく、コア関数が参照する `clojure.lang` の
インタフェース（[コレクションのインタフェース](reify.md#collection-interfaces)を含む）を実装でき、
`Object` のメソッドを上書きでき、そのメソッドにはフィールドが見えます。`Counted` の deftype は
数えられ、`IDeref` の deftype は deref でき、`IObj` の deftype は自身のメソッドが保つ
メタデータを持ち、`IPersistentMap` の deftype は `assoc`・`get`・`map?` からマップとして扱われ、
マップとして印字されます。

```clojure
(deftype Box [v] clojure.lang.IDeref (deref [_] v))
@(Box. 5) ; => 5
(deftype Squares [n]
  clojure.lang.Indexed
  (nth [_ i] (* i i))
  (nth [_ i nf] (if (< -1 i n) (* i i) nf))
  (count [_] n))
(count (Squares. 4)) ; => 4
(nth (Squares. 4) 9 :none) ; => :none
(deftype Money [cents] Object (toString [_] (str cents " cents")))
(str (Money. 5)) ; => "5 cents"
```
