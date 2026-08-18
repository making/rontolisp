# with-compilation-unit

`(with-compilation-unit (options...) body...)`

本体のフォームを順に評価し、最後の値を返します。つまり本体を包む `progn` です。
オプションリスト (`:override` および処理系拡張) は受け付けたうえで無視されます。

`progn` が実装のすべてであり、それで妥当です。オプションは外側のユニットの遅延警告
レポートをこのユニットへどう統合するかを制御するだけであり、rontolisp には警告を
遅延させる元となる [`compile-file`](../functions/compile-file.md) がありません。
rontolisp のプログラムは 1 パスで丸ごとコンパイルされ、ロードされたファイルは
そこへ差し込まれます。操作の並びをこれで包むライブラリ (ASDF はすべてのビルドを
包みます) は、求めている動的エクステントを得られます。

```lisp
(with-compilation-unit (:override t) 1 2 3) ; => 3
```
