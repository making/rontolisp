# rontolisp-maven-plugin

A Maven plugin that builds a project's Lisp and Java with [rontolisp](../README.md):

- compiles `src/main/lisp` into `target/classes`, so the Lisp classes ship in the project's
  jar beside the Java classes that call them (or in a Servlet war);
- compiles the program in `src/main/java`, written in rontolisp's subset of the Java
  language, to a WebAssembly module, a WASI 0.3 component or a native executable;
- runs the project's tests with `rontolisp test` on the programs rontolisp makes: a rove
  suite in `src/test/lisp`, or the JUnit Jupiter tests in `src/test/java`.

Documentation: [Maven Plugin](https://making.github.io/rontolisp/docs/en/compiling/maven-plugin.html)
([Japanese](https://making.github.io/rontolisp/docs/ja/compiling/maven-plugin.html)). Its
source is [`doc/en/compiling/maven-plugin.md`](../doc/en/compiling/maven-plugin.md).

## Goals

| Goal | Default phase | What it does |
| --- | --- | --- |
| `compile` | `process-sources` | `src/main/lisp` into `target/classes`; each file that declares a `rontolisp:jvm-export` becomes a class named by its path |
| `testCompile` | `process-test-sources` | `src/test/lisp` into `target/test-classes` |
| `test` | `test` | The rove suite of `src/test/lisp`, or with no such tree the JUnit Jupiter tests of `src/test/java`, on each backend `backends` names |
| `wasm` | `package` | The program in `src/main/java` to `target/<finalName>.wasm` (or a native executable) |

`compile` and `testCompile` run before javac because the Java sources compile against the
classes they write. Every goal stops at `-Drontolisp.skip=true`; `test` also at
`-DskipTests` and `-Dmaven.test.skip=true`.

## Requirements

Java 25 and Maven 3.9.9 or newer. The plugin's version is the version of the rontolisp it
embeds. Until a release it is `0.1.0-SNAPSHOT` on Central's snapshot repository, declared in
the pom as a plugin repository (below).

## A Lisp project

`src/main/lisp` compiled into the jar and the rove suite of `src/test/lisp` run at `test`:

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

`src/main/lisp/com/example/Kernels.lisp` with a
`(rontolisp:jvm-export 'scaled-sum :params '(:float :float) :returns :float)` becomes the
class `com.example.Kernels`, which `src/main/java` calls as `Kernels.scaledSum(2.5, 3.5)`.
A file without an export stays Lisp, loaded by the files that have one.

## A Java project

The pom of [`examples/java/hello-java`](../examples/java/hello-java/pom.xml), less the
lifecycle plugin versions it pins and its `exec-maven-plugin`:

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

```console
$ mvn package
$ wasmtime run target/hello-java.wasm
Hello World!
```

An execution of the `test` goal beside `wasm` runs the JUnit Jupiter tests of
`src/test/java` on the program rontolisp makes, after surefire runs them on the JDK.
[`examples/java/`](../examples/java/README.md) holds more projects of this kind.

## Main parameters

A compiler flag is a parameter under its command-line name in camel case
(`--warnings-as-errors` is `warningsAsErrors`, user property `rontolisp.warningsAsErrors`).
The documentation lists every parameter.

`compile` and `testCompile`:

| Parameter | Default | |
| --- | --- | --- |
| `sourceDirectory` / `testSourceDirectory` | `src/main/lisp` / `src/test/lisp` | The Lisp tree |
| `noMain` | `true` | Only files with a `rontolisp:jvm-export` compile; `false` compiles every file with a `main` |
| `servlet` | `false` | Servlet mode for a `war` project (`-o app.war`'s) |
| `simd`, `blas`, `gpu`, `parallel` | `false` | The kernel backends of `--simd`, `--blas`, `--gpu`, `--parallel` |
| `optimize` | `default` | `default`, `size` or `off` |
| `dynamic`, `noPrune`, `warningsAsErrors` | `false` | `--dynamic`, `--no-prune`, `--warnings-as-errors` |
| `systemPath`, `dists` | | `--system-path`, `--dist` |

`test`:

| Parameter | Default | |
| --- | --- | --- |
| `backends` | `interpreter` | `interpreter`, `jvm`, `wasm`, `component` (`-Drontolisp.backends=interpreter,jvm`) |
| `includes`, `excludes` | surefire's for Java, every `.lisp` for Lisp | Ant-style patterns of the test files |
| `javaSourcePath`, `systemPath`, `dists` | | `--java-source-path`, `--system-path`, `--dist` |

`wasm`:

| Parameter | Default | |
| --- | --- | --- |
| `mainClass` | | The class the program starts at; unset, the `@WitExport` classes |
| `outputFile` | `${project.build.directory}/${project.build.finalName}.wasm` | The output |
| `component` | `false` | A WASI 0.3 component |
| `noWasi` | `false` | A module that imports no WASI |
| `emitJsGlue`, `emitWorkerEntry` | `false` | The JavaScript host glue beside a `noWasi` module, and a Cloudflare Worker's `index.js` over it (written only where none is there; implies `emitJsGlue`) |
| `native`, `nativeTarget`, `nativeCpu` | `false`, the host's, `baseline` | A native executable |
| `optimize` | `default` | `default`, `size` or `off` |
| `javaSourcePath` | | Directories and sources jars read before the dependencies' sources jars |

`wasm` and the Java `test` read a library from its sources jar, fetched for each
dependency javac compiles against.

## Building from source

The plugin is outside the root reactor and depends on the rontolisp artifact by
coordinates, so a SNAPSHOT build installs rontolisp first. From the repository root:

```bash
./mvnw install -DskipTests
./mvnw -f rontolisp-maven-plugin/pom.xml install
./mvnw -f rontolisp-maven-plugin/pom.xml -Drontolisp.plugin.e2e=true test  # builds real projects with the installed plugin
```

A plugin installed this way is found in the local repository with no plugin repository.
`wasm`'s `native` needs one installed after `rontolisp-native/build.sh`: the plugin on
Central carries no native toolchain.
