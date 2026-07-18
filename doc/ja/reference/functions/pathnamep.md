# pathnamep

`(pathnamep object)`

常に nil を返します。rontolisp に pathname 型はなく (パスは通常の文字列です)、pathname であるオブジェクトは存在しません。`pathname` を含む移植性のある型ディスパッチがコンパイルでき、他の分岐を通るために存在します。

```lisp
(pathnamep "/tmp/data.json") ; => nil
```
