# Maven プラグイン（rontolisp-maven-plugin）

`rontolisp-maven-plugin` は、Maven プロジェクトの Lisp と Java を rontolisp でビルドします。
`src/main/lisp` を Java のクラスと同じプロジェクトの jar にコンパイルし、`src/main/java` の
プログラムを WebAssembly にコンパイルし、プロジェクトのテストを rontolisp が作るプログラムで
実行します。Lisp のプロジェクトにも Java のプロジェクトにも同じプラグインを使います。対象の
ソースツリーがないプロジェクトでは、ゴールは何もしません。

## ゴール

| ゴール | 既定のフェーズ | すること |
| --- | --- | --- |
| `compile` | `process-sources` | `src/main/lisp` を `target/classes` にコンパイルします。`rontolisp:jvm-export` を宣言するファイルがそれぞれクラスになります |
| `testCompile` | `process-test-sources` | 同じことを `src/test/lisp` から `target/test-classes` へ行います |
| `test` | `test` | `src/test/lisp` の rove スイートを `rontolisp test` で実行します。そのツリーがなければ `src/test/java` の JUnit Jupiter のテストを実行します |
| `wasm` | `package` | `src/main/java` のプログラムを `.wasm` モジュール、WASI 0.3 コンポーネント、ネイティブ実行ファイルのいずれかにコンパイルします |

どのゴールも `-Drontolisp.skip=true` で止まります。ゴールのプレフィックスは `rontolisp` です
（プラグインを宣言したプロジェクトで `mvn rontolisp:wasm`）。

## 準備

プラグインには Java 25 と Maven 3.9.9 以降が必要です。バージョンは、組み込んでいる rontolisp
のバージョンと同じです。リリースまでは Central のスナップショットリポジトリにある
`0.1.0-SNAPSHOT` なので、pom でプラグインリポジトリとして宣言します。

```xml
<pluginRepositories>
	<pluginRepository>
		<id>central-snapshots</id>
		<url>https://central.sonatype.com/repository/maven-snapshots/</url>
		<releases>
			<enabled>false</enabled>
		</releases>
		<snapshots>
			<enabled>true</enabled>
		</snapshots>
	</pluginRepository>
</pluginRepositories>
```

チェックアウトからビルドしたプラグイン（`./mvnw install -DskipTests` の後に
`./mvnw -f rontolisp-maven-plugin/pom.xml install -DskipTests`）は、プラグインリポジトリなしで
ローカルリポジトリから見つかります。

## Lisp のプロジェクト

Lisp のカーネル、それを呼ぶ Java、rove のテストを 1 つの jar にビルドします。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
		 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
		 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>

	<groupId>com.example</groupId>
	<artifactId>kernels</artifactId>
	<version>1.0.0-SNAPSHOT</version>

	<properties>
		<maven.compiler.release>25</maven.compiler.release>
		<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
		<rontolisp.version>0.1.0-SNAPSHOT</rontolisp.version>
	</properties>

	<pluginRepositories>
		<pluginRepository>
			<id>central-snapshots</id>
			<url>https://central.sonatype.com/repository/maven-snapshots/</url>
			<releases>
				<enabled>false</enabled>
			</releases>
			<snapshots>
				<enabled>true</enabled>
			</snapshots>
		</pluginRepository>
	</pluginRepositories>

	<build>
		<plugins>
			<plugin>
				<groupId>am.ik.rontolisp</groupId>
				<artifactId>rontolisp-maven-plugin</artifactId>
				<version>${rontolisp.version}</version>
				<executions>
					<execution>
						<goals>
							<goal>compile</goal>
							<goal>test</goal>
						</goals>
					</execution>
				</executions>
			</plugin>
		</plugins>
	</build>
</project>
```

```console
;; src/main/lisp/com/example/Kernels.lisp: a class, com.example.Kernels
(load "scale.lisp")

(defun scaled-sum (a b)
  (scale-by 2.0 (+ a b)))

(rontolisp:jvm-export 'scaled-sum :params '(:float :float) :returns :float)

;; src/main/lisp/com/example/scale.lisp: no export, so no class
(defun scale-by (scale x)
  (* scale x))

;; src/test/lisp/com/example/scale-test.lisp: a rove suite
(defpackage #:scale-test
  (:use #:cl #:rove))
(in-package #:scale-test)

(load "../../../../main/lisp/com/example/scale.lisp")

(deftest scale-by-doubles
  (ok (= (scale-by 2 3) 6)))
```

```java
// src/main/java/app/App.java
package app;

import com.example.Kernels;

public class App {
	public static void main(String[] args) {
		System.out.println(Kernels.scaledSum(2.5, 3.5));
	}
}
```

```console
$ mvn package
...
[INFO] --- rontolisp:0.1.0-SNAPSHOT:compile (default) @ kernels ---
[INFO] Compiled 1 of 2 Lisp sources to .../target/classes
[INFO] 1 Lisp source declare no (rontolisp:jvm-export ...) and stay Lisp
...
[INFO] --- rontolisp:0.1.0-SNAPSHOT:test (default) @ kernels ---
[INFO] rontolisp test on the interpreter: src/test/lisp/com/example/scale-test.lisp
[INFO]   All 1 test passed.
...
$ java -cp target/kernels-1.0.0-SNAPSHOT.jar app.App
12.0
```

**ソースセットは Lisp であって、エクスポートの寄せ集めではありません。** ファイルがクラスに
なるのは、`rontolisp:jvm-export` を 1 つ以上宣言したときだけです。これが Java の呼び出し側が
使うエントリポイントです（[JVM ライブラリのエクスポート](../guides/jvm-library.md)）。
`src/main/lisp` のそれ以外のファイルは普通の Lisp のままです。エクスポートするファイルが
`(load ...)` する補助コードや、インタプリタで実行するプログラムです。コンパイルされず、
エラーにもならず、クラス名の規則に従う必要もありません。

**エクスポートするファイルでは、`src/main/lisp` 以下のパスがクラス名になります。** `.java`
ファイルのパスと同じで、`src/main/lisp/com/example/Kernels.lisp` は `com.example.Kernels` に
なります。出力は通常の場所にある通常のクラスなので、`mvn install`、`mvn deploy`、jar を使う
Gradle のプロジェクト、IDE のどれにも追加の設定は要りません。

- `compile` は javac より前の `process-sources` で動きます。`src/main/java` は、このゴールが
  書き出すもの（カーネルのクラスと、`:float-vector` のエクスポートが返す
  `am.ik.rontolisp.runtime` のハンドル型）に対してコンパイルされるからです。Maven はフェーズの
  既定のバインディングを宣言したプラグインより先に実行するので、`compile` に結び付けた
  ゴールは javac の後になります。
- `testCompile` はその双子で、`process-test-sources` で `src/test/lisp` を
  `target/test-classes` にコンパイルし、`src/test/java` がそれに対してコンパイルされます
  （execution に `<goal>testCompile</goal>` を加えます）。
- `noMain` は、コマンドラインの `--no-main` と違って既定でオンです。ソースセットは
  ライブラリであり、エクスポートしないファイルが Lisp のまま残るのもこのためです。
  `<noMain>false</noMain>` にすると、すべてのファイルが `main` を持ってコンパイルされます。
  `rontolisp prog.lisp -o Prog.class` がプログラムをコンパイルするのと同じです。
- コンパイルエラーは、rontolisp の診断をそのまま持つビルド失敗になります。
  `file:line:column:` の接頭辞も含むので、IDE から該当箇所に移動できます。
- 古いソースがなければ何もコンパイルせず、1 つでもあればソースセット全体をコンパイルします。
  `(load "...")` はファイルを別のファイルに差し込むので、ファイルごとのタイムスタンプだけでは
  判断できません。

### war プロジェクト

`<servlet>true</servlet>` と `<packaging>war</packaging>` にすると、`compile` は
`src/main/lisp` から [Servlet war](../guides/http-handler.md#compiled-to-a-servlet-war) を
ビルドします。上の pom のプラグインの execution は次のようになります。

```xml
<execution>
	<goals>
		<goal>compile</goal>
	</goals>
	<configuration>
		<servlet>true</servlet>
	</configuration>
</execution>
```

```console
pom.xml                 <- <packaging>war</packaging>, a provided jakarta.servlet-api
src/main/lisp/App.lisp  <- the rontolisp:http-handler directive
```

```bash
mvn package     # target/<finalName>.war, deployable with no further configuration
```

- `maven-war-plugin` には設定が要りません。`target/classes` を `WEB-INF/classes` に
  コピーします。war に必要なもう 1 つのファイル、`jakarta.servlet.ServletContainerInitializer`
  のサービス宣言はこのゴールが書き出すので、`web.xml`、プログラム名、初期化パラメータの
  どれも設定しません。
- `jakarta.servlet-api` の依存（`provided`、6.0.0 以降）はプロジェクト自身のものです。
  rontolisp がコンパイルするものには同梱しません。
- servlet モードは `noMain` を無視します。`src/main/lisp` 以下のすべてのファイルを
  コンパイルし、各ファイルは自分の `rontolisp:http-handler` ディレクティブ（または
  [Clack](../guides/clack.md) のアプリケーションが使う `%http-server-start`）を持つ必要が
  あります。`-o app.war` と同じ要件です。共有するコードはハンドラが `(load ...)` するファイルに
  置き、`src/main/lisp` 以下の別の `.lisp` ファイルにはしません。
- `servlet` とパッケージングは互いに照合します。`<packaging>war</packaging>` のない
  `<servlet>true</servlet>`、またはその逆は、どちらが足りないかを示してビルドを失敗させます。
  すべてのリクエストに 404 を返す war はビルドしません。

## Java のプロジェクト

[Java 言語のサブセット](../java/index.md)のプログラムを、これまでどおり javac でコンパイルし、
`package` で WebAssembly にもコンパイルします。次の pom は
[`examples/java/hello-java`](https://github.com/making/rontolisp/tree/develop/examples/java/hello-java)
のものから、固定しているライフサイクルプラグインのバージョンと、JDK でプログラムを実行する
`exec-maven-plugin` を除いたものです。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
		 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
		 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>

	<groupId>com.example</groupId>
	<artifactId>hello-java</artifactId>
	<version>1.0.0-SNAPSHOT</version>

	<properties>
		<maven.compiler.release>25</maven.compiler.release>
		<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
		<rontolisp.version>0.1.0-SNAPSHOT</rontolisp.version>
		<rontolisp.mainClass>Hello</rontolisp.mainClass>
	</properties>

	<pluginRepositories>
		<pluginRepository>
			<id>central-snapshots</id>
			<url>https://central.sonatype.com/repository/maven-snapshots/</url>
			<releases>
				<enabled>false</enabled>
			</releases>
			<snapshots>
				<enabled>true</enabled>
			</snapshots>
		</pluginRepository>
	</pluginRepositories>

	<build>
		<finalName>${project.artifactId}</finalName>
		<plugins>
			<plugin>
				<groupId>am.ik.rontolisp</groupId>
				<artifactId>rontolisp-maven-plugin</artifactId>
				<version>${rontolisp.version}</version>
				<executions>
					<execution>
						<goals>
							<goal>wasm</goal>
						</goals>
					</execution>
				</executions>
			</plugin>
		</plugins>
	</build>
</project>
```

```java
// src/main/java/Hello.java
void main() {
    IO.println("Hello World!");
}
```

```console
$ mvn package
$ wasmtime run target/hello-java.wasm
Hello World!
```

出力ごとに execution を 1 つ書きます。次の 2 つで Preview 1 モジュールとコンポーネントを
ビルドします。

```xml
<executions>
	<execution>
		<id>preview1</id>
		<goals>
			<goal>wasm</goal>
		</goals>
	</execution>
	<execution>
		<id>component</id>
		<goals>
			<goal>wasm</goal>
		</goals>
		<configuration>
			<component>true</component>
			<outputFile>${project.build.directory}/${project.artifactId}-component.wasm</outputFile>
		</configuration>
	</execution>
</executions>
```

- プログラムは、`<mainClass>app.Main</mainClass>` なら
  `rontolisp src/main/java/app/Main.java -o target/app.wasm` がコンパイルするものと同じです。
  メインクラスのファイルと、それが名前を挙げるツリー内のファイルです
  （[複数ファイルのプログラム](../java/index.md#a-program-of-several-files)）。`mainClass` が
  なければ、プログラムは `@WitExport` が付いたクラスで、その world をエクスポートする
  コンポーネント（`<component>true</component>`）になります（[WIT のインタフェースと
  world](../java/wit.md)）。
- 出力は、`outputFile` で別の名前を指定しない限り `target/<finalName>.wasm` です。
  `<native>true</native>` はネイティブ実行ファイル `target/<finalName>` を書き出します。
  これにはネイティブのツールチェーンを持つ rontolisp が必要です
  （[ネイティブ実行ファイルへのコンパイル](native.md)）。
- フロントエンドが読むのはソースで、クラスファイルではありません
  （[サードパーティのライブラリを使う](../java/index.md#using-a-third-party-library)）。
  スコープが `compile`、`provided`、`system` の各依存について、その sources jar を
  プロジェクトのリポジトリから取得し、`<javaSourcePath>` の後に続けてソースパスに加えます。
  sources jar のない依存は警告になり、プログラムがそのクラスを名指しすると、ゴールはその名前と
  位置を示して失敗します: `.../app/Main.java:7:9: cannot find symbol: class Nullable`。
- プロジェクトの[リソース](../java/index.md#resources)は `target/classes`
  （`classesDirectory`）のもので、`process-resources` がコピー（フィルタ処理を含む）した
  ものです。クラスパスと同じ順で、`<javaSourcePath>` と依存より先に探します。
  [到達可能性メタデータ](../java/index.md#reflection-from-reachability-metadata)は、
  ここ（`src/main/resources/META-INF/native-image/`）と依存の jar から読みます。GraalVM の
  `native-image` がクラスパスから読むのと同じです。
- 前回のビルドより新しいファイル（ツリーとソースパスのファイル、リソース、jar）がなく、
  追加も削除もなく、パラメータとソースパスが同じで出力が存在するときは、何もコンパイル
  しません。そうでないとき、またはプラグインの rontolisp が変わったときは、プログラム全体を
  コンパイルします。
- `component` でビルドした [HTTP](../java/index.md#http) サーバーは、`target/` から
  `wasmtime serve` で動きます。

[`examples/java/`](https://github.com/making/rontolisp/tree/develop/examples/java) のうち
ライブラリを使うアプリケーションは、こうしたプロジェクトです。それぞれ親のない `pom.xml` を
持つので、リポジトリの外へコピーしたディレクトリでもそのままビルドできます。

## テスト

`test` は、`test` フェーズで surefire の後に、プロジェクトのテストを `rontolisp test` で
実行します。JDK で通っても、rontolisp が作るプログラムで通る保証にはならないからです。
`<backends>` が挙げるバックエンドごとに、プロジェクトのディレクトリで別プロセスとして
実行します。

- `interpreter`（既定。事前のコンパイルがないので最も速い）、`jvm`、`wasm`（WASI Preview 1）、
  `component`（WASI 0.3）です。最後の 2 つは `PATH` 上の `wasmtime` が必要です。
- `-Drontolisp.backends=interpreter,jvm,wasm,component` で 4 つすべてで実行します。
- 失敗したテストは、バックエンドごとにその名前を示してビルドを失敗させます。`-DskipTests`、
  `-Dmaven.test.skip=true`、`-Drontolisp.skip=true` でゴールを飛ばします。

`src/test/lisp` ツリーを持つプロジェクトは Lisp のプロジェクトで、rove のスイートを実行します。
それ以外は Java のテストを実行します。

### rove のスイート（`src/test/lisp`）

`src/test/lisp` のファイルのうち `deftest` でテストを定義するものごとに、`rontolisp test` を
1 回ずつ実行します（[テスト (rove)](../guides/testing.md)）。システムパスには
`src/main/lisp`、`src/test/lisp`、`<systemPath>` が入るので、どちらのツリーにあるシステムの
`.asd` も見つかります。`<includes>` と `<excludes>` はツリー以下のパスでファイルを選び
（既定ではすべての `.lisp`）、`<dists>` は `ql:quickload` がダウンロードしてよい dist を
指定します。上の [Lisp のプロジェクト](#a-lisp-project)は、このゴールを `compile` と並べて
宣言しています。

### JUnit Jupiter のテスト（`src/test/java`）

`src/test/java` のテストを、プロジェクトのテスト依存の sources jar からコンパイルした
JUnit Jupiter に対して、1 つのプログラムとして実行します（[Java のプログラムを
テストする](../java/testing.md)）。[Java のプロジェクト](#a-java-project)では、スコープ
`test` の `junit-jupiter` の依存を加え、`wasm` と並べて次の execution を書きます。

```xml
<execution>
	<id>rontolisp-test</id>
	<goals>
		<goal>test</goal>
	</goals>
</execution>
```

```console
$ mvn test
...
[INFO] --- rontolisp:VERSION:test (rontolisp-test) @ shop ---
[INFO] rontolisp test on the interpreter: 2 files of src/test/java
[INFO] Running shop.CartTest
[INFO] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in shop.CartTest
...
[ERROR] Failed to execute goal am.ik.rontolisp:rontolisp-maven-plugin:VERSION:test (rontolisp-test) on project shop: rontolisp test on the interpreter: failed: shop.CartTest.aLineCostsItsPriceTimesItsCount[2]
```

- 対象のファイルは surefire と同じです。`src/test/java` のファイルのうち、パスが
  `<includes>` のどれかに合い、`<excludes>` のどれにも合わないものです。指定がなければ
  surefire の既定値（`**/Test*.java`、`**/*Test.java`、`**/*Tests.java`、
  `**/*TestCase.java`、除外は `**/*$*`）を使います。サブセットがテストを拒否するファイルが
  あると実行が失敗するので、そのファイルは `<excludes>` で外します。
- ソースパスはプロジェクトのものです。テストリソースとリソース（`target/test-classes`、
  `target/classes`）、ソースルート（`src/main/java`）、`<javaSourcePath>`、そして javac が
  テストのコンパイルに使う各依存の sources jar（JUnit Jupiter のものを含む）の順です。
  sources jar のない依存は警告になります。
- `.wasm` は `/tmp` をプリオープンして実行します。`@TempDir` はそこに作られます。

## パラメータ

パラメータはプラグインまたは execution の `<configuration>` で設定します。ユーザー
プロパティを持つものはコマンドラインでも設定できます（`-Drontolisp.optimize=size`）。
コンパイラのフラグは、コマンドラインの名前をキャメルケースにした名前です:
`--warnings-as-errors` は `warningsAsErrors` です。

### `compile` と `testCompile`

| パラメータ | ユーザープロパティ | 既定値 | |
| --- | --- | --- | --- |
| `sourceDirectory`（`compile`） | | `${project.basedir}/src/main/lisp` | Lisp のツリー |
| `outputDirectory`（`compile`） | | `${project.build.outputDirectory}` | クラスの出力先 |
| `testSourceDirectory`（`testCompile`） | | `${project.basedir}/src/test/lisp` | テストの Lisp のツリー |
| `testOutputDirectory`（`testCompile`） | | `${project.build.testOutputDirectory}` | テストのクラスの出力先 |
| `noMain` | `rontolisp.noMain` | `true` | `--no-main`: `rontolisp:jvm-export` を持つファイルだけをコンパイルする |
| `servlet` | `rontolisp.servlet` | `false` | Servlet モード（`-o app.war` のモード）。`war` パッケージングが必要 |
| `simd` | `rontolisp.simd` | `false` | [`--simd`](../guides/simd-acceleration.md): Vector API のカーネル |
| `blas` | `rontolisp.blas` | `false` | [`--blas`](../guides/blas-acceleration.md) |
| `gpu` | `rontolisp.gpu` | `false` | [`--gpu`](../guides/gpu-acceleration.md) |
| `parallel` | `rontolisp.parallel` | `false` | [`--parallel`](../guides/simd-acceleration.md#using-more-than-one-core---parallel)。`simd` が必要 |
| `dynamic` | `rontolisp.dynamic` | `false` | [`--dynamic`](dynamic.md) |
| `optimize` | `rontolisp.optimize` | `default` | [`--optimize`](jvm.md#optimize-dead-code-elimination): `default`、`size`、`off` |
| `noPrune` | `rontolisp.noPrune` | `false` | `--no-prune`: 差し込んだライブラリの定義をすべて残す |
| `warningsAsErrors` | `rontolisp.warningsAsErrors` | `false` | [`--warnings-as-errors`](warnings.md) |
| `systemPath` | | | `--system-path`: ASDF のローダーが探すディレクトリのリスト |
| `dists` | | | `--dist`: `ql:quickload` が読む Quicklisp 形式の dist のリスト |
| `skip` | `rontolisp.skip` | `false` | ゴールを飛ばす |

`simd` でコンパイルしたクラスを実行する JVM は、`--add-modules jdk.incubator.vector` を
付けたときだけベクトルのカーネルを使います。付けなければスカラーの参照実装で動きます。

### `test`

| パラメータ | ユーザープロパティ | 既定値 | |
| --- | --- | --- | --- |
| `backends` | `rontolisp.backends` | `interpreter` | `interpreter`、`jvm`、`wasm`、`component`。リストまたはカンマ区切り |
| `includes` | | Java は surefire のもの、Lisp はすべての `.lisp` | ツリー以下のテストファイルのパスの Ant 形式のパターン |
| `excludes` | | Java は `**/*$*`、Lisp はなし | 外すファイルの Ant 形式のパターン |
| `testSourceDirectory` | | `${project.build.testSourceDirectory}` | Java のテストのツリー |
| `lispTestSourceDirectory` | | `${project.basedir}/src/test/lisp` | Lisp のテストのツリー。これがあれば Lisp のプロジェクト |
| `lispSourceDirectory` | | `${project.basedir}/src/main/lisp` | テストのツリーと一緒にシステムを探す |
| `javaSourcePath` | | | `--java-source-path`: 依存の sources jar より先に探すディレクトリと sources jar |
| `systemPath` | | | `--system-path`: Lisp のテストのシステムを探す追加のディレクトリ |
| `dists` | | | `--dist`: `ql:quickload` がダウンロードしてよい dist |
| `skip` | `rontolisp.skip` | `false` | ゴールを飛ばす |
| `skipTests` | `skipTests` | `false` | surefire と同じくゴールを飛ばす |
| `mavenTestSkip` | `maven.test.skip` | `false` | ゴールを飛ばす |

### `wasm`

| パラメータ | ユーザープロパティ | 既定値 | |
| --- | --- | --- | --- |
| `mainClass` | `rontolisp.mainClass` | | プログラムが始まるクラスのバイナリ名。なければ `@WitExport` のクラス |
| `sourceDirectory` | | `${project.build.sourceDirectory}` | プログラムを読むソースツリー |
| `classesDirectory` | | `${project.build.outputDirectory}` | リソース。ソースパスの先頭 |
| `outputFile` | `rontolisp.outputFile` | `${project.build.directory}/${project.build.finalName}.wasm` | 出力。`native` では `.wasm` なし |
| `component` | `rontolisp.component` | `false` | [`--component`](../guides/wasm-component.md): WASI 0.3 コンポーネント |
| `noWasi` | `rontolisp.noWasi` | `false` | [`--no-wasi`](wasm.md#host-boundaries): WASI をインポートしないリアクターモジュール |
| `hostBoundary` | `rontolisp.hostBoundary` | | [`--host-boundary`](wasm.md#host-boundaries): `noWasi` のモジュールの本文の境界 |
| `hostRandom` | `rontolisp.hostRandom` | `false` | `--host-random`: `random_get` をホストのインポートから得る |
| `hostOutput` | `rontolisp.hostOutput` | `false` | `--host-output`: 標準出力と標準エラーをホストのインポートへ |
| `hostFetch` | `rontolisp.hostFetch` | `false` | `--host-fetch`: fetch をホストのインポートで行う |
| `hostSockets` | `rontolisp.hostSockets` | `false` | [`--host-sockets`](../guides/wasm-gc-module.md#sockets-from-the-host---host-sockets): `--no-wasi` のモジュールのソケットをホストのソケットにする |
| `hostTimer` | `rontolisp.hostTimer` | `false` | `--host-timer`: モジュールの待機を JSPI を通じてホストに任せる |
| `wasiLogging` | `rontolisp.wasiLogging` | `false` | [`--wasi-logging`](../java/logging.md#the-hosts-log): Java のプログラムのログレコードをホストの `wasi:logging` へ |
| `reentrant` | `rontolisp.reentrant` | `false` | `--reentrant`: JSPI の呼び出しを重ねる |
| `emitWit` | `rontolisp.emitWit` | `false` | `--emit-wit`: コンポーネントの world を出力の隣に書く |
| `emitJsGlue` | `rontolisp.emitJsGlue` | `false` | `--emit-js-glue`: ホストのグルーを出力の隣に書く |
| `emitWorkerEntry` | `rontolisp.emitWorkerEntry` | `false` | [`--emit-worker-entry`](../guides/wasm-host-boundary.md#generating-the-host-glue---emit-js-glue): Cloudflare Worker の `index.js` をグルーの隣に書く。すでにあれば書かない。`emitJsGlue` を含意する |
| `optimize` | `rontolisp.optimize` | `default` | [`--optimize`](wasm.md#optimize-tree-shaking): `default`、`size`、`off` |
| `simd` | `rontolisp.simd` | `false` | [`--simd`](wasm.md#simd-acceleration---simd): v128 のカーネル |
| `reportLocations` | `rontolisp.reportLocations` | | [`--report-locations`](wasm.md#uncaught-condition-locations---report-locations) |
| `native` | `rontolisp.native` | `false` | [`--native`](native.md): ネイティブ実行ファイル |
| `nativeTarget` | `rontolisp.nativeTarget` | ホストのもの | [`--native-target`](native.md#flags)。`native` が必要 |
| `nativeCpu` | `rontolisp.nativeCpu` | `baseline` | [`--native-cpu`](native.md#flags)。`native` が必要 |
| `dynamic` | `rontolisp.dynamic` | `false` | [`--dynamic`](dynamic.md) |
| `noPrune` | `rontolisp.noPrune` | `false` | `--no-prune`: 差し込んだライブラリの定義をすべて残す |
| `warningsAsErrors` | `rontolisp.warningsAsErrors` | `false` | [`--warnings-as-errors`](warnings.md) |
| `javaSourcePath` | | | `--java-source-path`: 依存の sources jar より先に探すディレクトリと sources jar |
| `metadataRepository` | `rontolisp.metadataRepository` | | [`--java-metadata-repository`](../java/index.md#reflection-from-reachability-metadata): 依存について読む GraalVM の到達可能性メタデータの共有リポジトリ。`org.graalvm.buildtools:graalvm-reachability-metadata` のバージョン（プロジェクトのリポジトリから取得）か、そのディレクトリか zip |
| `skip` | `rontolisp.skip` | `false` | ゴールを飛ばす |
