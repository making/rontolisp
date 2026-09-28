# macOS GUI (objc / appkit)

2 つの組み込みパッケージで、何もインストールせずに rontolisp の REPL から本物の Cocoa ウィンドウを開けます。`objc` は JVM の Foreign Function API を通じて Objective-C ランタイムと AppKit をバインドし (JNI なし、同梱ネイティブライブラリなし、リフレクションなし)、`appkit` はその上に rontolisp で書かれた小さなウィジェット層です — ウィンドウ、ラベル、Lisp クロージャをアクションに持つボタン、色付きパネル、クリック、繰り返しタイマー、メニューバー項目。

> **macOS 専用。インタプリタ、JVM クラス、ネイティブ実行ファイルで動作。** 両パッケージは `java -jar rontolisp.jar`、`rontolisp` ネイティブバイナリ (バインディングはリフレクションを必要としないためで、これが `java:` 連携にはできないことです)、バインディングを持ち運ぶ `.class` / `.jar` にコンパイルしたプログラム、そしてランナー自身がバインディングである Apple シリコン向けの `--native` 実行ファイルで動作します。`.wasm` には foreign function API がないので、そうしたプログラムを `.wasm` にコンパイルすると `Cannot compile: appkit:window ...` エラーになります。Linux 上、またはネイティブアクセスを拒否する JVM (`--illegal-native-access=deny`) では、すべての `objc:` 関数が関数名で始まり理由を述べるメッセージの通常の `error` をシグナルします。

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

もっと大きなものも同じように Lisp で組み立てます。`examples/browser/minesweeper/minesweeper-macos.lisp` は Cocoa ウィンドウで完全なマインスイーパを遊べますし、`examples/macos/life-macos.lisp` はその中でライフゲームを走らせます。どちらも以下のウィジェットだけでできています。2 つが共有しているのはその上のボード、つまり両者がたまたま欲しがったクリック可能なタイルのグリッドを持つ小さな `board` パッケージ `examples/macos/board.lisp` です。これはボードゲームのポリシーであり、だからこそサンプルのままです。

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

### 宣言が呼び出しのすべてではない唯一のケース

ランタイムが印を付けてくれないのが可変長引数 (variadic) のセレクタです。
`+[NSArray arrayWithObjects:]` と `+[NSArray arrayWithObject:]` はどちらも `@@:@` とバイト単位で同一に宣言されており、両者を区別する情報はどこにもありません。しかし Apple シリコンではこの違いが呼び出しそのものです。可変長引数はスタックで渡され、固定引数はレジスタで渡されるからです。

そこでこの一群は名前で知られています。nil 終端のコンストラクタ (`arrayWithObjects:`、`initWithObjects:`、`setWithObjects:`、`orderedSetWithObjects:`、`dictionaryWithObjectsAndKeys:`、`initWithObjectsAndKeys:`) と、書式文字列の一族 (`stringWithFormat:`、`initWithFormat:`、`localizedStringWithFormat:`、`stringByAppendingFormat:`、`appendFormat:`、`predicateWithFormat:`、`raise:format:`) です。いずれも宣言された引数の個数を超えていくつでも引数を取り (オブジェクト、文字列、整数、浮動小数点数)、`nil` 終端子はバインディングが付けます。呼び出し側が書くものではありません。

```console
CL-USER> (objc:send (objc:send "NSArray" "arrayWithObjects:"
                      (objc:string "a") (objc:string "b") (objc:string "c")) "count")
3
CL-USER> (objc:send (objc:send "NSString" "stringWithFormat:"
                      (objc:string "%@ has %ld items, %.1f%% full")
                      (objc:string "cache") 3 62.5) "UTF8String")
"cache has 3 items, 62.5% full"
```

`arrayWithObjects:count:` は意図的にこの一族に含めていません。本物の配列と個数を取る、任意サイズのコレクションを作る固定引数の方法だからです。プログラム自身が宣言した可変長引数メソッドも表の外であり、それをバインディングが予見する手段はありません。

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
だけで何も足さずに駆動できます。

### `metal` パッケージ

どの Metal プログラムも同じように書く定型 — ウィンドウのコンテンツビュー上の
`CAMetalLayer`、デバイス、コマンドキュー、レンダーパス、ドローアブル、present と commit、
そしてシェーダ・パイプライン・バッファのヘルパ — が組み込みの **`metal`** パッケージです。
`appkit` と同じくインタプリタに同梱され、初回使用時に読み込まれます。意図的に含めていない
のはシェーダのソース、形状、描画コールです。それらはプログラム側のものです。

```console
CL-USER> (defvar *win* (appkit:window "metal" :width 640 :height 400 :dark t))
CL-USER> (defvar *ctx* (metal:attach *win* :clear '(0.05 0.06 0.09 1.0) :depth t))
CL-USER> (defvar *pipe* (metal:pipeline *ctx* (metal:library *ctx* *shaders*) "vertex_main" "fragment_main"))
CL-USER> (metal:run *ctx*
    (lambda (encoder)
      (objc:send encoder "setRenderPipelineState:" *pipe*)
      (objc:send encoder "drawPrimitives:vertexStart:vertexCount:" metal:+triangle+ 0 3)))
#<objc __NSCFTimer>
```

Metal が必要とするように見える唯一の C 関数 `MTLCreateSystemDefaultDevice()` は回避できま
す。`CAMetalLayer` の `preferredDevice` はプロパティで同じデバイスを返すからで、パッケージ
全体はその事実の上に立っています。シェーダは Lisp の文字列から実行時にコンパイルされ、コン
パイルに失敗したシェーダは Metal コンパイラ自身の診断 (キャレット付き) で送出します。
`metal:buffer` は数値を一度だけ GPU にコピーし、毎フレーム書き直す形状には
`metal:shared-buffer` と `metal:upload`、Metal がインラインで受け取りたがる小さな値には
`metal:uniform` を使います。パックド単精度配列はバッファのバイト列そのものなので、`linalg`
の行列も `geom:mesh` も一切変換なしに GPU へ届きます。全体は
[関数リファレンス](../reference/functions/metal.md)にあります。

`metal` は単独で成立します。`examples/macos/metal-triangle.lisp` は WebGL の hello world
を、`examples/macos/metal-cube.lisp` は陰影付きの回転する立方体を、
`examples/macos/metal-robot-arm.lisp` は自分で逆運動学を解いてクリックした先へ手を伸ばすロ
ボットアームを、`examples/macos/metal-pagoda-garden.lisp` はボクセルの庭 — 鯉の池の上に立つ
五重塔、舞い散る桜、クリックで訪れる夜 — を描きます。その 1 万 3 千個のボクセルは 1 個の立方
体を 1 万 3 千回描いたもので、頂点関数が `vertex_id` を 36 で割ってどのボクセルの上にいるかを
求めます。4 本とも `metal` を直接使っており、`geom` も `scene` も使っていません (OpenGL は逆で、
射程外のままです。`glClear` などは素の C 関数であり、`objc_msgSend` は届きません)。

そこにマウスを運ぶのが `objc:define-class` です。描画面は実行時に定義した `NSView` のサブク
ラスで、その `mouseDown:` / `mouseDragged:` / `scrollWheel:` は Lisp のクロージャ — ウィ
ジェット層が `NSBox` にクリックを答えさせるのと同じ動詞です。

`metal` の 1 つ上の段が **`scene`** パッケージです。`geom` ソリッドの 3D ビューアで、カメラ・
グリッド・フレームループがすでに書かれているので、モデル化した機械はウィンドウまで 3 行です。
[ソリッドモデリングガイド](solid-modeling.md#seeing-it-the-scene-viewer)を参照してください。

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

## LispWorks のインターフェース

上の動詞と並んで、`objc` は LispWorks 8.1 の Objective-C インターフェースのうち呼び出し側 (`objc:invoke`、`objc:invoke-bool`、`objc:invoke-into`、`objc:retain` / `objc:release` / `objc:autorelease`、自動解放プール、クラスとセレクタの変換) を、LispWorks の名前とラムダリストのまま持ちます。その Foundation 構造体は `cocoa` パッケージが持ちます。LispWorks のマニュアルの呼び出し・文字列・メモリ管理の節に沿って書いたコードは、`objc` を use するパッケージの中で、インタプリタ、コンパイル済みクラス、`--native` 実行ファイルのどれでもそのまま動きます。すべての名前は[関数リファレンス](../reference/functions/objc.md)にあります。

```console
CL-USER> (defpackage :my-app (:use :cl :objc))
:MY-APP
CL-USER> (in-package :my-app)
:MY-APP
MY-APP> (defvar *s* (invoke "NSString" "stringWithUTF8String:" "hello world"))
*S*
MY-APP> (invoke *s* "rangeOfString:" "world")
(6 . 5)
MY-APP> (invoke-bool *s* "hasPrefix:" "hello")
T
MY-APP> (invoke-into 'string *s* "uppercaseString")
"HELLO WORLD"
MY-APP> (invoke-into 'string "NSString"
                     '("stringWithFormat:" (objc-object-pointer :int)
                       :result-type objc-object-pointer :variadic-num-of-fixed 1)
                     "The integer %d" 42)
"The integer 42"
```

### 宣言型による変換

`objc:invoke` はメソッドの型エンコーディングをランタイムから読み (リスト形式のメソッドは型を自分で述べます)、それに従って各引数と結果を変換します。引数として渡した文字列やベクタは呼び出しの間だけ存在します。レシーバにないメソッドは、何も送る前に `No method ... for object ..., class ...` をシグナルします。クラスはランタイムがレシーバについて答えるものです。

| 宣言型 | 引数として渡せるもの | 結果 |
|--------|----------------------|------|
| `id` | オブジェクトポインタ、`nil`、文字列 (`NSString` になる)、ベクタ (要素も同様に変換した `NSArray` になる) | `objc:objc-object-pointer` または `nil` |
| `Class` | クラスポインタまたはクラス名 | `objc:objc-class` |
| `SEL` | セレクタまたはその名前 | `objc:sel` |
| `char *` | 文字列またはアドレス | 文字列 |
| `BOOL` / `_Bool` | `t`、`nil`、整数 | `1` か `0` |
| 整数 | 整数 (符号なし 64 ビットは 2^64-1 まで) | 整数 |
| `float` / `double` | 実数 | 倍精度浮動小数点数 |
| `NSRect` / `NSPoint` / `NSSize` | `#(x y width height)` / `#(x y)` / `#(width height)` | 倍精度のベクタ |
| `NSRange` | `(location . length)` | コンス |
| その他の構造体 | メモリ順に並べたフィールドのベクタ | フィールドのベクタ |
| ポインタ | アドレス、`nil`、オブジェクトポインタ | アドレス |

`objc:invoke-into` はさらに変換します。`'string` は結果の `NSString` を Lisp 文字列に、`'array` と `'(array string)` は `NSArray` をベクタにします。第一引数に渡したベクタやコンスには、構造体や配列の要素が格納されます。

### 所有権: ポインタは保持している参照だけを解放する

すべての送信はメインスレッド上で専用の自動解放プールの中で行われるため、送信が autorelease したオブジェクトは、ポインタ値が先に参照を取らない限り送信から戻った時点で消えています。ポインタ値はオブジェクトの結果すべてについて参照を取り、`alloc` / `new` / `copy` / `mutableCopy` / `init` のメソッドが返す参照はそのまま引き取ります。ポインタ値は、コレクタに回収されるときにまだ保持している参照を解放します。`objc:retain` はプログラムが解放すべき参照を加えます。`objc:release` と `objc:autorelease` はポインタが保持する参照を一つ手放し、保持していなければシグナルします。そのため、マニュアルの規則どおり所有するものを解放するコードが、コレクタの解放と二重に解放することはありません。`(objc:invoke p "release")` も同じ計数を通ります。

```console
MY-APP> (defvar *o* (alloc-init-object "NSObject"))
*O*
MY-APP> (retain-count (retain *o*))
2
MY-APP> (release *o*)
NIL
MY-APP> (release *o*)
NIL
MY-APP> (release *o*)
error: objc:release: #<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000600000C04010> holds no reference this program can give up
MY-APP> (with-autorelease-pool ()
          (invoke-into 'string (autorelease (string-to-ns-string "pooled")) "description"))
"pooled"
```

プールは Lisp 側で管理します。各送信がメインスレッドで自分のプールを積んで降ろすため、本物の `NSAutoreleasePool` は二つの送信にまたがれません。

### 値

オブジェクトは `objc:objc-object-pointer`、クラスは `objc:objc-class` (オブジェクトポインタでもある)、セレクタは `objc:sel` として返ります。どれも `structure-object` ではありません。一つのオブジェクトに対する二つの答えは `eq`、`eql`、`equal`、`equalp` のいずれでも等しく、どのハッシュテーブルでも互いに見つかります。ポインタは LispWorks と同じく `#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000600000C04010>` と表示します。表示するのはアドレスだけで、オブジェクトからは何も読みません。

### クラスの定義

`objc:define-objc-class` は Objective-C クラスを実装する CLOS クラスを定義し、
`objc:define-objc-method` / `objc:define-objc-class-method` は本体を Lisp で書いたメソッドを与えます。
マニュアルの 1.4 節の例はそのまま動きます。`(:unsigned :int)` を二つ取るメソッド、
`(current-super)` に送るメソッドを持つサブクラス、`objc:define-objc-struct` で宣言した構造体を返すメソッドです。

```console
MY-APP> (define-objc-class my-object ()
          ((slot1 :initarg :slot1 :initform nil))
          (:objc-class-name "MyObject"))
MY-OBJECT
MY-APP> (define-objc-method ("areaOfWidth:height:" (:unsigned :int))
            ((self my-object)
             (width (:unsigned :int))
             (height (:unsigned :int)))
          (* width height))
"areaOfWidth:height:"
MY-APP> (define-objc-class my-special-object (my-object)
          ()
          (:objc-class-name "MySpecialObject"))
MY-SPECIAL-OBJECT
MY-APP> (define-objc-method ("areaOfWidth:height:" (:unsigned :int))
            ((self my-special-object)
             (width (:unsigned :int))
             (height (:unsigned :int)))
          (* 4 (invoke (current-super) "areaOfWidth:height:" width height)))
"areaOfWidth:height:"
MY-APP> (invoke (alloc-init-object "MySpecialObject") "areaOfWidth:height:" 6 7)
168
MY-APP> (define-objc-struct (pair (:foreign-name "_Pair"))
          (:first :float)
          (:second :float))
PAIR
MY-APP> (define-objc-method ("pair" (:struct pair)) ((this my-object))
          (vector 1.0 2.0))
"pair"
MY-APP> (invoke (alloc-init-object "MyObject") "pair")
#(1.0 2.0)
```

メソッドの引数と結果には、宣言で書けるどの型でも使えます。あらゆる幅の整数、`:float` / `:double`、
構造体 (`invoke` がそれについて使う Lisp の値)、`t` / `nil` になる `objc:objc-bool` / `:boolean`、
オブジェクト、クラス、セレクタです。オブジェクト引数は受け取るときに変換でき
(`(arg objc-object-pointer string)`、`array`、`(array string)`)、オブジェクトとして返した文字列やベクタは
`NSString` / `NSArray` になります。Objective-C クラスを名付けず継承もしないクラスはミックスインで、
そのメソッドは名付けるサブクラスそれぞれに入ります。メソッド本体のエラーは表示され、メソッドはゼロを返します。
呼び出した Objective-C のフレームまで巻き戻ることはありません。

インスタンスは `objc:standard-objc-object` です。`make-instance` は Objective-C オブジェクトを確保して初期化し
(`init`、または渡した `:init-function`)、Objective-C 側が確保したオブジェクトにも Lisp オブジェクトが作られます。
そのためどちらも `objc:objc-object-from-pointer` で逆にたどれます。`objc:objc-object-var-value` は
`(:objc-instance-vars ...)` で宣言したインスタンス変数を読みます。Lisp オブジェクトは Objective-C オブジェクトの
参照カウントが 0 になるまで生き続けます (`make-instance` が取った参照はプログラムが `objc:release` するものです)。
0 になると `dealloc` の中で `objc:objc-object-destroyed` が実行され、`copy` で作った複製には
`objc:objc-object-copied` が呼ばれます。通知の監視もメソッドで行います。

```console
MY-APP> (define-objc-class watcher ()
          ((seen :initform nil :accessor seen))
          (:objc-class-name "Watcher"))
WATCHER
MY-APP> (define-objc-method ("noticed:" :void) ((self watcher) (note objc-object-pointer))
          (push (invoke-into 'string note "name") (seen self)))
"noticed:"
MY-APP> (defvar *w* (make-instance 'watcher))
*W*
MY-APP> (cocoa:add-observer *w* "noticed:" :name "Ping")
NIL
MY-APP> (invoke (invoke "NSNotificationCenter" "defaultCenter")
                "postNotificationName:object:" "Ping" nil)
NIL
MY-APP> (seen *w*)
("Ping")
```

### ブロック

ブロックは Cocoa がクロージャを受け取る形です。比較関数、列挙の関数、完了ハンドラがそうです。`objc:make-objc-block` は Lisp の関数とシグネチャからブロックを作ります。シグネチャは呼ぶ側が示します。メソッドのエンコーディングはブロックを取ることしか表さず、そのブロックが何を取るかは表さないためです。ブロックを渡す場所に素の関数を渡すと、推測せずにシグナルします。シグネチャはリスト形式のメソッドと同じ型で書いた `(result-type (argument-type*))`、または `objc:define-objc-block-type` で付けた名前です。引数はメソッドの本体に渡る引数と同じように変換されて関数に渡り、関数の値は逆向きに変換されます。`objc:with-objc-block` は本体の間だけ有効なブロックを作り、どの脱出でも解放します。非同期の処理にもこれで足ります。ブロックを保持する呼び出し先はコピーを持ち、そのコピーが関数を生かすからです。`objc:call-objc-block` は、誰が作ったブロックでも呼べます。

```console
MY-APP> (defvar *words* (invoke "NSArray" "arrayWithObjects:" "pear" "fig" "apple"))
*WORDS*
MY-APP> (with-objc-block (compare '(:long-long (objc-object-pointer objc-object-pointer))
                                  (lambda (a b)
                                    (let ((x (ns-string-to-string a))
                                          (y (ns-string-to-string b)))
                                      (cond ((string< x y) -1) ((string> x y) 1) (t 0)))))
          (invoke-into '(array string) *words* "sortedArrayUsingComparator:" compare))
#("apple" "fig" "pear")
MY-APP> (with-objc-block (each '(:void (objc-object-pointer (:unsigned :long-long)
                                        (:pointer objc-c++-bool)))
                               (lambda (word index stop)
                                 (declare (ignore stop))
                                 (format t "~a ~a~%" index (ns-string-to-string word))))
          (invoke *words* "enumerateObjectsUsingBlock:" each))
0 pear
1 fig
2 apple
NIL
MY-APP> (defvar *add* (make-objc-block '(:int (:int :int)) (lambda (a b) (+ a b))))
*ADD*
MY-APP> (call-objc-block '(:int (:int :int)) *add* 3 4)
7
MY-APP> (free-objc-block *add*)
NIL
```

C 関数もブロックを取ります (libdispatch の関数など)。このパッケージが持つ LispWorks の外部言語インターフェースの一部、`fli:define-foreign-function` で同じ型を使って宣言します:

```console
MY-APP> (fli:define-foreign-function (dispatch-queue-create "dispatch_queue_create")
            ((label objc-c-string) (attributes :pointer))
          :result-type objc-object-pointer)
DISPATCH-QUEUE-CREATE
MY-APP> (fli:define-foreign-function (dispatch-async "dispatch_async")
            ((queue objc-object-pointer) (work objc-at-question-mark))
          :result-type :void)
DISPATCH-ASYNC
MY-APP> (defvar *queue* (dispatch-queue-create "com.example.work" nil))
*QUEUE*
MY-APP> (defvar *done* nil)
*DONE*
MY-APP> (with-objc-block (work '(:void ()) (lambda () (setq *done* t)))
          (dispatch-async *queue* work))
NIL
MY-APP> (sleep 0.1)
NIL
MY-APP> *done*
T
```

ブロックは呼んだスレッドで実行されます。Foundation は比較関数や列挙の関数を、送信したスレッドで呼びます。送信はすべてメインスレッドで実行されるため、それはメインスレッドです。シリアルキューは処理を、`NSURLSession` は完了ハンドラを、libdispatch のワーカーでプログラムと並行して実行します。そこでの関数は、スペシャル変数についてプログラムのスレッドの束縛ではなく大域値を見ます (クロージャ自身が捕捉したものは別です)。`rontolisp:make-thread` で始めたスレッドと同じです。`--native` 実行ファイルは別のスレッドで Lisp を実行できません。別のスレッドから呼ばれた `void` のブロックは、プログラムの次の `sleep` を待ってメインスレッドで実行されます。値を返すブロックは拒否され、そのことが表示されて 0 を返します。そのため、ブロックを待つプログラムは次のように `sleep` で待てば、どのターゲットでも同じように動きます:

```console
MY-APP> (defvar *status* nil)
*STATUS*
MY-APP> (with-objc-block (handler '(:void (objc-object-pointer objc-object-pointer
                                           objc-object-pointer))
                                  (lambda (data response error)
                                    (declare (ignore data error))
                                    (setq *status* (invoke response "statusCode"))))
          (invoke (invoke (invoke "NSURLSession" "sharedSession")
                          "dataTaskWithURL:completionHandler:"
                          (invoke "NSURL" "URLWithString:" "https://example.com/")
                          handler)
                  "resume"))
NIL
MY-APP> (loop until *status* do (sleep 0.05))
NIL
MY-APP> *status*
200
```

### LispWorks との違い

rontolisp には外部メモリのインターフェースがないため、構造体は `invoke` がそれについて受け渡す Lisp の値です (`cocoa:set-ns-rect*` はベクタを埋めます)。マニュアルの `fli:with-dynamic-foreign-objects` を使う形に対応するものはありません。LispWorks はブロックを `objc` ではなく外部言語インターフェースで作ります。`objc:make-objc-block` などの名前はこのパッケージ独自のもので、`fli` が持つのは `define-foreign-function` だけです。どの関数も最初の使用時にランタイムを開くため、`objc:ensure-objc-initialized` を先に呼ぶ必要はありません。ランタイムの可変長メソッドの表にあるメソッド (`stringWithFormat:`、`arrayWithObjects:` など) は文字列形式でも呼べます。追加の引数はそれぞれ値から型を決め、末尾に `nil` を加えます。メソッドの構造体の結果は Lisp の値として返すか、キーワードでない結果スタイルが名付ける変数に埋めます。それに対するマニュアルの `fli:foreign-slot-value` に対応するものはありません。

## ネイティブバイナリ

`rontolisp` バイナリはビルド時に登録された `objc_msgSend` の形の固定テーブルを提供します — `appkit` 層が送るすべての形に加え、AppKit と Foundation の中核クラスで最も多い 60 の形で、それらが宣言するメソッドの 10 のうち 9 に届きます。テーブルにないセレクタは追加すべきエントリをそのまま示してシグナルします:

```text
objc:send: someRareSelector: the shape void(void*,void*,jshort) has no foreign-call stub
in this binary; register it under foreign.downcalls in reachability-metadata.json and rebuild
```

JVM は事前に何も登録せずどんな形でもバインドするので、バイナリを作る前にプログラムが何を送るかを知る場所は `java -jar` です。

`objc:define-objc-method` で定義したメソッドはそれぞれの形のアップコールで、同じように登録します。バイナリは上のクラスの例の形と、Lisp で定義したどのクラスにも入る三つのメソッドの形を扱い、それ以外の形の定義は `foreign.upcalls` に追加すべきエントリを示して拒否します。ブロックも同じくアップコールです。バイナリは上のブロックの形 (と、比較関数、処理の単位、三つのオブジェクトを取る完了ハンドラの形) を扱い、それ以外の形のブロックは作る時点で拒否します。`java -jar` と `--native` 実行ファイルはどんな形でも受け付けます。

可変長引数の呼び出しは別個の登録になるため、バイナリはその有界なグリッドも提供します。宣言された引数を超えて 11 個まで (バインディングが付ける `nil` 終端子を含めて 12 個)、うち先頭 3 個までは数、残りはオブジェクトです。これより長い、あるいは数がこれより多いリストは同じようにシグナルします。

## JVM クラスへのコンパイル

同じプログラムは `.class` や `.jar` にコンパイルでき、素の `java` ランチャで動きます。ランチャはプロセスの最初のスレッドを自分でイベントループに留めます:

```console
$ rontolisp examples/macos/counter.lisp -o Counter.class --class-name Counter
$ java Counter
$ rontolisp examples/macos/counter.lisp -o counter.jar
$ java -jar counter.jar
```

クラスは使用する `appkit` ウィジェットを抱え、バインディング全体 (`am.ik.objc`、クラス名に合わせてリネーム済み) は `Counter$Objc*.class` ファイルとしてクラスの隣 (または jar の中) に書き出されます。それらのファイルがあれば、`java.lang.foreign` を持つ JVM (コンパイラが動いたものか、それより新しいもの) 以外には何も必要ありません。素の `.class` を `--enable-native-access=ALL-UNNAMED` なしで実行すると JDK の restricted-method 警告が一度出ますが動作します。`.jar` はマニフェストでネイティブアクセスを有効にします。`rontolisp` バイナリもそうしたプログラムをコンパイルできます。`.wasm` 出力は拒否され (`Cannot compile: appkit:window ...`)、今後もそうです: そちら側には foreign function API も AppKit もありません。

jar は設定なしで GraalVM ネイティブイメージにもビルドできます (`native-image -jar counter.jar`)。バインディングが必要とするネイティブイメージ用メタデータ、つまり `rontolisp` バイナリが扱うのと同じメッセージ形状の表を jar 自身が持っています (前述の[ネイティブバイナリ](#the-native-binary))。イメージではプログラムの `main` がプロセスの最初のスレッドで始まるので、`rontolisp` バイナリと同じく、`main` 自身がそのスレッドをイベントループに渡し、プログラムを別のスレッドで実行します。

## ネイティブ実行ファイル

`--native` は同じプログラムを JVM を必要としない約 2.4 MB の実行ファイル 1 つにコンパイルします (Apple シリコン。そうした Mac では既定のターゲット、または `--native-target macos-aarch64`):

```console
$ rontolisp examples/macos/counter.lisp --native -o counter
$ ./counter
```

実行ファイルのランナー自身がバインディングです。ランナーが Objective-C ランタイムを直接呼ぶので、ランタイムが記述するセレクタはどれでも送れます — `rontolisp` バイナリのような固定の形の表はありません — し、送信 1 回は 1 マイクロ秒に満たない時間です。プログラムは AppKit が求めるプロセスの最初のスレッドで動くので、`objc:on-main` はただの呼び出しで、スレッドの移動はありません。プログラムが `sleep` で待つ間 (`appkit:wait` がそうします) ウィンドウはイベントを処理してタイマーを動かし、ボタンのクロージャはその待ちの中で実行されます。標準入力を読むプログラムでは、読み込みが返るまでウィンドウが応答しません。

所有権はほかと同じです: Lisp の値 1 つにつき参照 1 つで、値がガベージになると解放されます。違いは 1 つだけで、同じオブジェクトを包む 2 つの値はここでは `equal` になりません (インタプリタはアドレスで比べます)。`appkit` 層がそうしているように、`objc:address` の値を比べてください。実行ファイルでは `objc:data` は `bfloat16` 配列と量子化行列を受け付けません。

## 制限

- macOS のみ: インタプリタ (`java -jar`、または `rontolisp` バイナリ)、コンパイル済み `.class` / `.jar`、Apple シリコン向けの `--native` 実行ファイル。`.wasm` は不可で、`objc:` / `appkit:` の参照はそれ以外のすべての WASM 出力でコンパイルエラーです。
- アプリケーションバンドルのないプロセスには Dock アイコンもメニューバーもありません。Cmd-Q はなく、最後のウィンドウを閉じても終了しません — REPL がプロセスです。
- `objc:define-class` のコールバックの形は上の閉じた集合です。`objc:define-objc-method` はどんな形でも受け付けます (`rontolisp` バイナリでは登録済みの形)。ブロックも同じです。
- `--native` 実行ファイルが別のスレッドから呼ばれたブロックを実行するのは、そのブロックが何も返さない場合だけで、実行はプログラムの次の `sleep` の時点です。
- 扱える可変長引数セレクタは上の表のものです。プログラム自身が宣言したものは含まれず、ランタイムにはそれを判別する手段がありません。
- Apple シリコン向け。Intel Mac では 2 レジスタより広い構造体は `objc_msgSend_stret` で返され、バインディングはそれを選びますが動作確認はしていません。
