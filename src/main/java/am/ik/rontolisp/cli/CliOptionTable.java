package am.ik.rontolisp.cli;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import am.ik.rontolisp.cli.CliOption.Arity;
import am.ik.rontolisp.cli.CliOption.Group;
import org.jspecify.annotations.Nullable;

import static am.ik.rontolisp.cli.CliOption.Group.CLOJURE;
import static am.ik.rontolisp.cli.CliOption.Group.COMMON;
import static am.ik.rontolisp.cli.CliOption.Group.COMMON_LISP;
import static am.ik.rontolisp.cli.CliOption.Group.COMPONENT;
import static am.ik.rontolisp.cli.CliOption.Group.INTERPRETER;
import static am.ik.rontolisp.cli.CliOption.Group.JAVA;
import static am.ik.rontolisp.cli.CliOption.Group.JVM;
import static am.ik.rontolisp.cli.CliOption.Group.NATIVE;
import static am.ik.rontolisp.cli.CliOption.Group.SCHEME;
import static am.ik.rontolisp.cli.CliOption.Group.STARLARK;
import static am.ik.rontolisp.cli.CliOption.Group.WASM;

/**
 * Every option of the command line, with what {@link CliOptions} needs to parse it and
 * where {@code --help} lists it: one table, so the parser, the help and the command-line
 * reference ({@code doc/<lang>/getting-started/command-line.md}) cannot drift apart. An
 * option the table does not hold is refused.
 */
final class CliOptionTable {

	/** Width of the option column: a longer syntax gets a line of its own. */
	private static final int SYNTAX_WIDTH = 19;

	private static final String INDENT = " ".repeat(2 + SYNTAX_WIDTH);

	private static final int LINE_WIDTH = 80;

	private static final List<Group> COMPILED = List.of(JVM, WASM, COMPONENT, NATIVE);

	/** The JVM-hosted back ends, where a {@code java:} call reaches a real class. */
	private static final List<Group> HOSTED = List.of(INTERPRETER, JVM);

	/** Every option, in the order the help lists a group's. */
	static final List<CliOption> OPTIONS = List.of(
	// @formatter:off
		optional("-h, --help", "GROUPS", List.of(COMMON), """
				Show this help message: every group (all, the default), or
				the common options and the named groups alone, e.g.
				--help=frontend:java,backend:wasm"""),
		flag("-v, --version", List.of(COMMON), "Show version"),
		repeatable(value("-e, --eval", "FORMS", List.of(COMMON), """
				Take the program from this argument, not from a file
				Repeatable: every -e appends another top-level form, so
				-e '(defun f () 1)' -e '(print (f))' is one program.
				Combines with -o (compiles the inline program) and with
				every flag below; it cannot be given beside an input file.
				A relative (load "...") resolves against the working
				directory, there being no file to resolve against""")),
		value("-o", "OUT", List.of(COMMON), """
				Compile to OUT instead of interpreting. Its type picks the
				back end: .class, .jar or .war the JVM, .wasm WASM (with
				--component a WASI component); --native -o OUT makes a
				native executable"""),
		value("--source-language", "NAME", List.of(COMMON), """
				The entry source's language, overriding the pick from
				its extension: common-lisp (every extension's default),
				clojure, scheme, starlark or java -- see their groups.
				With no file, the REPL's language (a scheme>, clojure>,
				starlark> or java> prompt). A (load ...)ed file is still
				picked by its own extension, so one program may mix
				languages file by file. Only common-lisp compiles with
				--no-gc"""),
		flag("--dump-ir", List.of(COMMON), """
				Print the program's IR -- every top-level form after
				macro expansion and library splicing, the core forms
				the backends consume -- one per line, as readable
				Common Lisp, and exit without running or writing
				anything. The IR is the compile -o and its flags name
				(-o is not written); with no -o, a .wasm compile's where
				a flag only a .wasm takes is given (--no-wasi, ...),
				--native's with --native, else a .class compile's"""),
		value("--system-path", "DIRS", List.of(COMMON_LISP), """
				Directories searched for NAME.asd by asdf:load-system
				(joined with the platform path separator, like PATH; the
				RONTOLISP_SOURCE_REGISTRY environment variable adds more)
				ql:quickload downloads systems into ~/.rontolisp/quicklisp
				(override with the RONTOLISP_QUICKLISP_HOME env variable)"""),
		repeatable(value("--dist", "DISTS", List.of(COMMON_LISP), """
				Quicklisp-format distributions ql:quickload may download from,
				beside quicklisp: a name (ultralisp) or a distinfo URL, several
				comma-separated (the RONTOLISP_DISTS env variable adds more).
				Searched in the order given, quicklisp first unless named;
				each caches under ~/.rontolisp/<dist> (RONTOLISP_DIST_HOME).
				A program can install one itself: (ql-dist:install-dist NAME)""")),
		repeatable(value("--feature", "NAMES", List.of(COMMON_LISP), """
				Read-time features to ADD, comma-separated and repeatable, so
				that #+NAME reads as true (and *features* holds it at run
				time). For a portable library whose #+sbcl / #+clisp chain was
				written before rontolisp and so names none of our features,
				leaving it in its #-(or ...) else branch: --feature sbcl says
				you have read that branch and want it. The claim is yours --
				it also selects the branches that call that implementation's
				internals. The rontolisp features are refused: they describe
				the build, which -o and the flags beside it decide.""")),
		optional("--swank", "PORT", List.of(COMMON_LISP, INTERPRETER), """
				Serve the REPL over SWANK on localhost, for SLIME
				(M-x slime-connect); port 4005 unless PORT is given"""),
		clojure("-A", ":ALIASES", """
				The deps.edn aliases (-A:dev:test) every Clojure file of the
				run is read under, as clj -A"""),
		clojure("-M", "[:ALIASES] ARGS", """
				Run the working directory's deps.edn project as clj -M
				does, after every other option: the aliases' :main-opts,
				then ARGS -- -m my.app ARG... calls my.app/-main, a path
				runs that file, nothing is the REPL. With -o the run is
				compiled, its arguments baked in ahead of the artifact's own"""),
		clojure("-X", "[:ALIASES] ARGS", """
				Call a function with a map, as clj -X does: [fn] [key
				value]... [map], each read as EDN, over the aliases'
				:exec-fn and :exec-args"""),
		optional("--nrepl", "PORT", List.of(CLOJURE, INTERPRETER), """
				Serve the REPL over nREPL on localhost, for an editor
				(CIDER, Calva, Conjure) or clj -M -m nrepl.cmdline
				--connect; any free port unless PORT is given, written to
				.nrepl-port in the working directory"""),
		value("--scheme-standard", "NAME", List.of(SCHEME), """
				What every Scheme file of the program is read against:
				the entry file, a (load ...)ed one and the REPL.
				rontolisp (the default) is this implementation's own
				dialect: R7RS plus the SICP/MIT and R5RS names, visible
				without an import. r7rs is R7RS-small, strictly: a program
				begins with (import ...), no SICP/MIT or R5RS name, no
				redefinition of an imported name, eval takes its
				environment."""),
		value("--starlark-standard", "NAME", List.of(STARLARK), """
				What every Starlark file of the program is read against.
				rontolisp (the default) lets a global be bound again, if
				and for stand at the top level and a function call
				itself. spec is the specification's rules: each of the
				three is an error."""),
		value("--java-source-path", "DIRS-AND-JARS", List.of(JAVA), """
				The source path of a program in the Java subset (.java):
				directories and jars of the libraries it uses, as SOURCE. A
				class the program names and its own source tree lacks is read
				from here and compiled with it as far as it is reached, on
				every backend (a .wasm too); nothing is carried beside the output"""),
		repeatable(value("--java-source-dep", "G:A:V", List.of(JAVA), """
				Repeatable. Such a library by Maven coordinates: its sources jar
				(G:A:jar:sources:V) and those of what it depends on join the
				source path after --java-source-path. A library with no sources
				jar is refused by name""")),
		value("--java-metadata-repository", "VERSION-OR-PATH", List.of(JAVA), """
				The shared repository of GraalVM reachability metadata, read
				for the libraries --java-source-dep resolves as their own
				META-INF/native-image metadata is: a version of
				org.graalvm.buildtools:graalvm-reachability-metadata (its
				repository zip, fetched as a sources jar is), or a directory or
				zip of it. Its entries naming a class of the JDK are not read"""),
		repeatable(value("--java-repository", "[ID=]URL", List.of(JAVA, INTERPRETER, JVM), """
				Repeatable. A Maven repository (https:, http: or file:) for
				--java-dep and --java-source-dep to search after Maven Central;
				the ID (default java-repository-N) is what settings.xml's
				<server> and <mirror> name. The id central replaces Central's URL""")),
		flag("--java-snapshots", List.of(JAVA, INTERPRETER, JVM), """
				Search Central's snapshot repository (central-snapshots=
				https://central.sonatype.com/repository/maven-snapshots/)
				after the --java-repository ones, for a -SNAPSHOT library"""),
		flag("--java-update-snapshots", List.of(CLOJURE, JAVA, INTERPRETER, JVM), """
				Like mvn -U: ask every repository again for a SNAPSHOT, LATEST,
				RELEASE or version range (--java-dep, --java-source-dep and a
				Clojure deps.edn) instead of keeping what the local repository
				holds until its update policy (daily) asks"""),
		flag("--wasi-logging", List.of(JAVA, WASM, COMPONENT), """
				With --component or --no-wasi: a Java program's log records
				(java.util.logging, and SLF4J through it) go to the host's
				wasi:logging/logging instead of the standard error -- the level,
				the logger's name, the message. A component then imports that
				interface, which its host must offer (wasmCloud does; wasmtime
				does not); --emit-js-glue answers it on the console method of
				the level"""),
		value("--stack", "MiB", List.of(INTERPRETER), """
				Stack of the thread rontolisp runs the program on (default 16)
				The interpreter's recursion depth is the program's, so the CLI
				runs it on a thread of its own rather than the launcher's: the
				ceiling is the same number on every platform, and a java -jar
				-Xss no longer reaches it. Raise it for a program recursing
				deeper than 16 MiB of interpreter frames."""),
		flag("--buffered-output", List.of(INTERPRETER), """
				Block-buffer stdout (avoids interleaving when piped)
				Off by default so the REPL responds to each line"""),
		flag("--simd", List.of(INTERPRETER, JVM, WASM, NATIVE), """
				Accelerate the vec: and linalg: kernels with hardware SIMD
				JVM (.class): route to the jdk.incubator.vector bridge (run with
				java --add-modules jdk.incubator.vector). A runtime without the
				module runs the same class unaccelerated instead of failing --
				the consumer need not have compiled it -- warning once on stderr.
				WASM (.wasm, both wasm-GC and --no-gc): emit native v128
				(f64x2/f32x4). On wasm-GC packed float arrays stay GC-managed
				(v128 lane groups), so memory behaves as without --simd.
				Interpreter/REPL: run the vec: kernels on the Vector API (baked
				into the native binary; on java -jar add --add-modules
				jdk.incubator.vector, else it falls back to scalar)."""),
		flag("--parallel", HOSTED, """
				With --simd: split the matrix products across CPU cores
				vec:matvec / vec:matvec-into, linalg:dot over a matrix and
				linalg:matmul (the stacked product included) run a row range
				per thread once a call is big enough (~2^15 multiply-adds);
				every other kernel, every reduction included, stays on the
				calling thread. The rows are independent chains, so the
				results are bit-identical to --simd alone. RONTOLISP_THREADS
				sets the thread count (the caller included; default: half
				the available processors; 1 = serial); the workers are daemon
				threads that spin ~1 ms between calls before sleeping, so a
				loop of products keeps them busy. Interpreter (incl. the
				native binary) and JVM (.class) only -- WASM has no threads.
				Needs --simd: without it there is nothing to split. On the
				interpreter/REPL, a java -jar without jdk.incubator.vector
				refuses rather than splitting the scalar fallback across
				threads -- that would be a slowdown, not a speedup."""),
		flag("--blas", HOSTED, """
				Route the linalg: matrix product to a tuned CBLAS from the OS
				Interpreter (incl. the native binary) and JVM (.class) only --
				WASM has no foreign function interface. macOS finds Accelerate
				with no setup; on Linux install one (e.g. libopenblas0-pthread).
				A machine with none runs the same programs, unaccelerated. The
				library reorders its reduction, so results are close to but not
				bit-identical to the other backends. RONTOLISP_BLAS names a
				library outright; RONTOLISP_BLAS_VERBOSE=1 prints what was bound."""),
		flag("--gpu", HOSTED, """
				Route the linalg: matrix product and the transcendentals to
				an NVIDIA GPU, or to Apple Silicon through Metal
				Both product shapes: the rank-2 one and the stacked rank >= 3
				one (torch.bmm), which is a transformer's whole hot path; plus
				the element-wise exp/log/tanh/sin/cos/tan/asin/acos/atan/
				sinh/cosh/erf. sqrt/abs/negative/sign and add/sub/mul/div stay
				on the CPU: they are one instruction per element and a round
				trip cannot pay for them. And vec:matvec, the GEMV a decode
				loop is made of, over a matrix that stays on the device.
				Interpreter (incl. the native binary) and JVM (.class) only --
				the device is reached through the foreign function API, which
				WASM does not have. A compiled .class carries the whole
				binding for BOTH platforms and still runs with a plain
				`java Prog` (add --enable-native-access=ALL-UNNAMED to silence
				the JVM warning).
				Needs libcuda.so.1 (the NVIDIA driver) and nothing else, or on
				a Mac nothing at all: no CUDA toolkit, no Xcode. A machine
				without a device runs the same programs, unaccelerated.
				ON APPLE SILICON THE FLAG IS SINGLE-FLOAT ONLY: Metal has no
				double, so a #d array always stays on the CPU -- use #f arrays
				(torch: already defaults to them). Its floor is five times
				CUDA's, so it accepts from ~166x166x166 rather than ~51 cubed,
				from 131072 element-wise rather than 16384, and it declines
				the axis folds outright; the CUDA thresholds and member set
				are unchanged. For a rank >= 3 stack the product bound is on
				the TOTAL work, since the whole stack is one round trip.
				THE ONLY FLAG WHOSE RESULTS DO NOT MATCH THE OTHER BACKENDS
				ELEMENTWISE: the product fuses each multiply-add and the device
				has its own libm, so an accelerated exp/erf/... differs in the
				last few digits (and more at single float)."""),
		value("--java-classpath", "PATHS", HOSTED, """
				The program's Java class path: directories and jars whose
				classes the interpreter loads and a .class, .jar or .war
				output resolves java: calls against after the JDK's. A
				program jar copies them into NAME-lib/ beside it and names
				them in its manifest's Class-Path; a war carries them in
				WEB-INF/lib"""),
		repeatable(value("--java-dep", "G:A:V", HOSTED, """
				Repeatable. A Java library by Maven coordinates, with what it
				depends on (Maven's nearest-wins selection), from Maven
				Central through ~/.m2/repository (or settings.xml's
				localRepository); its jars join the class path after
				--java-classpath, and a generated pom lists the coordinates""")),
		flag("--warn-java-reflection", HOSTED, """
				Report every java: call that cannot be resolved before it runs
				(a compile warning; the interpreter sets java:*warn-on-reflection*)"""),
		flag("--dynamic", COMPILED, """
				Resolve unknown calls/vars at runtime (late binding)
				Lets sources that define functions via load compile as-is"""),
		optional("--optimize", "LEVEL", COMPILED, """
				Dead-code-eliminate the compiled output -- ON BY DEFAULT.
				Pass nothing and you get --optimize=default; the way to
				decline it is --optimize=off, not the absence of the flag
				WASM: drop functions unreachable from the exports/_start, in
				--component mode too; great with --no-wasi
				JVM: drop methods unreachable from main + compact the constant pool
				A function the program never takes as a value gets no funcall
				dispatch case, so library code goes too -- unless the program
				can name a function out of data (eval/read/load/~/name/), which
				keeps everything. -Drontolisp.debug.dispatchgate=true says which
				LEVEL says what to optimize FOR (no flag = bare flag = default)
				  off      drop nothing: what the backends emit on their own,
				           which is what a flagless build used to produce --
				           and ~300x the bytes of the default on a hello world
				  default  the elimination described above, nothing traded for it
				  size     default, plus: give up speed for size. On wasm-GC
				           that drops the two emissions that spend bytes on
				           speed (fused integer trees, unboxed locals): about
				           -20% of any module, but only integer arithmetic
				           fuses, so the price runs from ~+10% on a float
				           kernel to ~4x on integer crypto.
				           Accepted and identical to default on JVM and --no-gc"""),
		flag("--no-prune", COMPILED, """
				Keep every spliced library function in the compiled output
				By default unreachable library definitions (linalg:/vec:/...)
				are dropped at compile time; names forged at runtime from
				computed strings need this flag (or --dynamic) to resolve."""),
		flag("--warnings-as-errors", COMPILED, """
				With -o: fail the compile, writing nothing and exiting 1,
				when it warns about the program's own source (a wrong
				argument count, an undefined function, ...). Every warning
				is still printed; a spliced library's or a dist-installed
				system's warnings never count"""),
		value("--class-name", "NAME", List.of(JVM), """
				With a .class, .jar or .war output: the fully qualified name of
				the emitted class (com.example.Kernels). REQUIRED for a
				--no-main library jar, whose class IS its Java API; a program
				jar derives one from the -o file name (app.jar -> App) and is
				entered through the manifest. For a .class it replaces the
				name the -o path would give, so -o build/K.class can still be
				com.example.Kernels -- and it is how an ABSOLUTE -o path names
				a package at all, a leading / being no package name"""),
		flag("--no-main", List.of(JVM), """
				With a .class or .jar output: compile a LIBRARY class
				instead of a command -- no main method (and no Main-Class in
				a jar), and the class is entered through its
				rontolisp:jvm-export typed methods (at least one is required;
				main is the only tree-shaker root otherwise). The top level
				runs once, at class initialization, exactly as a --no-wasi
				reactor runs its top level at instantiation"""),
		value("--maven-coordinates", "G:A:V", List.of(JVM), """
				With a .jar or .war output: embed META-INF/maven/G/A/pom.xml and
				pom.properties, so the coordinates travel INSIDE the jar and
				`mvn install:install-file -Dfile=out.jar` needs no -DgroupId,
				-DartifactId, -Dversion or -DpomFile. The generated pom's
				<dependencies> are the --java-dep coordinates, empty without
				any: a compiled class embeds everything else it calls"""),
		flag("--emit-pom", List.of(JVM), """
				Write that same pom next to the jar as out.pom (for
				deploy-file), the way --emit-wit writes the world next to the
				.wasm. Needs --maven-coordinates"""),
		value("--java-release", "N", List.of(JVM), """
				With a .class, .jar or .war output: resolve java: calls against
				Java release N's API, read from the JDK's lib/ct.sym (default:
				the newest the JDK holds -- its own)"""),
		flag("--java-static", List.of(JVM), """
				With a .class, .jar or .war output: make every java: call that
				needs reflection a compile error, so the output has none and
				GraalVM native-image builds it with no reachability metadata"""),
		value("--report-locations", "WHAT", List.of(WASM, COMPONENT, NATIVE), """
				With a wasm-GC output (.wasm, --component, --native): print where
				an uncaught condition happened under its report, as the
				interpreter does. Off by default; costs bytes in every function
				read from a file, and gives a program without handler-case & co.
				the report itself (and exception handling) too
				  function  the function and the line its definition starts on
				  line      the innermost form, like the interpreter"""),
		flag("--no-gc", List.of(WASM, COMPONENT), """
				Emit a plain (non-wasm-GC) WASM module for pure-numeric exports
				Runs on any MVP runtime (no wasm-GC required, no import object). Only
				scalar rontolisp:wasm-export functions (:int/:float/:bool) work;
				ineligible (cons/string/I/O/...) functions are a compile error.
				Scalar vec: loops by default; add --simd for native v128.
				Packed vec: arrays are bump-allocated and never freed; reclaim
				with rontolisp:with-arena or use the -into kernels in hot loops.
				Add --component for a compact typed component-model wrap."""),
		flag("--no-wasi", List.of(WASM, COMPONENT), """
				Emit WASM with no WASI imports (reactor mode)
				Instantiates without an import object (beyond any
				rontolisp:wasm-import host functions); pure-compute
				rontolisp:wasm-export functions work, print is discarded
				(--host-output keeps it),
				other I/O traps. The core module also exports two host hooks,
				__ronto_seed_random and __ronto_set_time (nanos since the Unix
				epoch), for the two services it cannot answer alone -- call them
				before _initialize. With --component: a reactor component that
				imports NOTHING and runs its top level at instantiation"""),
		flag("--host-random", List.of(WASM), """
				With --no-wasi (core module only): draw `random` from the HOST
				instead of the built-in generator. The module then imports
				exactly one function, env.random_get(buf, len) -> errno (the
				preview1 signature), so a JS host adds one line to its import
				object; rontolisp:random-bytes works, and no
				__ronto_seed_random export is emitted (nothing left to seed)"""),
		flag("--host-output", List.of(WASM), """
				With --no-wasi (core module only): standard output and error to
				the HOST instead of discarding them. A module that writes then
				imports env.fd_write(fd, iovs, iovs_len, nwritten) -> errno (the
				preview1 signature), which --emit-js-glue implements as
				console.log / console.error lines, and what escapes its top level
				is reported before the trap, as on Preview 1"""),
		flag("--host-fetch", List.of(WASM), """
				With --no-wasi (core module only): route rontolisp:fetch at the
				HOST's own HTTP client. The module then imports
				env.fetch(request-json) -> response-json (strings via the
				wasm-import ABI) -- plus, on the streaming boundary below,
				env.readResponseBody(ptr, cap) -> i32 for the reply's body (a
				chunk per call into a buffer the module passes; 0 ends it), and
				fetch answers the same (:status :headers :body) plist as every
				other backend, :body the same asynchronous stream. A JS host
				implements them with fetch() behind WebAssembly.Suspending
				(JSPI) and enters exports via promising, or answers
				synchronously; the build prints the exact obligation"""),
		flag("--host-sockets", List.of(WASM), """
				With --no-wasi (core module only): route rontolisp:tcp-* -- and
				so usocket, and a Java program's java.net.Socket -- at the
				HOST's sockets. The module then imports tcp.* (socket,
				begin, end, read, write, flush, close, wait, ...), which
				--emit-js-glue answers over connect() of cloudflare:sockets:
				outbound connections only (no bind, listen or accept), each
				wait a JSPI suspension, and under the generated worker() a
				socket ends with the request that opened it"""),
		flag("--host-timer", List.of(WASM), """
				With --no-wasi (core module only): the HOST waits for the module.
				A wait for a time -- sleep, a Java thread's sleep or timed wait --
				suspends in env.sleep(ms) until the host's timer fires, and a Java
				server's request with nothing to do idles in env.idle(token, at),
				the host calling the module for other requests meanwhile, until
				env.ring(token) or that time: one request can wait for another.
				The host moves the clock (__ronto_set_time) before each returns.
				Needs JSPI: _initialize and every export are entered through
				WebAssembly.promising; --emit-js-glue implements all of it"""),
		value("--host-boundary", "MODE", List.of(WASM), """
				With --no-wasi (core module only): where an HTTP body crosses.
				Both shapes speak the same JSON envelope; MODE says whether a body
				rides inside it or streams beside it
				  envelope   (default) every body rides the envelope's own
				             "body" key. No body imports and no host-side
				             cursor: a Worker that reads one document and
				             answers one imports at most env.fetch
				  streaming  the request and response bodies leave the envelope
				             through env.readRequestBody / env.writeResponseBody
				             -- and, with --host-fetch, env.readResponseBody.
				             Ask for it when a body is BINARY (the envelope
				             carries one as JSON text, so ff fe 41 arrives as
				             seven bytes), LARGE (the envelope costs linear
				             memory proportional to it) or RELAYED from an
				             upstream reply you would forward a chunk at a time
				Either way --emit-js-glue writes the whole host half, so the
				JavaScript is three lines on both"""),
		flag("--reentrant", List.of(WASM), """
				With --no-wasi (core module only, and a suspending import: an
				:async t wasm-import or --host-fetch with fetch used): let a JSPI
				host OVERLAP calls into ONE instance instead of serialising them.
				The module then owns its per-call state -- dynamic (special)
				bindings move into a per-call task record, and cross-call staging
				into __ronto_park_alloc/__ronto_park_free blocks (a :string result
				is one the reader frees; --emit-js-glue writes all of it). Buys
				I/O overlap, never CPU parallelism: one stack still runs at a
				time. Composes with --host-boundary=streaming: the body imports
				then lead with an :int call id (the envelope's "call-id" key,
				the fetch reply's "body-id"), so each pull names its call.
				Not combinable with --dynamic or --component"""),
		flag("--emit-js-glue", List.of(WASM), """
				With --no-wasi: also write the JavaScript host glue next to
				the .wasm output (out.wasm -> out.js), derived from the
				program's own wasm-import/wasm-export declarations: the
				import object, the (ptr, len) staging and __ronto_alloc
				bracket, the WebAssembly.Suspending / promising entries a
				JSPI host needs, and the one-call-at-a-time queue. A host
				then supplies only what a declaration cannot say: one plain
				function per import"""),
		flag("--emit-worker-entry", List.of(WASM), """
				With --emit-js-glue (which it implies): also write the
				Cloudflare Worker's index.js next to the glue -- the three
				lines importing the module and the glue and exporting
				worker(module). Written once: an index.js already there is
				left as it is. Needs an http-handler reactor whose glue
				implements every import"""),
		flag("--component", List.of(COMPONENT), """
				Emit a WASI 0.3 component (run with: wasmtime run)
				WASM only; print/stdin/file I/O work. Pure-compute
				rontolisp:wasm-export functions (incl. :string/:s-expr)
				become typed component exports, callable via
				wasmtime run --invoke 'name(args)'
				With --no-gc: a compact reactor component (typed exports
				incl. :long and :string; print works via a tiny WASI 0.2
				stdio bridge; no wasm-GC, no flags)"""),
		flag("--emit-wit", List.of(COMPONENT), """
				With --component: also write the component's WIT world
				(imports + typed exports) next to the .wasm output, so hosts
				and binding generators (e.g. jco) need no wasm-tools introspection
				It is the only thing that reports what the component actually
				IMPORTS -- a hand-written world states only the export side"""),
		value("--scaffold-wit", "W", List.of(COMPONENT), """
				Generate a skeleton implementing the WIT world W instead of
				compiling: a rontolisp:wit-export directive plus one defun stub
				per export (with -o FILE; prints to stdout otherwise). Pick the
				world with --world NAME when the file declares several.
				The program then IMPLEMENTS the .wit: the compiler checks every
				defun against it, so no :params/:returns list is written by hand"""),
		value("--world", "NAME", List.of(COMPONENT), """
				With --scaffold-wit: the world to implement, when the file
				declares several"""),
		flag("--native", List.of(NATIVE), """
				With -o OUT: compile to the native executable OUT (see the
				group's description)"""),
		value("--native-target", "PLATFORM", List.of(NATIVE), """
				Build for linux-x86_64, linux-aarch64 or macos-aarch64
				instead of this machine's platform"""),
		value("--native-cpu", "LEVEL", List.of(NATIVE), """
				baseline (default: runs on every CPU of the platform),
				host (every feature of this machine's CPU), or
				x86-64-v2, x86-64-v3, x86-64-v4 on x86_64"""),
		testOnly(value("-r, --reporter", "S", List.of(), "rove reporter style: spec (default), dot or none")),
		testOnly(flag("--color", List.of(), "Force the ANSI colors on")),
		testOnly(flag("--disable-colors, --no-color", List.of(), "Force them off"))
	// @formatter:on
	);

	private CliOptionTable() {
	}

	/**
	 * The entry an option key names, by any of its spellings.
	 * @param key the key as written, without a glued value
	 * @return the option, or {@code null} when the table has none
	 */
	static @Nullable CliOption find(String key) {
		for (CliOption option : OPTIONS) {
			if (option.arity() != Arity.CLOJURE && option.names().contains(key)) {
				return option;
			}
		}
		return null;
	}

	/**
	 * Print the usage: the modes, the subcommands and every option group, or -- with a
	 * selection -- the common options and the named groups alone.
	 * @param out where to print
	 * @param selection the {@code --help=} value: empty or {@code all} for every group,
	 * else comma-separated {@link Group#selector() selectors}
	 * @throws IllegalArgumentException when a selector names no group
	 */
	static void printUsage(PrintStream out, @Nullable String selection) {
		Set<Group> groups = select(selection);
		boolean all = groups.size() == Group.values().length;
		out.println("Usage: rontolisp [options] [file] [-- ARG...]");
		if (all) {
			out.println("""
					  (no args)          REPL mode
					  file               Interpret the file
					  -e "FORMS"         Interpret the given program instead of a file (--eval)
					  file -o OUT        Compile the file; OUT's type picks the back end
					  file -- ARG...     Interpret the file with ARG... as the PROGRAM's own
					                     arguments: everything after -- is (uiop:command-line-
					                     arguments), never a rontolisp option. A compiled
					                     artifact takes them from its own host instead
					                     (java Out a b, wasmtime run out.wasm a b)

					Subcommands:
					  format PATH...     Re-indent Lisp source files in place (a
					                     directory is walked for .lisp and .asd files).
					                     See: rontolisp format --help
					  test TARGET        Run a rove test file, .asd or system and EXIT with
					                     its verdict: 0 all passed, 1 a test failed or none
					                     ran. With -o it compiles the run instead, and the
					                     artifact carries the same exit contract.
					                     See: rontolisp test --help
					  build SYSTEM       asdf:make of a program-op system: the executable its
					                     :entry-point runs, at its :build-pathname (no type is a
					                     native executable, .jar a JVM program, .wasm a module).
					                     See: rontolisp build --help
					  notices            The licence notices to ship with a compiled program
					                     (--native: its runner's too). See: rontolisp notices --help

					The options are grouped by the front end (the source language: the file's
					extension or --source-language) and the back end (-o) they belong to.
					--help=GROUP,... prints the common options and the named groups alone:""");
			wrap(out, "", Arrays.stream(Group.values()).map(Group::selector).toList());
		}
		Set<CliOption> described = new HashSet<>();
		for (Group group : groups) {
			out.println();
			out.println(group.heading());
			for (String line : description(group).lines().toList()) {
				out.println("  " + line);
			}
			// An option of several groups is described under the first printed: the
			// others name it after their own.
			Map<Group, List<String>> elsewhere = new EnumMap<>(Group.class);
			for (CliOption option : OPTIONS) {
				if (!option.groups().contains(group)) {
					continue;
				}
				if (described.add(option)) {
					print(out, option.syntax(), option.help());
				}
				else {
					Group first = option.groups().stream().filter(groups::contains).findFirst().orElseThrow();
					elsewhere.computeIfAbsent(first, key -> new ArrayList<>()).add(option.names().get(0));
				}
			}
			elsewhere.forEach((first, names) -> wrap(out, "See " + first.selector() + " for", names));
		}
	}

	/** Print {@code head} and the items, comma-separated, continued on indented lines. */
	private static void wrap(PrintStream out, String head, List<String> items) {
		StringBuilder line = new StringBuilder("  " + head);
		for (int i = 0; i < items.size(); i++) {
			String item = items.get(i) + (i < items.size() - 1 ? "," : "");
			if (line.length() + 1 + item.length() > LINE_WIDTH) {
				out.println(line);
				line = new StringBuilder("    ");
			}
			if (!line.toString().isBlank()) {
				line.append(' ');
			}
			line.append(item);
		}
		out.println(line);
	}

	private static void print(PrintStream out, String syntax, String help) {
		List<String> lines = help.lines().toList();
		int from = 0;
		if (syntax.length() <= SYNTAX_WIDTH - 1) {
			out.println("  " + syntax + " ".repeat(SYNTAX_WIDTH - syntax.length()) + lines.get(0));
			from = 1;
		}
		else {
			out.println("  " + syntax);
		}
		for (String line : lines.subList(from, lines.size())) {
			out.println(INDENT + line);
		}
	}

	/**
	 * The groups a {@code --help=} value selects, in the order they print.
	 * @param selection the value, {@code null} or empty for every group
	 * @return the groups, the common one always among them
	 */
	static Set<Group> select(@Nullable String selection) {
		if (selection == null || selection.isEmpty() || selection.equals("all")) {
			return EnumSet.allOf(Group.class);
		}
		Set<Group> groups = EnumSet.of(Group.COMMON);
		for (String selector : selection.split(",", -1)) {
			Group found = null;
			for (Group group : Group.values()) {
				if (group.selector().equals(selector.strip())) {
					found = group;
				}
			}
			if (found == null) {
				throw new IllegalArgumentException("--help: '" + selector + "' names no group; the groups are all, "
						+ Arrays.stream(Group.values()).map(Group::selector).collect(Collectors.joining(", ")));
			}
			groups.add(found);
		}
		return groups;
	}

	/** What the group's heading says before its options. */
	private static String description(Group group) {
		return switch (group) {
			case COMMON -> "Every front end and back end takes these.";
			case COMMON_LISP -> """
					The default: .lisp, .asd and every extension the other front ends do
					not claim.""";
			case CLOJURE -> """
					Clojure (.clj, .cljc) is EXPERIMENTAL: a small subset lowered to the
					same core forms, no subset or compatibility promise yet.""";
			case SCHEME -> """
					Scheme (.scm) is EXPERIMENTAL: a subset of R7RS-small (scheme base),
					partial conformance by design and no compatibility promise.""";
			case STARLARK -> """
					Starlark (.star) is EXPERIMENTAL: partial conformance and no
					compatibility promise; at its prompt a blank line ends a block.""";
			case JAVA -> """
					Java (.java) is an EXPERIMENTAL subset of the Java language: what it
					does not have yet refused by name and no compatibility promise; at
					its prompt a statement needs no ';'.""";
			case INTERPRETER -> """
					Without -o: the file, -e or the REPL runs in this process, on the
					interpreter.""";
			case JVM -> """
					With -o OUT.class: JVM bytecode. The -o path names the class:
					com/acme/K.class is com.acme.K, and a directory that cannot be a
					package (an absolute path above all) leaves just the file's own name;
					see --class-name. With -o OUT.jar: an executable jar (java -jar
					OUT.jar). With -o OUT.war: an http-handler program as a Servlet war
					that deploys unmodified on any Servlet 6 container (Tomcat, Jetty,
					...): no web.xml, no configuration; the container owns the port, so a
					written port is ignored.""";
			case WASM -> """
					With -o OUT.wasm: a wasm-GC module on WASI Preview 1 (wasmtime run
					OUT.wasm). With --no-wasi a reactor for a host such as JavaScript,
					with --no-gc a plain module of numeric exports.""";
			case COMPONENT -> "With -o OUT.wasm --component: a WASI 0.3 component (wasmtime run).";
			case NATIVE -> """
					With --native -o OUT: ONE self-contained native executable, the WASM
					output precompiled by wasmtime and appended to a small runner (no
					wasmtime needed to run it; ./OUT ARG...).
					It refuses --component, --no-wasi, --no-gc, --reentrant, --emit-js-glue
					and every --host-* flag.""";
		};
	}

	private static CliOption flag(String names, List<Group> groups, String help) {
		return option(names, Arity.FLAG, "", groups, help);
	}

	private static CliOption value(String names, String metavar, List<Group> groups, String help) {
		return option(names, Arity.VALUE, metavar, groups, help);
	}

	private static CliOption optional(String names, String metavar, List<Group> groups, String help) {
		return option(names, Arity.OPTIONAL, metavar, groups, help);
	}

	private static CliOption clojure(String name, String metavar, String help) {
		return option(name, Arity.CLOJURE, metavar, List.of(CLOJURE), help);
	}

	private static CliOption option(String names, Arity arity, String metavar, List<Group> groups, String help) {
		// a group prints in the enum's order, so the first group is where the option
		// is described in full
		List<Group> ordered = new ArrayList<>(groups);
		ordered.sort(null);
		return new CliOption(List.of(names.split(", ")), arity, metavar, false, false, ordered, help.stripTrailing());
	}

	private static CliOption repeatable(CliOption option) {
		return new CliOption(option.names(), option.arity(), option.metavar(), true, option.testOnly(), option.groups(),
				option.help());
	}

	private static CliOption testOnly(CliOption option) {
		return new CliOption(option.names(), option.arity(), option.metavar(), option.repeatable(), true,
				option.groups(), option.help());
	}

}
