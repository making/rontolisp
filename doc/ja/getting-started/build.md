# ビルドとインストール

## ビルド済みバイナリのダウンロード（推奨）

ビルド済みの native-image バイナリは
[GitHub のリリースページ](https://github.com/making/rontolisp/releases/tag/0.1.0-SNAPSHOT)
で公開されています。即座に起動でき、JVM のインストールも不要です。お使いのプラットフォーム
向けのアセットを選び、実行権限を付与して、`rontolisp` という名前で `PATH` に配置してください。

macOS（Apple Silicon）:

```bash
wget https://github.com/making/rontolisp/releases/download/0.1.0-SNAPSHOT/rontolisp-darwin-arm64
chmod +x rontolisp-darwin-arm64
sudo mv rontolisp-darwin-arm64 /usr/local/bin/rontolisp
```

Linux（x86-64）:

```bash
wget https://github.com/making/rontolisp/releases/download/0.1.0-SNAPSHOT/rontolisp-linux-amd64
chmod +x rontolisp-linux-amd64
sudo mv rontolisp-linux-amd64 /usr/local/bin/rontolisp
```

Linux（ARM64）:

```bash
wget https://github.com/making/rontolisp/releases/download/0.1.0-SNAPSHOT/rontolisp-linux-arm64
chmod +x rontolisp-linux-arm64
sudo mv rontolisp-linux-arm64 /usr/local/bin/rontolisp
```

インストールを確認します。

```bash
rontolisp --version
```

ビルド済みバイナリは他に何もインストールする必要はありません。`.wasm` 出力を実行するには、
[wasmtime](https://wasmtime.dev/) 14 以降のような wasm-GC 対応ランタイムが追加で必要です
（オプション）。

このドキュメントの残りの部分では `rontolisp` コマンドを使用します。代わりにソースから
ビルドする場合（以下参照）は、`rontolisp` を
`java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar` に置き換えてください。

## ソースからのビルド

**Java 25 以降**が必要です。

```bash
./mvnw clean package
```

これにより、すべての依存関係を含む実行可能 JAR である
`target/rontolisp-0.1.0-SNAPSHOT-exec.jar` が生成されます。
`java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar` で実行します。

### ネイティブイメージ（GraalVM）

GraalVM を使ってネイティブ実行ファイルを自分でビルドします。

```bash
./mvnw -Pnative clean package
```

これにより、即座に起動するスタンドアロンのネイティブバイナリ `target/rontolisp` が生成されます。

**要件:**
- GraalVM 25 以降（`native-image` ツールを含む）

**使い方:**

```bash
# REPL
rontolisp

# File interpretation
rontolisp program.lisp

# Compile to JVM bytecode
rontolisp hello.lisp -o Hello.class

# Compile to WASM
rontolisp hello.lisp -o hello.wasm
```
