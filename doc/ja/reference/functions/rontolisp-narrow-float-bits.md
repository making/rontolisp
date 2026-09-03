# rontolisp:narrow-float-bits

`(rontolisp:narrow-float-bits src format dst &key (start 0))`

パックされた浮動小数点配列 `src`（`single-float` または `double-float`、任意の
階数）を、パックされた `(unsigned-byte 16)` ベクタ `dst`（`f16` または `bfloat16`
のビットパターン。`format`（`:float16` または `:bfloat16`）で選択）へ、`src` の
フラットインデックス `0` から `dst` のフラットインデックス `start` へ行優先で
狭めます。`dst` を返します。

これは `rontolisp:float16-bits`/`rontolisp:bfloat16-bits` のバルク版であり、
`rontolisp:widen-float-bits` の逆方向です。チェックポイントをより狭い幅で書き
戻す際に使います。各要素は対応するスカラー版のプリミティブとまったく同じ丸め
（両方式とも **最近接偶数丸め**）になります。

```lisp
(let* ((src (make-array 3 :element-type 'single-float
                         :initial-contents (list 1.0 -2.5 100.0)))
       (dst (make-array 3 :element-type '(unsigned-byte 16))))
  (rontolisp:narrow-float-bits src :bfloat16 dst)
  (list (aref dst 0) (aref dst 1) (aref dst 2)))
; => (16256 49184 17096)
```

インタプリタ、JVM、両方の WASM バックエンドで動作します。`--no-gc` にはパックされた
浮動小数点配列のモデルがなく、コンパイル時に拒否されます。
