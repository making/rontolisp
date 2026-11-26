# rontolisp:quantized-rows

`(rontolisp:quantized-rows matrix rows)`

量子化行列（[`rontolisp:quantize`](rontolisp-quantize.md)）から、行インデックスの
リスト `rows` が指す行を集めて、同じフォーマットの新しい階数 2 の行列を作ります。
結果の第 `i` 行は `matrix` の第 `(nth i rows)` 行です。Q8_0 行列の 1 行はちょうど
34 バイトのブロック `cols / 32` 個ぶんなので、この収集は**ブロックをそのまま移動**
します。1 行につき 1 回の配列コピーで、逆量子化も再量子化もせず、結果のバイト列は
元のバイト列そのものです。この型にとっての `subseq` と
[`linalg:take-rows`](linalg-take-rows.md) を兼ねます。連続した範囲は連番の
インデックスで表します。

```lisp
(let* ((w (make-array '(4 32) :element-type 'single-float :initial-element 1.0))
       (m (rontolisp:quantize w 'q8-0))
       (g (rontolisp:quantized-rows m '(3 1))))
  (list g (array-dimensions g) (= (aref g 0 7) (aref m 3 7)) (= (aref g 1 7) (aref m 1 7))))
; => (#<quantized-matrix q8-0 (2 32)> (2 32) T T)
```

元の行列は階数 1（1 行の行列）でも構いません。結果は常に階数 2 なので、
`(rontolisp:quantized-rows m '())` は行が 0 個の行列です。範囲外のインデックスは
エラーになり、整数のリスト以外もエラーになります。

これが、量子化したまま重み行列を分割する方法です。たとえばヘッドごとに
`query | gate` が交互に並ぶ q_proj の分割に使います。逆量子化してから量子化し直す
方法も Q8_0 なら厳密ですが、WASM 向けにもコンパイルされるプログラムはその 2 つを
**名前として書けません**。

インタプリタと JVM のみ。WASM バックエンドでは呼び出し時にエラーを通知するので、
リーダの Q8_0 分岐はどこでもコンパイルでき、到達したときだけ拒否されます。
