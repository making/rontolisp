# defrecord

`(defrecord Name [fields...] Protocol (method [target & args] body...) ...)`

レコードを定義します。型タグ付きのマップです。すべてのマップが使うエントリ表を
`(:C%RECORD tag fields table class)` で包むため、マップ動詞はそれを通して読みます
（`get`・`contains?`・`keys`・`vals`・`count`・`seq` はエントリを読み、
`assoc`・`update`・`conj`・`merge` は表を組み直してタグを保ち、`dissoc` は宣言
フィールドが全部残る間はレコードを保ち、そうでなければオラクル同様プレーンな
マップに落ちます）。`=` は2つのレコードをタグとエントリで比べ、プレーンなマップ
と等しくなることは決してありません。`.equals` はオラクルの `mapEquals` と同じくタグを
見ません。コンストラクタは2つで、マングルされた関数に
lower されます。位置指定の `->Name`（数が違うとシグナル）とマップからの
`map->Name`（欠けたフィールドは `nil`、余分なエントリは保持）。`(Name. ...)` は
`->Name` に書き換わります。インラインのメソッド本体にはフィールドがローカルとして
見え、インスタンス呼び出しはインラインのメソッドと宣言フィールドに届きます
（[`.name`](dot-name.md)）。名前はファイル全体の事前走査に参加するため、コンストラクタ呼び出しは
定義より前に書けます。

レコードはオラクル同様リテラルで印字されます。`#user.R{:a 7}` のように、定義した
名前空間（`-` は `_` と綴る）と名前を並べ、宣言フィールドを先に置きます。リテラルは
ソースでも [read-string](read-string.md)/[read](read.md) でも読み戻せます。
`#ns.Name{:k v ...}` は評価しない本体からレコードを組み（欠けた
フィールドは `nil`、余分なキーは保持）、`#ns.Name[v ...]` は位置指定で組みます（数が
違えば拒否）。クラスはプログラムが定義したレコードでなければなりません。ドットのない
`#Name{...}` はタグ付きリテラルで、オラクル同様拒否されます
（`No reader function for tag Name`）。

仕様との差異:レコードの `str` もリテラルを綴ります。オラクルは `user.R@<hash>` を返します
（`toString` を上書きした本体は、その答えを返します。後述）。

```clojure
(defrecord R [a])
(def r (->R 7))
(println r)                       ; #user.R{:a 7}
(println (= r #user.R{:a 7}))     ; true
(println (get r :a))              ; 7
(println (= r (->R 7)))           ; true
(println (= r {:a 7}))            ; false
(println (get (assoc r :b 1) :b)) ; 1
```

本体は、[reify](reify.md#host-interfaces) のインタフェースのうちレコード自身が実装しない
もの（`IFn`、`IDeref`、`IReduceInit` など）を実装でき、`toString` を上書きできます。
`toString` は `str` が読み、印字は引き続きレコードのリテラルです。マップのインタフェースは
レコード自身のものなので、`ILookup`・`IObj`・`IPersistentMap`・`IHashEq`・`java.util.Map`・
`java.io.Serializable` を挙げることと、レコード自身が定義するメソッド（`count`、`seq`、`valAt`、
`assoc`、`iterator`、`meta`、`equals`、`hashCode` など）を定義することは、オラクルの
`Duplicate` による拒否になります。それらのインタフェースのメソッドのうち、レコードが
インタフェース側の実装に任せるもの（`assocEx`、`java.util.Map` の default メソッド）は名前を
挙げて拒否されます。本体が実装する Java のインタフェース（`Runnable`、`Comparable` など）は
Java から見たレコードの姿でもあり、レコードは Java のメンバに、エントリを持つ `java.util.Map`
であり、かつそれらのインタフェースを実装するオブジェクトとして渡ります
（[Java 相互運用](interop.md)）。

```clojure
(defrecord Adder [n] clojure.lang.IFn (invoke [_ x] (+ n x)))
((->Adder 10) 5) ; => 15
(defrecord Point [x y] Object (toString [_] (str "<" x "," y ">")))
(str (->Point 1 2)) ; => "<1,2>"
```
