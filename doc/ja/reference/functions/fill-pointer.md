# fill-pointer

`(fill-pointer vector)`

[`make-array`](make-array.md) の `:fill-pointer` で作成されたベクタのフィルポインタを返します。フィルポインタはベクタの実効長です。`length` や印字はフィルポインタで止まりますが、[`aref`](aref.md) はバッキングストレージ全体にアクセスできます。`setf` 可能な場所であり、`(setf (fill-pointer v) n)` で 0 からベクタの総サイズまでの任意の位置に移動できます。フィルポインタを持たない配列 (文字列リテラル、単純ベクタやパックドベクタ、ランク 2 の配列) を渡すと、期待型を `(and vector (satisfies array-has-fill-pointer-p))` とする `type-error` を通知します: `FILL-POINTER: The value "abc" is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))`(事前に [`array-has-fill-pointer-p`](array-has-fill-pointer-p.md) で確認してください)。`vector` がそもそも配列でなければ、期待型を `array` とする `type-error` を通知します: `FILL-POINTER: The value 5 is not of type ARRAY`。wasm-GC バックエンドでは、どちらも捕捉フォームを含むプログラムの場合に限ります。含まなければトラップします。`vector-push`、`vector-push-extend`、`vector-pop` もそれぞれの名前で両方を同じように報告し、格納は `(SETF FILL-POINTER)` として報告します。`adjust-array` など配列の形状を扱う他のアクセサは後者を報告します。格納する値が 0 からベクタの総サイズまでの整数でなければ、期待型を `(integer 0 size)` とする `type-error` を通知します: `(SETF FILL-POINTER): The value 9 is not of type (INTEGER 0 5)`。

```lisp
(defparameter *v* (make-array 5 :fill-pointer 2 :initial-element 0))
(fill-pointer *v*) ; => 2
(setf (fill-pointer *v*) 4) ; => 4
(length *v*) ; => 4
```
