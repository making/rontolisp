# uiop/os

`uiop/os` は、可搬なライブラリがホストについて尋ねることをまとめたものです。OS
は何か、処理系は何か、環境変数はどうなっているか、作業ディレクトリはどこか。
**22 個のエクスポートすべてが実装済み**で、ホストに関する答えはすべて 1 つの源
-- 上流と同じく [`*features*`](../data-types.md#comments-feature-conditionals-and-features) を評価する
`uiop:featurep` -- から導かれます。したがって 4 つのバックエンドすべてで同じ規則
が答えを決めます。

どの名前も 2 通りの綴りで参照できます。`uiop:getenv` と `uiop/os:getenv` は同じ
関数です（[uiop パッケージ](../uiop.md#sub-packages)）。

ここでの 3 つの答えは rontolisp 独自のもので、いずれも欠落ではなく決定です。

- **`uiop:os-unix-p` は無条件に `t`**。どのバックエンドも POSIX 型のファイル /
  名前文字列モデルを提供するので答えは正しいのですが、`*features*` にはあえて
  `:unix` を含めていません。含めるとフロントエンドが読むすべてのライブラリの
  `#+unix` 分岐が切り替わってしまい、OS 述語 1 つよりはるかに広い主張になるから
  です。
- **環境変数はホストから読み、書き込みはオーバーライドマップへ**。自分自身の
  プロセス環境を書き換えられるバックエンドはありません（JVM は原理的に不可、WASI
  は読み取り専用）。そのため `(setf (uiop:getenv name) value)` はプログラム単位の
  マップに値を記録し、`uiop:getenv` がそれを先に参照します。
- **`chdir` はどのバックエンドにもなく**、WASM バックエンドには作業ディレクトリ
  自体がありません。[作業ディレクトリ](#the-working-directory)を参照してください。

## ホストの識別

| 関数 | 返す値 |
|------|--------|
| `uiop:implementation-type` / `uiop:*implementation-type*` | `:rontolisp` |
| `uiop:lisp-version-string` | このビルドのバージョン。`(rontolisp:version)` が持つ文字列と同じ |
| `uiop:operating-system` | `:unix` |
| `uiop:detect-os` | `:os-unix` -- 上流は勝った feature を push して返しますが、ここでは push できないので返すだけです |
| `uiop:architecture` | 成果物が対象とする ABI。インタプリタと JVM では `:jvm`（クラスファイルは CPU 非依存）、両方の WASM 出力では `:wasm32` |
| `uiop:implementation-identifier` | 上の 4 つを連結して小文字化したもの。上流が fasl キャッシュのディレクトリ名を作るのと同じ形式です: `"rontolisp-<version>-unix-jvm"` |
| `uiop:hostname` | `nil` -- ホスト識別のプリミティブを持つバックエンドはなく、これは上流が自身の `#+` 節にない処理系上で返す値そのものです |
| `uiop:os-unix-p` | `t`（上記参照） |
| `uiop:os-macosx-p` / `uiop:os-windows-p` / `uiop:os-genera-p` | `nil` -- 上流と同じ `*features*` 上の導出で、ホスト OS の feature はここにはありません |
| `uiop:os-cond` | 上の述語群に対する `cond`。テストが真になる最初の節を選びます |

```lisp
(print (list (uiop:implementation-type) (uiop:operating-system) (uiop:detect-os)))
(print (list (uiop:os-unix-p) (uiop:os-windows-p) (uiop:hostname)))
(print (uiop:os-cond ((uiop:os-windows-p) :windows) ((uiop:os-unix-p) :unix) (t :other)))
```

```
(:RONTOLISP :UNIX :OS-UNIX)
(T NIL NIL)
:UNIX
```

## Feature 式

`uiop:featurep` は `*features*` に対して feature 式を**実行時に**評価します。
読み取り時に `#+` が行うのと同じで、アトムはメンバシップ判定、`(:not e)`、
`(:or e...)`、`(:and e...)` がそれらを組み合わせます。

```lisp
(print (list (uiop:featurep :rontolisp)
             (uiop:featurep '(:and :rontolisp :unicode))
             (uiop:featurep '(:or :no-such-feature :rontolisp))
             (uiop:featurep '(:not :no-such-feature))
             (uiop:featurep :no-such-feature)))
```

```
(T T T T NIL)
```

feature 集合は設計上バックエンドごとに異なります -- `:rontolisp-interpreter`、
`:rontolisp-jvm`、`:rontolisp-wasm` がそれらを区別します -- ので、
`uiop:featurep`（およびそこから導かれる `uiop:architecture`）は実行中のバック
エンドについて答えます。省略可能な第 2 引数で独自の feature 集合を判定できます:
`(uiop:featurep :x '(:x))` は `T` です。上流の引数リストが誘うような
`*features*` の再束縛は、ここではインタプリタ限定の書き方です。コンパイル
バックエンドは読み取り時にこの変数を置き換えるためです。

## 環境変数

| 関数 | 動作 |
|------|------|
| [`uiop:getenv`](../functions/uiop-getenv.md) | 変数の値を文字列で返し、未設定なら `nil` |
| `(setf uiop:getenv)` | 上の読み取りが先に参照するオーバーライドを記録します。値 `nil` は「未設定にする」意味です |
| `uiop:getenvp` | 値を返しますが、空文字列も `nil` として扱います -- 「この変数は本当に設定されているか」 |

```lisp
(setf (uiop:getenv "RONTOLISP_DOC_VAR") "hello")
(print (list (uiop:getenv "RONTOLISP_DOC_VAR") (uiop:getenvp "RONTOLISP_DOC_VAR")))
(setf (uiop:getenv "RONTOLISP_DOC_VAR") "")
(print (list (uiop:getenv "RONTOLISP_DOC_VAR") (uiop:getenvp "RONTOLISP_DOC_VAR")))
(setf (uiop:getenv "RONTOLISP_DOC_VAR") nil)
(print (uiop:getenv "RONTOLISP_DOC_VAR"))
```

```
("hello" "hello")
("" NIL)
NIL
```

このオーバーライドは**プログラムの実行単位**のもので、プロセス環境そのものを
変更するわけではありません。子プロセスからは見えませんし、このイメージの外側から
も見えません。書き込みを許さないホスト上での `(setf (uiop:getenv ...))` の正直な
形はこれであり、ライブラリが使うオプション設定の定石 -- いくつか変数を束縛し、
本体を実行し、元に戻す -- が 4 つのバックエンドすべてで同じように振る舞う理由でも
あります。

## 作業ディレクトリ

| 関数 | 動作 |
|------|------|
| `uiop:getcwd` | ホストの作業ディレクトリをディレクトリ形式で返します。ホストに存在しない場合は `uiop:not-implemented-error` をシグナルします |
| `uiop:chdir` | どのバックエンドでも `uiop:not-implemented-error` をシグナルします |

`uiop:getcwd` は、ホストプロセスが作業ディレクトリを持つインタプリタと JVM で
値を返します。**WASM バックエンドは両方ともシグナルします**。WASI プログラムには
preopen されたディレクトリが与えられるだけで「現在の」ディレクトリはなく、答える
べきものが存在しないからです。

`uiop:chdir` は JVM を含むすべてでシグナルします。Java は `user.dir` を起動時に
一度読むだけでプロセスの作業ディレクトリを移動できず、WASI には `chdir` 自体が
ありません。マージの見え方だけが変わり `open` は別の場所を解決し続ける、という
答えはエラーより悪いでしょう。

```lisp
(handler-case (uiop:chdir "/tmp")
  (uiop:not-implemented-error () :cannot-change-directory))   ; => :CANNOT-CHANGE-DIRECTORY
```

## Windows ショートカット

上流は小さな `.lnk` リーダーを持っており、その 2 つのオクテットプリミティブは
一般的にも有用です。

| 関数 | 動作 |
|------|------|
| `uiop:read-little-endian` | バイナリストリームから *n* オクテット（既定は 4）のリトルエンディアン非負整数を読みます |
| `uiop:read-null-terminated-string` | `0` までのオクテットを読み、文字列として返します |
| `uiop:parse-windows-shortcut` / `uiop:parse-file-location-info` | `uiop:not-implemented-error` をシグナルします |

2 つのリーダーは純粋なストリーム処理で、`read-byte` が動くところならどこでも動き
ます。2 つの `.lnk` パーサはファイル内を `file-position` で移動しますが、
rontolisp のファイルストリームはこれをサポートしないため、黙って誤解析する代わり
にそのプリミティブの名前を挙げてシグナルします。
