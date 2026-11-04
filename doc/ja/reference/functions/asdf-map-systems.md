# asdf:map-systems

`(asdf:map-systems function)`

登録済みの各システムのメタオブジェクトを引数に、登録順に `function` を呼びます。
ロード済みの名前は [`asdf:already-loaded-systems`](asdf-registered-systems.md)、1 つのシステムの
状態は `asdf:component-loaded-p`、探索なしの取得は `asdf:registered-system` で得られます。
nil を返します。

```lisp
(asdf:defsystem :mapped :components ())
(let ((seen nil))
  (asdf:map-systems (lambda (system)
                      (when (equal (asdf:component-name system) "mapped")
                        (setq seen t))))
  seen) ; => T
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。コンパイル済みプログラムのシステムはレジストリにあるものです
([`asdf:find-system`](asdf-find-system.md))。
