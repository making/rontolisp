# provide

`(provide module-name)`

モジュールをロード済みとして登録し、以後同じ名前の [`require`](require.md) がファイルをロードせずに戻るようにします。モジュール名をシンボルとして返します。`module-name` は指示子 (キーワード、シンボル、文字列) で、すでに provide 済みの名前を再度 provide しても no-op です。`require` で読み込まれるファイルは自身で `provide` を呼ぶことが期待されます (慣習的には先頭のフォームとして。これにより相互に require し合うファイルも停止できます)。

バックエンドの分担は `require` と同じです: インタプリタでは通常のランタイム関数、JVM/WASM のコンパイルパスではリテラルなトップレベルのコンパイル時ディレクティブです (ネストした、あるいは計算された `provide` はコンパイルエラー)。Common Lisp の `*modules*` 変数は利用できません。

```lisp
(provide :my-module) ; => MY-MODULE
```

```lisp
(provide :my-module)
(require :my-module) ; => MY-MODULE
```

この `require` はモジュールが同じプログラム内ですでに provide 済みのため即座に戻ります — `my-module.lisp` というファイルは探されません。
