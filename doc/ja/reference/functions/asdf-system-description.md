# asdf:system-description

`(asdf:system-description system)`

`defsystem` の宣言を読み出します。リーダーは `asdf:system-description`、
`asdf:system-long-description`、`asdf:system-author`、`asdf:system-maintainer`、
`asdf:system-license` (`asdf:system-licence` も同じ)、`asdf:system-homepage`、
`asdf:system-bug-tracker`、`asdf:system-mailto`、`asdf:system-long-name`、
`asdf:system-source-control`、`asdf:system-depends-on`、
`asdf:system-weakly-depends-on`、`asdf:system-defsystem-depends-on` と、`:properties` を読む
`(asdf:component-property system property)` です。どれも書かれたとおりの値を返します。
**これらのオプションの `#.` は評価しないので、リーダーは nil を返します**。本物の ASDF では
リーダーが評価します。スロットは本物の ASDF と同じ名前です
(`(slot-value system 'asdf::description)`)。

`asdf:system-source-file` はシステムを定義した `.asd` ファイルのネームストリングを返します。
インラインの `defsystem` では nil です。指定子も受け付けます。

```lisp
(asdf:defsystem :described :description "A demo" :license "MIT" :components ())
(asdf:system-description (asdf:find-system :described)) ; => "A demo"
(asdf:system-license (asdf:find-system :described)) ; => "MIT"
(asdf:system-author (asdf:find-system :described)) ; => NIL
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。コンパイル済みプログラムがこれらの値を持つのは、リーダーか
スロットの名前を書いている場合だけです。
