# rontolisp:plist-alist

`(rontolisp:plist-alist plist)`

プロパティリスト `plist`(奇数番目の要素がキー、偶数番目の要素が値)と同じ
キー・値を同じ順序で保持する連想リストを返します。
[`rontolisp:alist-plist`](rontolisp-alist-plist.md) の逆であり、
`alexandria:plist-alist` の軽量なサブセットなので、プログラムをそのまま
alexandria に切り替えられます。

```lisp
(rontolisp:plist-alist '(:a 1 :b 2))   ; => ((:A . 1) (:B . 2))
```

[`rontolisp:plist-hash-table`](rontolisp-plist-hash-table.md) と違い間に
ハッシュテーブルを挟まないため、順序は入力どおり(すべてのバックエンドで
決定的)であり、重複キーもまとめられずに残ります:

```lisp
(rontolisp:plist-alist '(:a 1 :a 9))   ; => ((:A . 1) (:A . 9))
```

## バックエンドサポート

すべてのバックエンド・すべての WASM モード(Preview 1 含む)で動作します:
この関数は rontolisp 自身で書かれており(プレリュードの一部)、使用時に
プログラムへ組み込まれてコンパイルされます。
