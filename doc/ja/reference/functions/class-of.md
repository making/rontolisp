# class-of

`(class-of object)`

lite 版: CLOS インスタンスにはクラスタグのシンボルを、それ以外の値には組み込み型名のシンボル (`integer`、`string`、`cons` など) を返します。クラスメタオブジェクトではなく名前です (rontolisp に MOP はありません)。

```lisp
(class-of 42) ; => integer
```
