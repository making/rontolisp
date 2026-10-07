# fdefinition

`(fdefinition function-name)`

関数名の関数値を返します。関数名はシンボル ([`symbol-function`](symbol-function.md) と同じ) か、`(defun (setf name) ...)` が定義する関数を指す `(setf name)` リストです。未定義の名前は `undefined-function` を通知し、その `cell-error-name` はリストも含めて名前そのものです。

クォートされたシンボルリテラル (`(fdefinition 'car)`) はコンパイラではコンパイル時に解決されます。実行時に計算されたシンボルはコンパイル済み名前レジストリを通じて遅延解決され、同じ関数値を返します — [`symbol-function`](symbol-function.md) と同じで、相違点はありません。クォートされた `(setf name)` リストは全バックエンドで `#'(setf name)` と同じです。実行時に組み立てた `(setf name)` リストはインタプリタだけが受け付け、コンパイルバックエンドは受け付けません。

```lisp
(funcall (fdefinition 'car) '(1 2 3)) ; => 1
```

```lisp
(defun (setf fd-first) (value list) (setf (car list) value))
(let ((l (list 1 2)))
  (funcall (fdefinition '(setf fd-first)) 9 l)
  l) ; => (9 2)
```

`fdefinition` は [`symbol-function`](symbol-function.md) と同じ `setf` の place です: `(setf (fdefinition 'name) fn)` は `fn` をそのシンボルのグローバルな関数定義としてインストールし、`(setf (fdefinition '(setf name)) fn)` は `(setf name)` 関数をインストールします。
