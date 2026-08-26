# macOS GUI (objc / appkit)

2 つの組み込みパッケージで、何もインストールせずに rontolisp の REPL から本物の Cocoa ウィンドウを開けます。`objc` は JVM の Foreign Function API を通じて Objective-C ランタイムと AppKit をバインドし (JNI なし、同梱ネイティブライブラリなし、リフレクションなし)、`appkit` はその上に rontolisp で書かれた小さなウィジェット層です — ウィンドウ、ラベル、Lisp クロージャをアクションに持つボタン、色付きパネル、クリック、繰り返しタイマー、メニューバー項目。

> **macOS 専用。インタプリタと JVM クラスで動作。** 両パッケージは `java -jar rontolisp.jar`、`rontolisp` ネイティブバイナリ (バインディングはリフレクションを必要としないためで、これが `java:` 連携にはできないことです)、そしてバインディングを内部に抱えた `.class` / `.jar` にコンパイルしたプログラムで動作します。どちらの WASM バックエンドにも foreign function API はないので、そうしたプログラムを `.wasm` にコンパイルすると `Cannot compile: appkit:window ...` エラーになります。Linux 上、またはネイティブアクセスを拒否する JVM (`--illegal-native-access=deny`) では、すべての `objc:` 関数が関数名で始まり理由を述べるメッセージの通常の `error` をシグナルします。

## REPL からウィンドウを

```console
CL-USER> (defvar *win* (appkit:window "counter" :width 420 :height 200))
CL-USER> (defvar *label* (appkit:label *win* "no clicks yet" :x 20 :y 120 :width 380))
CL-USER> (defvar *n* 0)
CL-USER> (appkit:button *win* "Click me" :x 20 :y 40
    :on-click (lambda ()
                (setq *n* (+ *n* 1))
                (appkit:set-text *label* (format nil "clicked ~a time(s)" *n*))))
```

ウィンドウが中央に前面表示され、ボタンをクリックするとクロージャが実行されてラベルが更新されます。その間も REPL はあなたのものです — ウィンドウはプロセスの最初のスレッド上にあり、入力を読むスレッドとは別です — し、ウィンドウを閉じても REPL は終了しません。`examples/macos/counter.lisp` は同じプログラムをスクリプトにしたもので、末尾の `(appkit:wait *win*)` がウィンドウが閉じられるまでブロックします。スクリプトのプロセスは最後のフォームが返ると終了するためです。

もっと大きなものも同じように Lisp で組み立てます。`examples/browser/minesweeper/minesweeper-macos.lisp` は Cocoa ウィンドウで完全なマインスイーパを遊べますし、`examples/macos/life-macos.lisp` はその中でライフゲームを走らせます。どちらも以下のウィジェットだけでできています。2 つが共有しているのはその上のボード、つまり両者がたまたま欲しがったクリック可能なタイルのグリッドを持つ小さな `cocoa` パッケージ `examples/macos/cocoa.lisp` です。これはボードゲームのポリシーであり、だからこそサンプルのままです。

`examples/macos/listener.lisp` は言語そのものをウィンドウに載せます。`NSTextView` のトランスクリプト、Return キーが Lisp のクロージャである編集可能な `NSTextField`、そして読み取った式への `eval` — 印字された出力も取り込み、エラーはプロセスを終わらせずに一行として表示されます。ウィンドウと評価器は同じイメージなので、そこに打ち込んだ式が次のウィンドウを開けます。

ウィンドウがまったくなくても構いません。`appkit:status-item` はシステムのメニューバーにタイトルを置き、`appkit:menu` は項目が Lisp のクロージャであるメニューをそこにぶら下げます。`:dock nil` を付けるとプロセスには Dock アイコンもアプリケーションスイッチャの項目もなくなります。これがメニューバープログラムの姿で、そのときの出口が `appkit:quit` です。引数なしの `appkit:wait` はそれが起きるまでブロックします。

```console
CL-USER> (defvar *n* 0)
CL-USER> (defvar *item*
    (appkit:status-item "λ" :dock nil
                        :menu (appkit:menu
                               (list (list "Count" (lambda ()
                                                     (setq *n* (+ *n* 1))
                                                     (appkit:set-text *item*
                                                                      (format nil "λ ~a" *n*))))
                                     :separator
                                     (list "Quit" #'appkit:quit "q")))))
```

`examples/macos/menubar.lisp` はそこに時計を入れたものです。`appkit:timer` が 1 秒ごとにタイトルを書き替え、メニュー項目の 1 つはウィンドウを開きます。`listener.lisp` と同じ証明を、メニューバーから行うわけです。

| 関数 | 用途 |
|------|------|
| `appkit:window` | `(appkit:window title &key (width 480) (height 300) background dark)` — 表示済み・中央配置の `NSWindow` |
| `appkit:label` | `(appkit:label window text &key x y width height (size 13) color (align :left) bold)` — 矩形内で文字列を中央寄せした `NSTextField` ラベル |
| `appkit:button` | `(appkit:button window title &key x y (width 120) (height 32) on-click)` — `NSButton`。`on-click` は引数なしの関数 |
| `appkit:panel` | `(appkit:panel window &key x y width height fill (radius 0) (border 0) border-color)` — 塗りつぶした角丸の `NSBox` |
| `appkit:color` | `(appkit:color r g b &optional (alpha 1.0))` — 0-255 の成分から作る `NSColor` |
| `appkit:font` | `(appkit:font size &key bold)` — そのサイズのシステムフォント |
| `appkit:set-text` | `(appkit:set-text view text)` — ボタンならタイトル、それ以外のコントロールなら string value |
| `appkit:set-color` | `(appkit:set-color view color)` — パネルなら塗りつぶし色、それ以外のコントロールなら文字色 |
| `appkit:text` | `(appkit:text view)` — タイトルまたは string value を Lisp 文字列で |
| `appkit:on-click` | `(appkit:on-click view handler)` — ハンドラはボタン番号を取る。1 が左、3 が右 |
| `appkit:click` | `(appkit:click button)` — クリックと同じようにアクションを実行 |
| `appkit:timer` | `(appkit:timer seconds fn)` — 繰り返す `NSTimer`。`fn` が `nil` を返すと止まる |
| `appkit:menu` | `(appkit:menu items)` — `NSMenu`。項目は `(title handler)` と省略可能なキー同値、`:separator` は区切り線 |
| `appkit:status-item` | `(appkit:status-item title &key menu (dock t))` — システムのメニューバーの `NSStatusItem`。`:dock nil` はアクセサリポリシー |
| `appkit:quit` | `(appkit:quit)` — Cmd-Q と同じようにアプリケーションを終了する |
| `appkit:close` | `(appkit:close window)` — ウィンドウを閉じる (隠す)。値は有効なまま |
| `appkit:visible-p` | `(appkit:visible-p window)` — 画面上にあるかどうか |
| `appkit:wait` | `(appkit:wait &optional window)` — ウィンドウが閉じられるまで、あるいはアプリケーションが終了するまで呼び出し側スレッドをブロック |

座標系は AppKit のもので、原点はウィンドウの左下です。ラベルは与えられた矩形の中で垂直方向に中央寄せされ、それがタイルの中央に数字を置いてくれます。パネルはそのタイルそのもので、どちらもクリックに応えます:

```console
CL-USER> (defvar *board* (appkit:window "tiles" :width 200 :height 200
                                 :background (appkit:color 26 29 38) :dark t))
CL-USER> (defvar *tile* (appkit:panel *board* :x 20 :y 20 :width 34 :height 34
                               :fill (appkit:color 104 116 146) :radius 7))
CL-USER> (defvar *digit* (appkit:label *board* "3" :x 20 :y 20 :width 34 :height 34
                                :size 19 :align :center :bold t))
CL-USER> (appkit:on-click *tile*
    (lambda (button) (appkit:set-color *tile* (appkit:color 230 233 241))))
#<objc RontoLispAppKitPanel>
CL-USER> (appkit:timer 1 (lambda () (appkit:set-text *digit* "4") nil))
#<objc __NSCFTimer>
```

すべてのウィジェットはただの Objective-C オブジェクトなので、この層にないものは `objc:send` 一つ分の距離にあります:

```console
CL-USER> (objc:send *win* "setBackgroundColor:"
    (objc:send "NSColor" "colorWithRed:green:blue:alpha:" 0.9 0.95 1.0 1.0))
CL-USER> (objc:send *win* "frame")
(690.0 676.0 420.0 228.0)
```

## objc パッケージ

`objc` は `java` とちょうど対になるもので、外部システムの名前を冠したパッケージに少数の汎用的な動詞があります。

| 関数 | 用途 |
|------|------|
| `objc:class` | `(objc:class "NSWindow")` — 名前でクラスを得る |
| `objc:send` | `(objc:send receiver "selector:with:" arg1 arg2)` — メッセージを送る。receiver はオブジェクト、クラス、またはクラス名の文字列 |
| `objc:define-class` | `(objc:define-class "Name" "NSObject" methods &optional protocols)` — メソッドが Lisp 関数であるクラス |
| `objc:on-main` | `(objc:on-main (lambda () ...))` — 関数をメインスレッドで実行してその値を返す |
| `objc:string` | `(objc:string "text")` — `NSString` |
| `objc:data` | `(objc:data buffer)` — パックバッファのバイト列を持つ `NSMutableData` |
| `objc:bytes` | `(objc:bytes data)` — `NSData` のバイト列をパックされた `(unsigned-byte 8)` ベクタとして |
| `objc:address` | `(objc:address object)` — オブジェクトのアドレス (整数) |
| `objc:objectp` | `(objc:objectp x)` — `x` が Objective-C オブジェクトかどうか |

```console
CL-USER> (objc:send (objc:string "hello world") "length")
11
CL-USER> (objc:send (objc:send (objc:string "hello") "uppercaseString") "UTF8String")
"HELLO"
CL-USER> (objc:send (objc:string "hello world") "rangeOfString:" "world")
(6 5)
CL-USER> (objc:send "NSNumber" "numberWithDouble:" 2.5)
#<objc __NSCFNumber>
```

### ランタイムは問い合わせられる対象

Objective-C が実行のその瞬間に決めることは、その瞬間に読み出せます。レシーバがある名前に
応えるか、実際のクラスは何か、メソッドがどんな型を宣言しているか、あるキーの下に何がある
か。

```console
CL-USER> (objc:send (objc:string "hi") "respondsToSelector:" "uppercaseString")
T
CL-USER> (objc:send (objc:send (objc:send (objc:string "hi") "class") "description") "UTF8String")
"NSTaggedPointerString"
CL-USER> (objc:send (objc:send (objc:string "hi") "methodSignatureForSelector:" "hasPrefix:") "methodReturnType")
"B"
CL-USER> (objc:send (objc:send (objc:string "hello") "valueForKey:" "length") "doubleValue")
5.0
```

2 行目はクラスクラスタを現行犯で捉えたものです。`objc:string` は `NSString` を求め、値に
応じて選ばれた非公開のサブクラスが返っています。`examples/macos/objc-runtime.lisp` は、この
側面のパッケージ全体を 1 つの実行可能なファイルにまとめたものです。文字列として持ち回り
`respondsToSelector:` で守るセレクタ、辿るクラス階層、読み出すメソッド自身の型エンコーディ
ング、キー値コーディングと文字列キーによるソート、`containsObject:` が呼び出す `isEqual:` が
Lisp のクロージャである実行時定義クラス、そして `NSNotificationCenter` のオブザーバ。ウィン
ドウは開きません。

### 境界は AppKit ではない

このマシン上のあらゆるフレームワークが Objective-C ランタイムを話します。プロセスにリンク
されていないフレームワークもメッセージ 1 つ分の距離にあり、`NSBundle` がそれをマップして
クラスを登録するので、次のフォームからはそのクラス名が解決します。

```console
CL-USER> (objc:send (objc:send "NSBundle" "bundleWithPath:"
    (objc:string "/System/Library/Frameworks/NaturalLanguage.framework")) "load")
T
CL-USER> (objc:send (objc:send "NLLanguageRecognizer" "dominantLanguageForString:"
    (objc:string "これは日本語の文章です")) "UTF8String")
"ja"
```

ここでの依存管理はこれで全部です。マニフェストもクラスパスもダウンロードもありません。
`examples/macos/system-frameworks.lisp` はそうして開かれる面を 1 つの実行可能なファイルに
したものです。Vision、NaturalLanguage、Core Image、そして音声合成 — どれも誰かが先に Lisp
向けにラップしたものではありません。中心にあるのは往復です。Lisp の文字列を Core Image が
画像に描き、それを Vision が読み戻し、機械が与えられたとおりに読んだかどうかを `equal` が
判定します。こちらもウィンドウを開かず、そして無音です。音声はスピーカーではなく AIFF
ファイルに合成されるためです。

### セレクタ自身のエンコーディングで型付け

`objc:send` はシグネチャを推測しません。Objective-C ランタイムはすべてのメソッドを完全に記述しており (`method_getTypeEncoding` は例えば `initWithContentRect:styleMask:backing:defer:` に対して `@68@0:8{CGRect={CGPoint=dd}{CGSize=dd}}16Q48Q56B64` を返します)、各引数と結果はその宣言に従ってマーシャリングされます:

| 宣言された型 | Lisp の引数 | Lisp の結果 |
|--------------|-------------|-------------|
| オブジェクト (`@`) | オブジェクト、`nil`、または文字列 (`NSString` として送られる) | オブジェクトまたは `nil` |
| クラス (`#`) | オブジェクトまたはクラス名 | オブジェクト |
| セレクタ (`:`) | セレクタ名の文字列 | 名前 |
| C 文字列 (`*`) | 文字列 | 文字列 |
| `BOOL` | `t` / `nil` | `t` / `nil` |
| 整数各種 | 整数 | 整数 |
| `float` / `double` | 数 | 浮動小数点数 |
| 構造体 (`{...}`) | 数のリスト (構造体のスカラーフィールドを順に。`NSRect` なら `(x y w h)`) | 数のリスト |
| その他のポインタ (`^`) | オブジェクト、整数アドレス、または `nil` | 整数アドレス |

receiver が応答しないセレクタ、引数の個数違い、宣言型に合わない引数はクラッシュではなく `error` になります。`performSelector...` メッセージの答えは捨てられます (その型はターゲットメソッドのもので、バインディングからは見えません)。ブロック、共用体、ビットフィールドはこの第一段階の範囲外で、それらを取るセレクタは名前を挙げて拒否されます。

### バイト列と `:error` 出力引数

汎用のメッセージ送信だけでは表現できないものが 2 つあります。メモリブロックと出力引数で
すが、どちらも Cocoa ではありふれたものです。1 つ目を担うのが `objc:data` です。パックバッ
ファ (任意ランクのパック float 配列、パックされた `(unsigned-byte 8|16|32)` ベクタ、文字列
の UTF-8) のバイト列を持つ `NSMutableData` を返します。並びは `write-sequence` が書くもの
とまったく同じで、リトルエンディアンの行優先です。あとは `[data bytes]` が `void *` 引数の
求めるアドレスになり、`[data mutableBytes]` は呼び出し先に渡せる書き込み領域になり、
`objc:bytes` がブロックを読み戻します。

2 つ目は `...error:` の慣習です。`NSError **` の位置にキーワード `:error` を渡すと、バイン
ディングがスロットを確保して渡し、呼び出しが失敗を報告しスロットが埋まっていたときには、
セレクタが返す素の `nil` の代わりに、そのエラーの内容でシグナルします。

```console
CL-USER> (objc:bytes (objc:data (make-array 2 :element-type 'single-float :initial-contents '(1.0 2.0))))
#(0 0 128 63 0 0 0 64)
CL-USER> (handler-case
      (objc:send "NSJSONSerialization" "JSONObjectWithData:options:error:" (objc:data "nope") 0 :error)
    (error (e) (princ-to-string e)))
"objc:send: JSONObjectWithData:options:error:: The data couldn’t be read because it isn’t in the correct format. [NSCocoaErrorDomain 3840]"
```

この 2 つが GPU を射程に入れます。Metal はほぼ全面が Objective-C の API なので、`objc:send`
だけで何も足さずに駆動できます。`examples/macos/metal-triangle.lisp` は WebGL の hello world
を、`examples/macos/metal-cube.lisp` は陰影付きの回転する立方体を描き、シェーダは Lisp の文
字列から実行時にコンパイルされます (OpenGL は逆で、射程外のままです。`glClear` などは素の C
関数であり、`objc_msgSend` は届きません)。

### スレッド: すべてはメインスレッドで起きる

AppKit はプロセスの最初のスレッドのものであり、すべての `objc:send` は自分でそこへ移動します — 同期的に、なので値は呼び出し側に返ってきます。複数の send から成るウィジェットは `objc:on-main` で包むと移動を 1 回だけ払うことになり、`appkit` の関数はそうしています。すでにメインスレッド上で動いている関数 (ボタンのハンドラ) は send をインラインで実行するので、コールバックから自由に GUI を呼び戻せます。

最初の `appkit:` 呼び出しは、スレッド 0 を AppKit 自身のイベントループ (`-[NSApplication run]`。誰もブロックせずにそこで開始します) に渡します。ウィンドウがそもそもクリックに応答するのはこれによるもので、プロセスがフォーカスを取りアプリケーションスイッチャに現れるのもこのためです。開始するのは `appkit` 層であり、汎用バインディングである `objc` ではありません。したがって `appkit:` 関数を一度も呼ばないプログラムが生の `objc:send` だけで作ったウィンドウは、描画はされても何にも反応しません。ウィンドウは `appkit:window` で作ってください。

コールバックはインタプリタの *グローバル* な動的束縛で動きます — REPL スレッドでの special 変数の `let` 束縛はそこから見えません — し、ハンドルされなかったエラーはシグナルではなく `objc: error in a callback: ...` として表示されます。AppKit のイベントの上にシグナル先となる Lisp のフレームは存在しないためです。

### 実行時に定義するクラス

`objc:define-class` はメソッドが Lisp 関数であるクラスを登録します。各メソッドは最初に receiver、続いて自身の引数を受け取ります:

```console
CL-USER> (defvar *target-class*
    (objc:define-class "MyTarget" "NSObject"
      (list (list "invoke:" (lambda (self sender)
                              (format t "clicked ~a~%" sender))))))
CL-USER> (defvar *target* (objc:send (objc:send *target-class* "alloc") "init"))
CL-USER> (objc:send button "setTarget:" *target*)
CL-USER> (objc:send button "setAction:" "invoke:")
```

メソッドの型は、スーパークラスがそのセレクタを宣言していればそこから、そうでなければ採用したプロトコルから取られ (`(objc:define-class "Delegate" "NSObject" methods '("NSWindowDelegate"))` は `windowShouldClose:` を `BOOL` として型付けします)、どちらにもなければ target/action の形 — 結果なし、コロンごとに 1 つのオブジェクト引数 — がデフォルトになります。メソッドが取れる形は閉じた集合です: 引数なし、オブジェクト引数 1 つまたは 2 つ、オブジェクト引数 1 つで `BOOL`・オブジェクト・整数のいずれかを返す。定義を再評価すると失敗せずクラスのメソッドが束縛し直されるので、REPL でハンドラを反復できます。

### 所有権

`objc:` の値はオブジェクトへの参照を 1 つ所有します — `alloc` / `new` / `copy` / `mutableCopy` / `retain` の結果からは引き継ぎ、それ以外は retain して — そして Lisp の値が回収されたときにメインスレッド上で解放します。つまり保持しているウィンドウや文字列は保持している限り有効で、手で解放するものはありません。唯一の規則: `objc:` で直接作るウィンドウには `appkit:window` がしているように `(objc:send win "setReleasedWhenClosed:" nil)` が必要です。さもないと閉じたときに Lisp の値がまだ持っている参照が解放されます。

## ネイティブバイナリ

`rontolisp` バイナリはビルド時に登録された `objc_msgSend` の形の固定テーブルを提供します — `appkit` 層が送るすべての形に加え、AppKit と Foundation の中核クラスで最も多い 60 の形で、それらが宣言するメソッドの 10 のうち 9 に届きます。テーブルにないセレクタは追加すべきエントリをそのまま示してシグナルします:

```text
objc:send: someRareSelector: the shape void(void*,void*,jshort) has no foreign-call stub
in this binary; register it under foreign.downcalls in reachability-metadata.json and rebuild
```

JVM は事前に何も登録せずどんな形でもバインドするので、バイナリを作る前にプログラムが何を送るかを知る場所は `java -jar` です。

## JVM クラスへのコンパイル

同じプログラムは `.class` や `.jar` にコンパイルでき、素の `java` ランチャで動きます。ランチャはプロセスの最初のスレッドを自分でイベントループに留めます:

```console
$ rontolisp examples/macos/counter.lisp -o Counter.class --class-name Counter
$ java Counter
$ rontolisp examples/macos/counter.lisp -o counter.jar
$ java -jar counter.jar
```

クラスはバインディング全体 (`am.ik.objc`、自身のパッケージにリネーム済み) と使用する `appkit` ウィジェットを抱えているので、`java.lang.foreign` を持つ JVM (コンパイラが動いたものか、それより新しいもの) 以外には何も必要ありません。素の `.class` を `--enable-native-access=ALL-UNNAMED` なしで実行すると JDK の restricted-method 警告が一度出ますが動作します。`.jar` はマニフェストでネイティブアクセスを有効にします。`rontolisp` バイナリもそうしたプログラムをコンパイルできます。`.wasm` 出力は拒否され (`Cannot compile: appkit:window ...`)、今後もそうです: そちら側には foreign function API も AppKit もありません。

## 制限

- macOS のみ: インタプリタ (`java -jar`、または `rontolisp` バイナリ) とコンパイル済み `.class` / `.jar`。`.wasm` は不可で、`objc:` / `appkit:` の参照は両 WASM バックエンドでコンパイルエラーです。
- アプリケーションバンドルのないプロセスには Dock アイコンもメニューバーもありません。Cmd-Q はなく、最後のウィンドウを閉じても終了しません — REPL がプロセスです。
- コールバックの形は上の閉じた集合です。構造体や整数の引数を持つデリゲートメソッド、ブロックを取るセレクタは、この段階にはない段を必要とします。
- Apple シリコン向け。Intel Mac では 2 レジスタより広い構造体は `objc_msgSend_stret` で返され、バインディングはそれを選びますが動作確認はしていません。
