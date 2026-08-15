# rename-file

`(rename-file file new-name)`

`file` を `new-name` へリネーム (移動) し、補完後の新しい名前をパス名として返します。
`new-name` は [`merge-pathnames`](merge-pathnames.md) と同じ規則で `file` とマージ
されるため、ファイル名だけを渡すと元のディレクトリに留まります。ファイルが元の場所に
残る結果になった場合は、[`delete-file`](delete-file.md) と同じく「そもそも存在しなかった」
場合も含めてエラーになります。

```console
> (rename-file "notes.txt" "notes.bak")
#P"notes.bak"
> (rename-file "db/2026.up.sql" "2026.down.sql")
#P"db/2026.down.sql"
```

制限: Common Lisp は `(values defaulted-new-name old-truename new-truename)` を
返しますが、ここでは補完後の新しい名前だけを返します。コンパイル系バックエンドでは
副次値が関数境界を越えられないためで、[`ensure-directories-exist`](ensure-directories-exist.md)
と同じ方針です。

## バックエンドサポート

インタプリタと JVM は実際にリネームします。WASM の 2 バックエンドは**呼び出し時**に
エラーを送出します。ここで取り込んでいる WASI のインポートにリネーム呼び出しがなく、
「後でファイルが新しい名前になっている」に誠実な代替の答えがないためです。
[`delete-file`](delete-file.md) や [`ensure-directories-exist`](ensure-directories-exist.md)
と同じ差異です。
