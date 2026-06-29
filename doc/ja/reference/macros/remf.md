# remf

`(remf place indicator)`

`place` に格納されたプロパティリストから、`indicator` に一致する最初のキー／値のペアを削除し、`place` をその場で更新します。一致するペアが見つかって削除された場合は `t` を、それ以外の場合は nil を返します。plist を変更すると同時に変化があったかどうかを返すため、結果を確認するには後で place を調べてください。

```lisp
(let ((p (list :a 1 :b 2))) (remf p :a) p) ; => (:b 2)
```
