# clojure.java.io

ファイル、URL と、その上のストリームの名前空間です。Clojure と同じくプログラムより前にロードされる
ので、`clojure.java.io/file` は `require` なしで使えます。Clojure の同名の名前空間について文書化
された振る舞いをもとに rontolisp 向けに書いた Clojure ソースです。値はホストオブジェクトではなく
rontolisp 自身のもので、`java.io.File`、`java.net.URL`、`java.net.URI` とバイトストリームは
インタプリタ、JVM、2 つの WASM ターゲットのどれでも同じように振る舞います。WASM では
[slurp](slurp.md) と同じく、ファイルにはそれを含む `--dir` プリオープンが必要です。なければ
open はオラクルの `java.io.FileNotFoundException` になります。

| var | 振る舞い |
|---|---|
| `file` | `(file arg)` / `(file parent child & more)`: パス、File、`file:` URL が指す `java.io.File`（`nil` には `nil`）。2 つ目以降の引数は、それぞれ直前のものの下の相対パス |
| `as-file`, `as-url` | `Coercions` プロトコル: 値が File として、また URL として表すもの。`nil` は `nil` |
| `as-relative-path` | `(as-relative-path x)`: 相対の File か文字列のパス。絶対パスは `IllegalArgumentException` |
| `reader`, `writer` | `(reader x & opts)`: パス、File、URL、URI、バイト配列、バイトストリームの上のバッファ付き文字ストリーム、またはストリームそのもの。`:encoding` で文字セットを指定し（既定は UTF-8）、`:append true` で writer のテキストを追記する |
| `input-stream`, `output-stream` | `(input-stream x & opts)`: パス、File、URL、URI、バイト配列の上のバッファ付きバイトストリーム、またはバイトストリームそのもの。`:append true` で出力ストリームのバイトを追記する |
| `copy` | `(copy input output & opts)`: バイトストリーム、バイト配列、リーダー、File、文字列の中身を、バイトストリーム、ライター、File に書く。文字は `:encoding` で変換する。ほかの組み合わせはオラクルの `IllegalArgumentException` |
| `delete-file` | `(delete-file f & [silently])`: ファイルを削除して `true` を返す。削除できなければ、`silently` が truthy ならそれを返し、そうでなければ `java.io.IOException` を投げる |
| `make-parents` | `(make-parents f & more)`: File `(file f & more)` の上の足りないディレクトリを作り、作ったかどうかを返す |
| `resource` | `(resource name)`: ソースパスが `name` で持つファイルの URL（[リソース](#resources)）、なければ `nil` |
| `make-reader`, `make-writer`, `make-input-stream`, `make-output-stream` | 4 つのストリーム関数がオプションをマップにして呼ぶ `IOFactory` プロトコル |
| `default-streams-impl` | 型を拡張するための `IOFactory` のメソッド: 入力ストリームの上のリーダー、出力ストリームの上のライター、両バイトストリームの拒否 |

[slurp](slurp.md) と [spit](spit.md) は、パスでないものを Clojure と同じく `reader` と `writer` で
開きます。[file-seq](file-seq.md) は File のディレクトリツリーをたどります。

```console
clojure> (require '[clojure.java.io :as io])
nil
clojure> (def f (io/file "/tmp/notes" "a.txt"))
#'user/f
clojure> (io/make-parents f)
true
clojure> (with-open [w (io/writer f)] (.write w "one\ntwo\n"))
nil
clojure> (with-open [r (io/reader f)] (vec (line-seq r)))
["one" "two"]
clojure> (with-open [in (io/input-stream f)] (.read in))
111
clojure> (io/copy f (io/file "/tmp/notes/b.txt"))
nil
clojure> (sort (map str (file-seq (io/file "/tmp/notes"))))
("/tmp/notes" "/tmp/notes/a.txt" "/tmp/notes/b.txt")
clojure> (io/delete-file "/tmp/notes/b.txt")
true
```

## ファイル、URL、URI

File はそのパスで、Unix の `java.io.File` と同じく正規化されます（スラッシュの重複も末尾の
スラッシュもありません）。`#object[java.io.File "path"]` と印字され、`str` はパスを返し、パスが
同じ 2 つの File は `=` です。メソッドはパスから答えるもの（`getName`、`getParent`、
`getParentFile`、`getPath`、`isAbsolute`、`getAbsolutePath`、`toURI`、
`toURL`、`compareTo`）と、ファイルシステムから答えるもの（`getCanonicalPath`（シンボリック
リンクをすべて解決し、存在しない部分は綴りのまま残す）、`exists`、`isFile`、`isDirectory`、
`length`、`lastModified`、`canRead`、`isHidden`、`list`、`listFiles`、`mkdir`、`mkdirs`、
`createNewFile`、`renameTo`、`delete`）があります。`(java.io.File. path)` と
`(java.io.File. parent child)` は同じ値を作り、ファイルストリームの構築（パスか File の上の
`java.io.FileReader.`、`FileWriter.`、`FileInputStream.`、`FileOutputStream.`）はこの名前空間の
ストリームを作ります。

URL は綴りを保ち、`getProtocol`、`getHost`、`getPort`、`getPath`、`getFile`、`getQuery`、
`getRef`、`getAuthority`、`getUserInfo` に `java.net.URL` と同じく答えます。知られていない
プロトコルの綴りはオラクルの `java.net.MalformedURLException` です。`file:` URL はそのファイルを
開きます。URI は `getScheme` と `getPath` に答え、`toURL` はその URL を返し、`uri?` は true です。
どれも `class` はそのクラスのキーワード（`:java.io.File`）を返し、`instance?`、`class` で振り分ける
マルチメソッド、そのクラスに拡張したプロトコルは、上位型も含めて値を受け付けます（バイトストリーム
なら `java.io.InputStream`）。

```clojure
(require '[clojure.java.io :as io])
(io/file "src" "app" "core.clj")
; => #object[java.io.File "src/app/core.clj"]
(.getName (io/file "src/app/core.clj"))
; => "core.clj"
(str (.getParentFile (io/file "src/app/core.clj")))
; => "src/app"
(= (io/file "a/b/") (io/file "a" "b"))
; => true
(str (io/as-url (io/file "/tmp/a b.txt")))
; => "file:/tmp/a%20b.txt"
(.getPort (io/as-url "https://example.com:8080/x?q=1"))
; => 8080
```

## ストリーム

ファイルの上のリーダーとライターは `*in*` や `*out*` と同じ文字ストリームなので、`line-seq`、
`read`、`.readLine`、`*in*` や `*out*` の `binding`、`with-open` が受け付けます。バイトストリームは
`read`（次のオクテット。終端の先では `-1`）、バイト配列やその一部への `read`（読んだ数。終端の
先では `-1`）、`readNBytes`、`readAllBytes`（バイト配列）、`available`、`skip`、`transferTo`、
`write`（int の下位オクテット、バイト配列やその一部）、`flush`、`close` に答えます。バイト
ストリームの上のリーダーはそれを復号し、その上のライターは flush か close のときにテキストを
符号化して書き込みます。`:encoding` が指定できるのは UTF-8、ISO-8859-1、US-ASCII（とその別名）
で、ほかの名前はオラクルの `java.io.UnsupportedEncodingException` です。

`(java.io.ByteArrayInputStream. bytes)` はバイト配列をコピーせずに読み、
`(java.io.ByteArrayInputStream. bytes off len)` は `off` からの一部を読みます。
`(java.io.ByteArrayOutputStream.)` は書き込まれたオクテットを集めます。`toByteArray` はその
写しを、`size` は個数を、`toString` は（`str` と同じく）UTF-8 か指定した文字セットのテキストを
返し、`writeTo` は別のバイトストリームへ書き、`reset` は中身を空にします。Java と同じく、
どちらも close しても何も変わりません。オクテットの上のストリーム（バイト配列、リソース）は
`mark` で取った位置を `reset` のために保持し、ファイルの上のストリームは保持しません
（`markSupported` は false）。

```clojure
(def out (java.io.ByteArrayOutputStream.))
(.write out (.getBytes "héllo"))
(.write out 33)
(vec (.toByteArray out))
; => [104 -61 -87 108 108 111 33]
(str out)
; => "héllo!"
(let [in (java.io.ByteArrayInputStream. (.toByteArray out)) buf (byte-array 3)]
  [(.read in buf) (vec buf) (vec (.readAllBytes in))])
; => [3 [104 -61 -87] [108 108 111 33]]
```

## リソース

`resource` は名前をソースパス（プログラムのプロジェクトと依存先のルート。ディレクトリと jar。
[プロジェクト](../semantics.md#projects-depsedn)）で探します。オラクルはクラスパスで探しますが、
同じプロジェクトはそこへ同じルートを入れます。文字列リテラルで書いた名前はプログラムの
コンパイル時に見つけます。ディレクトリのファイルはその `file:` URL を、jar のエントリは
`jar:file:...!/name` URL を返し、見つけたテキストはプログラムと一緒に運ばれるので、WASM
モジュールを含め、どこで動かしても同じように読めます。プログラムが計算した名前は実行時に、
ソースパスのディレクトリの下で探します。

```console
$ cat resources/config.edn
{:port 8080}
$ cat src/app/main.clj
(ns app.main (:require [clojure.java.io :as io] [clojure.edn :as edn]))
(prn (edn/read-string (slurp (io/resource "config.edn"))))
$ rontolisp src/app/main.clj        # deps.edn holds {:paths ["src" "resources"]}
{:port 8080}
```

## プロトコル

`Coercions` と `IOFactory` は、オラクルと同じくプログラムが拡張するプロトコルです。`Coercions`
に拡張した型は `file` にとって File になり、`IOFactory` に拡張した型は自分のメソッドで開かれ、
`slurp` と `spit` もそれを使います。`extend` は、メソッドを 1 つ差し替えた `default-streams-impl`
を受け付けます。

```clojure
(require '[clojure.java.io :as io])
(defrecord Doc [text])
(extend Doc io/IOFactory
  (assoc io/default-streams-impl
         :make-reader (fn [d _] (java.io.BufferedReader. (java.io.StringReader. (:text d))))))
(slurp (->Doc "hello"))
; => "hello"
(with-open [r (io/reader (->Doc "a\nb"))] (vec (line-seq r)))
; => ["a" "b"]
```

## 違い

- File、URL、URI、ストリームは、オラクルの `#object[...]` から識別ハッシュを除いた形で印字
  されます。ストリームの `str` はクラス名です（オラクルの `Class@hash` からハッシュを除いたもの）。
  `class` は、ほかの値と同じくキーワードを返します（[仕様との差異](../deviations.md)）。
- URL と URI の `.hashCode` は綴りの `String.hashCode` で、オラクルは構成要素からハッシュを
  作ります。綴りが同じ 2 つは `=` で、`java.net.URL` は解決したホストを比べます。File の
  `.hashCode` はオラクルと同じです。
- `file:` 以外のプロトコルの URL の読み取りは、名前を挙げて拒否します（`resource` が返した
  `jar:` URL は除きます）。オラクルは接続を開きます。
- `:encoding` が知る文字セットは 3 つで、オラクルは JDK のものを知っています。
- `input-stream` と `output-stream` は `ByteArrayInputStream` と `ByteArrayOutputStream` を
  そのまま返し、オラクルはバッファ付きストリームで包みます。ファイルの上のストリームは
  `mark` の位置を保持しません。
- `resource` は、プログラムが計算した名前をソースパスのディレクトリの下だけで探し、jar の中は
  探しません。渡されたクラスローダーは参照しません。
- `(java.net.URL. s)` と `(java.net.URI. s)` はホストオブジェクトを作ります（インタプリタと
  JVM）。どのバックエンドにもある URL は `as-url` が作り、その `.toURI` が URI です。
- `lastModified` は秒単位（1000 の倍数）で答えます。オラクルはミリ秒単位で答えることがあります。
- WASM では相対の File の `getAbsolutePath` を拒否します（作業ディレクトリがありません）。
  `canRead` はファイルが存在するかを答えます。
- `line-seq` はパスと同じく File、URL、バイトストリームも受け付けます（[line-seq](line-seq.md)）。
  オラクルはリーダーだけを受け付けます。
