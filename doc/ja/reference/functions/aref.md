# aref

`(aref array &rest subscripts)`

指定された 0 始まりの添字 (次元ごとに 1 つ: ランク 0 の配列には 0 個、ランク 1 のベクタには 1 つ、ランク 2 の配列には 2 つ、以降も同様) における `array` の要素を返します。文字列はランク 1 の文字配列なので、`(aref s i)` は [`char`](char.md) と同じ読み取りになります（文字列要素への書き込みは `schar`/`char` の setf 場所を使います）。ランクに依存しないフラットなアクセスには [`row-major-aref`](row-major-aref.md) が使えます。要素を変更するには、`aref` を `setf` の場所として使います: `(setf (aref array i j) value)`。これは `incf`/`decf`/`push` でも機能します。`#'aref` は第一級の関数値なので、他の関数と同じように `mapcar`/`funcall` に渡せます。

各添字はそれぞれの次元の範囲内でなければなりません。範囲外の添字は、その添字を datum とし `(integer 0 (d))`(`d` はその次元)を期待型とする `type-error` を通知し、すべてのバックエンドで `AREF: The value 3 is not of type (INTEGER 0 (3))` と報告されます(wasm-GC バックエンドでは捕捉フォームを含むプログラムの場合。含まなければトラップします)。列が次元を超えていれば、行優先で畳み込んだ添字が範囲内でも範囲外です。格納は、格納する値を評価・検査したあとに `(SETF AREF)` として報告します。

```lisp
(let ((a (make-array 3 :initial-element 0)))
  (setf (aref a 1) 9)
  (aref a 1)) ; => 9
(aref (make-array nil :initial-element 5)) ; => 5
(handler-case (aref (make-array '(2 3) :initial-element 0) 0 3)
  (type-error (e) (list (princ-to-string e) (type-error-expected-type e))))
; => ("AREF: The value 3 is not of type (INTEGER 0 (3))" (INTEGER 0 (3)))
```
