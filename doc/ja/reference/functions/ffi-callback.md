# ffi:callback

`(ffi:callback function return-type argument-types)`

Lisp の関数を指定した形の C 関数ポインタに変換し、C 側から Lisp を
呼び戻せるようにする。スタブの寿命はプログラム全体。コールバックから例外が抜けると
上位の C フレームへ巻き戻ってプロセスが終わってしまうため、それは起こらない ---
メッセージを表示し、宣言した型のゼロ値を返す。コールバックの形に `:string` と構造体型は
使えない (`:pointer` を受け取ること)。

```lisp
(ffi:pointerp (ffi:callback (lambda (a b) (- a b)) :int '(:int :int)))
; => T
```

コールバックを定義し直すと新しいアドレスになる。スタブは差し替えられないので、古いアドレスを保持している C 側は古い関数を呼び続ける。
