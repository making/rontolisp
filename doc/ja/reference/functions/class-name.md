# class-name

`(class-name class)`

クラスメタオブジェクト — [`find-class`](find-class.md) や [`class-of`](class-of.md) が返すもの — の名前シンボルを返します。引数がクラスメタオブジェクトでない場合はエラーをシグナルします。`(setf (class-name class) name)` はメタオブジェクトの名前を変えます。`find-class` は古い名前に対して引き続きそれを返します。

```lisp
(class-name (class-of "hello")) ; => STRING
```
