# coerce

`(coerce object result-type)`

`object` を指定されたシーケンス型または浮動小数点数型に変換します。`result-type` には `'list`、`'vector`、`'string` (およびそれらの `simple-`/`base-` 表記)、`'(vector t)` や `'(string 8)` のような複合指定、浮動小数点数型 (`'float`、`'single-float`、`'double-float`、`'short-float`、`'long-float` — すべて単一の double 表現)、あるいは `t` (恒等変換) を指定できます。計算された結果型も受理され、実行時にまさにそれらのファミリの中でディスパッチします。したがって `type` を変数に持つ `(coerce seq type)` はリテラル形式と同じ振る舞いになります。`'string` を結果とするには文字のシーケンスが必要です。すでに要求された型の値はそのまま返されます。`coerce` は第一級の関数値ではありません (`#'coerce` は利用できません) ので、直接呼び出してください。

```lisp
(coerce '(1 2 3) 'vector) ; => #(1 2 3)
(coerce (vector 1 2 3) 'list) ; => (1 2 3)
(coerce "ab" 'list) ; => (#\a #\b)
(coerce '(#\a #\b) 'string) ; => "ab"
```

```lisp
(coerce 1/4 'double-float) ; => 0.25
```

```lisp
(defun convert (seq type) (coerce seq type))
(convert (vector 1 2) 'list) ; => (1 2)
```
