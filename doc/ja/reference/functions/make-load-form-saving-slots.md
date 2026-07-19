# make-load-form-saving-slots

`(make-load-form-saving-slots object &key slot-names environment)`

ライト版スタブ: rontolisp には fasl ダンパがないため、この標準関数を呼び出すとエラーをシグナルします。ライブラリの `make-load-form` メソッド(処理系がコンパイル済みファイルをダンプするときにのみ実行される)をコンパイル可能にするために存在し、その呼び出し箇所は実行時には到達しません。

```console
> (make-load-form-saving-slots (make-instance 'point))
Error: make-load-form-saving-slots is not supported (no fasl dumper)
```
