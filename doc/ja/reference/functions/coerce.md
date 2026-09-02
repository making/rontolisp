# coerce

`(coerce object result-type)`

`object` を指定されたシーケンス型または浮動小数点数型に変換します。`result-type` には `'list`、`'vector`、`'string` (およびそれらの `simple-`/`base-` 表記)、`'(vector t)` や `'(string 8)` のような複合指定、浮動小数点数型 (`'float`、`'single-float`、`'double-float`、`'short-float`、`'long-float` — すべて単一の double 表現)、あるいは `t` (恒等変換) を指定できます。計算された結果型も受理され、実行時にまさにそれらのファミリの中でディスパッチします。したがって `type` を変数に持つ `(coerce seq type)` はリテラル形式と同じ振る舞いになります。引数なしの [`deftype`](../macros/deftype.md) 名は、まずその展開先へ解決されます。`'string` を結果とするには文字のシーケンスが必要です。すでに要求された型の値はそのまま返されます — ただし `'simple-string` を結果とする場合の「その型」は simple な文字列だけなので、フィルポインタ付きや adjustable の文字ベクタは作り直されます。`coerce` は第一級の関数値ではありません (`#'coerce` は利用できません) ので、直接呼び出してください。

ベクタの `result-type` が `(unsigned-byte 8)`、`(unsigned-byte 16)`、`(unsigned-byte 32)` の要素型を綴っている場合 — `'(vector (unsigned-byte 8))`、`'(simple-array (unsigned-byte 32) (*))` — [`make-array`](make-array.md) や [`concatenate`](concatenate.md) と同じ特殊化ベクタを構築します。`array-element-type` はその要素型を返し、対応する `simple-array` 指定子に対する `typep` は真になります。要素は要素幅にマスクされて格納されます。それ以外の要素型では要素型 `t` の一般ベクタになります。ルックアップテーブルは通常この綴りで書かれますが、要素がすべてリテラルのテーブルはコンパイル系のバックエンドではコンパイル時に構築されます。

```lisp
(coerce '(1 2 3) 'vector) ; => #(1 2 3)
(coerce (vector 1 2 3) 'list) ; => (1 2 3)
(coerce "ab" 'list) ; => (#\a #\b)
(coerce '(#\a #\b) 'string) ; => "ab"
```

```lisp
(coerce '(1 2 260) '(vector (unsigned-byte 8))) ; => #(1 2 4)
(array-element-type (coerce '(1) '(simple-array (unsigned-byte 32) (*)))) ; => (UNSIGNED-BYTE 32)
```

```lisp
(coerce 1/4 'double-float) ; => 0.25
```

```lisp
(defun convert (seq type) (coerce seq type))
(convert (vector 1 2) 'list) ; => (1 2)
```
