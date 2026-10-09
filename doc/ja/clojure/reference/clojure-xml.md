# clojure.xml

XML 文書を要素のマップに読み込み、また書き出す名前空間です。`clojure.xml` を require すると
使えます。Clojure の同名の名前空間について文書化された振る舞いをもとに rontolisp 向けに書いた
Clojure ソースです。`parse` は文書を自分で読み、すべてのバックエンドで同じように動きます。ホストの
SAX パーサーが読むのは、プログラムがそれを求めたときだけです。

| var | 振る舞い |
|---|---|
| `parse` | `(parse s)`: 文書 `s`（File、InputStream、ファイルや URL を示す文字列）のルート要素。要素は `:tag`（キーワード）、`:attrs`（キーワードから文字列へのマップ。属性がなければ `nil`）、`:content`（要素と文字列のベクター。空なら `nil`）のマップ |
| `parse` | `(parse s startparse)`: ホストの SAX パーサーが読む。`startparse` は `s` とホストの `ContentHandler` を受け取って呼ばれる（インタープリターと JVM）。ホストの `InputStream` などホストのオブジェクトの `(parse s)` は `startparse-sax-safe` を使う |
| `tag`、`attrs`、`content` | `(tag e)`: 要素の `:tag`、`:attrs`、`:content` |
| `element` | 要素の 3 つのキーの struct |
| `emit` | `(emit e)`: 宣言に続けて要素を XML 文書として出力する |
| `emit-element` | `(emit-element e)`: 要素を出力する。タグと文字列はそれぞれ 1 行 |
| `startparse-sax`、`startparse-sax-safe` | ホストの SAX パーサーを `startparse` にしたもの。safe のほうは外部エンティティを読まない（インタープリターと JVM） |
| `sax-parser`、`disable-external-entities` | 新しいホストの `SAXParser`。外部 DTD と外部エンティティを読まないよう設定したパーサー（インタープリターと JVM） |

```clojure
(require '[clojure.xml :as xml])
(xml/emit-element {:tag :a :attrs {:x "1"} :content ["t" {:tag :b}]})
```

```
<a x='1'>
t
<b/>
</a>
```

```console
clojure> (spit "/tmp/note.xml" "<note to='Tove'><body>Hi &amp; bye</body><empty/></note>")
nil
clojure> (xml/parse "/tmp/note.xml")
{:tag :note, :attrs {:to "Tove"}, :content [{:tag :body, :attrs nil, :content ["Hi & bye"]} {:tag :empty, :attrs nil, :content nil}]}
```

## 文書の読み込み

リーダーは検証を行わない XML 1.0 のリーダーです。文書はバイトオーダーマークか、宣言のエンコーディング
（UTF-8（既定）、UTF-16、ISO-8859-1、US-ASCII、windows-1252）で復号します。改行は 1 つの改行として
読み、内部サブセットが宣言する一般エンティティはマークアップも含めて展開し、外部 DTD と外部
エンティティは読みません。2 つのタグの間の文字データは、テキスト、CDATA セクション、参照、
コメントや処理命令の前後のテキストをつなげたもので、すべて空白（Java の
`Character.isWhitespace`）なら捨てます。整形式でない文書は `org.xml.sax.SAXParseException` で、
よくある誤りのメッセージは Clojure のパーサーと同じです。

## 組み込まれていないもの

`content-handler` と var `*stack*`、`*current*`、`*state*`、`*sb*` は名前を挙げて拒否します。
`parse` は状態を呼び出しの中に持ち、`startparse` にはその呼び出しのために作った
`ContentHandler` を渡します。

## 違い

- それ以外のエンコーディングは `java.io.UnsupportedEncodingException` です。Clojure のパーサーは
  JDK のすべての文字セットを読みます。
- `<!ATTLIST>` が宣言する属性の既定値は適用しません。
- ファイルを示さない文字列は、与えたパスを示す `java.io.FileNotFoundException` です。Clojure では
  URL でない文字列は `java.net.MalformedURLException` で、存在しないファイルは絶対パスで示します。
- まれな誤りも `SAXParseException` ですが、メッセージは rontolisp 独自のものです。
- WebAssembly では、`startparse` を渡した `parse` とホストのパーサー関数は、ほかの Java 相互運用と
  同じく呼び出した時点で失敗します。
