# rontolisp:alist-plist

`(rontolisp:alist-plist alist)`

連想リスト `alist` と同じキー・値を同じ順序で保持するプロパティリストを返します —
[`rontolisp:plist-alist`](rontolisp-plist-alist.md) の逆です。
`alexandria:alist-plist` の軽量なサブセットなので、プログラムをそのまま
alexandria に切り替えられます。

```lisp
(rontolisp:alist-plist '((:a . 1) (:b . 2)))   ; => (:a 1 :b 2)
```

[`rontolisp:hash-table-plist`](rontolisp-hash-table-plist.md) と違い間に
ハッシュテーブルを挟まないため、順序は入力どおり(すべてのバックエンドで
決定的)であり、重複キーもまとめられずに残ります。空リストは `nil` を返します。

## バックエンドサポート

すべてのバックエンド・すべての WASM モード(Preview 1 含む)で動作します:
この関数は rontolisp 自身で書かれており(プレリュードの一部)、使用時に
プログラムへ組み込まれてコンパイルされます。
