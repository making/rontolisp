# rontolisp:quantize

`(rontolisp:quantize array format)`

パックされた浮動小数点配列（`single-float`、`double-float`、`bfloat16`。階数 1 か 2、
最後の次元は 32 の倍数）を `format` の**量子化行列**に量子化します。形式は `q8-0`、
ggml の `Q8_0` の一つだけです。32 要素のブロックごとに binary16 のスケール `d` と
符号付き 8 ビットの量子 32 個を持ち、値は `q * d`、1 要素あたり 1.0625 バイトです。
算術は ggml 自身の `quantize_row_q8_0_ref` そのもの（ブロックの f32 絶対値最大、
`d = amax / 127`、各量子は `round(x / d)` で同点は 0 から遠い側へ、`d` は binary16 に
丸める）なので、同じ値に対して `llama-quantize` が書くバイト列を保持し、
`write-sequence` した結果は `llama.cpp` が読み戻せる Q8_0 テンソルです。

量子化行列は配列ではなく独自の型です。`aref` と `row-major-aref` は逆量子化した
`q * d` を double で返し、`(setf aref)` はエラーになります（要素には自分のスロットが
なく、1 要素を書くにはブロックごと再量子化が要る）。`array-dimensions` /
`array-rank` / `array-total-size` は使え、`array-element-type` は形式シンボル `q8-0`
を返し、`arrayp` は `nil`、
[`rontolisp:quantized-matrix-p`](rontolisp-quantized-matrix-p.md) と
`(typep x 'rontolisp:quantized-matrix)` が判定します。印字は
`#<quantized-matrix q8-0 (rows cols)>` で、リテラル構文はありません。

```lisp
(let ((w (make-array '(2 32) :element-type 'single-float :initial-element 0.0)))
  (dotimes (j 32)
    (setf (aref w 0 j) (* 4.0 j))
    (setf (aref w 1 j) (- 127.0 (* 4 j))))
  (setf (aref w 0 31) 127.0)
  (let ((m (rontolisp:quantize w 'q8-0)))
    (list m (array-dimensions m) (array-element-type m) (aref m 0 4) (aref m 1 1)
          (rontolisp:quantized-matrix-p m) (arrayp m))))
; => (#<quantized-matrix q8-0 (2 32)> (2 32) Q8-0 16.0 123.0 T NIL)
```

上の 2 行は絶対値最大が 127 なので `d` はちょうど 1 で、すべての値がそのまま残ります。
一般には値は最大で量子の半分、`amax / 254` だけ動きます。

[`vec:matvec`](../../guides/simd-acceleration.md) と `vec:matvec-into` は行列として量子化行列を受け取り、`#f`
または `#d` のベクタに対して ggml の整数内積の形（活性値を 32 要素ブロックごとに
int8 へ量子化し、ブロックごとに厳密な整数内積、ブロックごとに 1 回の double 積和）で
計算します。これはインタプリタと JVM、`--simd` / `--parallel` の有無を問わずビット
単位で同じ値です。他の `vec:` / `linalg:` 操作はパックされた浮動小数点配列を要求する
ので、先に [`rontolisp:dequantize`](rontolisp-dequantize.md) してください（例外は
`linalg:row` で、量子化行列の 1 行をそのまま `#f` ベクタに読み出します）。量子化した
積は f32 の積に対して量子化誤差（公開重みで相対 8e-3 程度）だけ離れており、丸め誤差
ではありません。Q8_0 モデルが出す数は、同じファイルから `llama.cpp` が出す数であって、
BF16 ファイルが出す数ではありません。

インタプリタと JVM のみ。両方の WASM バックエンドは `rontolisp:quantize` と
`rontolisp:dequantize` をコンパイル時に拒否します。`--gpu` と `--blas` はこの型を
辞退し、レーンカーネルかスカラー defun が答えます。`gguf:read` は Q8_0 テンソルから
この関数を経由せずに量子化行列を作ります。
