# rontolisp:hash-table-plist

`(rontolisp:hash-table-plist table)`

ハッシュテーブルのキー/値ペアのプロパティリストを返します —
[`rontolisp:plist-hash-table`](rontolisp-plist-hash-table.md) の逆です。
`alexandria:hash-table-plist` の軽量なサブセットです。

```lisp
(rontolisp:hash-table-plist (rontolisp:plist-hash-table (list :a 1)))   ; => (:A 1)
```

ペアの順序はテーブルの反復順序に従う(`maphash` と同様にバックエンド依存)
ため、単一エントリのテーブルでは明確に定まります。
[`rontolisp:json-parse`](rontolisp-json-parse.md) のオブジェクトではキーが
文字列であり、`getf` では検索できない(`eq` で比較するため)ので、それらは
代わりに `gethash` で読み取ってください。

## バックエンドサポート

すべてのバックエンド・すべての WASM モード(Preview 1 含む)で動作します:
この関数は rontolisp 自身で書かれており(プレリュードの一部)、使用時に
プログラムへ組み込まれてコンパイルされます。
