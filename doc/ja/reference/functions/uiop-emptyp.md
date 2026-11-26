# uiop:emptyp

`(uiop:emptyp x)`

`x` が `nil`、または長さ 0 のベクタ（文字列を含む）のとき `t` を、それ以外は `nil` を
返します。UIOP の一行定義を上流からそのまま持ってきたものです:

```lisp
(defun uiop:emptyp (x)
  (or (null x) (and (vectorp x) (zerop (length x)))))
```

つまり「中身がない」ことを、空の値が取り得る 2 つの形について答えるだけで、
シーケンスでない値が空でないとはどういうことかまでは決めません — 数値は単に空では
ありません。

```lisp
(list (uiop:emptyp nil) (uiop:emptyp "") (uiop:emptyp "ab"))   ; => (T T NIL)
```

## バックエンドサポート

4 つすべてのバックエンドで動作します: rontolisp 自身で書かれた uiop ライブラリの定義であり、
使用時にプログラムへ組み込まれてコンパイルされます。
