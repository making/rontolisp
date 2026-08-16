# O/R マッピング (mito, sxql)

[Mito](https://github.com/fukamachi/mito) — Common Lisp の O/R マッパー — は
`(ql:quickload "mito")` で無改変のままロードでき、これでシステム全体が入ります:
`mito-core` (DAO 層)、`mito-migration` (スキーマ差分とマイグレーションファイル)、
`lack-middleware-mito`。クエリの各句は
[SxQL](https://github.com/fukamachi/sxql) で書かれており、SxQL 単体でもロードして
使えます。

**データベースは PostgreSQL のみです。** `dbd-mysql` と `dbd-sqlite3` は FFI を
必要とするため存在しません。PostgreSQL ドライバは
[cl-postgres スタック](asdf-systems.md)の上に乗るので、mito は TCP ソケットを
開けるバックエンド — インタプリタ、JVM クラス、WASM コンポーネント — すべてで
動作し、WASM Preview 1 では動作しません。

## テーブル定義と接続

`dbd-postgres` は `mito` と並べて**明示的に** quickload してください。
`dbi:connect` はロード済みのシステムからドライバを解決するため、そして
コンパイルされたプログラムは実行時にシステムをロードできないためです。

`deftable` は mito の `dao-table-class` メタクラスを使う `defclass` です。
メタクラスプロトコルは定義時に実行されるので、`deftable` はリテラルな
オプションを持つトップレベルフォームでなければなりません。
`mito:table-definition` は mito が作成する DDL を返します — ドライバに種別を
問い合わせるため、生きた接続が必要です:

```console
$ cat blog.lisp
(ql:quickload '("mito" "dbd-postgres"))

(mito:deftable article ()
  ((title :col-type (:varchar 64))
   (body  :col-type (or :text :null))))

(mito:connect-toplevel :postgres :database-name "blog" :username "postgres"
                       :password "secret" :host "127.0.0.1" :port 5432)

(dolist (statement (mito:table-definition 'article))
  (format t "~a~%" (sxql:yield statement)))
$ rontolisp blog.lisp
CREATE TABLE article (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    title VARCHAR(64) NOT NULL,
    body TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
)
```

主キー `id` と `created_at` / `updated_at` の組は mito のデフォルトです
(`:auto-pk`、`record-timestamps-mixin`)。`(:auto-pk :uuid)` にすると主キーは
`VARCHAR(36)` に格納される v4 UUID になり、`(:table-name "...")` で導出される
テーブル名を上書きできます。

接続は `mito:disconnect-toplevel` で閉じます。必ず呼んでください: 依存の
`trivial-garbage` は no-op のファイナライザシムに解決されるため、接続を回収して
くれるものは何もありません。

## CRUD

```console
$ cat crud.lisp
(ql:quickload '("mito" "dbd-postgres"))
;; ... the deftable and connect-toplevel from above ...

(mito:ensure-table-exists 'article)

(let ((a (mito:create-dao 'article :title "Hello" :body "First post")))
  (format t "~a ~a~%" (mito:object-id a) (slot-value a 'title)))
(mito:insert-dao (make-instance 'article :title "Hello again" :body nil))

(let ((found (mito:find-dao 'article :title "Hello")))
  (setf (slot-value found 'body) "Edited")
  (mito:save-dao found))

(format t "~a~%"
        (mapcar (lambda (a) (slot-value a 'title))
                (mito:select-dao 'article
                                 (sxql:where (:like :title "Hello%"))
                                 (sxql:order-by :id))))

(mito:delete-dao (mito:find-dao 'article :title "Hello again"))
(format t "~a~%" (length (mito:select-dao 'article)))
$ rontolisp crud.lisp
1 Hello
(Hello Hello again)
1
```

スロットの読み出しには `slot-value` を使います: mito は `:conc-name` の
リーダ/ライタをメタクラスのフックから注入しますが、rontolisp はそれを
メタオブジェクトの**データ**としてのみ記録するため、`article-title` の形の
アクセサは定義され**ません** (「現在の制限」を参照)。生の SQL を書きたい場合は
`mito:retrieve-by-sql` と `mito:execute-sql` があります。

## スキーマのマイグレーション

入口は 2 つあり、実行できるバックエンドが異なります。

**生きたスキーマとの差分** — 3 バックエンドすべてで動きます。
`mito.migration:migration-expressions` は現在のクラス定義と実在のテーブルを
比較し、差を埋めるステートメントを返します。`migrate-table` はそれを
トランザクション内で実行します:

```console
$ cat migrate-table.lisp
(ql:quickload '("mito" "dbd-postgres"))
;; ... the same connect-toplevel, and `article` redefined one column wider ...
(mito:deftable article ()
  ((title :col-type (:varchar 64))
   (body  :col-type (or :text :null))
   (tag   :col-type (or (:varchar 16) :null))))

(dolist (statement (mito.migration:migration-expressions 'article))
  (format t "~a~%" (sxql:yield statement)))
(mito.migration:migrate-table 'article)
(format t "~a~%" (mito.migration:migration-expressions 'article))
$ rontolisp migrate-table.lisp
ALTER TABLE article ADD COLUMN tag character varying(16)
NIL
```

**マイグレーションファイル** — インタプリタと JVM のみです (「現在の制限」を
参照)。`generate-migrations` は `db/schema.sql` とタイムスタンプ付きの
`.up.sql` / `.down.sql` の組を書き出し、`migration-status` は適用状況を報告し、
`migrate` は未適用のファイルを (esrap でパースして) 適用します:

```console
$ cat db.lisp
(ql:quickload '("mito" "dbd-postgres"))
;; ... the deftable and connect-toplevel from above ...
(mito:generate-migrations #P"db/")
(mito:migration-status #P"db/")
(mito:migrate #P"db/")
$ rontolisp db.lisp
CREATE TABLE "article" (
    "id" BIGSERIAL NOT NULL PRIMARY KEY,
    "title" VARCHAR(64) NOT NULL,
    "body" TEXT,
    "created_at" TIMESTAMPTZ,
    "updated_at" TIMESTAMPTZ
);
Successfully generated: db/migrations/20260804003900.up.sql
 Status   Migration ID
--------------------------
  down    20260804003900
Applying 'db/schema.sql'...
-> CREATE TABLE "article" (
    "id" BIGSERIAL NOT NULL PRIMARY KEY,
    "title" VARCHAR(64) NOT NULL,
    "body" TEXT,
    "created_at" TIMESTAMPTZ,
    "updated_at" TIMESTAMPTZ
);
-> CREATE TABLE IF NOT EXISTS "schema_migrations" (
    "version" BIGINT PRIMARY KEY,
    "applied_at" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "dirty" BOOLEAN NOT NULL DEFAULT false
);
Successfully updated to the version 20260804003900.
```

## SxQL による SQL 生成

SxQL は `select-dao` の句そのものであり、単体でも使えます —
`(ql:quickload "sxql")` はソケットを開かず、**4 つ全て**のバックエンドで同一の
SQL を生成します。`sxql:yield` は SQL 文字列とバインド値リストを 2 値で返します:

```console
$ cat query.lisp
(ql:quickload "sxql")
(multiple-value-bind (sql binds)
    (sxql:yield (sxql:select :*
                  (sxql:from :article)
                  (sxql:where (:and (:like :title "%lisp%")
                                    (:in :status '("published" "draft"))))
                  (sxql:order-by (:desc :id))
                  (sxql:limit 10)))
  (format t "~a~%~a~%" sql binds))
(format t "~a~%" (sxql:yield (sxql:insert-into :article
                               (sxql:set= :title "Hello" :body "First post"))))
(format t "~a~%" (sxql:yield (sxql:update :article
                               (sxql:set= :title "Hi")
                               (sxql:where (:= :id 1)))))
(format t "~a~%" (sxql:yield (sxql:delete-from :article (sxql:where (:= :id 1)))))
$ rontolisp query.lisp
SELECT * FROM article WHERE ((title LIKE ?) AND (status IN (?, ?))) ORDER BY id DESC LIMIT 10
(%lisp% published draft)
INSERT INTO article (title, body) VALUES (?, ?)
UPDATE article SET title = ? WHERE (id = ?)
DELETE FROM article WHERE (id = ?)
```

`create-table`、`drop-table`、`add-column` 付きの `alter-table`、
`left-join ... :on`、`limit` / `offset`、`:desc` / `nulls` 付きの `order-by` も
揃っています。`sxql:*use-placeholder*` を `nil` に束縛すると、`?` プレース
ホルダではなく値をインラインで展開します。

マクロ中心のライブラリの常として、ホットなクエリ構築はコンパイル系バックエンド
向きです — インタプリタは評価のたびにマクロを再展開します。

## バックエンド

- **インタプリタ** — 上記すべて。
- **JVM クラス** — `rontolisp blog.lisp -o Blog.class && java Blog`。
  生成されたクラスは自己完結しています。
- **WASM コンポーネント** (`--component`) — wasmtime の 2 つの機能フラグと
  2 つのソケット権限が必要です:

  ```bash
  rontolisp blog.lisp -o blog.wasm --component
  wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y blog.wasm
  ```

  `:host` は **IPv4 リテラル**でなければなりません — WASM ではホスト名解決が
  配線されていません。
- **WASM Preview 1** は設計上ホストのソケット API を持たないため、mito の
  プログラムはそこでは実行時に失敗するモジュールではなく**コンパイルエラー**に
  なります (`listen requires the interpreter, the JVM backend or a --component
  socket stream`)。

## 現在の制限

- **PostgreSQL のみ。** `dbd-mysql` / `dbd-sqlite3` は FFI を必要とします。
  mito 自身の `mysql` / `sqlite3` のソースファイルはロードされますが、単に
  選択されることがありません。
- **`:conc-name` アクセサは生成されません。** mito はリーダとライタを
  メタクラスのフックから追加しますが、ここでのアクセサメソッドは元の
  `defclass` フォームから生成され、そこにはアクセサ指定がありません。
  `slot-value` を使ってください。
- **SQL の関数オペレータはインタプリタ限定です**: `(:count ...)`、
  `(:sum ...)`、`(:max ...)` など SxQL が関数呼び出しとして解決するもの —
  したがって `mito:count-dao` も — は JVM と WASM のバックエンドでエラーを
  通知します。そこでは `(length (mito:select-dao ...))` で数えてください。
- **マイグレーションファイルの書き出しはインタプリタ + JVM です。**
  `generate-migrations` と `migrate` のファイル削除分岐はディレクトリ作成と
  ファイル削除を必要としますが、WASM バックエンドはそれらをインポートして
  いないため呼び出し時にエラーになります。差分と適用の経路
  (`migration-expressions`、`migrate-table`、`migration-status`、および既存の
  ファイルに対する `migrate`) はどこでも動きます。
- **`:references` だけの指定には `:col-type` が必要です。**
  `(other-id :references (other id))` 単独ではエラーになります — これは上流の
  不具合で、同じソースの SBCL でも同一に再現します。
  `(other-id :col-type :bigint :references (other id))` と書いてください。
- **`:initform` を持つ NOT NULL カラムの追加**では、`migrate-table` が
  バインドリスト空のまま `DEFAULT ?` を発行し、PostgreSQL が
  `there is no parameter $1` を返します — これも上流の不具合で、SBCL でも同一
  です。呼び出しを包んでください:
  `(let ((sxql:*use-placeholder* nil)) (mito.migration:migrate-table 'article))`。

関連: ライブラリ一覧と下層の cl-dbi / cl-postgres については
[システム (asdf)](asdf-systems.md)、バックエンドごとのソケットの扱いについては
[TCP ソケット](tcp-sockets.md)、`lack-middleware-mito` が組み込まれる Web 側に
ついては [Clack Web アプリケーション](clack.md) を参照してください。
