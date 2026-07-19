# rontolisp:alist-hash-table

`(rontolisp:alist-hash-table alist &rest hash-table-initargs)`

連想リストからハッシュテーブルを構築します — 各 `(key . value)` コンスが
エントリになり、同じキーが複数あれば最初の出現が採用されます。末尾の引数は
そのまま `make-hash-table` へ渡されます。`alexandria:alist-hash-table` の
軽量なサブセットなので、プログラムをそのまま alexandria へ切り替えられます。
連想リスト(たとえば [`rontolisp:query-params`](rontolisp-query-params.md) の
結果やリクエストヘッダー)を JSON オブジェクトに変換する際は
[`rontolisp:json-stringify`](rontolisp-json-stringify.md) と組み合わせられます。

```lisp
(rontolisp:json-stringify (rontolisp:alist-hash-table (list (cons "n" 1))))   ; => "{"n":1}"
```

デフォルトのハッシュテーブルのテストは `alexandria:alist-hash-table` と同じく
`eql` です。内容で重複を排除したい文字列キーには `:test 'equal` を渡してください:

```lisp
(hash-table-count (rontolisp:alist-hash-table (list (cons "a" 1) (cons "a" 2)) :test 'equal))   ; => 1
```

逆の変換は [`rontolisp:hash-table-alist`](rontolisp-hash-table-alist.md) です。

## バックエンドサポート

すべてのバックエンド・すべての WASM モード(Preview 1 含む)で動作します:
この関数は rontolisp 自身で書かれており(プレリュードの一部)、使用時に
プログラムへ組み込まれてコンパイルされます。
