# shadowing-import

`(shadowing-import symbols &optional package)`

`symbols`(シンボル、またはそのリスト)を [`import`](import.md) と同様に `package`(省略時はカレントパッケージ)にインポートしますが、同名のシンボルがすでに存在しても衝突を通知せず**押しのけます**。押しのけられたシンボルはパッケージからアンインターンされ(自前のシンボルなら [`unintern`](unintern.md) 後と同様にホームを失います)、インポートされた各名前はパッケージのシャドーイングシンボルに加わるため、use リストが何を持ち込んでもアクセス可能なシンボルであり続けます。`t` を返します。[`defpackage`](../special-forms/defpackage.md) の `:shadowing-import-from` 節の実行時版です。

記録はパッケージのメンバーテーブルにあるため、すべてのバックエンドで動作します(インタプリタは生きたレジストリ、コンパイル系バックエンドはプログラムが [`make-package`](make-package.md) で作ったパッケージのテーブル)。読み込み/コンパイル時パッケージはそこでは凍結されており、何も変えずに `t` を返します。

```lisp
(make-package :si-src :use nil)
(make-package :si-dst :use nil)
(intern "X" :si-dst)
(shadowing-import (intern "X" :si-src) :si-dst) ; => T
(multiple-value-list (find-symbol "X" :si-dst)) ; => (SI-SRC::X :INTERNAL)
(package-shadowing-symbols :si-dst) ; => (SI-SRC::X)
```
