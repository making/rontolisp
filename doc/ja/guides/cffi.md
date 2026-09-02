# Cライブラリ（cffi）

rontolisp は **本物の、アップストリームの [CFFI](https://cffi.common-lisp.dev/)** を
動かします。Common Lisp エコシステムのCバインディングがすべて前提にしている、あの
ライブラリそのものであって、そのAPIを真似たものではありません。
`(ql:quickload :cffi)` は SBCL が取得するのと同じリリースをダウンロードし、その
ポータブルなソースを一切変更せずにロードし、`cffi:defcfun`、`cffi:defcstruct`、
`cffi:defcallback` などをそのまま提供します。

rontolisp 側が用意するのは、どの処理系も自分で書かねばならない1ファイルだけです。
CFFI が文書化しているバックエンドの継ぎ目、`cffi-sys` パッケージを、JVM の
foreign function API に結び付けたもの（JNI なし、同梱ネイティブライブラリなし、
リフレクションなし）。その継ぎ目より上——型システム、`defcfun` の引数ウォーカー、
enum と bitfield の層、translate/expand プロトコル——はアップストリームのコードです。

> **動く場所。** `cffi` は `java -jar rontolisp.jar` と REPL、`rontolisp`
> ネイティブバイナリ、そしてコンパイルした `-o Prog.class` / `-o app.jar`
> （バインディングを出力クラスの中に持ち運びます）で動きます。どちらの WASM
> バックエンドにも foreign function API はないので、そうしたプログラムを `.wasm` に
> コンパイルすると `Cannot compile: FFI:...` エラーになります（恒久的な仕様です）。

## 3行でC関数を呼ぶ

```console
CL-USER> (ql:quickload :cffi)
CL-USER> (cffi:defcfun "strlen" :long (s :string))
CL-USER> (strlen "hello, world")
12
```

`defcfun` はC関数名とその型を宣言し、定義される Lisp 関数がマーシャリングを行います。
`:string` は呼び出しのあいだ Lisp 文字列を外部メモリへコピーし、終わったら解放します。
戻り値が `:string` なら NUL 終端の UTF-8 を読み戻します。

`defcfun` のコストは**ダウンコールハンドル**です。ハンドルは呼び出しの**シェイプ**
（戻り値型と引数型の組）ごとに1回だけ作られ、同じシェイプのすべてのシンボルで
再利用されます。作るのに約 24 µs、呼ぶのに約 0.5 µs です。つまり新しいシェイプを
通る最初の呼び出しだけがハンドル代を払い、以降は——同じシェイプのどの関数からでも——
払いません。100個の関数を十数個のシェイプで定義するバインディングのウォームアップが
ミリ秒ではなくマイクロ秒で済むのはこのためです。

一度きりの呼び出しには、定義のいらない `foreign-funcall` があります。

```console
CL-USER> (cffi:foreign-funcall "getpid" :int)
30211
```

## ライブラリとフラットな名前空間

`define-foreign-library` はプラットフォームごとのライブラリ名を宣言し、
`use-foreign-library` がそれを開きます。以後は素の `defcfun` がそのシンボルを見つけます。

```console
CL-USER> (cffi:define-foreign-library libsqlite
    (:darwin "libsqlite3.dylib")
    (t "libsqlite3.so.0"))
CL-USER> (cffi:use-foreign-library libsqlite)
CL-USER> (cffi:defcfun ("sqlite3_libversion" sqlite-version) :string)
CL-USER> (sqlite-version)
"3.45.1"
```

`defcfun` はシンボルがどのライブラリ由来かを一切言いません。エコシステムのバインディングは
すべてこれに依存していて、CFFI はこれを *flat namespace* と呼びます。foreign function API
自体にはフラットな名前空間はない（ルックアップはライブラリ単位で、プロセス自身の
ルックアップは後から開かれたライブラリを見ません）ので、バックエンドが開いた
ライブラリをロード順に保持して順に探します。結果として、バインディングが前提にしている
振る舞いになります。

どの節が選ばれるかは `*features*` が決めます。そしてそこにはホストの名前が入ります。
macOS なら `:unix` と並んで `:darwin`・`:bsd`・`:arm64`（または `:x86-64`）が、Linux なら
`:linux` が入ります。`(:default "libsqlite3")` という節が一方のマシンでは
`libsqlite3.dylib` を、他方では `libsqlite3.so` を意味するのはこのためです。macOS では
さらに、システムのローダーが見つけられなかったときに CFFI が使うフォールバックの探索パスに
Homebrew の `/opt/homebrew/lib` が入るのもこの名前のおかげです。これらの名前は `cffi` が
依存している `trivial-features` が持ち込みます。

## メモリ・型・ポインタ

`with-foreign-object` は本体の範囲で確保し、`mem-ref` と `mem-aref` はポインタ越しに
読み書きし、`foreign-type-size` はC型のサイズを返します。

```console
CL-USER> (cffi:with-foreign-object (tv :long 2)
    (cffi:foreign-funcall "gettimeofday" :pointer tv :pointer (cffi:null-pointer) :int)
    (cffi:mem-ref tv :long))
1787757356
CL-USER> (list (cffi:foreign-type-size :int) (cffi:foreign-type-size :pointer))
(4 8)
```

C の整数型名は LP64 の幅です。`:long` と `:unsigned-long` は8バイト——rontolisp の
リンカが相手にするどのプラットフォームでもそうです。`defctype` は型に別名を付けます。
`:size`（CFFI 自身の `size_t` 別名）が解決するのは、rontolisp が `:64-bit` を
宣言しているからです。

ポインタは整数ではなく独自の値です。`cffi:pointerp` は `42` に対して `nil` を返し、
`make-pointer` と `pointer-address` が双方向に変換します。`foreign-alloc` は `malloc`、
`foreign-free` は `free` です——外部メモリはあらゆる Lisp のスコープより長生きします。
これが CFFI 自身の契約です。

## 文字列

```console
CL-USER> (cffi:with-foreign-string (s "hello")
    (cffi:foreign-string-to-lisp s))
"hello"
```

`foreign-string-alloc`、`foreign-string-free`、`lisp-string-to-foreign`、
`with-foreign-strings`、`with-foreign-pointer-as-string` はすべて揃っています。
エンコーディングの既定は UTF-8 です。`:latin-1` と `:us-ascii` は表現できるオクテットの
範囲で動き、それ以外の `:encoding` は誤ったバイト列を返す代わりに**シグナルします**
（[`babel` シム](asdf-systems.md#built-in-shim-systems)と同じ規則です）。

## 構造体、値渡しも含めて

```console
CL-USER> (cffi:defcstruct timeval (tv-sec :long) (tv-usec :long))
CL-USER> (cffi:with-foreign-object (tv '(:struct timeval))
    (cffi:foreign-funcall "gettimeofday" :pointer tv :pointer (cffi:null-pointer) :int)
    (cffi:foreign-slot-value tv '(:struct timeval) 'tv-sec))
1787757356
```

構造体の**値渡し・値返し**も、追加で何も入れずに動きます。

```console
CL-USER> (cffi:defcstruct div-t (quot :int) (rem :int))
CL-USER> (cffi:defcfun ("div" c-div) (:struct div-t) (numer :int) (denom :int))
CL-USER> (c-div 17 5)
(QUOT 3 REM 2)
```

他の処理系ではこの呼び出しは "Unable to call structures by value without cffi-libffi
loaded" をシグナルし、Cライブラリに依存したシステムのロードを提案してきます。
ここでは foreign function API がメンバ型から構造体レイアウトを自分で組み立てるので、
ただの呼び出しです——`cffi-libffi` は永久に不要です。CFFI と foreign function API で
レイアウトが一致しない構造体（手書きの `:offset`、ビットフィールド）は、推測で渡さずに
名指しで拒否されます。入れ子の構造体は平坦に並べられます。メモリも呼び出しも同じで、
2つの点からなる矩形は4つの double です。

`rontolisp` の**ネイティブバイナリ**は、シェイプごとにスタブを事前コンパイルする必要が
あるため、値渡し・値返しのシェイプを全部ではなく有界な族として持ちます。その族と、
そこから外れるものは下の[ネイティブバイナリでは](#in-the-native-binary)にあります。

## コールバックと可変長引数

`defcallback` は Lisp 関数をC関数ポインタにします。

```console
CL-USER> (cffi:defcallback cmp :int ((a :pointer) (b :pointer))
    (- (cffi:mem-ref a :int) (cffi:mem-ref b :int)))
CL-USER> (cffi:with-foreign-object (arr :int 4)
    (loop for i from 0 for v in '(4 2 9 1) do (setf (cffi:mem-aref arr :int i) v))
    (cffi:foreign-funcall "qsort" :pointer arr :long 4 :long 4
                          :pointer (cffi:callback cmp) :void)
    (loop for i below 4 collect (cffi:mem-aref arr :int i)))
(1 2 4 9)
```

コールバックから漏れたエラーは、その上のCフレームへ巻き戻ってプロセスを終わらせて
しまいます。そうならないよう、メッセージを表示し、宣言した型のゼロを返します。
コールバックを再定義すると**新しい**アドレスが返ります——古いアドレスを保持している
C側は、古い定義を呼び続けます。

可変長引数の呼び出しは、追加の引数を並べて書いた `foreign-funcall` です。CFFI が
昇格（`:float` は `:double` へ、`:char`/`:short` は `:int` へ）を行い、バックエンドが
可変長部分の開始位置を印付けます。これが x86-64 だけでなく AArch64 と Apple silicon でも
呼び出しを正しくします。

```console
CL-USER> (cffi:with-foreign-pointer (buf 64)
    (cffi:foreign-funcall "snprintf" :pointer buf :long 64 :string "%s-%d"
                          :string "x" :int 7 :int)
    (cffi:foreign-string-to-lisp buf))
"x-7"
```

## Cのグローバル変数

`defcvar` はCのグローバル変数に名前を付けます。以後、その Lisp 名は変数のように
読み書きできます——実体は生成されたアクセサに対する
[シンボルマクロ](../reference/special-forms/define-symbol-macro.md)であって変数では
ないため、`setf` や `incf` はそのままC側の記憶域に届きます。

```console
CL-USER> (cffi:defcvar ("optind" *optind*) :int)
CL-USER> *optind*
1
CL-USER> (setf *optind* 7)
7
CL-USER> (cffi:pointerp (cffi:get-var-pointer '*optind*))
T
```

## CFFI を使うライブラリ

上流の CFFI を動かす目的は、それに対して書かれたライブラリ群です。実際に試した結果:

| ライブラリ | 結果 |
|---|---|
| [**cl-sqlite**](https://common-lisp.net/project/cl-sqlite/) (`sqlite`) | **動きます。** `(ql:quickload "sqlite")` だけで本物のデータベースが手に入ります: `connect`、`execute-non-query`、`execute-to-list`、`execute-single`、`with-transaction`、手で進めるプリペアドステートメント。SQL エンジンを同梱しているわけではありません——あなたのマシンの `libsqlite3` がエンジンです。[`examples/jvm/cffi-sqlite.lisp`](https://github.com/making/rontolisp/blob/develop/examples/jvm/cffi-sqlite.lisp) を参照。インタプリタとネイティブバイナリで動きます。コンパイル済みクラスで動かない理由は下の `defcenum` の行を参照 |
| **static-vectors** | **ロードできませんし、できるようにもなりません。** これは CFFI の利用者ではなく、2つめの処理系シームです。`.asd` は自分のリストに無い処理系を拒否し、その先では、ポインタを取れるメモリを記憶域に持つベクタを供給する処理系ごとのファイルが必要になります。ここにはそうした配列がありません。代わりに `cffi:foreign-alloc` で確保してください |
| **cl+ssl** | **ロードでき**、OpenSSL も応答します。Quicklisp で最大級のバインディングの `defcvar`/`defcallback`/`defcstruct` の全面がここで動き、cl+ssl の Lisp BIO 経由で——ハンドシェイクの1オクテットごとに OpenSSL から Lisp へコールバックしながら——本物の TLS ハンドシェイクが完了します。途中でぶつかった障害はどれ1つ CFFI 側ではありませんでした。ただし使える HTTPS クライアントにはまだなりません。cl+ssl は `(etypecase socket (integer …) (stream …))` で BIO を選びますが、rontolisp のストリームは**整数そのもの**なので、ストリームハンドルをソケットのディスクリプタとして使うよう OpenSSL に指示してしまいます。そのため、rontolisp 自身の TLS の上に載る同梱の [`cl+ssl` シム](asdf-systems.md#built-in-shim-systems) が既定のままです——マシンに OpenSSL が要らず、CFFI が決して動かない WASM コンポーネントバックエンドでも動きます |

## 動かないもの

| | |
|---|---|
| `cffi-grovel` | grovel はCプログラムをコンパイル・実行してプラットフォームのヘッダを読むため、ロード時にCツールチェインが要ります。`:defsystem-depends-on` でこれを挙げるシステムは、中途半端にロードせず**その理由を述べて拒否されます**。大半のバインディングは grovel しません |
| `cffi-libffi` | こちらも拒否されます。理由は逆で、構造体の値渡しがすでに動く（上記）ので、追加する余地がありません |
| `with-pointer-to-vector-data` | ピン留めではなく**コピーイン・コピーアウト**です。本体は新しい外部バッファを見ることになり、C側が書いた内容が Lisp のベクタへ届くのは本体を抜けたときで、その前ではありません。本体の外へ持ち出したポインタはダングリングです |
| `:long-double` | ここでは外部型ではありません |

## ネイティブバイナリでは

ネイティブイメージは外部呼び出しの**シェイプ**ごとのスタブをビルド時にコンパイルします。
一方 `defcfun` はシェイプを実行時に、あなたのプログラムの中で発明します——そこで
バイナリは登録済みのグリッドを同梱します。幅の狭い整数はすべて64ビットのキャリアで
運ばれ、ポインタと文字列も同じキャリアです——ABI にとってポインタと64ビット整数は
同じパラメータだからです。おかげで C API のシェイプはパラメータあたり3種類のキャリアに
畳み込まれます。グリッドはその上で、ポインタ/整数の呼び出しをアリティ10まで、`double`
混じりはアリティ4まで、`float` はアリティ2まで、すべての戻り値キャリアについてカバーし、
コールバックのシェイプもアリティ6までカバーします。実際上、バインディングの固定
アリティの呼び出しはそのまま動きます。

**値返しの構造体**だけはそのようには畳み込めません。ABI はメンバそのものから返し方を
決めるので、メンバの並びがシェイプの一部だからです。代わりにバイナリは有界な族を持ちます
——Cのスカラ幅からなる1メンバ・2メンバの構造体すべてと、メンバがすべて
同じ型である3メンバ・4メンバの構造体——で、入れ子の構造体は平坦に数えます。こうした
呼び出しの*引数*のほうは畳み込まれるので、`div`、`ldiv`、`imaxdiv` は3つではなく1つの
登録済みシェイプです。

グリッドの外の呼び出し——たとえば7番目以降の幅の狭い整数引数や、族が持つより多い
メンバを持つ構造体——は、それを登録する `reachability-metadata.json` のエントリを
ひとつ名指しするエラーをシグナルします。直し方はそのエントリを足してバイナリを
再ビルドするか、任意のシェイプが結び付く `java -jar` で実行することです。

## 各部品の在り処

`(ql:quickload :cffi)` は他のシステムと同じようにアップストリームのリリースを取得します。
ロードを成立させている同梱物は3つです（[システム（asdf）](asdf-systems.md)も参照）。

- `cffi.asd` の差し替え——アップストリーム版は、自分のリストにない処理系に対して
  `(error "Sorry, this Lisp is not yet supported")` で始まり、`defmethod` で終わるため、
  データとして読めません。
- `cffi-sys` バックエンド。実装コンポーネントとして差し込まれるので、ディスク上の
  アップストリームのツリーには一切手を入れません。
- `src/strings.lisp` の代替。ロードできない唯一のポータブルファイルです（
  [`babel` シム](asdf-systems.md#built-in-shim-systems)が持たない babel の
  コードジェネレータを駆動するため）。その全面を `babel:string-to-octets` の上に
  再現しています。

それ以外——`package`、`sys-utils`、`utils`、`libraries`、`early-types`、`types`、
`enum`、`structures`、`functions`、`foreign-vars`、`features`——はアップストリームの
ソースそのもの、1バイトも変えていません。
