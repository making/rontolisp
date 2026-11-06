# ASDF システムから実行ファイルをビルドする

システムがプログラムであることは、ASDF の書き方で宣言します: `:build-operation "program-op"`、実行する関数を `:entry-point`、実行ファイルの置き場所を `:build-pathname` に書きます。`rontolisp build SYSTEM`、または rontolisp が実行するプログラムの中の `asdf:make` が、その実行ファイルをビルドします:

```console
$ cat app.asd
(defsystem "app"
  :build-operation "program-op"
  :build-pathname "bin/app"
  :entry-point "app:main"
  :components ((:file "main")))
$ cat main.lisp
(defpackage #:app (:use #:cl) (:export #:main))
(in-package #:app)
(defun main ()
  (format t "hello ~{~a~^ ~}~%" (uiop:command-line-arguments))
  t)
$ rontolisp build app
$ ./bin/app from the executable
hello from the executable
```

## 成果物

ビルドが書き出すものは名前で決まります。名前はシステムのディレクトリから見た `:build-pathname`、または `rontolisp build` に渡した `-o` です:

| 名前 | 成果物 | 実行方法 |
|------|--------|----------|
| 拡張子なし(`bin/app`) | このマシン向けの[ネイティブ実行ファイル](native.md) | `./bin/app` |
| `app.jar` | [JVM](jvm.md) のプログラム jar | `java -jar app.jar` |
| `app.class` | ファイル名と同じ名前の JVM クラス | `java -cp . app` |
| `app.wasm` | [WASI](wasm.md) モジュール(コンポーネントにするなら `--component`) | `wasmtime run app.wasm` |

```bash
rontolisp build app -o app.jar
rontolisp build app -o app.wasm
rontolisp build app --native-target linux-aarch64 --optimize=size
```

コンパイラのフラグはすべて、`-o` のときと同じようにビルドに渡ります。`rontolisp build app.asd` は、その `.asd` と同じ名前のシステムをビルドします。`:build-operation` が `program-op`(またはそのサブクラス)でないシステムの `rontolisp build` は失敗します。`asdf:make` はそのシステムをロードするだけだからです。

## ビルドのしくみ

rontolisp には保存できるヒープイメージがないので、ビルドはコンパイルです。システムはまずインタプリタにロードされます。これがビルドの走るイメージで、ライブラリのビルド時の処理もここで走ります。続いて `uiop:dump-image` がそのイメージをコンパイルします: プログラムがロードした各システムをターゲット向けに読み直し、その後にエントリーポイントの `uiop:restore-image` を置きます。したがって実行ファイルは、起動するとまずシステムをロードし、restore フックを走らせ、エントリーポイントを呼びます。その最初の値が終了ステータスになり、真なら 0、`nil` なら 1 です(ここでは `uiop:*lisp-interaction*` が `nil` です。SBCL のイメージは上流の既定値 `t` のままなので、何を返しても 0 で終了します)。実行ファイルの中では `uiop:*image-dumped-p*` は `:executable` です。

エントリーポイントには、関数名またはそれを指す文字列、`lambda` 式、`(function argument...)` 形式のリストを書けます。実行ファイルに入るのは、システムをロードして作られるものだけです: ビルドスクリプトがシステムの外で評価した定義や、システムのロード後に設定した値は入りません。エントリーポイントがクロージャのときは、名前を挙げて拒否します。

コンパイル済みのプログラムはビルドできません(コンパイラを持たないため)。そこで program-op システムの `asdf:make` を呼ぶと、`rontolisp build` を名指しするエラーをシグナルします。

## ビルドスクリプトから

Makefile の `sbcl --eval '(asdf:make :app)'` は、そのまま rontolisp のプログラムです:

```console
$ cat build.lisp
(asdf:make "app")
$ rontolisp build.lisp
```

## deploy

[deploy](https://shinmera.com/docs/deploy/) のアプリケーションもビルドできます: `:defsystem-depends-on (:deploy)` と `:build-operation "deploy-op"` を持つシステムです。deploy のビルド時フック(`:load`、`:deploy`、`:build`)はビルド中のインタプリタで走るので、`:deploy` フックはそのときに実行ファイルの隣へファイルを書き出します。`:boot` と `:quit` のフックは、実行ファイルの起動時と終了時に走ります。`deploy:data-directory` は実行ファイルのディレクトリです -- deploy が実行ファイルを書き出すシステム直下の `bin/`、または jar のあるディレクトリです。そこでは `deploy:deployed-p` は真です。`.wasm` のビルドは cffi なしで deploy をロードします。モジュールには外部ライブラリがないからです。`:pre-load` 型のフックは走りません。
