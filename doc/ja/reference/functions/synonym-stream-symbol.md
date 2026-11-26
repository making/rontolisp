# synonym-stream-symbol

`(synonym-stream-symbol stream)`

シノニムストリームの転送先シンボル、すなわち [`make-synonym-stream`](make-synonym-stream.md) に渡した引数を返します。`stream` がシノニムストリームでない場合はシグナルを発生させます。

```lisp
(synonym-stream-symbol (make-synonym-stream '*standard-output*)) ; => *STANDARD-OUTPUT*
```
