;;;; rontolisp-intro.lisp -- an introduction to rontolisp for a programmer who has never
;;;; seen it: what it is, how to run it, the language, the backends, the libraries.
;;;;
;;;; Every fact comes from the repository (README.md, doc/ja, .kb, examples/,
;;;; bench-report/). The runnable blocks are small, so they finish at once on the
;;;; hosted browser runtime. Rendered by decks/build.sh.

(deck :title "rontolisp 入門"
      :author "rontolisp"
      :date "2026-10-23"
      :url "https://github.com/making/rontolisp"
      :description "Java で実装した Common Lisp 処理系 rontolisp の入門。インストールから Common Lisp・Scheme・Clojure の各言語、インタプリタ・JVM・WASM・ネイティブの各バックエンド、ライブラリまでを紹介する"
      :page-url "https://making.github.io/rontolisp/decks/rontolisp-intro/rontolisp-intro.html"
      :page-image "rontolisp-intro.png"
      :page-image-font ("fonts/BIZUDPGothic-Regular.ttf" "fonts/BIZUDPGothic-Bold.ttf")
      :theme (theme :base :light :code :dark)
      :aspect :16x9
      :runtime :hosted
      :kicker :section
      :agenda "話の流れ"
      :progress t
      :page-numbers t)

;;; --- what rontolisp is ------------------------------------------------------------

(section "rontolisp とは")

(slide :title "Java で実装した Common Lisp 処理系"
  (p "一つのソースを、三つの実行モードで動かす。")
  (ul (li (b "インタプリタ") " REPL とファイル実行。ツリーウォークで評価する")
      (li (b "JVM コンパイラ") " " (tt ".class") " や " (tt ".jar") " を出力する")
      (li (b "WASM コンパイラ") " wasm-GC を使った " (tt ".wasm") " を出力する"))
  (p "コアライブラリは外部依存を持たない。JVM と WASM のバイトコードは、ASM などのライブラリを使わずに生成する。"))

(slide :title "まず動かしてみる"
  (p "このデッキのコードは、ブラウザ上の rontolisp で実行できる。クリックして編集し、Run で実行する。")
  (say "Run で 3628800 が出る" :at "(fact 10)" :step t
    (code :lang "lisp" "
      (defun fact (n)
        (if (= n 0) 1 (* n (fact (- n 1)))))
      (print (fact 10))"))
  (notes "Run を押す。続けて (fact 30) に書き換えて実行し、多倍長整数になることを見せる。"))

(slide :title "Common Lisp、Scheme、Clojure を読む"
  (column
    (p (b "Common Lisp"))
    (code :lang "lisp" "
      ;; sq.lisp
      (defun sq (x) (* x x))
      (print (sq 12))"))
  (column
    (p (b "Scheme"))
    (code :lang "scheme" "
      ;; sq.scm
      (define (sq x) (* x x))
      (display (sq 12))"))
  (column
    (p (b "Clojure"))
    (code :lang "clojure" "
      ;; sq.clj
      (defn sq [x] (* x x))
      (println (sq 12))"))
  (p "どの言語のソースも同じコアフォームに変換するので、インタプリタ、JVM、WASM、ネイティブのどれでも動く。"))

;;; --- getting started --------------------------------------------------------------

(section "インストールと最初の実行")

(slide :title "インストール"
  (p "リリースページのビルド済みバイナリを " (tt "PATH") " に置く。JVM は要らない。")
  (code :lang "console" "
    $ URL=https://github.com/making/rontolisp/releases/download/0.1.0-SNAPSHOT
    $ wget $URL/rontolisp-darwin-arm64
    $ chmod +x rontolisp-darwin-arm64
    $ sudo mv rontolisp-darwin-arm64 /usr/local/bin/rontolisp
    $ rontolisp --version")
  (ul "Linux の x86-64 と ARM64 向けもある"
      "Java 25 以降があれば、実行可能 JAR でも動く"))

(slide :title "REPL"
  (p "引数なしで起動すると REPL になる。定義は入力をまたいで残る。")
  (code :lang "console" "
    $ rontolisp
    CL-USER> (+ 1 2)
    3
    CL-USER> (defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
    FACT
    CL-USER> (fact 10)
    3628800
    CL-USER> (quit)")
  (p "プロンプトには現在のパッケージ名が出る。括弧が閉じるまで複数行を読む。"))

(slide :title "ファイルを実行する"
  (column
    (code :lang "lisp" :run nil "
      ;; program.lisp
      (defun square (x) (* x x))
      (print (square 5))
      (print (square 12))"))
  (column
    (code :lang "console" "
      $ rontolisp program.lisp

      25
      144
      $ rontolisp -e \"(print (+ 1 2))\"

      3")
    (ul "REPL と違い、評価した値を表示しない"
        "エラーで終わると、終了コードが 0 以外になる")))

(slide :title "コマンドの一覧"
  (table :header ("コマンド" "動作")
    ((tt "rontolisp") "REPL")
    ((tt "rontolisp app.lisp") "ファイルをインタプリタで実行")
    ((tt "rontolisp -e \"(...)\"") "引数のプログラムを実行")
    ((tt "rontolisp app.lisp -o OUT") (cell "コンパイル。" (tt "OUT") " の拡張子で出力を選ぶ"))
    ((tt "rontolisp format src/") "ソースを正規のレイアウトにインデントし直す")
    ((tt "rontolisp test tests/main.lisp") "rove のテストを実行し、結果を終了コードで返す")))

;;; --- the language -----------------------------------------------------------------

(section "言語")

(slide :title "Common Lisp の主な機能"
  (table :header ("機能" "主なフォーム")
    ("マクロ" (cell (tt "defmacro") "、バッククォート、" (tt "macroexpand")))
    ("CLOS" (cell (tt "defclass") "、" (tt "defgeneric") "、" (tt "defmethod")))
    ("コンディションとリスタート" (cell (tt "handler-case") "、" (tt "restart-case")))
    ("多値" (cell (tt "values") "、" (tt "multiple-value-bind")))
    ("繰り返し" (cell (tt "loop") "、" (tt "dolist") "、" (tt "dotimes")))
    ("数値" "多倍長整数、有理数、浮動小数点数")))

(slide :title "ANSI Common Lisp テストスイート"
  (p "19,769 件のテストのうち " (accent (b "16,511 件")) " が通過している (83.5%)。")
  (ul "計測の対象はインタプリタ"
      "CI が毎日スイート全体を実行し、通過数が変わるたびに結果を記録する"
      "対応する機能を増やし、通過数を引き続き上げていく")
  (source "ansi-test/results/interpreter.md (2026-10-21 時点、スイートのリビジョン ca06bd9)"))

(slide :title "プログラムもリスト"
  (p "コードは S 式で書く。S 式はリストなので、マクロはリストを組み立ててコードを作る。")
  (cells '(progn (princ "hi ") (princ "hi ")))
  (code :lang "lisp" "
    (defmacro twice (&body body) `(progn ,@body ,@body))
    (print (macroexpand-1 '(twice (princ \"hi \"))))
    (twice (princ \"hi \"))"))

(slide :title "CLOS とコンディション"
  (code :lang "lisp" "
    (defclass circle () ((r :initarg :r :reader r)))
    (defclass square () ((side :initarg :side :reader side)))
    (defgeneric area (shape))
    (defmethod area ((c circle)) (* 3 (r c) (r c)))
    (defmethod area ((s square)) (* (side s) (side s)))
    (print (mapcar #'area (list (make-instance 'circle :r 2)
                                (make-instance 'square :side 3))))
    (handler-case (/ 1 0)
      (division-by-zero () :infinity))"))

(slide :title "Scheme のフロントエンド"
  (p "Scheme のコードでは、" (tt "(scheme base)") " などの R7RS-small のライブラリを " (tt "import") " して使う。")
  (code :lang "scheme" "
    (import (scheme base) (scheme write))
    (define (count-up n)
      (let loop ((i 0) (acc '()))
        (if (= i n)
            (reverse acc)
            (loop (+ i 1) (cons (* i i) acc)))))
    (display (count-up 5))"))

(slide :title "Clojure のフロントエンド"
  (p "Clojure のコードでは、" (tt "->>") " などのスレッディングマクロや、シーケンスを扱うコア関数を使える。")
  (code :lang "clojure" "
    (defn fact [n]
      (if (< n 2) 1 (* n (fact (- n 1)))))
    (println (fact 20))
    (println (->> (range 10) (filter odd?) (map #(* % %)) (reduce +)))
    (println (frequencies \"mississippi\"))"))

(slide :title "言語はファイルごとに決まる"
  (table :header ("拡張子" "言語")
    ((tt ".lisp .asd") "Common Lisp")
    ((tt ".scm") "Scheme")
    ((tt ".clj .cljc") "Clojure")
    ("それ以外" (cell (tt "--source-language") " で指定する")))
  (p "一つのプログラムに、複数の言語のファイルを混ぜられる。"))

;;; --- backends ---------------------------------------------------------------------

(section "バックエンド")

(slide :title "一つのパイプラインから三つの実行モードへ"
  (flow "ソース (CL、Scheme、Clojure)" "コアフォームに変換" "マクロ展開"
        ("インタプリタ、" (tt ".class") "、" (tt ".wasm")))
  (ul "言語の違いは最初の変換で吸収する"
      "バックエンドが受け取るのは、どの言語でも同じコアフォーム"))

(slide :title "出力の拡張子でバックエンドを選ぶ"
  (code :lang "console" "
    $ rontolisp hello.lisp                                # インタプリタ
    $ rontolisp hello.lisp -o Hello.class && java Hello   # JVM
    $ rontolisp hello.lisp -o hello.jar && java -jar hello.jar
    $ rontolisp hello.lisp -o hello.wasm && wasmtime run hello.wasm
    $ rontolisp hello.lisp -o hello-c.wasm --component && wasmtime run hello-c.wasm
    $ rontolisp hello.lisp --native -o hello && ./hello   # ネイティブ")
  (p "どの形でも " (tt "(print (+ 1 2))") " は 3 を出力する。"))

(slide :title "JVM と WASM"
  (column
    (p (b "JVM"))
    (ul (li (tt ".jar") " は " (tt "java -jar") " で動く")
        "Java のライブラリにもできる"))
  (column
    (p (b "WASM"))
    (ul (li "既定は WASI Preview 1")
        (li (tt "--component") " で WASI 0.3")
        (li (tt "--no-wasi") " でインポートなし")
        (li (tt "--no-gc") " で wasm-GC のないエンジン向け"))))

(slide :title "ネイティブ実行ファイル"
  (p (tt "--native") " は、WASM 出力を wasmtime でマシンコードにプリコンパイルし、小さなランナーの後ろに付ける。")
  (ul "実行に wasmtime も JVM も要らない"
      "起動は約 10 ms"
      (li (tt "--native-target") " で Linux の x86_64 と aarch64、macOS の aarch64 向けを作れる"))
  (source "doc/ja/compiling/native.md"))

(slide :title "インタプリタとコンパイル結果の速さ"
  (lead "fib ベンチマークの実行時間 (短いほど速い)")
  (say "ループ中心のコードは、コンパイルで 20〜200 倍速くなる" :at "インタプリタ"
    (bars :unit " ms" ("インタプリタ" "5,442") ("JVM" 60) ("WASM" 218)))
  (source "bench-report/results/benchmarks.md (2026-09-04, Linux x86_64, AMD EPYC 9V74)"))

(slide :title "インタプリタは試行錯誤に使う"
  (ul "すぐに起動し、コンパイル後と同じ意味で実行する"
      "I/O が中心のスクリプトなら、速さの差は表に出にくい"
      "CPU 負荷の高い処理は、コンパイルしてから動かす")
  (code :lang "console" "
    $ rontolisp program.lisp -o program.jar
    $ java -jar program.jar"))

(slide :title "ブラウザで動かす"
  (ul (li (b "プレイグラウンド") " rontolisp 自体を GraalVM の Web Image で WebAssembly にしたもの。"
          "REPL と、" (tt ".class") " や " (tt ".wasm") " へのコンパイルがブラウザの中で完結する")
      (li (b "ドキュメント") " リファレンスの Lisp の例は Run で実行できる")
      (li (b "このデッキ") " 同じブラウザ版がコードを実行している")
      (li (b "自分のプログラム") " WASM にコンパイルしてページから呼ぶ ("
          (tt "examples/browser/") " のマインスイーパーや WebGL)")))

;;; --- interop and libraries --------------------------------------------------------

(section "連携とライブラリ")

(slide :title "Java 連携"
  (p (tt "java") " パッケージの関数で、任意の Java API をリフレクション経由で呼べる。")
  (code :lang "lisp" :run nil "
    (java:call (java:new \"java.lang.StringBuilder\" \"ab\") \"length\")  ; => 2
    (java:static \"java.lang.Math\" \"max\" 3 7)                        ; => 7
    (java:field \"java.lang.Integer\" \"MAX_VALUE\")                    ; => 2147483647")
  (p "JVM 上のインタプリタと、コンパイルした " (tt ".class") " で使える。"))

(slide :title "ASDF で複数ファイルのプロジェクトを読む"
  (column
    (code :lang "lisp" :run nil "
      ;; my-app.asd
      (defsystem :my-app
        :depends-on (:base)
        :serial t
        :components ((:file \"package\")
                     (:file \"main\")))"))
  (column
    (ul (li (tt ".asd") " はプレーンなデータとして解析する")
        "依存するシステムから順に読み込む"
        (li (tt "--system-path") " で探す場所を足す")
        "インタプリタ、JVM、WASM のどれでも同じように動く")))

(slide :title "実在のライブラリをそのまま読む"
  (code :lang "console" "
    $ rontolisp
    CL-USER> (ql:quickload \"split-sequence\")
    (split-sequence)
    CL-USER> (split-sequence:split-sequence #\\, \"a,b,c\")
    (\"a\" \"b\" \"c\")")
  (ul (li (tt "ql:quickload") " は Quicklisp の配布物から取得し、" (tt "~/.rontolisp/quicklisp/") " にキャッシュする")
      "alexandria、cl-ppcre、cl-who、jzon、chipz などが、上流のソースのままインタプリタ、JVM、WASM で動く"))

(slide :title "そのほかの組み込みパッケージ"
  (table :header ("パッケージ" "用途")
         :caption "使えるバックエンドはパッケージごとに異なる。詳しくは各ガイドにある"
    ((tt "uiop") "環境変数、ファイル、ディレクトリなど")
    ((cell (tt "rove") "、" (tt "rontolisp test")) "テスト")
    ((tt "rontolisp:fetch") "HTTP リクエスト")
    ((tt "clack") "Web アプリケーション")
    ((cell (tt "linalg") "、" (tt "torch")) "線形代数とニューラルネットワーク")
    ((tt "cffi") "C ライブラリの呼び出し (JVM)")
    ((cell (tt "objc") "、" (tt "appkit")) "macOS の GUI")))

(slide :title "Web アプリを一つのソースで各環境へ"
  (p "Clack のアプリケーションを、ソースを変えずにビルドだけで各環境に合わせる。")
  (table :header ("ビルド" "動かす場所")
    ((tt "rontolisp app.lisp") "インタプリタ")
    ((tt "-o app.jar") "JVM、Google App Engine")
    ((tt "-o app.war") "Servlet コンテナ")
    ((tt "-o app.wasm --component") (cell (tt "wasmtime serve") " など"))
    ((tt "-o worker.wasm --no-wasi") "Cloudflare Workers"))
  (source "examples/net/httpbin-clack.lisp、examples/cloudflare-workers/、examples/gae/"))

(slide :title "行列演算を SIMD、BLAS、GPU で速くする"
  (p (tt "linalg") " と " (tt "vec") " の行列演算を、フラグでハードウェアに振り向ける。")
  (table :header ("フラグ" "計算する場所")
    ((tt "--simd") "CPU のベクトル命令")
    ((tt "--blas") "OS の BLAS (Accelerate、OpenBLAS など)")
    ((tt "--gpu") "NVIDIA の GPU (CUDA)、Mac の GPU (Metal)"))
  (ul "BLAS や GPU のないマシンでも、同じプログラムが同じ結果を出す"
      (li "128x128 の行列積 1 回が、インタプリタの 1,848 ms から " (tt "--simd") " で 0.62 ms、"
          (tt "--blas") " で 0.034 ms になる"))
  (source "doc/ja/guides/blas-acceleration.md、gpu-acceleration.md (計測は Apple M4 Max)"))

(slide :title "Mac アプリを作る"
  (column
    (ul (li (tt "objc") " で AppKit を呼ぶ")
        (li (tt "appkit") " でウィンドウやボタンを組み立てる")
        "インタプリタ、JVM、ネイティブ実行ファイルで動く"
        "追加のインストールは要らない"))
  (column
    (image "macos-minesweeper.png" :alt "rontolisp で書いた macOS のマインスイーパー"))
  (source "examples/browser/minesweeper/minesweeper-macos.lisp"))

(slide :title "Metal で GPU に描く"
  (p (tt "metal") " パッケージで Metal を呼び、約 13,000 個のボクセルからなる庭園をアニメーションで描く。")
  (image "macos-pagoda-garden.png" :alt "Metal で描いたボクセルの庭園と五重塔")
  (source "examples/macos/metal-pagoda-garden.lisp"))

;;; --- next steps -------------------------------------------------------------------

(section "次の一歩")

(slide :title "examples ディレクトリ"
  (table :header ("ディレクトリ" "内容")
    ((tt "console/") "アルゴリズムとコンソール入出力")
    ((cell (tt "scheme/") " " (tt "clojure/")) "Scheme と Clojure のプログラム")
    ((cell (tt "ml/") " " (tt "llm/")) "数値計算、機械学習、Llama 2 の推論")
    ((cell (tt "net/") " " (tt "db/")) "ソケット、HTTP サーバ、PostgreSQL")
    ((cell (tt "jvm/") " " (tt "macos/")) "Java 連携、Swing、Cocoa、Metal")
    ((tt "browser/") "ページで動かす WASM")
    ((tt "asdf/") "実在のライブラリの読み込み")))

(slide :title "次に読むもの"
  (ul (li (link "https://making.github.io/rontolisp/docs/" "ドキュメント")
          " はじめに、コンパイル、言語リファレンス、ガイド")
      (li (link "https://making.github.io/rontolisp/playground.html" "プレイグラウンド")
          " インストールせずに試す")
      (li (link "https://making.github.io/rontolisp/skill/" "エージェントスキル")
          " AI コーディングエージェント向けに、ドキュメントから生成したもの")
      (li (link "https://github.com/making/rontolisp" "GitHub")
          " ソースと " (tt "examples/"))))

(slide :class "closing" "インタプリタで試し、同じソースを JVM、WASM、ネイティブで動かす。")
