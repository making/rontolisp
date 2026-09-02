# with-input-from-string

`(with-input-from-string (stream string) body...)`

`stream` を `string` から読み取る入力ストリームに束縛して本体フォームを評価し、最後のフォームの値を返します。`read-line` は文字列を 1 行ずつ消費し、終端で nil を返します。`read` は 1 つのデータをパースしてストリームをその直後に残すため、繰り返し呼べば文字列をデータム単位でたどれ、行内の最初のデータ以降が失われることはありません。3 つすべてのバックエンドで動作します。

```lisp
(with-input-from-string (s "(1 2 3)")
  (read s)) ; => (1 2 3)
```

束縛する変数名を `*standard-input*` にすると、本体の間だけストリーム引数なしの
read 系関数がまとめてリダイレクトされます。呼び出した関数の中も含まれます。
`read-line` / `read-char` / `read` / `peek-char` は呼び出し時点の (動的に束縛
された) `*standard-input*` の値を読むためです。ストリーム引数に `nil` を渡した
場合も同じ指定子として扱われるので、自分の optional 引数を転送するリーダーも
リダイレクトに追従します。プロセスの標準入力を常に指すのは `t` だけです。

```lisp
(progn
  (defun next-line (&optional stream) (read-line stream))
  (with-input-from-string (*standard-input* "from the string")
    (next-line))) ; => "from the string"
```
