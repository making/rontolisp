# documentation

`(documentation name doc-type)` / `(setf (documentation name doc-type) docstring)`

簡易版: docstring はどこにも保存されないため、読み取りは `nil` を返し、`setf` 形式は docstring を値としつつ破棄します。ロード時にドキュメントを付与するライブラリ(`(setf (documentation 'f 'function) "...")`)がそのままロードできるように受理されます。

```lisp
(defun greet () "hi")
(setf (documentation 'greet 'function) "Says hi.") ; => "Says hi."
(documentation 'greet 'function) ; => nil
```
