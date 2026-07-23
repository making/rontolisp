# rontolisp:hash-table-alist

`(rontolisp:hash-table-alist table)`

ハッシュテーブルのキー/値ペアの連想リストを返します —
[`rontolisp:alist-hash-table`](rontolisp-alist-hash-table.md) の逆です。
`alexandria:hash-table-alist` の軽量なサブセットです。

```lisp
(rontolisp:hash-table-alist (rontolisp:alist-hash-table '(("k" . 7))))   ; => (("k" . 7))
```

ペアの順序はテーブルの反復順序に従う(`maphash` と同様にバックエンド依存)
ため、単一エントリのテーブルでは明確に定まります。

## バックエンドサポート

すべてのバックエンド・すべての WASM モード(Preview 1 含む)で動作します:
この関数は rontolisp 自身で書かれており(プレリュードの一部)、使用時に
プログラムへ組み込まれてコンパイルされます。
