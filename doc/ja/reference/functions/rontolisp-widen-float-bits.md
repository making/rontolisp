# rontolisp:widen-float-bits

`(rontolisp:widen-float-bits bits format dst &key (start 0))`

パックされた `(unsigned-byte 16)` ベクタ `bits`（`f16` または `bfloat16` の
ビットパターン。`format`（`:float16` または `:bfloat16`）で選択）を、パックされた
浮動小数点配列 `dst`（`single-float` または `double-float`、任意の階数）へ、フラット
インデックス `start` から行優先で拡張します。`dst` を返します。

これは `rontolisp:bits-float16`/`rontolisp:bits-bfloat16` のバルク版です。公開されて
いるチェックポイントのテンソルは 1 要素ずつではなく、16 ビットパターンのベクタ丸ごと
として届きます。1 要素ずつ拡張していては要素ごとに関数呼び出しの境界コストがかかって
しまいます。各要素はスカラー版のプリミティブとまったく同じ結果になります。拡張は
両方式とも厳密かつ全域的なので、決して丸めません。

```lisp
(let* ((bits (make-array 3 :element-type '(unsigned-byte 16)
                          :initial-contents (list (rontolisp:float16-bits 1.0)
                                                   (rontolisp:float16-bits -2.5)
                                                   (rontolisp:float16-bits 100.0))))
       (dst (make-array 5 :element-type 'single-float :initial-element 0.0)))
  (rontolisp:widen-float-bits bits :float16 dst :start 2)
  (list (aref dst 0) (aref dst 1) (aref dst 2) (aref dst 3) (aref dst 4)))
; => (0.0 0.0 1.0 -2.5 100.0)
```

`:start` は、チャンク単位で読み込んだ結果をテンソル全体の宛先の途中に置くための
ものです。パターンについては
[checkpoint:stage-float-bits](checkpoint-stage-float-bits.md) を参照してください。
インタプリタ、JVM、両方の WASM バックエンドで動作します。`--no-gc` にはパックされた
浮動小数点配列のモデルがなく、コンパイル時に拒否されます。`rontolisp:narrow-float-bits`
が逆方向です。
