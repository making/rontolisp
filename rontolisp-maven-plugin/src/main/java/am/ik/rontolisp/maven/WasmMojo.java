package am.ik.rontolisp.maven;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.Version;
import am.ik.rontolisp.cli.WasmSourceCompiler;
import am.ik.rontolisp.codegen.wasm.WasmReportLocations;
import am.ik.rontolisp.compiler.HostBoundary;
import am.ik.rontolisp.compiler.OptimizeLevel;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the program {@code src/main/java} holds, from its main class, to a
 * {@code .wasm} module or component (or a native executable), through the front end for
 * an experimental subset of the Java language -- the compile {@code rontolisp
 * app/Main.java -o out.wasm} runs, in process.
 * <p>
 * <strong>Bound to {@code package}</strong>: {@code src/main/java} still compiles with
 * javac and its tests still run in surefire, so the same sources run on a JDK; the module
 * is one more artifact of the build, beside the jar.
 * <p>
 * The front end reads source, so a class the program names from a dependency jar is
 * refused by name, as the command line refuses it. Every parameter is a CLI flag under
 * its camel-cased name.
 */
@Mojo(name = "wasm", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class WasmMojo extends AbstractMojo {

	/** Skips the goal. */
	@Parameter(property = "rontolisp.skip", defaultValue = "false")
	private boolean skip;

	/**
	 * The binary name of the class the program starts at ({@code app.Main} is the file
	 * {@code app/Main.java} under {@link #sourceDirectory}).
	 */
	@Parameter(property = "rontolisp.mainClass", required = true)
	private @Nullable String mainClass;

	/** The root of the source tree the program is read from. */
	@Parameter(defaultValue = "${project.build.sourceDirectory}", required = true)
	private @Nullable File sourceDirectory;

	/**
	 * The output: {@code ${project.build.directory}/${project.build.finalName}.wasm} by
	 * default, without the {@code .wasm} under {@code native}.
	 */
	@Parameter(property = "rontolisp.outputFile")
	private @Nullable File outputFile;

	@Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
	private @Nullable File buildDirectory;

	@Parameter(defaultValue = "${project.build.finalName}", readonly = true, required = true)
	private @Nullable String finalName;

	/** {@code --component}: a WASI 0.3 component. */
	@Parameter(property = "rontolisp.component", defaultValue = "false")
	private boolean component;

	/** {@code --no-wasi}: a reactor module that imports no WASI. */
	@Parameter(property = "rontolisp.noWasi", defaultValue = "false")
	private boolean noWasi;

	/** {@code --optimize}: {@code default}, {@code size} or {@code off}. */
	@Parameter(property = "rontolisp.optimize", defaultValue = "default")
	private String optimize = "default";

	/** {@code --simd}: native v128 kernels. */
	@Parameter(property = "rontolisp.simd", defaultValue = "false")
	private boolean simd;

	/** {@code --host-random}: {@code random_get} from a host import. */
	@Parameter(property = "rontolisp.hostRandom", defaultValue = "false")
	private boolean hostRandom;

	/** {@code --host-fetch}: fetches through a host import. */
	@Parameter(property = "rontolisp.hostFetch", defaultValue = "false")
	private boolean hostFetch;

	/** {@code --reentrant}: overlapped JSPI calls. */
	@Parameter(property = "rontolisp.reentrant", defaultValue = "false")
	private boolean reentrant;

	/** {@code --host-boundary}: the {@code --no-wasi} module's body boundary. */
	@Parameter(property = "rontolisp.hostBoundary")
	private @Nullable String hostBoundary;

	/** {@code --report-locations}: the location lines of the uncaught report. */
	@Parameter(property = "rontolisp.reportLocations")
	private @Nullable String reportLocations;

	/** {@code --emit-wit}: the component's world beside the output. */
	@Parameter(property = "rontolisp.emitWit", defaultValue = "false")
	private boolean emitWit;

	/** {@code --emit-js-glue}: the host glue beside the output. */
	@Parameter(property = "rontolisp.emitJsGlue", defaultValue = "false")
	private boolean emitJsGlue;

	/**
	 * {@code --native}: a native executable, the module precompiled into a runner.
	 * {@code <native>}: the field cannot carry a keyword's name.
	 */
	@Parameter(alias = "native", property = "rontolisp.native", defaultValue = "false")
	private boolean nativeExecutable;

	/** {@code --native-target}: the executable's platform, the host's by default. */
	@Parameter(property = "rontolisp.nativeTarget")
	private @Nullable String nativeTarget;

	/** {@code --native-cpu}: the executable's CPU level, {@code baseline} by default. */
	@Parameter(property = "rontolisp.nativeCpu")
	private @Nullable String nativeCpu;

	/** {@code --dynamic}: late binding. */
	@Parameter(property = "rontolisp.dynamic", defaultValue = "false")
	private boolean dynamic;

	/** {@code --no-prune}: keep every spliced library definition. */
	@Parameter(property = "rontolisp.noPrune", defaultValue = "false")
	private boolean noPrune;

	/** {@code --warnings-as-errors}: fail the build on a warning about the program. */
	@Parameter(property = "rontolisp.warningsAsErrors", defaultValue = "false")
	private boolean warningsAsErrors;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (this.skip) {
			getLog().info("Skipping rontolisp compilation (rontolisp.skip)");
			return;
		}
		if (!this.nativeExecutable && (this.nativeTarget != null || this.nativeCpu != null)) {
			throw new MojoFailureException(
					"nativeTarget and nativeCpu describe a native executable, so they need <native>true</native>");
		}
		File output = output();
		WasmProgram program = new WasmProgram(required(this.sourceDirectory, "sourceDirectory").toPath(),
				required(this.mainClass, "mainClass"), output.toPath(),
				required(this.buildDirectory, "buildDirectory").toPath()
					.resolve("rontolisp")
					.resolve(output.getName() + ".status"),
				configuration(), this::configure);
		boolean compiled;
		try {
			compiled = program.compile();
		}
		catch (IOException ex) {
			throw new MojoExecutionException("rontolisp compilation failed: " + ex.getMessage(), ex);
		}
		catch (RuntimeException ex) {
			// The rontolisp diagnostic verbatim: it carries file:line:column, so an IDE
			// can jump to it.
			throw new MojoFailureException(
					ex.getMessage() == null ? ex.getClass().getName() : Objects.requireNonNull(ex.getMessage()), ex);
		}
		getLog().info(compiled ? "Compiled " + this.mainClass + " to " + output
				: "Nothing to compile - " + output + " is up to date");
	}

	private File output() {
		if (this.outputFile != null) {
			return this.outputFile;
		}
		return new File(required(this.buildDirectory, "buildDirectory"),
				required(this.finalName, "finalName") + (this.nativeExecutable ? "" : ".wasm"));
	}

	private void configure(WasmSourceCompiler compiler) {
		compiler.component(this.component)
			.noWasi(this.noWasi)
			.optimize(OptimizeLevel.parse(this.optimize))
			.simd(this.simd)
			.hostRandom(this.hostRandom)
			.hostFetch(this.hostFetch)
			.reentrant(this.reentrant)
			.hostBoundary(this.hostBoundary == null ? null : HostBoundary.parse(this.hostBoundary))
			.reportLocations(this.reportLocations == null ? null : WasmReportLocations.parse(this.reportLocations))
			.emitWit(this.emitWit)
			.emitJsGlue(this.emitJsGlue)
			.dynamic(this.dynamic)
			.noPrune(this.noPrune)
			.warningsAsErrors(this.warningsAsErrors);
		if (this.nativeExecutable) {
			compiler.nativeExecutable(this.nativeTarget, this.nativeCpu);
		}
	}

	/**
	 * What the output depends on besides the sources, one line, for the staleness check:
	 * the compiler that built it too, so a new SNAPSHOT of it rebuilds.
	 */
	private String configuration() {
		return String.join(" ", List.of("rontolisp=" + Version.getVersion() + "@" + Version.getGitCommit(),
				"mainClass=" + this.mainClass, "component=" + this.component, "noWasi=" + this.noWasi,
				"optimize=" + this.optimize, "simd=" + this.simd, "hostRandom=" + this.hostRandom,
				"hostFetch=" + this.hostFetch, "reentrant=" + this.reentrant, "hostBoundary=" + this.hostBoundary,
				"reportLocations=" + this.reportLocations, "emitWit=" + this.emitWit, "emitJsGlue=" + this.emitJsGlue,
				"native=" + this.nativeExecutable, "nativeTarget=" + this.nativeTarget, "nativeCpu=" + this.nativeCpu,
				"dynamic=" + this.dynamic, "noPrune=" + this.noPrune, "warningsAsErrors=" + this.warningsAsErrors));
	}

	private static <T> T required(@Nullable T value, String name) {
		return Objects.requireNonNull(value, name + " is required");
	}

}
