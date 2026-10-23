;;;; intro.lisp -- a talk introducing lispresen, and the golden input of check.sh.
;;;;
;;;; Each feature comes as a pair of slides: the first shows the deck code that writes it,
;;;; the next shows the result. A feature the player provides gets instructions and then a
;;;; slide to try it on.

(deck :title "lispresen: S式で書くプレゼンテーション"
      :author "rontolisp"
      :date "2026-10-23"
      :logo (image "logo.svg" :alt "rontolisp")
      :url "https://github.com/making/rontolisp/tree/develop/lispresen"
      :theme (theme :base :light :code :dark)
      :aspect :16x9
      :runtime :hosted
      :kicker :section
      :agenda "話の流れ"
      :progress t
      :page-numbers t)

;; The deck code an explanation slide shows: highlighted, never run.
(define sexp (text)
  (code :lang "lisp" :run nil text))

;;; --- what lispresen is, and why S-expressions -------------------------------------

(section "lispresen とは")

(slide :title "S式のデッキから HTML を一つ作る"
  (flow (tt "talk.lisp") "lispresen" (tt "talk.html") "ブラウザ")
  (ul "書き手は内容だけを書く"
      "文字の大きさと配置は lispresen が決める"
      "できた HTML ファイル一つで発表できる"))

(slide :title "三つの特長"
  (ol (li (b "読めない文字を出さない")
          (ul "収まらないスライドは、文字を縮めずにエラーにする"))
      (li (b "スライドのコードを動かす")
          (ul "Lisp、Scheme、Clojure をその場で編集して実行する"))
      (li (b "どこで描画しても同じ出力")
          (ul "インタプリタ、JVM、WASM、SBCL が同じバイト列を書く"))))

(slide :title "なぜS式で書くのか"
  (ul "差分をコードと同じようにレビューできる"
      (li "構造が明示される"
          (ul "インデントに意味を持たせない"
              "見えないスタイルがない"))
      "プログラムで生成したデッキもただのリスト")
  (notes "Markdown や WYSIWYG のツールと比べて話す。"))

(slide :title "デッキは読むだけで評価しない"
  (p "トップレベルのフォームを一つずつ " (tt "read") " する。")
  (ul (li "マクロも " (tt "#.") " も使えない")
      "どのバックエンドでも同じ言語になる"
      "信頼できないデッキも安全に描画できる")
  (notes "フォームごとに読むので、診断には行番号が付く。"))

;;; --- writing a deck ---------------------------------------------------------------

(section "デッキを書く")

(slide :title "段落と箇条書きの書き方"
  (p (tt "slide") " の中に、ブロックを上から順に並べる。")
  (sexp "
    (slide :title \"段落と箇条書き\"
      \"文字列だけでも段落になる。\"
      (ul \"ul は記号付きの箇条書き\"
          (li \"入れ子は\" (ul \"二段まで\")))
      (ol \"ol は番号付き\" \"7 項目からは警告する\")
      (notes \"発表者ウィンドウにだけ出る。\"))"))

(slide :title "段落と箇条書き"
  "文字列だけでも段落になる。"
  (ul "ul は記号付きの箇条書き"
      (li "入れ子は" (ul "二段まで")))
  (ol "ol は番号付き" "7 項目からは警告する")
  (notes "発表者ウィンドウにだけ出る。"))

(slide :title "文字の装飾の書き方"
  (p "装飾もフォームで書き、段落の中に並べる。")
  (sexp "
    (lead \"lead は控えめな色の段落\")
    (p (b \"太字\") \"、\" (i \"italic\") \"、\" (tt \"(code)\") \"、\"
       (mark \"マーカー\") \"、\" (accent \"アクセント色\") \"、\"
       (link \"https://github.com/making/rontolisp\" \"リンク\"))"))

(slide :title "文字の装飾"
  (lead "lead は控えめな色の段落")
  (p (b "太字") "、" (i "italic") "、" (tt "(code)") "、"
     (mark "マーカー") "、" (accent "アクセント色") "、"
     (link "https://github.com/making/rontolisp" "リンク")))

(slide :title "表の書き方"
  (p "行はセルのリスト。数値だけの列は右に揃う。")
  (sexp "
    (table :header (\"種類\" \"フォーム\" \"数\")
           :row-header t :align (:left :left :right)
           :caption \"どれもリストとして書く\"
      (\"図\" (tt \"flow cells bars\") \"3\")
      ...
      ((cell :colspan 2 \"合計\") \"27\"))"))

(slide :title "デッキの要素"
  (table :header ("種類" "フォーム" "数")
         :row-header t :align (:left :left :right)
         :caption "どれもリストとして書く"
    ("トップレベル" (tt "deck section slide define") "4")
    ("ブロック" (tt "p lead ul ol code quote column image table") "9")
    ("部品" (tt "li cell") "2")
    ("図" (tt "flow cells bars") "3")
    ("インライン" (tt "b i tt mark accent link") "6")
    ("注釈" (tt "say notes source") "3")
    ((cell :colspan 2 "合計") "27")))

(slide :title "引用と出典の書き方"
  (p (tt "source") " はスライドの下端に出典を一行で置く。")
  (sexp "
    (quote :cite \"Alan J. Perlis\"
      \"A language that doesn't affect the way ...\")
    (source \"Alan J. Perlis, \" (i \"Epigrams on Programming\")
            \", SIGPLAN Notices 17(9), 1982\")"))

(slide :title "考え方を変える言語"
  (quote :cite "Alan J. Perlis"
    "A language that doesn't affect the way you think about programming is not worth knowing.")
  (source "Alan J. Perlis, " (i "Epigrams on Programming")
          ", SIGPLAN Notices 17(9), 1982"))

(slide :title "コードの書き方"
  (p "文字列の共通の字下げは取り除く。" (tt "console") " はシェルの記録として描く。")
  (sexp "
    (code :lang \"lisp\" :run nil \"
      (defun square (x)
        (* x x))\")
    (code :lang \"console\" \"
      $ lispresen/check.sh
      ok   interpreter\")"))

(slide :title "コードとコンソール"
  (code :lang "lisp" :run nil "
    (defun square (x)
      (* x x))")
  (code :lang "console" "
    $ lispresen/check.sh
    ok   interpreter
    ok   jvm
    skip native: the jar carries no precompiler for this platform (linux-x86_64)"))

(slide :title "段組みの書き方"
  (p "続けて書いた " (tt "column") " が横に並ぶ。前後のブロックは全幅になる。")
  (sexp "
    (lead (b \"問題\") \" ... → \" (b (accent \"解決\")) \" ...\")
    (column
      (code :lang \"lisp\" :run nil \"...\"))
    (column
      (ul \"コードの列は ...\" \"残りを ...\"))"))

;; A lead above a row of columns; the columns' widths come from their content.
(slide :title "列の幅は内容で決まる"
  (lead (b "問題") " 等幅の列ではコードの行が収まらない → "
        (b (accent "解決")) " 列ごとに必要な幅を測って配分する")
  (column
    (code :lang "lisp" :run nil "
      (row-widths row k c (- 1920 224))"))
  (column
    (ul "コードの列は最長の行が収まる幅を取る"
        "残りを文章の列が使う")))

(slide :title "吹き出しの書き方"
  (p "指す先を " (tt "say") " で包む。コードの中は " (tt ":at") " の文字列で指す。")
  (sexp "
    (li \"日本語の改行は \"
        (say \"BudouX のモデルで決める\" (mark \"文節ごと\")))
    (say \"n が 2 未満なら n を返す\" :at \"(< n 2)\"
      (code :lang \"lisp\" :run nil \"...\"))"))

(slide :title "吹き出しで指す"
  (ul "置き場所は lispresen が探す"
      (li "日本語の改行は " (say "BudouX のモデルで決める" (mark "文節ごと"))))
  (say "n が 2 未満なら n を返す" :at "(< n 2)"
    (code :lang "lisp" :run nil "
      (defun fib (n)
        (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))")))

(slide :title "流れ図の書き方"
  (p "箱を左から右へ矢印でつなぐ。" (tt ":at") " で箱の一つを指せる。")
  (sexp "
    (say \"テンプレートもここで展開する\" :at \"parse\"
      (flow \"read\" \"parse\" \"segment\"
            \"layout\" \"lint\" \"emit\"))"))

(slide :title "lispresen の処理の流れ"
  (say "テンプレートもここで展開する" :at "parse"
    (flow "read" "parse" "segment" "layout" "lint" "emit")))

(slide :title "コンスセルの書き方"
  (p (tt "cells") " はリストを箱と矢印で描く。ドット対も描ける。")
  (sexp "
    (column
      (say \"car はセルの下に描く\" :at \"slide\"
        (cells '(slide :title \"t\" (p \"x\")))))
    (column (cells '((a . 1) (b . 2))))"))

;; The cdr chain on one row, each car below its cell, a dotted pair's cdr to its right.
(slide :title "スライドもリスト"
  (column
    (say "car はセルの下に描く" :at "slide"
      (cells '(slide :title "t" (p "x")))))
  (column (cells '((a . 1) (b . 2)))))

(slide :title "画像の書き方"
  (p "画像は残りの場所に収まる。" (tt "--embed") " で HTML に埋め込む。")
  (sexp "
    (say \"--embed で HTML に埋め込んだ\"
      (image \"pipeline.png\"
             :alt \"read から emit までの六段階\"
             :caption \"PNG はテーマの色に従わない\"))"))

(slide :title "画像"
  (say "--embed で HTML に埋め込んだ"
    (image "pipeline.png"
           :alt "read, parse, segment, layout, lint, emit の六段階"
           :caption "PNG はテーマの色に従わない")))

(slide :title "テンプレートの書き方"
  (p (tt "define") " で名前を付け、使う所を本体で置き換える。評価はしない。")
  (sexp "
    (define side (head &rest points)
      (column (p (b head)) (ul points)))

    (slide :title \"インタプリタとコンパイラ\"
      (side \"インタプリタ\" \"起動が速い\" \"eval が完全\")
      (side \"コンパイラ\" \"実行が速い\" \"成果物が一つ\"))"))

;; A template: a use is replaced by the body, &rest spliced where it appears.
(define side (head &rest points)
  (column (p (b head)) (ul points)))

(slide :title "インタプリタとコンパイラ"
  (side "インタプリタ" "起動が速い" "eval が完全")
  (side "コンパイラ" "実行が速い" "成果物が一つ"))

;;; --- layout -------------------------------------------------------------------------

(section "レイアウトはツールが決める")

(slide :title "文字の大きさは自動で決まる"
  (p "スライドごとに、収まる最大の段を選ぶ。")
  (ol (li "どのスライドも 1920 x 1080 (" (tt ":aspect :4x3") " なら 1440 x 1080) で組み、画面に合わせて拡大する")
      "本文は 56px から始め、必要なときだけ 40px まで下げる"
      "40px でも収まらなければ、縮めずにエラーとして報告する"))

(slide :title "大きさの段階"
  (table :header ("役割" "既定" "警告の境" "下限")
         :row-header t
         :caption "単位は px。警告の境を下回ると警告し、下限でも収まらなければエラーにする"
    ("題" "80" "64" "56")
    ("本文" "56" "48" "40")
    ("コード" "44" "32" "28")
    ("表" "48" "40" "36")
    ("吹き出し" "44" "40" "36")
    ("図のラベル" "48" "40" "36")))

;; :layout names the layout the slide's shape already gives, so it changes nothing.
(slide :title "レイアウトはスライドの形で決まる" :layout :table
  (table :header ("スライドの形" "レイアウト")
         :caption ":layout で選ぶこともできる"
    ("題がなく、段落が一つ" (tt "statement"))
    ("題と画像が一つ" (tt "image"))
    ("題とコードが一つ" (tt "code"))
    ("題と表が一つ" (tt "table"))
    ("題と引用が一つ" (tt "quote"))
    ((cell (tt "column") " を含む") (tt "columns"))
    ("それ以外" (tt "standard"))))

(slide :title "一文だけのスライドの書き方"
  (p "題を付けずに段落を一つだけ置くと、大きな文字で中央に置く。")
  (sexp "
    (slide \"読みやすさは、書き手ではなくツールが守る。\")"))

(slide "読みやすさは、書き手ではなくツールが守る。")

(slide :title "読みにくさを報告する"
  (code :lang "console" "
    $ java -jar lispresen.jar talk.lisp --check
    talk.lisp:42: slide 7 \"比較\": warning: a list with 8 items is hard to follow; keep it to 6")
  (ul (li "診断には行番号が付く。" (tt "--strict") " なら警告でも失敗する")
      "文字色と背景のコントラストも検査する"))

(slide :title "発表するマシンのフォントで測り直す"
  (p "発表するマシンのフォントは、lispresen が幅を測ったフォントと違うことがある。")
  (ul "開いたときに、各スライドの高さを測る"
      "はみ出す所だけ、段を一つずつ下げる"
      (li "URL に " (tt "?check") " を付けると、下げた所を一覧する")))

(slide :title "測り直しを確かめる"
  (ol (li "URL の末尾に " (tt "?check") " を付けて開き直す")
      (li "右上に " (tt "runtime check: no corrections") " と出れば、補正はない")))

;;; --- presenting -------------------------------------------------------------------

(section "発表する")

(slide :title "キー操作"
  (table :header ("キー" "動作")
         :caption "スワイプでも送れる。印刷すると 1 枚 1 ページの PDF になる"
    ((tt "→ ↓ Space PageDown") "次へ")
    ((tt "← ↑ PageUp") "前へ")
    ((tt "Home End") "最初と最後")
    ((tt "F") "全画面 (ダブルクリックでも)")
    ((tt "O") "全スライドの一覧")
    ((tt "P") "発表者ウィンドウ")
    ((tt "E") "コードの編集")))

(slide :title "操作してみる"
  (ol "← で一枚戻り、→ で戻ってくる"
      "O で一覧を開き、矢印で選んで Enter で開く"
      "F で全画面にし、もう一度 F で戻る"
      (li "URL の末尾を " (tt "#5") " にして開き直す")))

(slide :title "発表者ウィンドウ"
  (p (tt "P") " で開く。どちらのウィンドウで送っても、もう一方が付いてくる。")
  (sexp "(notes \"このノートは発表者ウィンドウにだけ出る。\")")
  (ul "今のスライドと次のスライド"
      "スライドの話者ノート"
      "タイマー (クリックで 0 に戻る)"
      "Start で、もう一つのディスプレイに全画面で映す"))

(slide :title "発表者ウィンドウを開く"
  (ol "P を押す"
      "このスライドのノートを読む"
      "→ で送り、こちらのウィンドウも進むことを確かめる")
  (notes "このノートは発表者ウィンドウにだけ出る。"))

(slide :title "ページ送りの書き方"
  (p "ページ送りのたびに一つずつ見せる。隠れている間も場所を取る。")
  (sexp "
    (ol :step t \"リストの項目\" \"コードの行\"
        (li (say \"項目の後に出る\" :step t \"吹き出し\")))
    (code :steps ((1 2) \"(fib 20)\") ...)"))

(slide :title "一つずつ見せる"
  (ol :step t "リストの項目" "コードの行"
      (li (say "項目の後に出る" :step t "吹き出し")))
  (p "→ で順に出し、← で一つずつ戻す。"
     (tt "(deck :callouts :step)") " ならどの吹き出しも送りで出る。"))

(slide :title "コードを動かす書き方"
  (p "Lisp、Scheme、Clojure のコードはスライドの上で動く。")
  (sexp "
    (say \"Run で 6765 が出る\" :at \"(fib 20)\" :step t
      (code :file \"fib.lisp\" :lines (4 6)
            :steps ((1 2) \"(fib 20)\")))")
  (ul "クリックか E で編集し、Ctrl+Enter か Run で実行する"
      "Reset でデッキに書いたコードに戻す"
      "出力はスライドに重ねて開く。↑ ↓ でスクロールし、× か Esc で閉じる"))

;; Page advances bring the definition forward, then the call, then the callout on it; the
;; code is lines of a file beside the deck, its language from the extension.
(slide :title "その場で動かす"
  (say "Run で 6765 が出る" :at "(fib 20)" :step t
    (code :file "fib.lisp" :lines (4 6) :steps ((1 2) "(fib 20)")))
  (notes "Run を押す。続けて (fib 25) に書き換えて実行し、Reset で戻す。"))

(slide :title "前のスライドの定義を使う"
  (p "同じ言語のコードは定義を共有する。前のスライドで Run してから実行する。")
  (code :lang "lisp" "(mapcar #'fib '(1 2 3 4 5 6 7 8 9 10))"))

(slide :title "書き換えを動きで見せる書き方"
  (p (tt ":morph t") " のスライドに入ると、前のスライドの最初のコードから変わる。")
  (sexp "
    (slide :title \"ループで書き直す\" :morph t
      (code :lang \"lisp\" \"
        (defun fib (n)
          (loop for a = 0 then b
                and b = 1 then (+ a b)
                repeat n
                finally (return a)))\"))")
  (p "共通のトークンは移動し、消えるものは薄れ、増えるものは現れる。"))

(slide :title "再帰で書く"
  (code :lang "lisp" "
    (defun fib (n)
      (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
    (fib 20)"))

;; :morph: on a page advance the code changes from the previous slide's, the tokens the
;; two share moving to their new place.
(slide :title "ループで書き直す" :morph t
  (code :lang "lisp" "
    (defun fib (n)
      (loop for a = 0 then b
            and b = 1 then (+ a b)
            repeat n
            finally (return a)))
    (fib 20)"))

(slide :title "Clojure と Scheme の書き方"
  (p (tt ":timeout") " 秒で止める。" (tt ":fresh t") " は新しいインタプリタで、"
     (tt ":stdin") " を標準入力にして動かす。")
  (sexp "
    (code :lang \"clojure\" :timeout 5 \"...\")
    (code :lang \"scheme\" :fresh t :stdin \"3 4\" \"...\")"))

(slide :title "Clojure と Scheme"
  (column
    (code :lang "clojure" :timeout 5 "
      (defn square [x] (* x x)) ; a comment
      (map square (range 10))"))
  (column
    (code :lang "scheme" :fresh t :stdin "3 4" "
      (define (sq x) (* x x))
      (display (+ (sq (read)) (sq (read))))
      (newline)")))

(slide :title "ランタイムの置き場所"
  (lead "ランタイムは rontolisp のブラウザ版で、約 35 MB ある")
  (ul (li (tt ":runtime :hosted") " 開いたときにネットワークから読む (既定)")
      (li (tt ":runtime \"runtime/\"") " デッキの横から読む")
      (li (tt ":runtime :none") " コードを動かさずに表示する"))
  (code :lang "console" "
    $ cp web/dist/rontoplayground.js* talk/runtime/
    $ jwebserver -d \"$PWD/talk\""))

;;; --- looks ---------------------------------------------------------------------------

(section "見た目を選ぶ")

(slide :title "テーマの書き方"
  (p "組み込みは " (tt ":light") "、" (tt ":dark") "、" (tt ":high-contrast") "、"
     (tt ":midnight") "。個々の色は名前で差し替える。")
  (sexp "
    (deck :theme :midnight)
    (deck :theme (theme :base :dark :accent \"#ff9f43\"))
    (deck :theme (theme :base :light :code :dark))")
  (p "文字色はどれも、背景とのコントラスト比 4.5:1 以上を検査する。"))

(slide :title "明るい地に暗いコード"
  (lead "このデッキは :light を基に、コードの色だけ :dark にした")
  (code :lang "lisp" :run nil "
    (list \"string\" :keyword 42 nil) ; a comment")
  (p "本文の " (tt "tt") "、" (mark "マーカー") "、" (accent "アクセント色")
     " も同じテーマの色で描く。")
  (p (tt "--theme midnight") " を付けて描き直すと、このデッキが紺と金になる。"))

(slide :title "道しるべの書き方"
  (p "表紙、目次、題の上の小見出し、進み具合はデッキの冒頭で決める。")
  (sexp "
    (deck :title \"lispresen\" :author \"rontolisp\"
          :logo (image \"logo.svg\" :alt \"rontolisp\")
          :url \"https://github.com/making/rontolisp\"
          :agenda \"話の流れ\" :kicker :section
          :progress t :page-numbers t)
    (slide :kicker \"スライドごとにも書ける\" ...)
    (slide :kicker nil ...)"))

(slide :title "道しるべを確かめる" :kicker "スライドごとにも書ける"
  (ol (li "Home で表紙に戻り、ロゴと URL の QR コードを見る")
      "節の始まりの目次では、今の節が強調される"
      "題の上の小見出しは節の題。このスライドだけ別の文にした"
      "下端のバーとページ番号が今の位置を示す"))

;;; --- outputs and backends ---------------------------------------------------------

(section "出力と始め方")

(slide :title "描画は文字列から文字列への関数"
  (sexp "
    (lispresen:render source &key theme runtime read-file embed)
    ;; => (values html diagnostics)")
  (ul "入出力をしないので、CLI からもブラウザからも呼べる"
      "整数だけで計算するので、どこでも同じバイト列になる"))

(slide :title "どのバックエンドでも同じ HTML"
  (table :header ("バックエンド" "成果物" "実行")
         :caption "check.sh が六つの出力をバイト単位で比べる"
    ("インタプリタ" "なし" (tt "rontolisp cli.lisp"))
    ("JVM" (tt ".jar") (tt "java -jar"))
    ("WASM" (tt ".wasm") (tt "wasmtime run"))
    ("WASM component" (tt ".wasm") (tt "wasmtime run"))
    ("ネイティブ" "実行ファイル" (tt "./lispresen"))
    ("SBCL" "なし" (tt "sbcl --script cli.lisp"))
    ("ブラウザ" (cell :colspan 2 (tt "web/") " のエディタが入力のたびに描き直す"))))

(slide :title "棒グラフの書き方"
  (p "値は整数か、文字列で書いた小数。単位は書いたとおりに付く。")
  (sexp "
    (say \"JVM なしで動く\" :at \"ネイティブ\"
      (bars :unit \" KB\" (\"JVM (jar)\" 297) (\"WASM\" 788)
            (\"WASM component\" 796) (\"ネイティブ\" \"6,700\")))
    (source \"2026-10-22, macOS arm64 で測定\")"))

;; A bar chart: a value is an integer or a decimal written as a string, the unit written
;; after it; the callout names a bar by its label. The source stands at the bottom.
(slide :title "コンパイルした CLI の大きさ"
  (say "JVM なしで動く" :at "ネイティブ"
    (bars :unit " KB" ("JVM (jar)" 297) ("WASM" 788) ("WASM component" 796)
          ("ネイティブ" "6,700")))
  (source "2026-10-22, macOS arm64 で測定 ("
          (link "https://github.com/making/rontolisp/blob/develop/lispresen/DESIGN.md"
                "DESIGN.md") ")"))

(slide :title "コマンドライン"
  (code "
    lispresen DECK [-o OUT] [--theme NAME] [--runtime URL|hosted|none]
                   [--embed] [--check] [--strict]")
  (ul (li (tt "-") " で標準入力から読み、標準出力に書く")
      (li (tt "--embed") " で画像を埋め込み、ファイル一つで完結させる")
      (li (tt "--theme") " でデッキのテーマを差し替える")
      (li "終了コードは 0 が成功、1 がエラー、2 が使い方の誤り")))

;; :kicker nil drops the section title the deck puts above this slide's title.
(slide :title "始め方" :kicker nil
  (code :lang "console" "
    $ ./mvnw package
    $ JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
    $ java -jar $JAR lispresen/cli.lisp -- talk.lisp
    $ open talk.html")
  (p "見本はこのデッキ、" (tt "lispresen/examples/intro.lisp") "。"))

;; :class adds a class to the slide's <section>; no built-in style uses this one.
(slide :class "closing" "スライドも、コードと同じように書いて、レビューして、動かす。")
