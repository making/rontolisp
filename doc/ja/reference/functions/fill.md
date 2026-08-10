# fill

`(fill sequence item &key start end)`

`sequence` の `:start`（デフォルト 0）から `:end`（デフォルトは長さ）までの各要素に `item` を格納し、そのシーケンスを返します。CL と同じく破壊的です: ベクタ（一般のもの、パックド `(unsigned-byte 8|16|32)` や浮動小数点ベクタ、[`make-string`](make-string.md) や [`make-array`](make-array.md) の `:element-type 'character` で確保した文字列）はその場で書き換えられ、リストも同様です。文字列リテラルも渡せますが、コンパイル系バックエンドではイミュータブルな値なので、その場での変更ではなく新しい文字列を返します（インタプリタはその場で書き換えます）— [`replace`](replace.md) と同じ差異です。`--no-gc` 以外のすべてのバックエンドで利用できます。

```lisp
(fill (make-array 5 :element-type '(unsigned-byte 8) :initial-element 9) 0 :start 1 :end 4) ; => #(9 0 0 0 9)
(fill (list 1 2 3) 7) ; => (7 7 7)
```
