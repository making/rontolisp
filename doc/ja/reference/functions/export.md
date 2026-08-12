# export

`(export symbols &optional package)`

`symbols` (シンボル、またはそのリスト) を `package` (既定では現在のパッケージ) の**外部シンボル**にします。これにより [`use-package`](use-package.md) を通じて修飾なしで見えるようになり、コロン2つではなく1つで綴られます。`t` を返します。[`defpackage`](../special-forms/defpackage.md) の `:export` 節の実行時形式であり、[`unexport`](unexport.md) が逆操作です。

ここではパッケージは読み込み/コンパイル時に解決されるため ([パッケージ](../packages.md) を参照)、リテラルなトップレベル呼び出しは `in-package` と同様にコンパイル時に消費され、それ以降のフォームに対して効力を持ちます。これがすべてのバックエンドで動作する理由です。実行時に計算する呼び出し (実行時に構築したシンボルリスト) はインタプリタでのみ動作します。

**定義より前に export してください。** ここではシンボルは正規の綴りで同定され、export するとその綴りが `pkg::name` から `pkg:name` に変わります。export より*前*に行った `defun` は内部の綴りのままなので、後の `pkg:name` の呼び出し側からは見つかりません。`export` はファイルの先頭に置くか、`defpackage` の `:export` 節を使ってください。

```lisp
(defpackage #:greeter2 (:use #:cl))
(in-package #:greeter2)
(export '(hello))
(defun hello () "hi")
(in-package #:cl-user)
(greeter2:hello) ; => "hi"
```
