# export

`(export symbols &optional package)`

`symbols` (シンボル、またはそのリスト) を `package` (既定では現在のパッケージ) の**外部シンボル**にします。これにより [`use-package`](use-package.md) を通じて修飾なしで見えるようになり、コロン2つではなく1つで綴られます。`t` を返します。[`defpackage`](../special-forms/defpackage.md) の `:export` 節の実行時形式であり、[`unexport`](unexport.md) が逆操作です。

ここではパッケージは読み込み/コンパイル時に解決されるため ([パッケージ](../packages.md) を参照)、リテラルなトップレベル呼び出しは `in-package` と同様にコンパイル時に消費され、それ以降のフォームに対して効力を持ちます。これがすべてのバックエンドで動作する理由です。実行時に計算する呼び出し (実行時に構築したシンボルリスト) はインタプリタでのみ動作します。

export が変えるのは*アクセス可能性*だけなので、公開する定義の前でも後でも構いません。関数を定義してファイルの末尾で export するという Common Lisp の日常的な書き方が動作します。

```lisp
(defpackage #:greeter2 (:use #:cl))
(in-package #:greeter2)
(export '(hello))
(defun hello () "hi")
(in-package #:cl-user)
(greeter2:hello) ; => "hi"
```

```lisp
(defpackage #:greeter3 (:use #:cl))
(defun greeter3::hi () "hi")
(export '(greeter3::hi) :greeter3)
(greeter3:hi) ; => "hi"
```

`export` より*前*に書いた参照は、Common Lisp と同様にエラーです。その時点ではまだ外部シンボルではありません。

1点の相違: 最初に名前が現れた後で export されたシンボルは、コロン2つ (`greeter3::hi`) で表示されます。ここでは修飾子は表示時に計算されるのではなく、シンボルに保持されているためです。どちらの綴りも同じシンボルを指します。
