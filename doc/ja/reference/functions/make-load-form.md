# make-load-form

`(make-load-form object &optional environment)`

コンパイル対象のコードにオブジェクトがリテラルとして現れたとき、**コンパイラ**が参照する総称関数です。メソッドはそのオブジェクトを再構築するフォームを返し、コンパイル後のプログラムではオブジェクトがそのフォームに置き換わります。

オブジェクトがコードに入り込むのは、マクロが生きたオブジェクトを自身の展開結果に埋め込むときです——ライブラリがこれを必要とする標準的な理由がそれです。CFFI は引数か戻り値の型が `defcenum` である `defcfun` のすべてでこれを行い、オブジェクトがコンパイルを生き延びるようメソッドを定義しています。

[`print-object`](print-object.md) と同じくシステムメソッドはありません: コンパイル経路は、いずれかのメソッドが特定化している型だけを経由させます。どのメソッドも扱わない型は従来どおりスロットを書き出す形でダンプされるので、`make-load-form` メソッドを定義しないプログラムのコンパイル結果はこれまでと変わりません。2値を返す形式にも対応しています: メソッドは生成フォームと初期化フォームを返すことができ、初期化フォームは生成直後のオブジェクトに対して実行されます。

生成フォームが評価されるのは**プログラムの実行あたり1回**であって使用箇所ごとではありません。したがってフォームが高価なメソッド(外部型のパース、スキャナのコンパイル)でも、その作業は一度きりです。

```lisp
(defstruct mlf-pt x y)
(defmethod make-load-form ((p mlf-pt) &optional env)
  (declare (ignore env))
  (list 'make-mlf-pt :x (mlf-pt-x p) :y (mlf-pt-y p)))
(make-load-form (make-mlf-pt :x 1 :y 2))
; => (MAKE-MLF-PT :X 1 :Y 2)
```

コンパイラが書き出せないスロット(ハッシュテーブル、外部ポインタ)を持つオブジェクトがコンパイル済みプログラムに届くのは、このメソッドがあるからです。メソッドがなければ、そうしたリテラルは `Cannot quote: ...` でコンパイルに失敗します。それがその型にメソッドが必要だという合図です。

```lisp
(defclass mlf-box () ((name :initarg :name :accessor mlf-box-name) (cache :initarg :cache)))
(defmethod make-load-form ((b mlf-box) &optional env)
  (declare (ignore env))
  (list 'make-instance ''mlf-box :name (mlf-box-name b)))
(defparameter *mlf-box* (make-instance 'mlf-box :name "dumped" :cache (make-hash-table)))
(defmacro mlf-splice-box () *mlf-box*)
(mlf-box-name (mlf-splice-box))
; => "dumped"
```

インタプリタはこの仕組みを一切必要としません——リテラルが生きたオブジェクトそのものだからです。したがってメソッドが変えるのはコンパイル済みプログラムの挙動だけで、両者は構成上一致します。

ライト版: 定数の中の引用された**配列**やハッシュテーブル経由でしか到達できないオブジェクトは再構築されません(引用されたコンス構造は再構築されます)。生成フォームが、生成中のオブジェクト自身を参照することはできません。
