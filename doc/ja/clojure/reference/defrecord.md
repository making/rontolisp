# defrecord

`(defrecord Name [fields...] Protocol (method [target & args] body...) ...)`

レコードを定義します。型タグ付きのマップです。すべてのマップが使うエントリ表を
`(:C%RECORD tag fields table)` で包むため、マップ動詞はそれを通して読みます
（`get`・`contains?`・`keys`・`vals`・`count`・`seq` はエントリを読み、
`assoc`・`update`・`conj`・`merge` は表を組み直してタグを保ち、`dissoc` は宣言
フィールドが全部残る間はレコードを保ち、そうでなければオラクル同様プレーンな
マップに落ちます）。`=` は2つのレコードをタグとエントリで比べ、プレーンなマップ
と等しくなることは決してありません。コンストラクタは2つで、マングルされた関数に
lower されます。位置指定の `->Name`（数が違うとシグナル）とマップからの
`map->Name`（欠けたフィールドは `nil`、余分なエントリは保持）。`(Name. ...)` は
`->Name` に書き換わります。インラインのメソッド本体にはフィールドがローカルとして
見えます。名前はファイル全体の事前走査に参加するため、コンストラクタ呼び出しは
定義より前に書けます。

仕様との差異:レコードはラッパーリストで印字されます（`(:C%RECORD :R (:a) {:a 7})`）。
オラクルは `#user.R{:a 7}` と印字します。

```clojure
(defrecord R [a])
(def r (->R 7))
(println (get r :a))              ; 7
(println (= r (->R 7)))           ; true
(println (= r {:a 7}))            ; false
(println (get (assoc r :b 1) :b)) ; 1
```
