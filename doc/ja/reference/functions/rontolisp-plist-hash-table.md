# rontolisp:plist-hash-table

`(rontolisp:plist-hash-table plist &rest hash-table-initargs)`

プロパティリストからハッシュテーブルを構築します — 奇数番目の要素がキー、
偶数番目の要素が値になり、末尾の引数はそのまま `make-hash-table` へ渡されます。
`alexandria:plist-hash-table` の軽量なサブセットなので、プログラムをそのまま
alexandria へ切り替えられます。JSON オブジェクトの構築では
[`rontolisp:json-stringify`](rontolisp-json-stringify.md) と組み合わせられます:
キーワードのキーは小文字化されるため、`:name` は `"name"` になります。

```lisp
(rontolisp:json-stringify (rontolisp:plist-hash-table (list :name "rontolisp")))   ; => "{"name":"rontolisp"}"
```

複数のキーを持つオブジェクトも同様に動作します(JSON 出力のキー順序は
`maphash` と同様にバックエンド依存です)。テーブルは本物のハッシュテーブル
なので、値は `gethash` で読み戻せます:

```lisp
(gethash :ok (rontolisp:plist-hash-table (list :name "x" :ok t)))   ; => T
```

デフォルトのハッシュテーブルのテストは `alexandria:plist-hash-table` と同じく
`eql` です。変更するには `:test 'equal`(または任意の `make-hash-table` 引数)を
渡してください:

```lisp
(gethash "k" (rontolisp:plist-hash-table (list "k" 9) :test 'equal))   ; => 9
```

逆の変換は [`rontolisp:hash-table-plist`](rontolisp-hash-table-plist.md) です。

## バックエンドサポート

すべてのバックエンド・すべての WASM モード(Preview 1 含む)で動作します:
この関数は rontolisp 自身で書かれており(プレリュードの一部)、使用時に
プログラムへ組み込まれてコンパイルされます。
