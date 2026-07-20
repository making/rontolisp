# symbol-name

`(symbol-name symbol)`

シンボルの名前を文字列として返します。リーダーは Common Lisp と同じくエスケープされていないシンボルを大文字化する([リーダーのケースのガイド](../../guides/reader-case.md)を参照)ため、ユーザーシンボルは大文字化された名前を報告します — `(symbol-name 'foo)` は `"FOO"` で、Common Lisp と同じ答えです。唯一の相違点は、**標準**シンボルの正規の綴りが小文字であることです: CL が `"CAR"` と答えるところで `(symbol-name 'car)` は `"car"` です。キーワードの先頭の `:` と、[`gensym`](gensym.md)/[`make-symbol`](make-symbol.md) の結果の `#:` プレフィックスはパッケージマーカーであって名前の一部ではないため、取り除かれます — `princ` が印字するテキストと同じです(`prin1` はマーカーを保ちます)。

コンパイルバックエンド(JVM/WASM)では `symbol-name` は `princ-to-string` の機構を共有するため、シンボル以外の引数はエラーにならずその表示テキストを返します(インタプリタはエラーを通知します)。

リーダーはシンボル名の Common Lisp エスケープ構文をサポートします: バックスラッシュは次の 1 文字をそのまま名前の一部にし、`|...|` の複数エスケープはパイプの間のすべて — 空白や終端文字を含む — を名前の一部にします。`'|when used|` は `"when used"` という名前の 1 つのシンボルで、`'|Foo|` は混在ケースを保ちます。

```lisp
(symbol-name 'foo) ; => "FOO"
```

```lisp
(symbol-name :bar) ; => "BAR"
```

```lisp
(symbol-name 'car) ; => "car"
```

```lisp
(intern (symbol-name 'round-trip)) ; => ROUND-TRIP
```

```lisp
(symbol-name '|when used|) ; => "when used"
```
