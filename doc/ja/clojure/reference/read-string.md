# read-string

`(read-string s)` / `(read-string opts s)`

文字列の最初のデータを読み、データとして答えます。答えは同じテキストをクオートしたときの値と同じなので、`(= (read-string "[1 :k]") '[1 :k])` が成り立ちます。数・文字列・文字・キーワード（`::kw` は呼び出し元の名前空間で解決）・シンボル・リスト・ベクター・マップ・セットは、名前空間マップ（`#:ns{...}`・`#::{...}`）も含めて、ソースのリーダと同じように読まれます。リーダメタデータはオラクルと同じように付きます（`^:k` は `{:k true}`、シンボルか文字列は `{:tag x}`、ベクターは `{:param-tags v}`。シンボルはメタデータを持ちません）。`#_` は次のデータを読み飛ばします。マップのキーやセットの要素が重複するとオラクルと同じ `Duplicate key` に、オラクルが読まないキーワードやシンボル（`a:`、`x/`、`//`）は `Invalid token` になります。レコードリテラル（`#ns.Name{...}`・`#ns.Name[...]`、名前にドットを含むタグ）は、プログラムが定義するクラスのレコードを、評価しない本体から組み立てます。それ以外のタグはオラクルと同じ順に読まれます。まず `*data-readers*` がタグのシンボルに対応づける関数（プログラムの[データリーダ](../syntax.md#tagged-literals)で、`binding` で置き換えられます）、次に `#inst`・`#uuid` の[値](instants.md)、最後にタグと値を受け取る `*default-data-reader-fn*` です。どれも読まないタグにはリーダ関数がなく、`nil` を答えた場合は `No dispatch macro` になります。最初のデータより後ろのテキストは無視します。空の入力は `EOF while reading` を通知しますが、オプションマップに `:eof` があればその値を答えます。リーダ条件は、オプションマップに `:read-cond :allow` がなければ拒否されます（`Conditional read not allowed`）。あれば `.cljc` ファイルと同様に読まれ、`:features` のセットが `:rontolisp`、`:clj`、`:default` にフィーチャを加えます。`:read-cond :preserve` では `#?(...)`・`#?@(...)` がリスト全体を持つ [reader-conditional](reader-conditional.md) として読まれ、その中のタグ付きリテラルは [tagged-literal](tagged-literal.md) として読まれます。`#=` の読み取り時評価はソースと同様に拒否されます。`@x` は `'@x` と同じく `(deref x)` と読まれます（オラクルは `(clojure.core/deref x)`）。すべてのバックエンドで動きます。値としては1引数または2引数を取ります。

```clojure
(defrecord Point [x y])
(println (read-string "[1 :k \"s\" (a b)]"))
(println (= (read-string "#user.Point{:x 1 :y 2}") (->Point 1 2)))
(println (read-string {:eof :none} ""))
(println (read-string {:read-cond :allow} "[#?(:cljs 1 :clj 2) #?@(:clj [3 4])]"))
(println (read-string {:read-cond :allow :features #{:cljs}} "#?(:cljs 1 :clj 2)"))
(println (read-string {:read-cond :preserve} "#?(:cljs #js {} :clj 2)"))
(println (binding [*data-readers* {'my/tag (fn [x] [:tagged x])}] (read-string "#my/tag 1")))
(println (binding [*default-data-reader-fn* (fn [tag x] [tag x])] (read-string "#foo/bar 2")))
```

```
[1 :k s (a b)]
true
:none
[2 3 4]
1
#?(:cljs #js {} :clj 2)
[:tagged 1]
[foo/bar 2]
```
