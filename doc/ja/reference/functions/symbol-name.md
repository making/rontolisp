# symbol-name

`(symbol-name symbol)`

シンボルの名前を文字列として返します。rontolisp のシンボルは大文字小文字を保存する(名前は書いたとおりに読み戻される、通常は小文字)ため、Common Lisp と違い結果は大文字化**されません**: `(symbol-name 'foo)` は `"FOO"` ではなく `"foo"` です。キーワードの先頭の `:` と、[`gensym`](gensym.md)/[`make-symbol`](make-symbol.md) の結果の `#:` プレフィックスはパッケージマーカーであって名前の一部ではないため、取り除かれます — `princ` が印字するテキストと同じです(`prin1` はマーカーを保ちます)。

コンパイルバックエンド(JVM/WASM)では `symbol-name` は `princ-to-string` の機構を共有するため、シンボル以外の引数はエラーにならずその表示テキストを返します(インタプリタはエラーを通知します)。

リーダーはシンボル名の Common Lisp エスケープ構文をサポートします: バックスラッシュは次の 1 文字をそのまま名前の一部にし、`|...|` の複数エスケープはパイプの間のすべて — 空白や終端文字を含む — を名前の一部にします。`'|when used|` は `"when used"` という名前の 1 つのシンボルです。rontolisp のシンボルはもともと大文字小文字を保存するため、`|Foo|` とエスケープなしの `Foo` は同じシンボルを指します。

```lisp
(symbol-name 'foo) ; => "foo"
```

```lisp
(symbol-name :bar) ; => "bar"
```

```lisp
(intern (symbol-name 'round-trip)) ; => round-trip
```

```lisp
(symbol-name '|when used|) ; => "when used"
```
