# clojure.walk

入れ子になったデータを汎用に走査する名前空間です。Clojure と同じくプログラムより先に読み込まれて
いるので、`clojure.walk/postwalk` は `require` なしで使えます。`(require '[clojure.walk :as walk])`
で別名を付けることもできます。Clojure の同名の名前空間について文書化された振る舞いをもとに
rontolisp 向けに書いた Clojure ソースで、すべてのバックエンドで同じように動きます。

| var | 振る舞い |
|---|---|
| `walk` | `(walk inner outer form)`: `form` の各要素に `inner` を適用し、その結果から `form` と同じ種類の値を組み立て直して `outer` を適用する |
| `postwalk` | `(postwalk f form)`: 深さ優先で、要素を置き換えたあとの各値に `f` を適用する |
| `prewalk` | `(prewalk f form)`: 深さ優先で、各値にまず `f` を適用し、その結果の中へ降りる |
| `postwalk-replace`、`prewalk-replace` | `(postwalk-replace smap form)`: `smap` のキーである値をすべて、`smap` での対応する値に置き換える |
| `keywordize-keys`、`stringify-keys` | どの深さのマップでも、文字列のキーをキーワードに、キーワードのキーを文字列（名前部分）に変える |
| `postwalk-demo`、`prewalk-demo` | 訪れた値を `Walked: x` の形で表示し、form を返す |
| `macroexpand-all` | form に含まれるすべての seq を外側から順にマクロ展開する |

```clojure
(clojure.walk/postwalk #(if (number? %) (inc %) %) [1 '(2 [3]) #{4}])
; => [2 (3 [4]) #{5}]
(require '[clojure.walk :as walk])
(walk/keywordize-keys {"user" {"name" "ann"}})
; => {:user {:name "ann"}}
(walk/postwalk-replace '{x 1} '(+ x (* x 2)))
; => (+ 1 (* 1 2))
(walk/prewalk #(if (vector? %) (vec (reverse %)) %) [[1 2] [3 4]])
; => [[4 3] [2 1]]
```

組み立て直したコレクションは元の種類を保ちます。リストはリストのまま、遅延シーケンスは実体化され、
レコードは型とフィールド以外のキーを、ソート済みコレクションは比較関数を保ち、どの値もメタデータを
引き継ぎます。マップの要素は `[キー 値]` のエントリです。`keywordize-keys` と `stringify-keys` は、
どのマップもハッシュマップとして返します。

## 違い

- `macroexpand-all` が展開するのはプログラム自身のマクロです。コアのフォーム（`when`、`->` など）は
  ここではマクロではないため書かれたまま残り、Clojure が返す展開形にはなりません
  （[macroexpand](macroexpand.md)）。
- マップのエントリはただの 2 要素ベクターなので（[仕様との差異](../deviations.md)）、走査中に `map-entry?` で
  判定する関数は、2 要素のベクターをすべてエントリとみなします。
