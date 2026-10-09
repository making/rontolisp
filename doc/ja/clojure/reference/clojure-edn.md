# clojure.edn

Clojure のデータ記法 [EDN](https://github.com/edn-format/edn) を文字列やストリームから読み取る
名前空間です。Clojure と同じくプログラムより先に読み込まれているので、`clojure.edn/read-string`
は `require` なしで使えます。すべてのバックエンドで動きます。

| var | 振る舞い |
|---|---|
| `read-string` | `(read-string s)` / `(read-string opts s)`: 文字列の最初のデータを返す。`nil` には `nil` を返し、入力の終わりでは `nil`（引数 1 つのとき）、`:eof` オプションの値、または `EOF while reading` エラーになる |
| `read` | `(read)` / `(read stream)` / `(read opts stream)`: ストリーム（省略時は `*in*`）からデータを 1 つ読み、ストリームをその直後に残す。入力の終わりでは `:eof` オプションの値かエラーになる |

```clojure
(require '[clojure.edn :as edn])
(edn/read-string "{:id 7, :tags #{\"x\"}, :point [1.5 -2]}")
; => {:id 7, :tags #{"x"}, :point [1.5 -2]}
(edn/read-string "")
; => nil
(edn/read-string {:eof :done} "")
; => :done
(edn/read-string "#:user{:name \"ann\"}")
; => #:user{:name "ann"}
(meta (edn/read-string "^:private [1]"))
; => {:private true}
```

EDN はデータだけを表します。クォートはシンボルの一部で（`'a` はシンボル `'a` として読まれます）、
シンタックスクォート、`~`、`@`、`#'`、`#(...)`、`#"..."`、`#=`、`#?` はオラクルと同じメッセージで
拒否されます。自動解決の `::keyword` も同様です。数値は数字か、符号と数字で始まるため、`.5` は
シンボルです。マップのキーやセットの要素が重複すると `Duplicate key` エラーになります。

## タグ付きリテラル

`#tag value` は `value` を読み、`:readers` マップがタグのシンボルに対して持つ関数を呼びます。
なければ組み込みの `#inst` と `#uuid`（ソースと同じく読まれる[値](instants.md)）を使い、
それもなければ `:default` 関数をタグと値で呼びます。どれも受け付けないタグは
`No reader function for tag` エラーです。リーダーには関数値なら何でも使えます。関数、var、
キーワード、マップのいずれでも構いません。オラクルと同じく、EDN の読み取りは `*data-readers*` も
`*default-data-reader-fn*` も参照しません。

```clojure
(require '[clojure.edn :as edn])
(edn/read-string {:readers {'cm (fn [n] (/ n 100.0))}} "[#cm 150 #cm 20]")
; => [1.5 0.2]
(edn/read-string {:default (fn [tag value] {:tag tag :value value})} "#my/point [1 2]")
; => {:tag my/point, :value [1 2]}
(edn/read-string "[#inst \"2020-06-15T10:20:30Z\" #uuid \"1-1-1-1-1\"]")
; => [#inst "2020-06-15T10:20:30.000-00:00" #uuid "00000001-0001-0001-0001-000000000001"]
```

## 違い

- `read` は文字ストリームなら何でも受け付けます。素の `clojure.java.io/reader` も読めますが、
  オラクルは `java.io.PushbackReader` を要求します。
- `N` と `M` の数値は、ただの整数と正確な比として読まれます（[構文](../syntax.md#numbers)）。
- シンボルはメタデータを持たないため、`^:k sym` はメタデータのないシンボルとして読まれます。
