# nstring-upcase nstring-downcase nstring-capitalize

`(nstring-upcase string)` -- `(nstring-downcase string)` -- `(nstring-capitalize string)`

[`string-upcase`](string-upcase.md)、[`string-downcase`](string-downcase.md)、[`string-capitalize`](string-capitalize.md) の破壊的な綴りです。変換後の文字を引数へ書き戻し、その文字列を返します。変換規則は非破壊版と同一なので、戻り値はどのバックエンドでも同じです。

書き込みが実際に行われるのは **可変な文字ベクタ**（[`make-string`](make-string.md) や `(make-array n :element-type 'character)` が作るもの）の場合です。同一オブジェクトが返り、呼び出し側が保持している参照からも変更が見えます。

```lisp
(let ((s (make-string 3 :initial-element #\a)))
  (list (eq s (nstring-upcase s)) s))
; => (T "AAA")
```

**不変** な文字列の場合、コンパイル系バックエンドは書き込みではなく再構築を行います。これは添字書き込み（`(setf (aref s i) c)`、[`replace`](replace.md)、[`fill`](fill.md)）が共通して持つ差異で、呼び出し側の参照はそのまま残り、インタプリタでは変換されます。移植性のあるコードは戻り値を使ってください。戻り値は 4 バックエンドすべてで正しい値です。

```lisp
(nstring-upcase (copy-seq "hello world")) ; => "HELLO WORLD"
```

変換は文字列全体に及びます。非破壊版と同様、`:start` / `:end` は受け付けません。いずれも第一級の関数値なので、`#'nstring-upcase` を `funcall`・`mapcar`・`intern` に渡せます。

## バックエンド対応

4 バックエンドすべて。rontolisp ソースによる 1 つの定義で、参照されたプログラムにのみ差し込まれます。
