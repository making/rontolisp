# coerce

`(coerce object result-type)`

`object` を指定されたシーケンス型、浮動小数点数型、複素数型、実数型、または関数型に変換します。`result-type` には `'list`、`'vector`、`'string` (およびそれらの `simple-`/`base-` 表記)、`'(vector t)` や `'(string 8)` のような複合指定、浮動小数点数型 (`'float`、`'single-float`、`'double-float`、`'short-float`、`'long-float` — すべて単一の double 表現)、`'complex` (複素数はそのまま答え、実数は自身の [`complex`](complex.md) を答えます。完全なものは降格するので `(coerce 5 'complex)` は `5`、`(coerce 5.0 'complex)` は `#C(5.0 0.0)` です)、`'(complex part-type)` (両部分を先に強制するので `(coerce 5 '(complex single-float))` は `#C(5.0 0.0)` です)、`'real` (実数はそのまま答え、それ以外は捕捉可能な型エラーを通知します)、`'function` (関数はそのまま返され、シンボルは [`symbol-function`](symbol-function.md) を通じて解決され、リテラルの lambda リストはクロージャになります)、あるいは `t` (恒等変換) を指定できます。計算された結果型も受理され、実行時にまさにそれらのファミリの中でディスパッチします。したがって `type` を変数に持つ `(coerce seq type)` はリテラル形式と同じ振る舞いになります。引数なしの [`deftype`](../macros/deftype.md) 名は、まずその展開先へ解決されます。`'string` を結果とするには文字のシーケンスが必要です。すでに要求された型の値はそのまま返されます — ただし `'simple-string` を結果とする場合の「その型」は simple な文字列だけなので、フィルポインタ付きや adjustable の文字ベクタは作り直されます。`#'coerce` も同じ計算された型のディスパッチを持つ関数値です。

ベクタの `result-type` が `(unsigned-byte 8)`、`(unsigned-byte 16)`、`(unsigned-byte 32)` の要素型を綴っている場合 — `'(vector (unsigned-byte 8))`、`'(simple-array (unsigned-byte 32) (*))` — [`make-array`](make-array.md) や [`concatenate`](concatenate.md) と同じ特殊化ベクタを構築します。`array-element-type` はその要素型を返し、対応する `simple-array` 指定子に対する `typep` は真になります。要素は要素幅にマスクされて格納されます。それ以外の要素型では要素型 `t` の一般ベクタになります。ルックアップテーブルは通常この綴りで書かれますが、要素がすべてリテラルのテーブルはコンパイル系のバックエンドではコンパイル時に構築されます。

ベクタの `result-type` がパックされた浮動小数点要素型を綴っている場合 — `'(vector single-float)`、`'(simple-array double-float (*))`、`'(array bfloat16)` — その幅のパック浮動小数点配列を構築します。これは [`make-array`](make-array.md) が作るものと同じ表現で、`array-element-type` はその要素型を返します。要素は配列自身の幅で格納されるため、整数の要素は浮動小数点数になり、より広い幅のものは丸められます。実数でない要素はエラーです。`bfloat16` の配列はインタプリタと JVM バックエンドにのみ存在します。

ベクタの `result-type` が `character` を綴っている場合 — `'(vector character)` — 代わりに文字列を構築します。これは `make-array` の `:element-type 'character` が作るものと同じ表現で、`array-element-type` は `character` を返し、`stringp` は真になり、印字形式も普通の文字列です。

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
(coerce '(1 2) '(vector single-float)) ; => #f(1.0 2.0)
(array-element-type (coerce '(1.0) '(simple-array double-float (*)))) ; => DOUBLE-FLOAT
```

```lisp
(coerce '(#\a #\b) '(vector character)) ; => "ab"
(array-element-type (coerce '(#\a #\b) '(vector character))) ; => CHARACTER
```

```lisp
(coerce 1/4 'double-float) ; => 0.25
```

```lisp
(coerce 5 'complex) ; => 5
```

```lisp
(coerce 5.0 'complex) ; => #C(5.0 0.0)
```

```lisp
(coerce 5 '(complex single-float)) ; => #C(5.0 0.0)
```

```lisp
(funcall (coerce 'car 'function) '(1 2 3)) ; => 1
```

```lisp
(defun convert (seq type) (coerce seq type))
(convert (vector 1 2) 'list) ; => (1 2)
```
