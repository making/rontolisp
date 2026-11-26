# Maven Plugin (rontolisp-maven-plugin)

`rontolisp-maven-plugin` builds a Maven project's Lisp and Java with rontolisp: it compiles
`src/main/lisp` into the project's jar beside the Java classes, compiles the program in
`src/main/java` to WebAssembly, and runs the project's tests on the programs rontolisp makes.
The same plugin serves a Lisp project and a Java project; a goal finds nothing to do in a
project without its source tree.

## Goals

| Goal | Default phase | What it does |
| --- | --- | --- |
| `compile` | `process-sources` | Compiles `src/main/lisp` into `target/classes`: each file that declares a `rontolisp:jvm-export` becomes a class |
| `testCompile` | `process-test-sources` | The same for `src/test/lisp` into `target/test-classes` |
| `test` | `test` | Runs the rove suite of `src/test/lisp` with `rontolisp test`, or with no such tree the JUnit Jupiter tests of `src/test/java` |
| `wasm` | `package` | Compiles the program in `src/main/java` to a `.wasm` module, a WASI 0.3 component or a native executable |

Every goal stops at `-Drontolisp.skip=true`. The goal prefix is `rontolisp`
(`mvn rontolisp:wasm` in a project that declares the plugin).

## Setting up

The plugin needs Java 25 and Maven 3.9.9 or newer. Its version is the version of the
rontolisp it embeds. Until a release, it is a `0.1.0-SNAPSHOT` on Central's snapshot
repository, which the pom declares as a plugin repository:

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

A plugin built from a checkout (`./mvnw install -DskipTests`, then
`./mvnw -f rontolisp-maven-plugin/pom.xml install -DskipTests`) is found in the local
repository with no plugin repository.

## A Lisp project

Lisp kernels, the Java that calls them and a rove test, built into one jar:

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

**A source set is Lisp, not a pile of exports.** A file becomes a class exactly when it
declares at least one `rontolisp:jvm-export`, the entry point a Java caller uses
([Export a JVM Library](../guides/jvm-library.md)). Everything else in `src/main/lisp`
stays ordinary Lisp: the support code the exported files `(load ...)`, a program run with
the interpreter. It is not compiled, it is not an error, and it does not have to be named
like a class.

**The path under `src/main/lisp` is the class name** of a file that exports, as a `.java`
file's path is: `src/main/lisp/com/example/Kernels.lisp` becomes `com.example.Kernels`. The
output is ordinary classes in the ordinary place, so `mvn install`, `mvn deploy`, a Gradle
consumer of the jar and an IDE need nothing more.

- `compile` runs at `process-sources`, before javac, because `src/main/java` compiles
  against what it writes: the kernel's class and the `am.ik.rontolisp.runtime` handle
  type a `:float-vector` export hands out. Maven runs the default bindings of a phase
  ahead of a declared plugin, so a goal bound to `compile` would run after javac.
- `testCompile` is its twin: `src/test/lisp` into `target/test-classes` at
  `process-test-sources`, for `src/test/java` to compile against
  (`<goal>testCompile</goal>` in the execution).
- `noMain` is on by default, unlike the command line's `--no-main`: a source set is a
  library, and that is what leaves an unexported file Lisp. With `<noMain>false</noMain>`
  every file gets a `main` and compiles, as `rontolisp prog.lisp -o Prog.class` compiles a
  program.
- A compile error fails the build with the rontolisp diagnostic verbatim, its
  `file:line:column:` prefix included, so an IDE can jump to it.
- When no source is stale, nothing is compiled; when one is, the whole source set is. A
  `(load "...")` splices one file into another, so a per-file timestamp cannot decide alone.

### A war project

With `<servlet>true</servlet>` and `<packaging>war</packaging>`, `compile` builds a
[Servlet war](../guides/http-handler.md#compiled-to-a-servlet-war) from
`src/main/lisp`, the plugin's execution in the pom above becoming:

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

- `maven-war-plugin` needs no configuration: it copies `target/classes` into
  `WEB-INF/classes`. The goal writes the one other file the war needs, the
  `jakarta.servlet.ServletContainerInitializer` service declaration, so there is no
  `web.xml`, program name or init parameter to set.
- The `jakarta.servlet-api` dependency (`provided`, 6.0.0 or newer) is the project's own;
  rontolisp bundles it into nothing it compiles.
- Servlet mode ignores `noMain`: every file under `src/main/lisp` compiles and must carry
  its own `rontolisp:http-handler` directive (or the `%http-server-start` seam a
  [Clack](../guides/clack.md) application rides on), as `-o app.war` requires. Shared code
  belongs in a file the handler loads with `(load ...)`, not in another `.lisp` file under
  `src/main/lisp`.
- `servlet` and the packaging are checked against each other. `<servlet>true</servlet>`
  without `<packaging>war</packaging>`, or the reverse, fails the build naming which is
  missing, rather than building a war that answers every request with a 404.

## A Java project

A program in [the subset of the Java language](../java/index.md), compiled by javac as
ever and, at `package`, to WebAssembly. This is the pom of
[`examples/java/hello-java`](https://github.com/making/rontolisp/tree/develop/examples/java/hello-java)
less the lifecycle plugin versions it pins and the `exec-maven-plugin` that runs the
program on the JDK:

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

One execution per output; two build a Preview 1 module and a component:

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

- The program is the one `rontolisp src/main/java/app/Main.java -o target/app.wasm`
  compiles for `<mainClass>app.Main</mainClass>`: the main class's file and the files of
  the tree it names ([A program of several files](../java/index.md#a-program-of-several-files)).
  With no `mainClass` the program is the classes `@WitExport` marks, a component
  (`<component>true</component>`) exporting their worlds ([WIT interfaces and
  worlds](../java/wit.md)).
- The output is `target/<finalName>.wasm` unless `outputFile` names another;
  `<native>true</native>` writes the native executable `target/<finalName>`, which needs a
  rontolisp carrying the native toolchain ([Compile to a Native Executable](native.md)).
- The front end reads source, not class files
  ([Using a third-party library](../java/index.md#using-a-third-party-library)). The
  sources jar of each `compile`, `provided` and `system` dependency is fetched from the
  project's repositories and joins the source path after `<javaSourcePath>`. A dependency
  with no sources jar is a warning; a class of it the program names fails the goal with its
  name and position: `.../app/Main.java:7:9: cannot find symbol: class Nullable`.
- The project's [resources](../java/index.md#resources) are those of `target/classes`
  (`classesDirectory`), where `process-resources` copied and filtered them, searched ahead
  of `<javaSourcePath>` and the dependencies, the class path's order.
  [Reachability metadata](../java/index.md#reflection-from-reachability-metadata) is read
  from there (`src/main/resources/META-INF/native-image/`) and from the dependencies'
  jars, as GraalVM's `native-image` reads it from the class path.
- When no file of the tree or of the source path, no resource and no jar is newer than the
  last build, none was added or removed, the parameters and the source path are the same
  and the output exists, nothing is compiled. Otherwise, or when the plugin's rontolisp
  changed, the whole program is.
- An [HTTP](../java/index.md#http) server built with `component` runs from `target/` under
  `wasmtime serve`.

The applications of
[`examples/java/`](https://github.com/making/rontolisp/tree/develop/examples/java) that use
a library are such projects, each standalone: a `pom.xml` with no parent, so a directory
copied out of the repository builds on its own.

## Tests

`test` runs the project's tests with `rontolisp test` at `test`, after surefire: a JDK's
green run is no proof of the program rontolisp makes. Each run is a process of its own in
the project's directory, once per backend `<backends>` names:

- `interpreter` (the default, the quickest: it compiles nothing first), `jvm`, `wasm`
  (WASI Preview 1) and `component` (WASI 0.3); the last two need `wasmtime` on `PATH`.
- `-Drontolisp.backends=interpreter,jvm,wasm,component` runs on all four.
- A failed test fails the build with its name, for each backend. `-DskipTests`,
  `-Dmaven.test.skip=true` and `-Drontolisp.skip=true` skip the goal.

A project with a `src/test/lisp` tree is a Lisp project and runs its rove suite; any other
runs its Java tests.

### Rove suites (`src/test/lisp`)

Each file of `src/test/lisp` that defines a test with `deftest` is a `rontolisp test` run
of its own ([Testing (rove)](../guides/testing.md)), with `src/main/lisp`,
`src/test/lisp` and `<systemPath>` on the system path, so a system's `.asd` in either tree
is found. `<includes>` and `<excludes>` select the files by their paths below the tree
(every `.lisp` by default), and `<dists>` names the dists `ql:quickload` may download
from. The [Lisp project](#a-lisp-project) above declares the goal beside `compile`.

### JUnit Jupiter tests (`src/test/java`)

The tests of `src/test/java` run as one program against JUnit Jupiter compiled from the
sources jars of the project's test dependencies ([Testing a Java
program](../java/testing.md)). The execution, beside `wasm` in the
[Java project](#a-java-project) with a `junit-jupiter` dependency of scope `test`:

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

- The files are surefire's: those of `src/test/java` whose paths match `<includes>` and no
  `<excludes>`, surefire's defaults (`**/Test*.java`, `**/*Test.java`, `**/*Tests.java`,
  `**/*TestCase.java`; `**/*$*`) unless set. A file whose tests the subset refuses fails
  the run, and `<excludes>` leaves it out.
- The source path is the project's: its test resources and resources (`target/test-classes`,
  `target/classes`), its source roots (`src/main/java`), `<javaSourcePath>`, then the
  sources jar of each dependency javac compiles the tests against, JUnit Jupiter's among
  them. A dependency with no sources jar is a warning.
- A `.wasm` runs with `/tmp` preopened, where a `@TempDir` is made.

## Parameters

A parameter is set in the plugin's or an execution's `<configuration>`, and one with a user
property also on the command line (`-Drontolisp.optimize=size`). A compiler flag keeps the
command line's name in camel case: `--warnings-as-errors` is `warningsAsErrors`.

### `compile` and `testCompile`

| Parameter | User property | Default | |
| --- | --- | --- | --- |
| `sourceDirectory` (`compile`) | | `${project.basedir}/src/main/lisp` | The Lisp tree |
| `outputDirectory` (`compile`) | | `${project.build.outputDirectory}` | Where the classes go |
| `testSourceDirectory` (`testCompile`) | | `${project.basedir}/src/test/lisp` | The test Lisp tree |
| `testOutputDirectory` (`testCompile`) | | `${project.build.testOutputDirectory}` | Where the test classes go |
| `noMain` | `rontolisp.noMain` | `true` | `--no-main`: only files with a `rontolisp:jvm-export` compile |
| `servlet` | `rontolisp.servlet` | `false` | Servlet mode (`-o app.war`'s); needs `war` packaging |
| `simd` | `rontolisp.simd` | `false` | [`--simd`](../guides/simd-acceleration.md): the Vector API kernels |
| `blas` | `rontolisp.blas` | `false` | [`--blas`](../guides/blas-acceleration.md) |
| `gpu` | `rontolisp.gpu` | `false` | [`--gpu`](../guides/gpu-acceleration.md) |
| `parallel` | `rontolisp.parallel` | `false` | [`--parallel`](../guides/simd-acceleration.md#using-more-than-one-core---parallel); needs `simd` |
| `dynamic` | `rontolisp.dynamic` | `false` | [`--dynamic`](dynamic.md) |
| `optimize` | `rontolisp.optimize` | `default` | [`--optimize`](jvm.md#optimize-dead-code-elimination): `default`, `size` or `off` |
| `noPrune` | `rontolisp.noPrune` | `false` | `--no-prune`: keep every spliced library definition |
| `warningsAsErrors` | `rontolisp.warningsAsErrors` | `false` | [`--warnings-as-errors`](warnings.md) |
| `systemPath` | | | `--system-path`: a list of directories the ASDF loader searches |
| `dists` | | | `--dist`: a list of Quicklisp-format dists `ql:quickload` reads |
| `skip` | `rontolisp.skip` | `false` | Skips the goal |

A JVM that runs a class compiled with `simd` gets the vector kernels only with
`--add-modules jdk.incubator.vector`; without it the class runs the scalar reference.

### `test`

| Parameter | User property | Default | |
| --- | --- | --- | --- |
| `backends` | `rontolisp.backends` | `interpreter` | `interpreter`, `jvm`, `wasm`, `component`; a list or comma-separated |
| `includes` | | surefire's for Java, every `.lisp` for Lisp | Ant-style patterns of the test files' paths below the tree |
| `excludes` | | `**/*$*` for Java, none for Lisp | Ant-style patterns of the files left out |
| `testSourceDirectory` | | `${project.build.testSourceDirectory}` | The Java test tree |
| `lispTestSourceDirectory` | | `${project.basedir}/src/test/lisp` | The Lisp test tree; its presence makes the project a Lisp one |
| `lispSourceDirectory` | | `${project.basedir}/src/main/lisp` | Searched for systems with the test tree |
| `javaSourcePath` | | | `--java-source-path`: directories and sources jars searched before the dependencies' sources jars |
| `systemPath` | | | `--system-path`: more directories a Lisp test's systems are found in |
| `dists` | | | `--dist`: the dists `ql:quickload` may download from |
| `skip` | `rontolisp.skip` | `false` | Skips the goal |
| `skipTests` | `skipTests` | `false` | Skips the goal, as it skips surefire |
| `mavenTestSkip` | `maven.test.skip` | `false` | Skips the goal |

### `wasm`

| Parameter | User property | Default | |
| --- | --- | --- | --- |
| `mainClass` | `rontolisp.mainClass` | | The binary name of the class the program starts at; unset, the `@WitExport` classes |
| `sourceDirectory` | | `${project.build.sourceDirectory}` | The source tree the program is read from |
| `classesDirectory` | | `${project.build.outputDirectory}` | The resources, first on the source path |
| `outputFile` | `rontolisp.outputFile` | `${project.build.directory}/${project.build.finalName}.wasm` | The output; no `.wasm` under `native` |
| `component` | `rontolisp.component` | `false` | [`--component`](../guides/wasm-component.md): a WASI 0.3 component |
| `noWasi` | `rontolisp.noWasi` | `false` | [`--no-wasi`](wasm.md#host-boundaries): a reactor module that imports no WASI |
| `hostBoundary` | `rontolisp.hostBoundary` | | [`--host-boundary`](wasm.md#host-boundaries): the `noWasi` module's body boundary |
| `hostRandom` | `rontolisp.hostRandom` | `false` | `--host-random`: `random_get` from a host import |
| `hostOutput` | `rontolisp.hostOutput` | `false` | `--host-output`: standard output and error to a host import |
| `hostFetch` | `rontolisp.hostFetch` | `false` | `--host-fetch`: fetches through a host import |
| `hostSockets` | `rontolisp.hostSockets` | `false` | [`--host-sockets`](../guides/wasm-gc-module.md#sockets-from-the-host---host-sockets): sockets through the host's, on a `--no-wasi` module |
| `hostTimer` | `rontolisp.hostTimer` | `false` | `--host-timer`: the host waits for the module, through JSPI |
| `wasiLogging` | `rontolisp.wasiLogging` | `false` | [`--wasi-logging`](../java/logging.md#the-hosts-log): a Java program's log records to the host's `wasi:logging` |
| `reentrant` | `rontolisp.reentrant` | `false` | `--reentrant`: overlapped JSPI calls |
| `emitWit` | `rontolisp.emitWit` | `false` | `--emit-wit`: the component's world beside the output |
| `emitJsGlue` | `rontolisp.emitJsGlue` | `false` | `--emit-js-glue`: the host glue beside the output |
| `emitWorkerEntry` | `rontolisp.emitWorkerEntry` | `false` | [`--emit-worker-entry`](../guides/wasm-host-boundary.md#generating-the-host-glue---emit-js-glue): the Cloudflare Worker's `index.js` beside the glue, written only where none is there; implies `emitJsGlue` |
| `optimize` | `rontolisp.optimize` | `default` | [`--optimize`](wasm.md#optimize-tree-shaking): `default`, `size` or `off` |
| `simd` | `rontolisp.simd` | `false` | [`--simd`](wasm.md#simd-acceleration---simd): v128 kernels |
| `reportLocations` | `rontolisp.reportLocations` | | [`--report-locations`](wasm.md#uncaught-condition-locations---report-locations) |
| `native` | `rontolisp.native` | `false` | [`--native`](native.md): a native executable |
| `nativeTarget` | `rontolisp.nativeTarget` | the host's | [`--native-target`](native.md#flags); needs `native` |
| `nativeCpu` | `rontolisp.nativeCpu` | `baseline` | [`--native-cpu`](native.md#flags); needs `native` |
| `dynamic` | `rontolisp.dynamic` | `false` | [`--dynamic`](dynamic.md) |
| `noPrune` | `rontolisp.noPrune` | `false` | `--no-prune`: keep every spliced library definition |
| `warningsAsErrors` | `rontolisp.warningsAsErrors` | `false` | [`--warnings-as-errors`](warnings.md) |
| `javaSourcePath` | | | `--java-source-path`: directories and sources jars searched before the dependencies' sources jars |
| `metadataRepository` | `rontolisp.metadataRepository` | | [`--java-metadata-repository`](../java/index.md#reflection-from-reachability-metadata): the shared repository of GraalVM reachability metadata, read for the dependencies -- a version of `org.graalvm.buildtools:graalvm-reachability-metadata`, resolved from the project's repositories, or a directory or zip of it |
| `skip` | `rontolisp.skip` | `false` | Skips the goal |
