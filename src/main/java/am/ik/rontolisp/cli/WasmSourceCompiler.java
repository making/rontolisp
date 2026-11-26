package am.ik.rontolisp.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import am.ik.rontolisp.codegen.wasm.NoGcWasmCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.codegen.wasm.WasmReportLocations;
import am.ik.rontolisp.compiler.FetchResponseShape;
import am.ik.rontolisp.compiler.HostBoundary;
import am.ik.rontolisp.compiler.HostGlueEmitter;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.eval.DistClient;
import am.ik.rontolisp.eval.SocketsLibrary;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceStandards;
import org.jspecify.annotations.Nullable;

/**
 * Compiles one program to WebAssembly IN PROCESS -- a {@code .wasm} module or component,
 * or the native executable a {@code --native} module is precompiled into -- and writes
 * it, with nothing of the command line.
 * <p>
 * The WASM twin of {@link JvmSourceCompiler}: the seam {@code rontolisp-maven-plugin}'s
 * {@code wasm} goal compiles {@code src/main/java} through. The CLI's own {@code -o
 * out.wasm} / {@code --native} path holds one of these for its wasm flags, refuses their
 * conflicts through {@link #refuseConflicts}, compiles through {@link #compileProgram}
 * and writes through {@link Result#write}, so the two cannot drift.
 * <p>
 * Every setter is one CLI flag and the defaults are the CLI's. A compile runs on a thread
 * of the CLI's stack, as {@link JvmSourceCompiler}'s does.
 */
public final class WasmSourceCompiler {

	private boolean dynamic;

	private boolean component;

	private boolean noWasi;

	private boolean noGc;

	private OptimizeLevel optimize = OptimizeLevel.DEFAULT;

	private boolean simd;

	private boolean hostRandom;

	private boolean hostOutput;

	private boolean hostFetch;

	private boolean hostSockets;

	private boolean hostTimer;

	private boolean wasiLogging;

	private boolean reentrant;

	private @Nullable HostBoundary hostBoundary;

	private @Nullable WasmReportLocations reportLocations;

	private boolean emitWit;

	private boolean emitJsGlue;

	private boolean emitWorkerEntry;

	private Consumer<String> notices = message -> System.err.println("rontolisp: " + message);

	private @Nullable NativeTarget nativeTarget;

	private boolean noPrune;

	private @Nullable String baseDir;

	private List<String> systemPath = List.of();

	private List<String> dists = List.of();

	private List<String> features = List.of();

	private @Nullable String sourceLanguage;

	private List<String> javaSourcePath = List.of();

	private @Nullable String javaMetadataRepository;

	private List<String> javaDependencies = List.of();

	private boolean warningsAsErrors;

	/**
	 * @param dynamic {@code --dynamic}: late binding, no static resolution
	 */
	public WasmSourceCompiler dynamic(boolean dynamic) {
		this.dynamic = dynamic;
		return this;
	}

	/**
	 * @param component {@code --component}: a WASI 0.3 component
	 */
	public WasmSourceCompiler component(boolean component) {
		this.component = component;
		return this;
	}

	/**
	 * @param noWasi {@code --no-wasi}: a reactor module that imports no WASI
	 */
	public WasmSourceCompiler noWasi(boolean noWasi) {
		this.noWasi = noWasi;
		return this;
	}

	/**
	 * @param noGc {@code --no-gc}: the scalar backend's plain module
	 */
	public WasmSourceCompiler noGc(boolean noGc) {
		this.noGc = noGc;
		return this;
	}

	/**
	 * @param optimize the {@code --optimize} level
	 */
	public WasmSourceCompiler optimize(OptimizeLevel optimize) {
		this.optimize = optimize;
		return this;
	}

	/**
	 * @param simd {@code --simd}: native v128 kernels
	 */
	public WasmSourceCompiler simd(boolean simd) {
		this.simd = simd;
		return this;
	}

	/**
	 * @param hostRandom {@code --host-random}: {@code random_get} from a host import
	 */
	public WasmSourceCompiler hostRandom(boolean hostRandom) {
		this.hostRandom = hostRandom;
		return this;
	}

	/**
	 * @param hostOutput {@code --host-output}: {@code fd_write} to a host import
	 */
	public WasmSourceCompiler hostOutput(boolean hostOutput) {
		this.hostOutput = hostOutput;
		return this;
	}

	/**
	 * @param hostFetch {@code --host-fetch}: {@code rontolisp:fetch} through a host
	 * import
	 */
	public WasmSourceCompiler hostFetch(boolean hostFetch) {
		this.hostFetch = hostFetch;
		return this;
	}

	/**
	 * @param hostSockets {@code --host-sockets}: {@code rontolisp:tcp-*} through the
	 * host's sockets on a {@code --no-wasi} core module
	 * @return this compiler
	 */
	public WasmSourceCompiler hostSockets(boolean hostSockets) {
		this.hostSockets = hostSockets;
		return this;
	}

	/**
	 * @param hostTimer {@code --host-timer}: the host waits for the module -- a wait for
	 * a time in its timer, a call with nothing to do idling in it
	 * @return this
	 */
	public WasmSourceCompiler hostTimer(boolean hostTimer) {
		this.hostTimer = hostTimer;
		return this;
	}

	/**
	 * @param wasiLogging {@code --wasi-logging}: a program's log records to the host's
	 * {@code wasi:logging/logging}, which the component then imports
	 * @return this
	 */
	public WasmSourceCompiler wasiLogging(boolean wasiLogging) {
		this.wasiLogging = wasiLogging;
		return this;
	}

	/**
	 * @param reentrant {@code --reentrant}: overlapped JSPI calls
	 */
	public WasmSourceCompiler reentrant(boolean reentrant) {
		this.reentrant = reentrant;
		return this;
	}

	/**
	 * @param hostBoundary {@code --host-boundary}, or {@code null} for the default
	 */
	public WasmSourceCompiler hostBoundary(@Nullable HostBoundary hostBoundary) {
		this.hostBoundary = hostBoundary;
		return this;
	}

	/**
	 * @param reportLocations {@code --report-locations}, or {@code null} for the default
	 */
	public WasmSourceCompiler reportLocations(@Nullable WasmReportLocations reportLocations) {
		this.reportLocations = reportLocations;
		return this;
	}

	/**
	 * @param emitWit {@code --emit-wit}: write the component's world beside the module
	 */
	public WasmSourceCompiler emitWit(boolean emitWit) {
		this.emitWit = emitWit;
		return this;
	}

	/**
	 * @param emitJsGlue {@code --emit-js-glue}: write the host glue beside the module
	 */
	public WasmSourceCompiler emitJsGlue(boolean emitJsGlue) {
		this.emitJsGlue = emitJsGlue;
		return this;
	}

	/**
	 * @param emitWorkerEntry {@code --emit-worker-entry}: write the Cloudflare Worker's
	 * {@code index.js} beside the glue, unless one is there already (implies
	 * {@link #emitJsGlue})
	 */
	public WasmSourceCompiler emitWorkerEntry(boolean emitWorkerEntry) {
		this.emitWorkerEntry = emitWorkerEntry;
		return this;
	}

	/**
	 * @param notices where a write reports what it left alone (a Worker entry already
	 * there); standard error by default
	 */
	public WasmSourceCompiler notices(Consumer<String> notices) {
		this.notices = notices;
		return this;
	}

	/**
	 * {@code --native}: precompile the module into a native executable.
	 * @param platform {@code --native-target}, or {@code null} for the host
	 * @param cpu {@code --native-cpu}, or {@code null} for the baseline
	 */
	public WasmSourceCompiler nativeExecutable(@Nullable String platform, @Nullable String cpu) {
		return nativeTarget(NativeTarget.of(platform, cpu));
	}

	WasmSourceCompiler nativeTarget(@Nullable NativeTarget nativeTarget) {
		this.nativeTarget = nativeTarget;
		return this;
	}

	/**
	 * @param noPrune {@code --no-prune}: keep every spliced library definition
	 */
	public WasmSourceCompiler noPrune(boolean noPrune) {
		this.noPrune = noPrune;
		return this;
	}

	/**
	 * @param baseDir the directory a {@code (load "...")} resolves against
	 */
	public WasmSourceCompiler baseDir(@Nullable String baseDir) {
		this.baseDir = baseDir;
		return this;
	}

	/**
	 * @param systemPath the ASDF system search path ({@code --system-path})
	 */
	public WasmSourceCompiler systemPath(List<String> systemPath) {
		this.systemPath = List.copyOf(systemPath);
		return this;
	}

	/**
	 * @param dists the Quicklisp-format distributions ({@code --dist})
	 */
	public WasmSourceCompiler dists(List<String> dists) {
		this.dists = List.copyOf(dists);
		return this;
	}

	/**
	 * @param features read-time features declared on top of the target's own
	 * ({@code --feature})
	 */
	public WasmSourceCompiler features(List<String> features) {
		this.features = List.copyOf(features);
		return this;
	}

	/**
	 * @param sourceLanguage {@code --source-language}, or {@code null} to pick the entry
	 * file's language from its extension
	 */
	public WasmSourceCompiler sourceLanguage(@Nullable String sourceLanguage) {
		if (sourceLanguage != null) {
			SourceLanguage.parse(sourceLanguage);
		}
		this.sourceLanguage = sourceLanguage;
		return this;
	}

	/**
	 * @param roots the source path of a program in the Java subset: the directories and
	 * jars its libraries are read from as source and compiled with it
	 * ({@code --java-source-path})
	 */
	public WasmSourceCompiler javaSourcePath(List<String> roots) {
		this.javaSourcePath = List.copyOf(roots);
		return this;
	}

	/**
	 * @param repository the shared repository of reachability metadata the libraries of a
	 * program in the Java subset are looked up in, a directory or a zip of its layout
	 * ({@code --java-metadata-repository}), or {@code null} for none
	 * @param dependencies the Maven coordinates ({@code group:artifact:version}) of the
	 * libraries on the source path it is asked for
	 */
	public WasmSourceCompiler javaMetadataRepository(@Nullable String repository, List<String> dependencies) {
		this.javaMetadataRepository = repository;
		this.javaDependencies = List.copyOf(dependencies);
		return this;
	}

	/**
	 * @param warningsAsErrors {@code --warnings-as-errors}: a compile that warns about
	 * the program's own source fails with a {@link WarningsAsErrorsException}
	 */
	public WasmSourceCompiler warningsAsErrors(boolean warningsAsErrors) {
		this.warningsAsErrors = warningsAsErrors;
		return this;
	}

	/**
	 * Compiles a source text: the front end, then the backend the flags choose.
	 * <p>
	 * A failure carries the front end's {@code file:line:column:} prefix, as the command
	 * line reports it.
	 * @param source the program text
	 * @param entryFile the path the text was read from: diagnostics, {@code (load ...)}
	 * provenance, and for a {@code .java} file the source tree its other classes are read
	 * from
	 * @return the compiled module, to {@link Result#write}
	 */
	public Result compile(String source, @Nullable String entryFile) {
		refuseConflicts(true);
		if (this.nativeTarget != null) {
			NativeToolchain.load().check(this.nativeTarget);
		}
		return SizedThread.call("rontolisp-compile", SizedThread.WORKER_STACK_BYTES, () -> {
			DistClient dists = DistClient.createDefault(this.dists);
			return CompileDiagnostics.recording(dists, () -> {
				try (JavaClassPath classPath = JavaClassPath.of(List.of())) {
					CompileFrontend.Result frontend = CompileFrontend.run(CompileFrontend.Request.builder()
						.source(source)
						.entryFile(entryFile)
						.sourceLanguage(this.sourceLanguage)
						.standards(SourceStandards.DEFAULT.withJavaSourcePath(this.javaSourcePath)
							.withJavaMetadataRepository(this.javaMetadataRepository, this.javaDependencies))
						.systemPath(this.systemPath)
						.dists(dists)
						.declaredFeatures(this.features)
						.javaClassLoader(classPath.classLoader())
						.javaClassLookup(classPath.classFiles())
						.javaClassPath(classPath::add)
						.options(frontendOptions(this.baseDir).build())
						.build());
					Result compiled = compileProgram(frontend);
					CompileDiagnostics.failOnWarnings(this.warningsAsErrors);
					return compiled;
				}
			});
		});
	}

	/**
	 * The front end's options for a WASM output under these flags.
	 * @param baseDir the directory a {@code (load "...")} resolves against
	 * @return the options, for the caller to finish
	 */
	CompileFrontend.Options.Builder frontendOptions(@Nullable String baseDir) {
		return CompileFrontend.Options.builder()
			.baseDir(baseDir)
			.wasm(true)
			.dynamic(this.dynamic)
			.component(this.component)
			.noWasi(this.noWasi)
			.noGc(this.noGc)
			.hostFetch(this.hostFetch)
			.hostSockets(this.hostSockets)
			.hostTimer(this.hostTimer)
			.wasiLogging(this.wasiLogging)
			.hostBoundary(this.hostBoundary)
			.reentrant(this.reentrant)
			.noPrune(this.noPrune)
			// Every runner answers the rlhttp imports of a fetch; only the macOS /
			// Apple-silicon one the rlobjc imports, so only that target accepts an
			// objc: program (.kb/objc.md, "--native").
			.nativePlatform(this.nativeTarget != null ? this.nativeTarget.platform() : null);
	}

	/**
	 * Refuses a flag these flags or the output cannot honor, by name: never a silent
	 * no-op. Called before the front end runs.
	 * @param wasmFile whether the output is a {@code .wasm} file (the CLI's {@code -o}
	 * also names a class, a jar, a war or a native executable)
	 */
	void refuseConflicts(boolean wasmFile) {
		// --native is the wasm-GC backend's WASI Preview 1 command module, precompiled
		// and appended to a runner stub: every flag that asks for a DIFFERENT module is
		// refused by name rather than half-honoured.
		if (this.nativeTarget != null) {
			record Flag(boolean given, String name, String why) {
			}
			for (Flag flag : List.of(
					new Flag(this.component, "--component",
							"the runner runs a WASI Preview 1 command module, not a component"),
					new Flag(this.noWasi, "--no-wasi", "the runner is the WASI host; a reactor has no _start to run"),
					new Flag(this.noGc, "--no-gc",
							"the runner's engine is the wasm-GC one; compile --no-gc to a .wasm"),
					new Flag(this.hostRandom, "--host-random", "the runner answers random_get from the OS itself"),
					new Flag(this.hostOutput, "--host-output", "the runner writes standard output and error itself"),
					new Flag(this.hostFetch, "--host-fetch",
							"the runner answers rontolisp:fetch itself (no env.fetch import); drop the flag"),
					new Flag(this.hostSockets, "--host-sockets",
							"the runner answers the program's sockets itself; drop the flag"),
					new Flag(this.hostTimer, "--host-timer",
							"the runner waits for a time itself, and serves requests on threads of its own"),
					new Flag(this.wasiLogging, "--wasi-logging",
							"the runner provides no WIT interface; a program logs to the standard error"),
					new Flag(this.hostBoundary != null, "--host-boundary", "the runner supplies no env.* body imports"),
					new Flag(this.reentrant, "--reentrant",
							"overlapped calls need a JSPI host, and the runner is not one"),
					new Flag(this.emitJsGlue, "--emit-js-glue", "there is no JavaScript host to write glue for"),
					new Flag(this.emitWorkerEntry, "--emit-worker-entry",
							"there is no Worker to write an entry for"))) {
				if (flag.given()) {
					throw new UnsupportedOperationException(
							"--native cannot be combined with " + flag.name() + ": " + flag.why());
				}
			}
		}
		boolean wasmOutput = this.nativeTarget != null || wasmFile;
		// --emit-wit describes a component's typed world, so it is meaningless for any
		// other output; fail fast instead of silently ignoring the request.
		if (this.emitWit && !(this.component && wasmFile)) {
			throw new UnsupportedOperationException(
					"--emit-wit requires --component and a .wasm output (e.g. -o out.wasm --component --emit-wit)");
		}
		// --emit-js-glue writes the host half of a boundary only a --no-wasi wasm-GC core
		// module has: a component is instantiated through its own bindings (jco), and
		// --no-gc's boundary is flat scalars and (ptr,len) pairs over its own exported
		// memory -- an import object a host writes in a few lines, with no staging,
		// no arena bracket and no suspending entry to derive.
		// --emit-worker-entry writes an entry over that glue, so it is refused where the
		// glue is.
		if ((this.emitJsGlue || this.emitWorkerEntry) && !(coreReactor() && wasmFile)) {
			String flag = this.emitJsGlue ? "--emit-js-glue" : "--emit-worker-entry";
			throw new UnsupportedOperationException(flag + " requires --no-wasi and a .wasm output, without"
					+ " --component (a component's host glue is its bindings generator's) or --no-gc (whose host"
					+ " functions take flat scalars and (ptr,len) pairs over its exported memory: write the import"
					+ " object directly) -- e.g. -o out.wasm --no-wasi " + flag);
		}
		// --host-random routes the wasm-GC backend's random_get slot at a host import,
		// so it means nothing anywhere else. The backend-selection half is checked here
		// (the other two backends never see the flag); the --no-wasi / --component half
		// is the compiler's own guard, next to the contract it protects.
		if (this.hostRandom && !wasmFile) {
			throw new UnsupportedOperationException(
					"--host-random requires a .wasm output: it routes the WASM random_get slot at a host import "
							+ "(the interpreter and the JVM backend draw from the JVM's own generator)");
		}
		if (this.hostRandom && this.noGc) {
			throw new UnsupportedOperationException("--host-random cannot be combined with --no-gc: the scalar "
					+ "(non-GC) backend has no `random` at all, so there is no draw to route");
		}
		// --host-output is the same family: the fd_write slot.
		if (this.hostOutput && !wasmFile) {
			throw new UnsupportedOperationException(
					"--host-output requires a .wasm output: it routes the WASM fd_write slot at a host import "
							+ "(the interpreter and the JVM backend write to the JVM's own standard streams)");
		}
		if (this.hostOutput && this.noGc) {
			throw new UnsupportedOperationException("--host-output cannot be combined with --no-gc: the scalar "
					+ "(non-GC) backend's printing module imports wasi_snapshot_preview1.fd_write itself; "
					+ "drop --no-wasi to keep that import");
		}
		// --host-fetch is the same family: it routes rontolisp:fetch at a host import on
		// the wasm-GC reactor, so it means nothing anywhere else.
		if (this.hostFetch && !wasmFile) {
			throw new UnsupportedOperationException(
					"--host-fetch requires a .wasm output: it routes rontolisp:fetch at a host import "
							+ "(the interpreter and the JVM backend fetch through the JDK HttpClient)");
		}
		if (this.hostFetch && this.noGc) {
			throw new UnsupportedOperationException("--host-fetch cannot be combined with --no-gc: the scalar "
					+ "(non-GC) backend has no fetch (or strings) at all, so there is nothing to route");
		}
		// --host-sockets is the same family: rontolisp:tcp-* at the host's sockets, which
		// only a --no-wasi wasm-GC core module imports. A component's sockets are
		// wasi:sockets and a WASI command module's host answers none.
		if (this.hostSockets && !(coreReactor() && wasmFile)) {
			throw new UnsupportedOperationException("--host-sockets requires --no-wasi and a .wasm output, without"
					+ " --component (whose sockets are wasi:sockets) or --no-gc (the scalar backend has no"
					+ " streams): it routes rontolisp:tcp-* at the host's sockets -- e.g. -o out.wasm --no-wasi"
					+ " --host-sockets");
		}
		// --host-timer has the HOST wait for the module, through imports only the
		// --no-wasi
		// wasm-GC core module takes and a JSPI host answers: a component waits in its own
		// host's timers already, --no-gc has no threads and no sleep to wait in, and a
		// WASI command module's host is wasmtime, which satisfies no env.* import. Nor
		// with
		// --reentrant, whose calls overlap anywhere: under the flag the host lets a call
		// in
		// only while another idles, where the program's run time holds nothing of a
		// call's.
		if (this.hostTimer && !(coreReactor() && wasmFile)) {
			throw new UnsupportedOperationException("--host-timer requires --no-wasi and a .wasm output, without"
					+ " --component (whose host's timers a component waits in already) or --no-gc (which has no sleep"
					+ " to wait in) -- e.g. -o out.wasm --no-wasi --host-timer");
		}
		if (this.hostTimer && this.reentrant) {
			throw new UnsupportedOperationException("--host-timer cannot be combined with --reentrant: under"
					+ " --host-timer the host lets a call in while another IDLES, where the program's run time holds"
					+ " nothing of a call's; --reentrant overlaps calls wherever one is parked");
		}
		// --wasi-logging imports wasi:logging/logging: a component's import, or a
		// --no-wasi
		// module's through the canonical ABI, which its glue answers on the console. A
		// WASI command module's host answers no WIT interface, and the interpreter and
		// the JVM log to the standard error. Refused rather than ignored, as the host
		// flags
		// above.
		if (this.wasiLogging && !((this.component || this.noWasi) && wasmFile)) {
			throw new UnsupportedOperationException("--wasi-logging requires --component or --no-wasi and a .wasm"
					+ " output: it imports wasi:logging/logging, which a component's host or the --no-wasi glue"
					+ " answers -- e.g. -o out.wasm --component --wasi-logging");
		}
		if (this.wasiLogging && this.noGc) {
			throw new UnsupportedOperationException("--wasi-logging cannot be combined with --no-gc: the scalar"
					+ " (non-GC) backend's component imports carry no enum, and no program of it logs");
		}
		// --host-boundary chooses between two shapes only the --no-wasi wasm-GC core
		// module HAS. Everywhere else the envelope is not a choice: a reactor component's
		// host functions cross the canonical ABI (no :bytes import to take a body out
		// through), --no-gc has no packed array to carry one in (its host imports carry
		// flat scalars and strings), and a WASI command module's host is `wasmtime run`,
		// which satisfies no env.* import. Refused rather than ignored -- a build script
		// that names a boundary it is not getting is exactly the silence this flag exists
		// to end.
		if (this.hostBoundary != null && !(coreReactor() && wasmFile)) {
			throw new UnsupportedOperationException("--host-boundary requires --no-wasi and a .wasm output, without"
					+ " --component (whose host functions cross the canonical ABI, so its bodies are in band"
					+ " already) or --no-gc (which has no packed array to carry a body in)"
					+ " -- e.g. -o out.wasm --no-wasi --host-boundary=" + HostBoundary.ENVELOPE.spelling());
		}
		// --report-locations buys the wasm-GC entry's uncaught report its location lines;
		// the interpreter always prints them and --no-gc has no report to put them under.
		if (this.reportLocations != null && (!wasmOutput || this.noGc)) {
			throw new UnsupportedOperationException("--report-locations reaches a wasm-GC output only (-o out.wasm,"
					+ " --component or --native, without --no-gc): it chooses what the module spends on the location"
					+ " lines under its uncaught report. The interpreter always prints them");
		}
		// --reentrant relaxes the wasm-GC reactor's one-call-at-a-time contract, so it
		// means nothing anywhere else; the finer guards (needs a suspending import,
		// no streaming boundary, no --dynamic) live in the compiler, next to the
		// contracts they protect.
		if (this.reentrant && !(coreReactor() && wasmFile)) {
			throw new UnsupportedOperationException("--reentrant requires --no-wasi and a .wasm output, without"
					+ " --component (whose concurrency is the component model's) or --no-gc (which has no suspending"
					+ " imports to overlap on) -- e.g. -o out.wasm --no-wasi --host-fetch --reentrant");
		}
		// --component, --no-wasi and --no-gc choose a .wasm module's shape; a JVM output
		// has none (--native is refused above). Last, so a dependent flag names itself
		// first.
		if (this.nativeTarget == null && !wasmFile) {
			record Shape(boolean given, String name, String why) {
			}
			for (Shape shape : List.of(
					new Shape(this.component, "--component",
							"it builds a WASI component, which the interpreter and the JVM backend do not have"),
					new Shape(this.noWasi, "--no-wasi",
							"it builds a reactor module, which the interpreter and the JVM backend do not have"
									+ " (a JVM library is --no-main)"),
					new Shape(this.noGc, "--no-gc",
							"it picks the scalar WASM backend, which the interpreter and the JVM backend do not have"))) {
				if (shape.given()) {
					throw new UnsupportedOperationException(shape.name() + " requires a .wasm output: " + shape.why());
				}
			}
		}
	}

	private boolean coreReactor() {
		return this.noWasi && !this.component && !this.noGc;
	}

	/**
	 * The backend half alone, for a caller that has already run the front end.
	 * @param frontend what the front end answered under {@link #frontendOptions}
	 * @return the compiled module
	 */
	Result compileProgram(CompileFrontend.Result frontend) {
		if (this.noGc) {
			// --no-gc selects the separate scalar (non-GC) lowering: a plain MVP module
			// with no wasm-GC types, no imports and no memory, for pure-numeric
			// rontolisp:wasm-export functions. A PRINT-FREE program is a pure-compute
			// reactor already; a printing one imports the single fd_write, which
			// --no-wasi replaces with the discarding sink (the GC contract), so the
			// module keeps zero imports. With --component the same core module is
			// wrapped as a compact reactor-style component (typed scalar exports, no
			// adapter, no wasm-GC requirement) instead of the GC component pipeline.
			// --simd is the orthogonal acceleration switch: with it the vec: kernels
			// lower to native v128 (f64x2/f32x4); without it to plain scalar loops that
			// run on a runtime lacking the SIMD proposal.
			NoGcWasmCompiler compiler = NoGcWasmCompiler.builder()
				.optimize(this.optimize)
				.simd(this.simd)
				.component(this.component)
				.noWasi(this.noWasi)
				// a lowered language's program and its run time are written for Common
				// Lisp, not for the scalar dialect
				.exact(frontend.lowered())
				.build();
			byte[] bytes = compiler.compile(frontend.program());
			return new Result(bytes, this.nativeTarget,
					this.emitWit ? Objects.requireNonNull(compiler.componentWit()) : null, null, null, this.notices);
		}
		// rontolisp:http-handler compiles to a wasi:http/incoming-handler component
		// (--component only): the front end spliced http.lisp's %serve-handle
		// wasm-export and removed the directive. A serve-mode component's only export is
		// wasi:http/incoming-handler (the component builder lifts no user exports
		// there), so a world of function exports could not be honored -- say so instead
		// of dropping it.
		if (frontend.serve() && frontend.witWorld()) {
			throw new UnsupportedOperationException(
					"rontolisp:wit-export cannot be combined with rontolisp:http-handler: a serve-mode "
							+ "component exports only wasi:http/incoming-handler");
		}
		// A reactor's entry points are its exports. A WASI command module (Preview 1,
		// and so --native) has one entry, _start, which a host runs once and which ends
		// the instance; the component lifts no run instead, and the --no-wasi module
		// names its entry _initialize.
		if (frontend.reactor() && !this.component && !this.noWasi) {
			throw new UnsupportedOperationException("rontolisp:wit-export :reactor t makes the program a reactor, "
					+ "whose entry points are its world's exports: compile it with --component"
					+ (this.nativeTarget != null ? ", not --native, whose module is a command"
							: " (a component) or --no-wasi (a core module), not to a WASI command module"));
		}
		// --simd routes the vectorizable vec: kernels to emitted v128 helpers and
		// switches a packed float array's storage to an (array (mut v128)) of lane
		// groups -- still a GC object the engine collects. --host-random: the one opt-in
		// out of "instantiate with nothing": every `random` draw is the host's entropy.
		// runtimeFeatures: the compiled program's *features* starts out holding exactly
		// what the front end READ it with, so a run-time (member :F *features*) and the
		// #+F beside it cannot disagree.
		WasmLispCompiler compiler = WasmLispCompiler.builder()
			.dynamic(this.dynamic)
			.component(this.component)
			.noWasi(this.noWasi)
			.optimize(this.optimize)
			.serve(frontend.serve())
			.reactor(frontend.reactor())
			.simd(this.simd)
			.hostRandom(this.hostRandom)
			.hostOutput(this.hostOutput)
			.hostFetch(this.hostFetch)
			// --native: fetch is the runner's (HostFetchLibrary's runner shape).
			.runnerFetch(this.nativeTarget != null)
			.reentrant(this.reentrant)
			.reportLocations(this.reportLocations)
			.runtimeFeatures(frontend.features().names())
			.build();
		byte[] bytes = compiler.compile(frontend.program());
		// The glue names the module by the file it is written to, which only the write
		// knows.
		return new Result(bytes, this.nativeTarget,
				this.emitWit ? Objects.requireNonNull(compiler.componentWit()) : null,
				this.emitJsGlue || this.emitWorkerEntry ? compiler::hostGlueJs : null,
				this.emitWorkerEntry ? compiler::workerEntryJs : null, this.notices);
	}

	/**
	 * A compiled module, and what is written beside it.
	 */
	public static final class Result {

		private final byte[] module;

		private final @Nullable NativeTarget nativeTarget;

		private final @Nullable String wit;

		private final @Nullable Function<String, @Nullable String> glue;

		private final @Nullable Function<String, @Nullable String> workerEntry;

		private final Consumer<String> notices;

		Result(byte[] module, @Nullable NativeTarget nativeTarget, @Nullable String wit,
				@Nullable Function<String, @Nullable String> glue,
				@Nullable Function<String, @Nullable String> workerEntry, Consumer<String> notices) {
			this.module = module;
			this.nativeTarget = nativeTarget;
			this.wit = wit;
			this.glue = glue;
			this.workerEntry = workerEntry;
			this.notices = notices;
		}

		/**
		 * @return the WebAssembly module or component (for {@code --native}, the module
		 * the executable precompiles)
		 */
		public byte[] module() {
			return this.module.clone();
		}

		/**
		 * Writes the output: the module, or under {@code --native} the executable, plus
		 * the {@code .wit} ({@code --emit-wit}), the {@code .js} glue
		 * ({@code --emit-js-glue}) and the Worker's {@code index.js}
		 * ({@code --emit-worker-entry}, only where none is there) beside a {@code .wasm}.
		 * @param output the {@code -o} path
		 * @throws IOException when a file cannot be written
		 */
		public void write(Path output) throws IOException {
			// The entry is settled before anything is written: a module whose glue has no
			// complete worker() is refused with nothing on disk.
			String stem = output.toString().endsWith(".wasm")
					? output.toString().substring(0, output.toString().length() - ".wasm".length()) : output.toString();
			Path glueFile = Path.of(stem + ".js");
			Path entryFile = output.resolveSibling(HostGlueEmitter.WORKER_ENTRY);
			String entry = null;
			if (this.workerEntry != null) {
				if (glueFile.getFileName().toString().equals(HostGlueEmitter.WORKER_ENTRY)) {
					throw new UnsupportedOperationException(
							"--emit-worker-entry writes the Worker's " + HostGlueEmitter.WORKER_ENTRY + ", which -o "
									+ output + " names as its glue: compile to another -o name (e.g. worker.wasm)");
				}
				entry = Objects.requireNonNull(this.workerEntry.apply(glueFile.getFileName().toString()));
			}
			Path parent = output.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			if (this.nativeTarget != null) {
				// Only the executable is written: the .wasm and its precompiled form
				// never
				// leave memory. The runner is the one the module's imports need: a
				// fetch's or a server's rlhttp and a socket's rltcp only the network
				// runner answers.
				NativeToolchain toolchain = NativeToolchain.load();
				Set<String> imports = WasmLispCompiler.importModules(this.module);
				boolean network = imports.contains(FetchResponseShape.RUNNER_IMPORT_MODULE)
						|| imports.contains(SocketsLibrary.RUNNER_IMPORT_MODULE);
				NativeExecutable.write(output, toolchain.assemble(toolchain.stub(this.nativeTarget.platform(), network),
						toolchain.precompile(this.module, this.nativeTarget)));
				return;
			}
			Files.write(output, this.module);
			if (this.wit != null) {
				Files.writeString(Path.of(stem + ".wit"), this.wit);
			}
			if (this.glue != null) {
				// The glue's name is the module's, so in a Worker directory `-o
				// src/index.wasm` aims it straight at a hand-written src/index.js. Only
				// a file this flag wrote before is overwritten; anything else is the
				// host's own and is refused by name, because the .wasm is written by now.
				if (Files.exists(glueFile)
						&& !Files.readString(glueFile, StandardCharsets.UTF_8).startsWith(HostGlueEmitter.MARKER)) {
					throw new UnsupportedOperationException("--emit-js-glue would overwrite " + glueFile
							+ ", which it did not write (it does not start with \"" + HostGlueEmitter.MARKER
							+ "\"). Compile to a different -o name, or move that file aside");
				}
				Files.writeString(glueFile, Objects.requireNonNull(this.glue.apply(glueFile.getFileName().toString())));
			}
			if (entry != null) {
				// Written once and the host's from then on: the entry is where a Worker
				// takes things over (a remoteAddr, a host import), so a build never
				// fights an edit of it -- nor its own earlier output, which carries no
				// marker to tell the two apart.
				if (Files.exists(entryFile)) {
					this.notices.accept(entryFile + " is there already, so it is left as it is (--emit-worker-entry"
							+ " never overwrites the Worker's entry)");
				}
				else {
					Files.writeString(entryFile, entry, StandardCharsets.UTF_8);
				}
			}
		}

	}

}
