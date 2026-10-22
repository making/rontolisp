;;;; intro.lisp -- a deck presenting lispresen itself, and the golden input of check.sh.

(deck :title "lispresen: S式で書くプレゼンテーション"
      :author "rontolisp"
      :date "2026-10-22"
      :logo (image "logo.svg" :alt "rontolisp")
      :url "https://github.com/making/rontolisp/tree/develop/lispresen"
      :theme (theme :base :light :code :dark)
      :kicker :section
      :agenda t
      :page-numbers t)

(section "なぜS式で書くのか")

(slide :title "スライドはデータ"
  (ul :step t "差分をコードと同じようにレビューできる"
      (li "構造が明示される"
          (ul "インデントに意味を持たせない"
              "見えないスタイルがない"))
      "生成したデッキもただのリスト")
  (notes "デッキは read するだけで評価しないことに触れる。"))

(slide "読みやすさは、書き手ではなくツールが守る。")

(section "レイアウトはツールが決める")

(slide :title "文字の大きさは自動で決まる"
  (p "スライドごとに、収まる最大の段を " (b "段階表") " から選ぶ。")
  (ol "本文は 56px から 40px まで"
      "最小でも収まらなければエラーとして報告する"
      (li "日本語は " (say "BudouX のモデルで区切る" (mark "文節")) " で改行する")))

;; A diagram from an S-expression; the callout names one of its boxes.
(slide :title "全体像"
  (say "テンプレートもここで展開する" :at "parse"
    (flow "read" "parse" "segment" "layout" "lint" "emit")))

;; The cdr chain on one row, each car below its cell, a dotted pair's cdr to its right.
(slide :title "リストは箱と矢印になる"
  (lead (tt "cells") " はリストのコンスセルをそのまま描く")
  (column (cells '(slide :title "t" (p "x"))))
  (column (cells '((a . 1) (b . 2)))))

(slide :title "画像も置ける"
  (image "pipeline.png"
         :alt "read, parse, segment, layout, lint, emit の六段階"
         :caption "同じ流れを画像で描いたもの。テーマの色には従わない"))

(slide :title "デッキの読み方"
  (p "トップレベルのフォームを一つずつ " (tt "read-from-string") " で読む。")
  (code :lang "lisp" :run nil "
    (multiple-value-bind (form end)
        (read-from-string source t nil :start pos)
      (push (list form line) forms)
      (setf pos end))"))

;; Page advances bring the definition forward, then the call, then the callout on it; the
;; code is lines of a file beside the deck, its language from the extension.
(slide :title "その場で動かす"
  (say "Run で 6765 が出る" :at "(fib 20)" :step t
    (code :file "fib.lisp" :lines (4 6) :steps ((1 2) "(fib 20)"))))

(slide :title "前のスライドの定義を使う"
  (p "同じ言語のコードは一つの環境を共有する。")
  (code :lang "lisp" "(mapcar #'fib '(1 2 3 4 5 6 7 8 9 10))"))

(slide :title "Clojure も動く"
  (code :lang "clojure" :timeout 5 "
    (defn square [x] (* x x)) ; a comment
    (println :squares)
    (map square (range 10))"))

;; A template: a use is replaced by the body, &rest spliced where it appears.
(define side (head &rest points)
  (column (p (b head)) (ul points)))

(slide :title "インタプリタとコンパイラ"
  (side "インタプリタ" "起動が速い" "eval が完全")
  (side "コンパイラ" "実行が速い" "成果物が一つ" "どのバックエンドでも同じ出力"))

;; A lead above a row of columns; the columns' widths come from their content.
(slide :title "列の幅は内容で決まる"
  (lead (b "問題") " 等幅の列ではコードの行が収まらない → "
        (b (accent "解決")) " 列ごとに必要な幅を測って配分する")
  (column
    (code :lang "lisp" :run nil "
      (row-widths row k c (- 1920 224))")
    (code :lang "console" "
      $ lispresen/check.sh
      ok   interpreter
      ok   jvm
      skip native: the jar carries no precompiler for this platform (linux-x86_64)"))
  (column
    (ul "コードの列は最長の行が収まる幅を取る"
        "残りを文章の列が使う"
        "出力の長い行は端末のように折り返す")))

(slide :title "引用"
  (quote :cite "Alan Perlis" "A language that doesn't affect the way you think about programming is not worth knowing."))

(slide :title "バックエンドの比較"
  (table :header ("バックエンド" "起動" "成果物" "コマンド")
         :caption "どれも同じ HTML を出力する"
    ("インタプリタ" "速い" "なし" (tt "rontolisp cli.lisp"))
    ("JVM" "普通" (tt ".jar") (tt "java -jar"))
    ("WASM" "速い" (tt ".wasm") (tt "wasmtime run"))))

(slide :title "表の数値は右に揃う"
  (table :header ("項目" "前年" "今年" "増減")
         :row-header t
    ("スライド数" "24" "31" "+7")
    ("平均文字数" "1,204" "986" "-18.1%")
    ("警告" "12" "--" "-12")))
