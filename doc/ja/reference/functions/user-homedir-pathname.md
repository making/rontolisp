# user-homedir-pathname

`(user-homedir-pathname &optional host)`

ユーザーのホームディレクトリを**ディレクトリ**パス名として返します。名前文字列は
必ず区切り文字で終わり、name と type の要素は nil です。値は環境変数 `HOME` から
得られます。ホストは 1 つしかないため、`host` 引数は受け付けたうえで無視されます。

`HOME` が設定されていなければ `nil` です。これは Common Lisp が許容する値であり、
環境をまったく渡されなかった WASI ゲストにとって正直な答えでもあります。

```console
$ rontolisp -e '(print (user-homedir-pathname))'
#P"/home/you/"
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。バックエンドごとの環境プリミティブの上に書かれた
rontolisp ソースによる 1 つの定義があります。
