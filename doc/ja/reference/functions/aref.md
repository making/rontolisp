# aref

`(aref array &rest subscripts)`

指定された 0 始まりの添字 (次元ごとに 1 つ: ランク 0 の配列には 0 個、ランク 1 のベクタには 1 つ、ランク 2 の配列には 2 つ、以降も同様) における `array` の要素を返します。文字列はランク 1 の文字配列なので、`(aref s i)` は [`char`](char.md) と同じ読み取りになります（文字列要素への書き込みは `schar`/`char` の setf 場所を使います）。ランクに依存しないフラットなアクセスには [`row-major-aref`](row-major-aref.md) が使えます。要素を変更するには、`aref` を `setf` の場所として使います: `(setf (aref array i j) value)`。これは `incf`/`decf`/`push` でも機能します。`#'aref` は第一級の関数値なので、他の関数と同じように `mapcar`/`funcall` に渡せます。

各添字はそれぞれの次元の範囲内でなければなりません。範囲外の添字は、その添字を datum とし `(integer 0 (d))`(`d` はその次元)を期待型とする `type-error` を通知し、すべてのバックエンドで `AREF: The value 3 is not of type (INTEGER 0 (3))` と報告されます(wasm-GC バックエンドでは捕捉フォームを含むプログラムの場合。含まなければトラップします)。列が次元を超えていれば、行優先で畳み込んだ添字が範囲内でも範囲外です。格納は、格納する値を評価・検査したあとに `(SETF AREF)` として報告します。`array` が配列でなければ、期待型を `array` とする `type-error` を通知します: `AREF: The value 5 is not of type ARRAY`。添字の個数が配列のランクと異なれば、その配列を datum とし、添字の個数をランクとする配列を期待型とする `type-error` を通知します。期待型は添字 1 つなら `vector`、0 個なら `(array * nil)`、2 つなら `(array * (* *))` です。2x2 の配列に対する `(aref m 1)` は `AREF: The value #2A((1 2) (3 4)) is not of type VECTOR` と報告し、格納は `(SETF AREF)` として報告します(wasm-GC バックエンドでは、これも捕捉フォームを含むプログラムの場合)。引数はすべて先に評価されます。そのあと各添字の型、配列とそのランク、格納する値、範囲の順に検査します。`#'aref` も同じ順に検査します。

```lisp
(let ((a (make-array 3 :initial-element 0)))
  (setf (aref a 1) 9)
  (aref a 1)) ; => 9
(aref (make-array nil :initial-element 5)) ; => 5
(handler-case (aref (make-array '(2 3) :initial-element 0) 0 3)
  (type-error (e) (list (princ-to-string e) (type-error-expected-type e))))
; => ("AREF: The value 3 is not of type (INTEGER 0 (3))" (INTEGER 0 (3)))
(handler-case (aref (make-array '(2 2) :initial-element 0) 1)
  (type-error (e) (type-error-expected-type e)))
; => VECTOR
```
