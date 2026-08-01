# class-name

`(class-name class)`

クラスメタオブジェクト — [`find-class`](find-class.md) や [`class-of`](class-of.md) が返すもの — の名前シンボルを返します。引数がクラスメタオブジェクトでない場合はエラーをシグナルします。

```lisp
(class-name (class-of "hello")) ; => STRING
```
