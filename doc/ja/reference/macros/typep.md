# typep

`(typep object 'type-specifier)`

`object` が指定した型かどうかを判定します。lite 版: 型指定子は通常リテラル (クオートされた) 型です — [`typecase`](typecase.md) がサポートするのと同じ集合 (アトミックな型名、登録済みクラス、引数なしのユーザー [`deftype`](deftype.md) 名、複合指定子 `(or ...)`/`(and ...)`/`(not ...)`/`(member ...)`/`(eql ...)`/`(satisfies ...)`/範囲付き数値型/`(unsigned-byte n)`/`(signed-byte n)`/配列系) です。未知の指定子は何にもマッチしません。

配列系とは `(array ELEMENT-TYPE DIMENSIONS)`、`(simple-array ELEMENT-TYPE DIMENSIONS)`、`(vector ELEMENT-TYPE SIZE)`、`(simple-vector SIZE)` — [`type-of`](../functions/type-of.md) が組み立てる指定子そのものです。両方の要素が検査されます: 要素型は配列の昇格後の [`array-element-type`](../functions/array-element-type.md) と、次元は配列自身の次元と比較されます。`DIMENSIONS` にはリスト (どの位置の `*` も「任意のサイズ」を意味します)、ランクを表す整数、ランク 0 配列を表す `nil`、または `*` を書けます。`vector` 系の 2 つの綴りはランクを 1 に固定します。

`simple-` 系の綴りはエイリアスではなく、厳密に小さい型です: `simple-array`、`simple-vector`、`simple-string` は値が simple であること — フィルポインタを持たず、`:adjustable` でもなく、displaced でもないこと — を追加で要求します。`array`、`vector`、`string` はどちらでも受け付けます。`simple-vector` はちょうど `(simple-array t (*))` なので、文字列やパックされたベクタは simple-vector ではありません。

```lisp
(let ((a (make-array 4 :fill-pointer 0)))
  (list (typep a 'vector) (typep a 'simple-vector) (typep a 'simple-array))) ; => (T NIL NIL)
```

サイズ付きの文字列指定子 — `(string n)`、`(simple-string n)`、および `(vector character n)` / `(simple-array character (n))` という綴り — が比較するのは配列の次元であって [`length`](../functions/length.md) ではありません。文字ベクタの `length` はフィルポインタなので、1 文字だけ入った容量 4 のベクタは `(string 1)` ではなく `(string 4)` です。

```lisp
(let ((s (make-array 4 :element-type 'character :fill-pointer 0)))
  (vector-push #\a s)
  (list (length s) (typep s '(string 4)) (typep s '(string 1)))) ; => (1 T NIL)
```

実行時に計算された指定子もサポートされ、受け付ける集合は同じです: アトミックな型名 (登録済みのクラス / 構造体 / コンディション、または組み込みの型名)、クラスメタオブジェクト ([`find-class`](../functions/find-class.md) や [`class-of`](../functions/class-of.md) が返すものは自分自身のクラスを指し示します)、そして上記の複合指定子です。複合指定子の場合、先頭要素と引数はコンパイル時に畳み込むのではなく指定子の値そのものから読み取られます。したがって `(typep a (type-of a))` はどの配列の形でも `T` を返します。`class` はすべてのクラスメタオブジェクトが属するクラスなので、`(typep x 'class)` が「これはクラスか?」の判定になります。

```lisp
(typep 5 '(unsigned-byte 8)) ; => T
```

```lisp
(typep 500 '(unsigned-byte 8)) ; => NIL
```

```lisp
(let ((a (make-array 4)))
  (list (type-of a) (typep a (type-of a)))) ; => ((SIMPLE-VECTOR 4) T)
```

```lisp
(defclass animal () ())
(defclass dog (animal) ())
(list (typep (make-instance 'dog) (find-class 'animal))
      (typep (find-class 'dog) 'class)
      (typep 42 'class)) ; => (T T NIL)
```
