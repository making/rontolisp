package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedSet;
import java.util.Set;
import java.util.TreeMap;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.compiler.DeadTypeBranchPruner;
import am.ik.rontolisp.compiler.ToplevelStatements;
import am.ik.rontolisp.compiler.CompileWarnings;
import am.ik.rontolisp.compiler.RuntimeNameProducers;
import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.macro.LispAsync;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.SignalMessages;
import am.ik.rontolisp.macro.SpecialVarCollector;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.BuiltinCallArity;
import am.ik.rontolisp.compiler.BuiltinFunctionWrappers;
import am.ik.rontolisp.compiler.CompileTimeBoundp;
import am.ik.rontolisp.compiler.ConcatenateForms;
import am.ik.rontolisp.compiler.MutableStringProducers;
import am.ik.rontolisp.compiler.AstOutliner;
import am.ik.rontolisp.compiler.CrossLambdaExitLowering;
import am.ik.rontolisp.compiler.DesignatorSpellings;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.rontolisp.compiler.GlobalVarCollector;
import am.ik.rontolisp.compiler.LispCompiler;
import am.ik.rontolisp.compiler.NestedDefunRedefinition;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.compiler.ShadowedBuiltins;
import am.ik.rontolisp.compiler.SequenceIoNarrowing;
import am.ik.rontolisp.compiler.StreamDesignators;
import am.ik.rontolisp.compiler.JvmExportDirective;
import am.ik.rontolisp.compiler.WasmImportDirective;

import am.ik.jvm.AccessFlag;
import am.ik.jvm.ClassDefinition;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.JvmClassSplitter;
import am.ik.jvm.MethodCode;
import am.ik.jvm.OperandStack;
import org.jspecify.annotations.Nullable;

/**
 * Compiles Lisp expressions to JVM .class bytecode, stamped class file version 61 (Java
 * 17), the mandatory StackMapTable computed as {@link JvmClassSplitter} writes the class.
 * Supports first-class functions, closures, and capture-by-reference semantics.
 */
public final class JvmLispCompiler implements LispCompiler {

	/**
	 * The class-file major version the finished class is stamped with (61 = Java 17).
	 * Emission itself stays version-agnostic; the writer ({@link JvmClassSplitter})
	 * computes the StackMapTable that every version above 50 requires and stamps this
	 * version as the final step of {@link #compile}.
	 */
	private static final int CLASS_MAJOR_VERSION = 61;

	private final String className;

	private final boolean dynamic;

	private final OptimizeLevel optimize;

	private final boolean simdAccel;

	/**
	 * Whether the {@code geom:} kernel bridge may be emitted
	 * ({@link Builder#geomKernels}). True in every build; the seam exists so the test
	 * that proves the bridge answers what the defuns answer has an oracle to compile
	 * against.
	 */
	private final boolean geomKernels;

	private final boolean blasAccel;

	private final boolean gpuAccel;

	private final boolean parallelAccel;

	/**
	 * The names the compiled program's {@code *features*} starts out holding. The JVM
	 * backend's own set unless the frontend {@link Builder#runtimeFeatures(List) says
	 * otherwise} -- reading and running must agree on it, and only the frontend knows
	 * what it read with.
	 */
	private final List<String> runtimeFeatures;

	/**
	 * Library mode ({@code --no-main}): no {@code main} method; the class is entered
	 * through its {@code rontolisp:jvm-export} wrappers only. See {@link Builder#noMain}.
	 */
	private final boolean noMain;

	/**
	 * Servlet mode ({@code -o app.war}): the program serves through a servlet container
	 * that owns the port, so the {@code rontolisp:http-handler} directive registers its
	 * handler and RETURNS (no bind, no block), the top level moves into {@code <clinit>}
	 * (the container's initializer runs it via {@code Class.forName}), and the two
	 * servlet adapter classes join {@link #runtimeClassFiles()}. See
	 * {@link Builder#servlet}.
	 */
	private final boolean servletMode;

	/**
	 * Whether the last {@link #compile} declared a packed float-array boundary type, i.e.
	 * whether the emitted class needs the handle half of {@link #runtimeClassFiles()}
	 * beside it.
	 */
	private boolean needsHandleRuntime;

	/**
	 * Whether the last {@link #compile} serves HTTP, i.e. whether the emitted class needs
	 * the served-request half of {@link #runtimeClassFiles()} beside it.
	 */
	private boolean needsHttpRuntime;

	/**
	 * Whether the last {@link #compile} fetches, i.e. whether the emitted class needs the
	 * fetch transport ({@code RontoFetch}) beside it.
	 */
	private boolean needsFetchRuntime;

	/**
	 * Whether the last {@link #compile} uses hash tables, i.e. whether the emitted class
	 * needs {@code RontoHashTable} beside it: the key fold for an {@code equalp} table,
	 * and the tombstone machinery (tombstone/liveCount/liveValues/maybeCompact) every
	 * table's put/remove/count/values helpers call since `.todo/855`.
	 */
	private boolean needsHashTableRuntime;

	/**
	 * Whether the last {@link #compile} can observe a complex value, i.e. whether the
	 * emitted class needs the {@code _c*} helpers' travelling holder
	 * ({@code RontoComplex}) beside it.
	 */
	private boolean needsComplexRuntime;

	/**
	 * Whether the compiled program can open a BIDIRECTIONAL ({@code :direction :io}) or
	 * {@code :if-exists :overwrite} file stream, so the travelling
	 * {@code RontoIoFileStream} goes beside the output.
	 */
	private boolean needsIoStreamRuntime;

	/**
	 * Whether the program carries {@code runtime.RontoStringInputStream}: it can make a
	 * string input stream, which is always built positioned (so {@code listen} answers
	 * uniformly).
	 */
	private boolean needsStringInputRuntime;

	/**
	 * Whether the positioned character file streams ({@code RontoCharFileReader} /
	 * {@code RontoCharFileWriter}) go beside the output: a program that names
	 * {@code file-position} and can open a character file stream.
	 */
	private boolean needsCharFileRuntime;

	/**
	 * The {@code $PartN} classes the last {@link #compile} split the program into, keyed
	 * by their {@code path/Name$PartN.class}; empty for a program whose pool fits one
	 * class file, which is every program but the largest.
	 */
	private Map<String, byte[]> partClassFiles = Map.of();

	/**
	 * The bridge classes a feature ships beside the program rather than defining at run
	 * time -- the {@code java:}, {@code geom:}, {@code --simd}, {@code --blas} and
	 * {@code --gpu} bridges and the {@code objc:} / {@code ffi:} libraries
	 * ({@code .kb/template-class-embedding.md}) -- keyed like {@link #partClassFiles}.
	 * Reset by every compile attempt.
	 */
	private Map<String, byte[]> bridgeClassFiles = new LinkedHashMap<>();

	/**
	 * The most constant-pool entries one emitted class may carry before the program is
	 * split ({@link Builder#classPoolLimit}): the class-format limit, except in a test
	 * that forces the split onto a small program.
	 */
	private final int classPoolLimit;

	/**
	 * The Java release {@code java:} sites resolve against ({@code --java-release}), or
	 * {@code null} for the newest the JDK holds.
	 */
	private final @Nullable Integer javaRelease;

	/** The class path {@code java:} sites resolve against after the JDK. */
	private final List<java.nio.file.Path> javaClasspath;

	/** Whether a {@code java:} site left to run time is reported. */
	private final boolean warnJavaReflection;

	/**
	 * Whether a {@code java:} site that needs the reflective bridge is a compile error
	 * ({@code --java-static}).
	 */
	private final boolean javaStatic;

	/**
	 * The class-file major version this attempt stamps: {@link #CLASS_MAJOR_VERSION}, or
	 * the version of the Java release a {@code java:} program's sites resolved against
	 * when that is newer.
	 */
	private int classMajorVersion = CLASS_MAJOR_VERSION;

	/**
	 * The program-side methods this attempt's generated {@code java:} interface
	 * implementations call ({@link JvmJavaImplementations#callbackNames()}): found from
	 * another class, so kept in the program class when it is split and rooted for the
	 * tree-shaker.
	 */
	private Set<String> implementationCallbacks = Set.of();

	/**
	 * The class files {@code java:} sites resolve against, opened by the first attempt
	 * that compiles one and closed when {@link #compile(List)} returns.
	 */
	private @Nullable JvmClassFileLookup javaClasses;

	/**
	 * The methods something outside the class's own bytecode finds by NAME, which
	 * therefore stay in the class when it is split: {@code _apply} and {@code _strv},
	 * which the shipped java:/objc:/ffi: bridges look up with {@code getDeclaredMethod},
	 * {@code _lispToString}, which the java: bridge shows a value in a message with,
	 * {@code _bf16Value}, which it reads a bfloat16 vector's elements through,
	 * {@code _jsig}/{@code _jfail}, which its {@code Proxy} records what a callback
	 * raised with and its calls pass it on through, and
	 * {@code _gpuMaterialize}/{@code _gpuWritten}, which the travelling float-array
	 * handle resolves through {@code MethodHandles} ({@code .kb/jvm-export.md}).
	 */
	private static final Set<String> REFLECTIVELY_FOUND_METHODS = Set.of("_apply", "_strv", "_lispToString",
			JvmFloatArrayRuntimeBuilder.BF16_VALUE, JvmJavaDirectSites.SIGNAL, JvmJavaDirectSites.FAIL,
			"_gpuMaterialize", "_gpuWritten");

	/** The array runtime helper group ({@link JvmArrayRuntimeBuilder}). */
	private static final String GROUP_ARRAYS = "arrays";

	/** The hash-table runtime helper group ({@link JvmHashRuntimeBuilder}). */
	private static final String GROUP_HASH = "hash-tables";

	/**
	 * The {@code equalp} key-fold helpers
	 * ({@link JvmHashRuntimeBuilder#EQUALP_METHOD_NAMES}), a group of their own so a
	 * program that folds no key carries none of them.
	 */
	private static final String GROUP_HASH_EQUALP = "hash-tables-equalp";

	/**
	 * The identity-table helpers ({@link JvmHashRuntimeBuilder#IDENTITY_METHOD_NAMES}), a
	 * group of their own so a program that builds no {@code eq}/{@code eql} table carries
	 * none of them.
	 */
	private static final String GROUP_HASH_IDENTITY = "hash-tables-identity";

	/** The embedded eval/apply runtime group ({@link JvmEvalRuntimeBuilder}). */
	private static final String GROUP_EVAL = "eval";

	/** The complex-number runtime helper group ({@link JvmComplexRuntimeBuilder}). */
	private static final String GROUP_COMPLEX = "complex";

	/**
	 * The eval runtime's own methods; {@code _lookup$N} segments hang off
	 * {@code _lookup}.
	 */
	private static final Set<String> EVAL_METHOD_NAMES = Set.of("_eval", "_store", "_envLookup", "_lookup");

	/**
	 * The apply tier's gate: {@code _apply} and the spread dispatcher, without the
	 * interpreter (see {@code usesApplyRuntime} in {@link #compile(List, Set, Map)}).
	 */
	private static final String GROUP_APPLY = "apply";

	/**
	 * The {@code java:} bridge's gate: embedded only when a site needs it
	 * ({@link JvmJavaSites#needsBridge}); a site that turns out to after all calls the
	 * absent {@code _javaInit}.
	 */
	private static final String GROUP_JAVA_BRIDGE = "java-bridge";

	/**
	 * Lisp code runs on threads other than the program's own, so a special's dynamic
	 * binding is thread-scoped ({@link JvmDynVarRuntimeBuilder}) and the multiple-value
	 * channel one register per thread ({@link JvmMvChannel}): forced on when the attempt
	 * generated a {@code java:} implementation whose callbacks the source scan did not
	 * predict -- a host calls them from a thread of its choosing.
	 */
	private static final String GROUP_OTHER_THREADS = "other-threads";

	/**
	 * Which gate emits a given runtime helper, i.e. which gate to force on when the
	 * finished class turns out to call that helper without it having been emitted. A
	 * helper absent from this table is not recoverable and makes the compile fail loudly
	 * instead.
	 */
	private static @Nullable String gateGroupFor(String helperName) {
		if (JvmArrayRuntimeBuilder.METHOD_NAMES.contains(helperName)) {
			return GROUP_ARRAYS;
		}
		if (JvmHashRuntimeBuilder.EQUALP_METHOD_NAMES.contains(helperName)) {
			return GROUP_HASH_EQUALP;
		}
		if (JvmHashRuntimeBuilder.IDENTITY_METHOD_NAMES.contains(helperName)) {
			return GROUP_HASH_IDENTITY;
		}
		if (JvmHashRuntimeBuilder.METHOD_NAMES.contains(helperName)) {
			return GROUP_HASH;
		}
		if (EVAL_METHOD_NAMES.contains(helperName)) {
			return GROUP_EVAL;
		}
		if ("_apply".equals(helperName) || JvmTailBounce.APPLY_RAW_NAME.equals(helperName)) {
			return GROUP_APPLY;
		}
		if (JvmJavaRuntimeBuilder.INIT_METHOD.equals(helperName)) {
			return GROUP_JAVA_BRIDGE;
		}
		if (JvmComplexRuntimeBuilder.METHOD_NAMES.contains(helperName)) {
			return GROUP_COMPLEX;
		}
		return null;
	}

	/**
	 * Thrown by the compile pass when the finished class calls an own-class helper the
	 * run decided not to emit, and the missing helper belongs to a gate the next run can
	 * force on. Never escapes {@link #compile(List)}.
	 */
	private static final class GateUnderpredicted extends RuntimeException {

		private final Set<String> groups;

		private GateUnderpredicted(Set<String> groups) {
			super(null, null, false, false);
			this.groups = groups;
		}

	}

	/**
	 * HotSpot refuses to JIT-compile a method over 8000 bytecodes and reports nothing
	 * ({@code .kb/hot-path-method-size.md}), so a library function we compile into
	 * something bigger runs interpreted for the life of the process. The tail-spine
	 * splitter ({@code JvmBodyOutliner}) cuts what it can; what it cannot -- a decision
	 * tree that is ONE form, which is what {@code proc-parse}'s {@code match-i-case}
	 * generates -- has to be cut at the AST level, before this attempt began. So the
	 * attempt reports the function and the size it came out at, and the next one cuts it
	 * to fit ({@link AstOutliner}). Never escapes {@link #compile(List)}.
	 */
	private static final class MethodTooLarge extends RuntimeException {

		/** The functions to cut next time, each with the budget to cut it under. */
		private final Map<String, AstOutliner.Budget> budgets;

		private MethodTooLarge(Map<String, AstOutliner.Budget> budgets) {
			super(null, null, false, false);
			this.budgets = budgets;
		}

	}

	/** HotSpot's {@code HugeMethodLimit}: a method over this is never JIT-compiled. */
	private static final int HUGE_METHOD_LIMIT = 8000;

	/**
	 * What an outlined piece should come in at -- the same margin under the cliff the
	 * dispatch segments and the body splitter keep.
	 */
	private static final int OUTLINE_TARGET_BYTES = 6000;

	/**
	 * Below this, another whole compile is not worth it: the function is one the pass
	 * cannot cut small enough, and it stays over the limit rather than being cut into
	 * pieces so small the closures cost more than the cliff.
	 */
	private static final int OUTLINE_TARGET_FLOOR_BYTES = 2000;

	/**
	 * Create a new JVM compiler targeting the given class name, every option at its
	 * default; {@link #builder()} sets the others.
	 * @param className the fully qualified class name for the generated class
	 */
	public JvmLispCompiler(String className) {
		this(builder().className(className));
	}

	private JvmLispCompiler(Builder builder) {
		if (builder.parallel && !builder.simd) {
			throw new IllegalArgumentException(
					"--parallel splits the --simd kernels across threads, so it needs --simd");
		}
		this.className = Objects.requireNonNull(builder.className, "className is required");
		this.dynamic = builder.dynamic;
		this.optimize = builder.optimize;
		this.simdAccel = builder.simd;
		this.blasAccel = builder.blas;
		this.gpuAccel = builder.gpu;
		this.parallelAccel = builder.parallel;
		this.geomKernels = builder.geomKernels;
		this.runtimeFeatures = builder.runtimeFeatures;
		this.noMain = builder.noMain;
		this.servletMode = builder.servlet;
		// The system property reaches here unchecked: past the format limit it would send
		// a
		// pool no class can carry down the single-class path.
		this.classPoolLimit = Math.clamp(builder.classPoolLimit, 1, ConstantPool.MAX_INDEX);
		this.javaRelease = builder.javaRelease;
		this.javaClasspath = builder.javaClasspath;
		this.warnJavaReflection = builder.warnJavaReflection;
		this.javaStatic = builder.javaStatic;
	}

	/**
	 * Creates a builder for a JVM compiler. {@link Builder#className} is required; every
	 * other option defaults to what the CLI selects when its flag is absent.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link JvmLispCompiler}.
	 */
	public static final class Builder {

		private @Nullable String className;

		private boolean dynamic;

		private OptimizeLevel optimize = OptimizeLevel.DEFAULT;

		private boolean simd;

		private boolean blas;

		private boolean gpu;

		private boolean parallel;

		private boolean geomKernels = true;

		private List<String> runtimeFeatures = LispMacroExpander.backendFeatures(false);

		private boolean noMain;

		private boolean servlet;

		private int classPoolLimit = Integer.getInteger("rontolisp.jvm.class-pool-limit", ConstantPool.MAX_INDEX);

		private @Nullable Integer javaRelease;

		private List<java.nio.file.Path> javaClasspath = List.of();

		private boolean warnJavaReflection;

		private boolean javaStatic;

		private Builder() {
		}

		/**
		 * Sets the class the compiler generates. Required.
		 * @param className the fully qualified class name for the generated class
		 * @return this builder
		 */
		public Builder className(String className) {
			this.className = className;
			return this;
		}

		/**
		 * Selects late binding ({@code --dynamic}). When {@code true}, unresolved
		 * function calls and variable references are not rejected at compile time but
		 * resolved at runtime against the embedded {@code eval} global environment, so a
		 * program that defines functions via {@code load} can compile without changes.
		 * This forces the {@code eval} runtime to be emitted.
		 * @param dynamic whether to resolve unresolved references at run time
		 * @return this builder
		 */
		public Builder dynamic(boolean dynamic) {
			this.dynamic = dynamic;
			return this;
		}

		/**
		 * Sets what to optimize the class FOR (the CLI's {@code --optimize}). Every level
		 * but {@link OptimizeLevel#NONE} dead-code-eliminates the class as it is written
		 * ({@link JvmClassSplitter}): methods unreachable from {@code main} (and any
		 * static field only they reference) are not written. {@link OptimizeLevel#SIZE}
		 * is accepted and equals {@link OptimizeLevel#DEFAULT} here: this backend has
		 * nothing that spends bytes on speed -- the emissions the level declines are
		 * wasm-GC ones, and the same program's JVM bytecode is a third the size of its
		 * WASM to begin with.
		 * <p>
		 * Defaults to {@link OptimizeLevel#DEFAULT} -- the level an absent
		 * {@code --optimize} selects, so an embedder that names no level gets what this
		 * project's own frontend gives. Declining the optimizer is asked for by name:
		 * {@link OptimizeLevel#NONE}.
		 * @param optimize the optimization level
		 * @return this builder
		 */
		public Builder optimize(OptimizeLevel optimize) {
			this.optimize = optimize;
			return this;
		}

		/**
		 * Selects the {@code --simd} lowering. When {@code true}, the six vectorizable
		 * {@code vec:} kernels
		 * ({@code add}/{@code sub}/{@code mul}/{@code scale}/{@code dot}/{@code sum}) are
		 * lowered at their call sites to a shipped {@code jdk.incubator.vector} bridge
		 * ({@link JvmSimdVectorTemplate}) instead of the scalar {@code vec.lisp}
		 * reference. Running such a class requires
		 * {@code java --add-modules jdk.incubator.vector}.
		 * @param simd whether to lower the vectorizable kernels to the Vector API bridge
		 * @return this builder
		 */
		public Builder simd(boolean simd) {
			this.simd = simd;
			return this;
		}

		/**
		 * Selects the {@code --blas} lowering. When {@code true}, the {@code linalg:}
		 * matrix product is lowered at its call sites to a shipped CBLAS bridge
		 * ({@link JvmBlasTemplate}), which binds a tuned library out of the OS at run
		 * time and declines to whatever is below it -- the {@code --simd} kernel or the
		 * scalar defun -- when there is none. Orthogonal to {@link #simd}: either, both
		 * or neither.
		 * @param blas whether to lower the matrix product to the CBLAS bridge
		 * @return this builder
		 */
		public Builder blas(boolean blas) {
			this.blas = blas;
			return this;
		}

		/**
		 * Selects the {@code --gpu} lowering. When {@code true}, the matrix-by-matrix
		 * case of the {@code linalg:} product is lowered at its call sites to a shipped
		 * device bridge ({@link JvmGpuTemplate} over a renamed copy of
		 * {@code am.ik.gpu}), which offers the product to an NVIDIA GPU and declines to
		 * whatever is below it -- the CBLAS bridge, the {@code --simd} kernel or the
		 * scalar defun -- when there is no device or the product is one it does not take.
		 * Orthogonal to {@link #simd} and {@link #blas}: any combination.
		 * @param gpu whether to lower the matrix product to the device bridge
		 * @return this builder
		 */
		public Builder gpu(boolean gpu) {
			this.gpu = gpu;
			return this;
		}

		/**
		 * Selects {@code --parallel}. When {@code true}, the {@code --simd} bridge's GEMV
		 * / GEMM call sites ({@code vec:matvec}, {@code vec:matvec-into},
		 * {@code linalg:dot}, the stacked {@code linalg:matmul}) bind to the entries that
		 * split their rows across {@code RONTOLISP_THREADS} threads -- the same row
		 * chains, so the same bits ({@code .kb/simd-parallel.md}). A modifier of
		 * {@link #simd}, which it therefore requires ({@link #build()} refuses it alone);
		 * the emitted bytes differ by those method names alone.
		 * @param parallel whether to bind the row-splitting kernel entries
		 * @return this builder
		 */
		public Builder parallel(boolean parallel) {
			this.parallel = parallel;
			return this;
		}

		/**
		 * Disabling this suppresses {@link JvmGeomKernelCompiler}, so
		 * {@code geom:read-obj}, {@code geom:mesh}, {@code geom:wireframe} and
		 * {@code geom::%vertex-extremes} are emitted as calls to the {@code geom.lisp}
		 * defuns alone and no bridge travels. There is no flag behind this and no reason
		 * for a program to ask for it: the bridge answers what the defuns answer, bit for
		 * bit. It exists so the test that PROVES that has an oracle to compile against --
		 * the interpreter's {@code setGeomKernels} twin.
		 * @param geomKernels whether the geom kernel bridge may be emitted
		 * @return this builder
		 */
		Builder geomKernels(boolean geomKernels) {
			this.geomKernels = geomKernels;
			return this;
		}

		/**
		 * Sets the feature names the compiled program's {@code *features*} starts out
		 * holding. The frontend passes the set it READ the program with, so a
		 * {@code (member :rontolisp-component *features*)} at run time answers what the
		 * {@code #+rontolisp-component} beside it answered at read time. Left alone, the
		 * backend's base set stands ({@link LispMacroExpander#backendFeatures}).
		 * @param features the feature names, without the leading colon
		 * @return this builder
		 */
		public Builder runtimeFeatures(List<String> features) {
			this.runtimeFeatures = List.copyOf(features);
			return this;
		}

		/**
		 * Compile a library class instead of a command: no {@code main} method is emitted
		 * (the CLI's {@code --no-main}, the twin of the WASM side's {@code --no-wasi}
		 * reactor turn). The program must declare at least one
		 * {@code rontolisp:jvm-export} -- {@code main} is the only tree-shaker root an
		 * unexported program has, so a main-less class without exports would shake to
		 * nothing -- and its top level runs in {@code <clinit>}, i.e. once, when the
		 * class is initialized by the first call into it (a class with exports runs its
		 * top level there whether or not {@code main} is kept; see
		 * {@code .kb/jvm-export.md}).
		 * @param noMain whether to omit the {@code main} entry point
		 * @return this builder
		 */
		public Builder noMain(boolean noMain) {
			this.noMain = noMain;
			return this;
		}

		/**
		 * Selects servlet mode ({@code -o app.war}). The program must serve (a
		 * {@code rontolisp:http-handler} directive or the {@code %http-server-start}
		 * seam): a war with nothing for the container to call is refused at compile time.
		 * The top level moves into {@code <clinit>} exactly as an export does -- the
		 * container's initializer triggers it through {@code Class.forName} -- and the
		 * directive stores the handler funcref and returns instead of calling the
		 * blocking {@code serve}: the container owns the port.
		 * @param servlet whether to compile for a servlet container
		 * @return this builder
		 */
		public Builder servlet(boolean servlet) {
			this.servlet = servlet;
			return this;
		}

		/**
		 * Sets the most constant-pool entries one emitted class may carry before the
		 * program is split into {@code $PartN} classes -- a test instrument: below the
		 * class-format limit (the default, also read from
		 * {@code -Drontolisp.jvm.class-pool-limit}) it forces the split onto a program
		 * small enough to test, and the parts are then filled to this many entries.
		 * @param classPoolLimit the entry limit per class, at most
		 * {@link ConstantPool#MAX_INDEX}
		 * @return this builder
		 */
		Builder classPoolLimit(int classPoolLimit) {
			if (classPoolLimit < 1 || classPoolLimit > ConstantPool.MAX_INDEX) {
				throw new IllegalArgumentException("a class pool limit is 1.." + ConstantPool.MAX_INDEX);
			}
			this.classPoolLimit = classPoolLimit;
			return this;
		}

		/**
		 * Sets the Java release {@code java:} call sites are resolved against
		 * ({@code --java-release}): the platform classes are read from the JDK's
		 * {@code ct.sym} for that release. Left alone, the newest release the JDK holds
		 * -- its own -- which is what the interpreter on that JDK resolves against.
		 * @param javaRelease the release, or {@code null} for the default
		 * @return this builder
		 */
		public Builder javaRelease(@Nullable Integer javaRelease) {
			this.javaRelease = javaRelease;
			return this;
		}

		/**
		 * Sets the class path {@code java:} call sites are resolved against after the
		 * platform ({@code --java-classpath}): directories and jar/zip archives.
		 * @param javaClasspath the entries, searched in order
		 * @return this builder
		 */
		public Builder javaClasspath(List<java.nio.file.Path> javaClasspath) {
			this.javaClasspath = List.copyOf(javaClasspath);
			return this;
		}

		/**
		 * Reports each {@code java:} call site that cannot be resolved at compile time
		 * and is resolved by reflection at run time instead
		 * ({@code --warn-java-reflection}, the compile path's
		 * {@code java:*warn-on-reflection*}).
		 * @param warnJavaReflection whether to report them
		 * @return this builder
		 */
		public Builder warnJavaReflection(boolean warnJavaReflection) {
			this.warnJavaReflection = warnJavaReflection;
			return this;
		}

		/**
		 * Makes a {@code java:} call site that cannot be compiled to a direct call -- one
		 * left to run-time reflection, a {@code java:proxy}, a function value passed
		 * where an interface is expected -- a compile error ({@code --java-static}), so
		 * the class carries no reflective bridge at all: what GraalVM native-image
		 * compiles without reachability metadata.
		 * @param javaStatic whether to refuse such sites
		 * @return this builder
		 */
		public Builder javaStatic(boolean javaStatic) {
			this.javaStatic = javaStatic;
			return this;
		}

		/**
		 * Builds the compiler.
		 * @return a new JVM compiler
		 * @throws NullPointerException when no class name was set
		 * @throws IllegalArgumentException when {@link #parallel} is set without
		 * {@link #simd}
		 */
		public JvmLispCompiler build() {
			return new JvmLispCompiler(this);
		}

	}

	/**
	 * The runtime class files the compiled class needs BESIDE it — the packed float-array
	 * handle a {@code :float-vector} / {@code :float-matrix} export hands out with its
	 * marshalling seam, the embedded HTTP server a {@code rontolisp:http-handler} program
	 * serves through, the {@code equalp} key fold a program that writes
	 * {@code :test 'equalp} places its keys by, and the complex holder a program that can
	 * observe a complex value builds. Empty unless the program does one of those, so an
	 * ordinary compilation still produces exactly one file. Two kinds live in the
	 * program's own package instead: a split program's {@code $PartN} classes and the
	 * template bridges ({@code $JavaBridge}, {@code $GeomBridge}, {@code $SimdBridge},
	 * {@code $BlasBridge}, and the {@code $Gpu*}, {@code $Objc*} and {@code $Ffi*}
	 * library copies). Some entries are not classes: {@code $BlasBridge} and the
	 * {@code $Gpu*} and {@code $Objc*} copies bring their native-image registration under
	 * {@code META-INF/native-image/}
	 * ({@link JvmBlasRuntimeBuilder#nativeImageMetadataPath},
	 * {@link JvmGpuRuntimeBuilder#nativeImageMetadataPath},
	 * {@link JvmObjcRuntimeBuilder#nativeImageMetadataPath}).
	 *
	 * <p>
	 * The runtime classes are written at their canonical names rather than renamed into
	 * the program's package ({@link JvmRuntimeClassFiles}), and the {@code runtime}
	 * package they come from imports nothing, which is what makes the output run with no
	 * rontolisp jar on the classpath ({@code .kb/jvm-export.md}). Valid after
	 * {@link #compile}.
	 * @return each file's path within an output tree (or jar), mapped to its bytes
	 */
	public Map<String, byte[]> runtimeClassFiles() {
		if (!this.needsHandleRuntime && !this.needsHttpRuntime && !this.needsFetchRuntime && !this.needsHashTableRuntime
				&& !this.needsComplexRuntime && !this.needsIoStreamRuntime && !this.needsCharFileRuntime
				&& !this.needsStringInputRuntime && this.partClassFiles.isEmpty() && this.bridgeClassFiles.isEmpty()) {
			return Map.of();
		}
		// A program too large for one class brings its $PartN classes, and a bridged
		// program its bridge: they are written beside the class exactly where the runtime
		// classes are, in its own package.
		Map<String, byte[]> files = new LinkedHashMap<>(this.partClassFiles);
		files.putAll(this.bridgeClassFiles);
		if (this.needsIoStreamRuntime) {
			files.putAll(JvmRuntimeClassFiles.read(JvmIoRuntimeBuilder.RUNTIME_CLASS_FILES));
		}
		if (this.needsCharFileRuntime) {
			files.putAll(JvmRuntimeClassFiles.read(JvmIoRuntimeBuilder.CHAR_FILE_RUNTIME_CLASS_FILES));
		}
		if (this.needsStringInputRuntime) {
			files.putAll(JvmRuntimeClassFiles.read(JvmIoRuntimeBuilder.STRING_INPUT_RUNTIME_CLASS_FILES));
		}
		if (this.needsHandleRuntime) {
			files.putAll(JvmExportRuntimeBuilder.runtimeClassFiles());
		}
		if (this.needsHashTableRuntime) {
			files.putAll(JvmRuntimeClassFiles.read(JvmHashRuntimeBuilder.RUNTIME_CLASS_FILES));
		}
		if (this.needsComplexRuntime) {
			files.putAll(JvmComplexRuntimeBuilder.runtimeClassFiles());
		}
		if (this.needsFetchRuntime) {
			files.putAll(JvmRuntimeClassFiles.read(JvmFetchRuntimeBuilder.RUNTIME_CLASS_FILES));
		}
		if (this.needsHttpRuntime) {
			files.putAll(JvmHttpHandlerRuntimeBuilder.runtimeClassFiles());
			// A war additionally carries the servlet transport -- the THIRD travelling
			// list, reached only here so no .class/.jar output ever gains the
			// jakarta.servlet reference (.kb/jvm-export.md, "What travels").
			if (this.servletMode) {
				files.putAll(JvmHttpHandlerRuntimeBuilder.warRuntimeClassFiles());
			}
		}
		return Map.copyOf(files);
	}

	@Override
	public byte[] compile(List<LispVal> program) {
		// Runtime helper GROUPS are gated on a scan of the SOURCE program, but several
		// lowerings introduce the primitive that calls a helper only during compileExpr,
		// after that scan has run (`(setf (elt s i) v)` -> %aset / %schar-set is the
		// worked example). The gate is therefore a prediction, and a wrong one used to
		// ship an invokestatic to a method that was never generated -- JVM resolution is
		// lazy, so it survived verification and failed at run time only if the branch was
		// taken. So the prediction is checked against the emitted bytecode (see the
		// unresolvedSelfMethods call at the end of the pass) and a mispredicted gate is
		// simply re-run with that group forced on: the build is then identical to one
		// whose source did mention an array operator, rather than merely not crashing.
		// Each retry strictly grows `forced`, so the loop terminates.
		// See .kb/adjustable-arrays.md ("The array gate is a consequence, not a
		// prediction").
		Set<String> forced = new LinkedHashSet<>();
		// The second retry dimension: the functions to cut into their own methods
		// because the attempt that just ran MEASURED them over HotSpot's limit. Like
		// `forced` it strictly grows -- a name is added, or its target shrinks toward
		// the floor -- so the loop terminates.
		Map<String, AstOutliner.Budget> outline = new LinkedHashMap<>();
		try {
			return compileAttempts(program, forced, outline);
		}
		finally {
			JvmClassFileLookup classes = this.javaClasses;
			if (classes != null) {
				classes.close();
				this.javaClasses = null;
			}
		}
	}

	private byte[] compileAttempts(List<LispVal> program, Set<String> forced, Map<String, AstOutliner.Budget> outline) {
		while (true) {
			// A retried attempt's bytecode is thrown away, and so are its warnings: a
			// warning printed as it was emitted said the same thing twice for one compile
			// once. CompileWarnings buffers this attempt's and
			// only the attempt that SHIPS gets to print.
			CompileWarnings.startAttempt();
			try {
				byte[] bytes = compile(program, forced, outline);
				CompileWarnings.flushAttempt();
				return bytes;
			}
			catch (MethodTooLarge signal) {
				CompileWarnings.discardAttempt();
				if (System.getProperty("rontolisp.debug.outline") != null) {
					System.err.println("[outline] retry " + signal.budgets);
				}
				outline.putAll(signal.budgets);
			}
			catch (GateUnderpredicted signal) {
				CompileWarnings.discardAttempt();
				if (!forced.addAll(signal.groups)) {
					throw new IllegalStateException(
							"JvmLispCompiler: runtime helper gate " + signal.groups + " stayed under-predicted");
				}
			}
			catch (RuntimeException ex) {
				// A real failure: this attempt is the last one, so its warnings still
				// describe the program the user is being told about.
				CompileWarnings.flushAttempt();
				throw ex;
			}
		}
	}

	private byte[] compile(List<LispVal> program, Set<String> forcedGroups,
			Map<String, AstOutliner.Budget> outlineBudgets) {
		// The load-context brackets LoadInliner put around each spliced file become
		// assignments of *load-pathname* / *load-truename* -- when the program reads
		// either; otherwise they are dropped here and nothing downstream sees them.
		// Before the resolver, whose own marker arm is the backstop for a bracket this
		// pass did not lower.
		program = LispMacroExpander.lowerLoadContextMarkers(program);
		// Resolve packages (in-package directives, qualified symbols, *package*) up front
		// so
		// the rest of compilation sees canonical names.
		PackageResolver packageResolver = new PackageResolver();
		program = packageResolver.resolveProgram(program);
		// The host types of variables -- declared, inferred from a let initializer,
		// proclaimed -- onto the java: sites they type, as the interpreter lowers each
		// top-level form before running it (compiler/JavaDeclarations): the site
		// resolver then reads the site alone.
		program = lowerJavaDeclarations(program);
		// A quoted designator of a wrapped built-in becomes #'name before any wrapper
		// gate scans the program for that spelling (compiler/FunctionDesignators).
		program = am.ik.rontolisp.compiler.FunctionDesignators.normalizeBuiltinDesignators(program);
		// The printer's accessibility table (.kb/pretty-printer.md): baked from the
		// final registry over the resolved program, only for a program that can print
		// under a package other than cl-user -- every other program keeps its raw
		// symbol spellings and stays byte-identical.
		am.ik.rontolisp.SymbolPrintTable symbolPrintTable = LispMacroExpander.printsUnderAPackage(program)
				&& LispMacroExpander.usesPrintControls(program) ? packageResolver.symbolPrintTable(program) : null;
		// Whether the program ITSELF names a printer-control variable, decided here --
		// before expandTopLevelDefinitions injects the renderer's defvars, which mention
		// every one of them.
		boolean printControlVariables = LispMacroExpander.mentionsPrintControlVariable(program);
		// A (boundp 'name) over a literal symbol is decided here, against the globals the
		// top-level forms before it declare (compiler/CompileTimeBoundp): the probe is
		// what forces the eval runtime, and the guard it tests is what keeps the
		// definition it wraps from surfacing as a top-level definer. The CLI folds the
		// same program before its tree-shaker runs; this run decides what only the
		// canonical spellings can decide, and keeps a direct compiler invocation
		// equivalent.
		program = CompileTimeBoundp.fold(program, this.dynamic, true);
		// Splice top-level (progn ...)/(eval-when ...) so Pass 1 collects the defuns
		// nested in them (the CLI already flattens via UserMacroExpander; this keeps
		// direct compiler invocations equivalent).
		program = LispMacroExpander.flattenTopLevel(program);
		if (this.optimize.eliminatesDeadCode()) {
			// A typecase clause whose type no call site's argument can have is dead code
			// the class shaker cannot see, because its reachability is by NAME
			// (compiler/DeadTypeBranchPruner, .kb/optimize-dead-code-elimination.md).
			program = DeadTypeBranchPruner.prune(program);
		}
		// The (rontolisp:async (defun ...)) wrapper expands first (the CLI already did;
		// this keeps direct compiler invocations and the playground equivalent), so the
		// placement check, Pass 1 and the async lowering below only ever see the
		// canonical async-defun/async-lambda forms.
		// Then rontolisp:await placement is checked on the raw forms, and every
		// async-defun/async-lambda lowers to an ordinary defun/lambda over the
		// %async-run primitive (virtual threads), so Pass 1 and everything below see
		// only the ordinary shapes.
		try {
			program = LispMacroExpander.rewriteAsyncSugar(program);
			LispAsync.checkTopLevel(program);
		}
		catch (IllegalArgumentException ex) {
			throw new UnsupportedOperationException(ex.getMessage());
		}
		program = LispAsync.lowerProgram(program);
		// Splice top-level defstructs/defclasses/defgenerics/defmethods into their
		// generated defuns before lambda-list desugaring (the generated constructors
		// use &key) so Pass 1 collects them as ordinary functions; the registries make
		// accessors setf-able places and resolve make-instance/slot-value/dispatch.
		Map<String, Integer> structAccessors = new HashMap<>();
		ClosRegistry closRegistry = new ClosRegistry();
		// A java: member that throws signals java:java-exception: a program that can make
		// a host call registers it before any type test over it expands (a wasm one
		// cannot, so it registers the class only where it names it).
		if (programUsesAnyJavaOp(program)) {
			closRegistry.ensureJavaExceptionSeeded();
		}
		// Whether the program uses the restart system (handler-bind / restart-case /
		// invoke-restart & friends). Decided on the SURFACE program -- the expansions
		// happen lazily during Pass 2, so the pre-scans below cannot see their
		// products -- and threaded into the expression compiler (the signal hook, the
		// real cerror) and the channel gates. Computed before
		// expandTopLevelDefinitions, which runs the same scan to inject the
		// restart-runtime defuns.
		boolean restartMode = LispMacroExpander.usesRestartSystem(program);
		// Whether signal needs the clause-type match at the signal point (the program
		// both signals and establishes a handler-case). Decided on the SURFACE program
		// like restartMode; expandTopLevelDefinitions runs the same scan to inject the
		// %hc-match-p defun and the cluster-stack defvar.
		boolean signalClauseMatch = LispMacroExpander.needsSignalClauseMatch(program);
		// Whether a printer-control variable is in play. Decided on the SURFACE program,
		// like the scan that gives the variable its defvar, and threaded into the
		// expression compiler so every printing operator routes through %print-cased.
		boolean printControls = LispMacroExpander.usesPrintControls(program);
		// The dispatch narrower drops generic-function branches no call site can select
		// (compiler/GenericDispatchNarrowing); only an optimizing, early-bound compile
		// may narrow -- under --dynamic any name resolves at run time.
		program = LispMacroExpander.expandTopLevelDefinitions(program, structAccessors, closRegistry,
				packageResolver::spellsAsExternal, this.dynamic, SignalMessages.RENDERED,
				this.optimize.eliminatesDeadCode() && !this.dynamic
						? new am.ik.rontolisp.compiler.GenericDispatchNarrowing() : null);
		// The read/compile-time package table for the runtime package API (see
		// .kb/packages.md): injected after package resolution, from the resolver's
		// final registry, only when the program can need it at run time.
		program = LispMacroExpander.injectBakedPackageTable(program, packageResolver);
		// The rows a find-symbol / intern the lowerings cannot fold reads what a package
		// reaches through an import, a re-export or a use from (injectBakedAccess).
		// Before the find-package helper: the rows' status helper calls find-package.
		am.ik.rontolisp.macro.BakedSymbolAccess symbolAccess = am.ik.rontolisp.macro.BakedSymbolAccess
			.of(packageResolver);
		program = LispMacroExpander.injectBakedAccess(program, symbolAccess);
		// The computed find-package lookup, once per program: its sites call it instead
		// of building the baked table each (LispMacroExpander.injectFindPackageHelper).
		program = LispMacroExpander.injectFindPackageHelper(program, packageResolver.runtimePackageTable(),
				packageResolver.runtimePackagesMutable());
		if (System.getProperty("rontolisp.debug.dump-program") != null) {
			for (LispVal form : program) {
				System.err.println(form.print());
			}
		}
		// A generic function whose name is a compiler-lowered built-in (fast-io's close
		// methods): rename its dispatcher, keep the built-in as the default method, and
		// route the program's call sites through it. No-op without such a generic.
		program = ShadowedBuiltins.process(program, closRegistry);
		// A defun nested in a function body REDEFINES a top-level defun of the same name
		// at run time, and only a global variable can hold both answers: the top-level
		// definition is renamed and an assignment of its function value takes its place,
		// so the name resolves through the variable like every other non-top-level defun
		// (.kb/core-representation.md, "The NAME half"). A no-op unless the two
		// spellings actually meet, and placed after every pass that can introduce a
		// top-level defun of its own (defstruct/defclass accessors, ShadowedBuiltins).
		program = NestedDefunRedefinition.rewrite(program);
		// Whether an instance value can exist in this class at all. The predicates and
		// _equal need the answer BEFORE any body is compiled (their shape changes), and
		// with the gate off nothing they would guard against can be constructed -- so an
		// instance-free program stays byte-identical to a build that never knew about
		// instances. Restart mode forces it on: the signal hook synthesizes simple-*
		// instances for plain string signals.
		boolean mayUseInstances = LispMacroExpander.mayCreateInstances(program, closRegistry) || restartMode;
		// Whether a handler landing pad exists -- the gate for a %program-error signal
		// carrying its program-error INSTANCE (LispMacroExpander.lowerProgramError).
		// Decided on the same snapshot as the instance gate, which a pad forces on.
		boolean hasLandingPad = LispMacroExpander.establishesLandingPad(program);
		// The stream-value gate is decided on the SAME program snapshot, because
		// mayCreateInstances above already answers for it: read them apart and a later
		// desugaring could turn one on without the other, which is a %obj-new with no
		// instance representation behind it.
		final boolean usesStreamValues = LispMacroExpander.mayCreateStreamValues(program);
		// Cut a function an earlier attempt measured over HotSpot's HugeMethodLimit
		// into pieces small enough to be JIT-compiled: an oversized evaluated
		// sub-form becomes a local function, which is what reaches the shape the
		// tail-spine splitter cannot cut (.kb/hot-path-method-size.md). Before the
		// lowering below, because that is what turns the go/return-from LEAVING an
		// outlined form into a non-local exit; empty (and a no-op) on a first
		// attempt, so a program with no oversized method never sees this pass.
		AstOutliner.Result astOutlined = AstOutliner.outline(program, outlineBudgets);
		program = astOutlined.program();
		// Desugar extended lambda lists (&optional/&key/&aux) into the native
		// "required + &rest" shape so the passes below only see that shape.
		// Lower a return-from that crosses a lambda boundary into an EH-based non-local
		// exit (before desugarProgram, so the %fn-block wrap for a same-function
		// return-from naturally nests around the injected let/%nlx-catch).
		CrossLambdaExitLowering.Result crossLambda = CrossLambdaExitLowering.lower(program);
		program = crossLambda.program();
		// catch/throw ride the same _nleTl channel as a lowered cross-lambda exit, so
		// either one makes handler-case non-local-exit aware. Restart mode rides it
		// too: the restart-case expansion transfers through catch/throw, which the
		// surface scans cannot see (the expansion happens during Pass 2).
		boolean blockExitChannel = crossLambda.used() || programUsesSymbol(program, LispNames.CATCH)
				|| programUsesSymbol(program, LispNames.THROW) || restartMode;
		// Read before the lambda lists lose their &optional bounds.
		Set<String> builtinShapedDefuns = BuiltinCallArity.builtinShapedDefuns(program);
		// A binding of a name that only local special declarations make special is
		// lexical where no such declaration names it: renamed apart here, on the lambda
		// lists as written, so every occurrence of a name in the special set collected
		// below is special (compiler/SpecialDeclarationScoping).
		program = am.ik.rontolisp.compiler.SpecialDeclarationScoping.scope(program);
		// The lambda-list desugaring keeps the binding of a supplied-p variable named
		// like a special, so it is handed the specials the program declares; a
		// parameter named like one is bound dynamically where Pass 1 and Pass 2 extract
		// the function (LambdaLists.toNative), against the full set collected below.
		program = LambdaLists.desugarProgram(program, LambdaLists.MAX_PHYSICAL_PARAMS,
				SpecialVarCollector.collectDeclared(program));
		// Create the %mv-spill global (a top-level setq) when the program uses a
		// multiple-value operator: the expansions read/write it across functions.
		program = LispMacroExpander.injectMvSpillGlobal(program, this.runtimeFeatures);
		// Special (dynamically bound) variables -- the program's own and the standard
		// ones injectMvSpillGlobal just declared. Collected over the WHOLE program: a
		// local (declare (special x)) inside a defun body (cl-ppcre's remove-registers-p)
		// must make x a global cell for its free readers too. Here, before Pass 1, as a
		// parameter named like one binds it dynamically (LambdaLists.toNative). A
		// SequencedSet, not a plain Set: this order mints the _g$ static fields, and
		// collectDynamicallyBound copies it wholesale when the program has a progv, so
		// an unordered set here makes the emitted class differ per JVM run
		// (.kb/emitted-output-determinism.md).
		SequencedSet<String> specialVars = SpecialVarCollector.collect(program);
		ConstantPool cp = new ConstantPool();
		ClassEntry thisClass = cp.classEntry(this.className);
		ClassEntry objectClass = cp.classEntry("java/lang/Object");

		ClassEntry systemClass = cp.classEntry("java/lang/System");
		FieldRefEntry systemOut = cp.fieldRef(systemClass, "out", "Ljava/io/PrintStream;");
		ClassEntry printStreamClass = cp.classEntry("java/io/PrintStream");

		ClassEntry longClass = cp.classEntry("java/lang/Long");
		MethodRefEntry longValueOf = cp.methodRef(longClass, "valueOf", "(J)Ljava/lang/Long;");
		MethodRefEntry longValue = cp.methodRef(longClass, "longValue", "()J");

		MethodRefEntry printlnStr = cp.methodRef(printStreamClass, "println", "(Ljava/lang/String;)V");
		MethodRefEntry printStr = cp.methodRef(printStreamClass, "print", "(Ljava/lang/String;)V");
		MethodRefEntry printlnVoid = cp.methodRef(printStreamClass, "println", "()V");

		ClassEntry integerClass = cp.classEntry("java/lang/Integer");
		MethodRefEntry integerValueOf = cp.methodRef(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
		MethodRefEntry integerValue = cp.methodRef(integerClass, "intValue", "()I");

		ClassEntry doubleClass = cp.classEntry("java/lang/Double");
		MethodRefEntry doubleValueOf = cp.methodRef(doubleClass, "valueOf", "(D)Ljava/lang/Double;");
		MethodRefEntry doubleToString = cp.methodRef(doubleClass, "toString", "()Ljava/lang/String;");

		ClassEntry numberClass = cp.classEntry("java/lang/Number");
		MethodRefEntry numberDoubleValue = cp.methodRef(numberClass, "doubleValue", "()D");

		Utf8Entry lispToStringName = cp.utf8Entry("_lispToString");
		Utf8Entry lispToStringDescUtf = cp.utf8Entry("(Ljava/lang/Object;)Ljava/lang/String;");
		MethodRefEntry lispToStringMethod = cp.methodRef(thisClass, lispToStringName, lispToStringDescUtf);
		Utf8Entry consToStringName = cp.utf8Entry("_consToString");
		Utf8Entry consToStringDescUtf = cp.utf8Entry("([Ljava/lang/Object;)Ljava/lang/String;");
		MethodRefEntry consToStringMethod = cp.methodRef(thisClass, consToStringName, consToStringDescUtf);
		Utf8Entry lispToDisplayStringName = cp.utf8Entry("_lispToDisplayString");
		MethodRefEntry lispToDisplayStringMethod = cp.methodRef(thisClass, lispToDisplayStringName,
				lispToStringDescUtf);
		Utf8Entry consToDisplayStringName = cp.utf8Entry("_consToDisplayString");
		MethodRefEntry consToDisplayStringMethod = cp.methodRef(thisClass, consToDisplayStringName,
				consToStringDescUtf);
		Utf8Entry appendName = cp.utf8Entry("_append");
		Utf8Entry appendDescUtf = cp.utf8Entry("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		MethodRefEntry appendMethod = cp.methodRef(thisClass, appendName, appendDescUtf);
		ClassEntry stringClass = cp.classEntry("java/lang/String");
		MethodRefEntry stringCharAt = cp.methodRef(stringClass, "charAt", "(I)C");
		MethodRefEntry stringLength = cp.methodRef(stringClass, "length", "()I");
		MethodRefEntry stringSubstring = cp.methodRef(stringClass, "substring", "(II)Ljava/lang/String;");
		// Used by _lispToDisplayString to cut a symbol's package qualifier / marker: the
		// princ spelling is everything after the last colon.
		MethodRefEntry stringLastIndexOf = cp.methodRef(stringClass, "lastIndexOf", "(I)I");
		MethodRefEntry objectEquals = cp.methodRef(objectClass, "equals", "(Ljava/lang/Object;)Z");
		// CHARACTER runtime representation references (used by _lispToString /
		// _lispToDisplayString to print the #\name form and the bare glyph,
		// respectively). A CHARACTER is a length-1 int[]{codePoint} (see
		// JvmEmitHelper.boxCodePoint) -- the discriminator is INSTANCEOF [I. The classic
		// java/lang/Character is still cached below because the char builtins delegate
		// to Character.toUpperCase(int) / Character.isLetter(int) / Character.digit(int,
		// int) / Character.toString(int) for JDK-provided semantics.
		ClassEntry charBoxClass = cp.classEntry("[I");
		ClassEntry characterClass = cp.classEntry("java/lang/Character");
		MethodRefEntry characterToString = cp.methodRef(characterClass, "toString", "(I)Ljava/lang/String;");
		Utf8Entry charPrin1Name = cp.utf8Entry("_charPrin1");
		Utf8Entry charPrin1Desc = cp.utf8Entry("(I)Ljava/lang/String;");
		MethodRefEntry charPrin1Method = cp.methodRef(thisClass, charPrin1Name, charPrin1Desc);
		ClassEntry mathClass = cp.classEntry("java/lang/Math");
		MethodRefEntry mathAbsLong = cp.methodRef(mathClass, "abs", "(J)J");
		MethodRefEntry mathAbsDouble = cp.methodRef(mathClass, "abs", "(D)D");
		MethodRefEntry mathFloor = cp.methodRef(mathClass, "floor", "(D)D");
		MethodRefEntry mathCeil = cp.methodRef(mathClass, "ceil", "(D)D");
		MethodRefEntry mathRint = cp.methodRef(mathClass, "rint", "(D)D");

		// Math helper references for sqrt/exp/log/trig/expt/signum compilers.
		Map<String, MethodRefEntry> mathOps = JvmMathFnCompiler.buildOps(cp, mathClass);

		// System helper references for the time / getenv compilers.
		Map<String, MethodRefEntry> systemOps = new java.util.LinkedHashMap<>();
		systemOps.put("currentTimeMillis", cp.methodRef(systemClass, "currentTimeMillis", "()J"));
		systemOps.put("nanoTime", cp.methodRef(systemClass, "nanoTime", "()J"));
		systemOps.put("getenv", cp.methodRef(systemClass, "getenv", "(Ljava/lang/String;)Ljava/lang/String;"));

		// read-line helper
		ClassEntry bufferedReaderClass = cp.classEntry("java/io/BufferedReader");
		ClassEntry inputStreamReaderClass = cp.classEntry("java/io/InputStreamReader");
		MethodRefEntry brInit = cp.methodRef(bufferedReaderClass, "<init>", "(Ljava/io/Reader;)V");
		MethodRefEntry brReadLine = cp.methodRef(bufferedReaderClass, "readLine", "()Ljava/lang/String;");
		MethodRefEntry isrInit = cp.methodRef(inputStreamReaderClass, "<init>", "(Ljava/io/InputStream;)V");
		FieldRefEntry systemIn = cp.fieldRef(systemClass, "in", "Ljava/io/InputStream;");
		MethodRefEntry stringConcat = cp.methodRef(stringClass, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		Utf8Entry stdinReaderFieldName = cp.utf8Entry("_stdinReader");
		Utf8Entry stdinReaderFieldDesc = cp.utf8Entry("Ljava/io/BufferedReader;");
		FieldRefEntry stdinReaderField = cp.fieldRef(thisClass, stdinReaderFieldName, stdinReaderFieldDesc);
		Utf8Entry readLineHelperName = cp.utf8Entry("_readLine");
		Utf8Entry readLineHelperDesc = cp.utf8Entry("()Ljava/lang/Object;");
		MethodRefEntry readLineHelperMethod = cp.methodRef(thisClass, readLineHelperName, readLineHelperDesc);

		// The async/await runtime (JvmAsyncRuntimeBuilder): %async-run (the lowered
		// async-defun/async-lambda), the generic _await resolver, the first-class stream
		// operations and the futurep/streamp predicates all live in one builder, emitted
		// when the program touches any of them (http-handler included: its handle()
		// awaits the handler's future and drains a stream response body). _fetch is
		// separate (JvmFetchRuntimeBuilder): its transport settles the future to the
		// response plist itself, so _await knows nothing of HTTP.
		String fetchQualified = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.FETCH);
		String awaitQualified = LispNames.AWAIT_QUALIFIED;
		boolean usesHttpHandler = programUsesSymbol(program,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.HTTP_HANDLER))
				// The stoppable %http-server-* seam (the clack-handler-rontolisp shim)
				// rides the same runtime: the Handler interface, the injected handle()
				// method and the _httpHandlerFn slot.
				|| programUsesSymbol(program,
						PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, LispNames.HTTP_SERVER_START));
		// A war exists to be called by a servlet container, so a program with no
		// handler to register is refused HERE (an embedder gets the check too), not
		// discovered as a dead deployment.
		if (this.servletMode && !usesHttpHandler) {
			throw new UnsupportedOperationException("-o app.war serves through a servlet container, but this program"
					+ " has no rontolisp:http-handler directive (and no rontolisp::%http-server-start): there is"
					+ " nothing for the container to call. Compile to a .class or .jar instead");
		}
		boolean usesFetch = programUsesSymbol(program, fetchQualified);
		boolean usesAsyncSpawn = programUsesSymbol(program, LispNames.ASYNC_RUN_QUALIFIED) || usesHttpHandler;
		boolean usesStreamOps = programUsesSymbol(program,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MAKE_STREAM))
				// %stream-new, the from-thunk (PULL) constructor every backend shares.
				|| programUsesSymbol(program, LispNames.STREAM_NEW_INTERNAL_QUALIFIED)
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.STREAM_READ))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.STREAM_WRITE))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.STREAM_CLOSE))
				|| programUsesSymbol(program,
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.ASYNC_STREAMP));
		boolean usesAsyncRuntime = usesFetch || usesAsyncSpawn || usesStreamOps
				|| programUsesSymbol(program, awaitQualified)
				// %future-force (the function spelling of await, e.g. the http-reactor
				// transport's boundary resolve) compiles to the same _await helper.
				|| programUsesSymbol(program, LispNames.FUTURE_FORCE_QUALIFIED)
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.FUTUREP))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WAIT_FOR));
		MethodRefEntry fetchHelperMethod = usesFetch
				? cp.methodRef(thisClass, JvmFetchRuntimeBuilder.METHOD_NAME, JvmFetchRuntimeBuilder.METHOD_DESC)
				: null;
		MethodRefEntry awaitHelperMethod = usesAsyncRuntime
				? cp.methodRef(thisClass, JvmAsyncRuntimeBuilder.AWAIT_METHOD, JvmAsyncRuntimeBuilder.AWAIT_DESC)
				: null;
		MethodRefEntry asyncRunHelperMethod = usesAsyncRuntime ? cp.methodRef(thisClass,
				JvmAsyncRuntimeBuilder.ASYNC_RUN_METHOD, JvmAsyncRuntimeBuilder.ASYNC_RUN_DESC) : null;
		MethodRefEntry futurepHelperMethod = usesAsyncRuntime
				? cp.methodRef(thisClass, JvmAsyncRuntimeBuilder.FUTUREP_METHOD, JvmAsyncRuntimeBuilder.UNARY_DESC)
				: null;
		MethodRefEntry streampHelperMethod = usesAsyncRuntime
				? cp.methodRef(thisClass, JvmAsyncRuntimeBuilder.STREAMP_METHOD, JvmAsyncRuntimeBuilder.UNARY_DESC)
				: null;
		MethodRefEntry makeStreamHelperMethod = usesAsyncRuntime ? cp.methodRef(thisClass,
				JvmAsyncRuntimeBuilder.MAKE_STREAM_METHOD, JvmAsyncRuntimeBuilder.MAKE_STREAM_DESC) : null;
		MethodRefEntry streamNewHelperMethod = usesAsyncRuntime ? cp.methodRef(thisClass,
				JvmAsyncRuntimeBuilder.STREAM_NEW_METHOD, JvmAsyncRuntimeBuilder.STREAM_NEW_DESC) : null;
		MethodRefEntry streamReadHelperMethod = usesAsyncRuntime
				? cp.methodRef(thisClass, JvmAsyncRuntimeBuilder.STREAM_READ_METHOD, JvmAsyncRuntimeBuilder.UNARY_DESC)
				: null;
		MethodRefEntry streamWriteHelperMethod = usesAsyncRuntime ? cp.methodRef(thisClass,
				JvmAsyncRuntimeBuilder.STREAM_WRITE_METHOD, JvmAsyncRuntimeBuilder.STREAM_WRITE_DESC) : null;
		MethodRefEntry streamCloseHelperMethod = usesAsyncRuntime
				? cp.methodRef(thisClass, JvmAsyncRuntimeBuilder.STREAM_CLOSE_METHOD, JvmAsyncRuntimeBuilder.UNARY_DESC)
				: null;
		MethodRefEntry drainBodyHelperMethod = usesAsyncRuntime
				? cp.methodRef(thisClass, JvmAsyncRuntimeBuilder.DRAIN_BODY_METHOD, JvmAsyncRuntimeBuilder.UNARY_DESC)
				: null;
		// An output file the program never closes is flushed on every way out of it, as
		// C's exit flushes stdio and both wasm backends write through. Gated on the
		// program naming one of the two file-stream producers (the program is not
		// macro-expanded yet: with-open-file becomes open later, and a quit inside its
		// body skips the close), so every other artifact keeps its exact bytes.
		final @Nullable MethodRefEntry flushStreamsMethod = programUsesSymbol(program, LispNames.OPEN)
				|| programUsesSymbol(program, LispNames.WITH_OPEN_FILE)
						? cp.methodRef(thisClass, JvmFlushStreamsBuilder.METHOD, JvmFlushStreamsBuilder.DESC) : null;
		MethodRefEntry waitForHelperMethod = usesAsyncRuntime
				? cp.methodRef(thisClass, JvmAsyncRuntimeBuilder.WAIT_FOR_METHOD, JvmAsyncRuntimeBuilder.UNARY_DESC)
				: null;

		// TCP/TLS socket helpers: emitted only when the program uses a rontolisp:tcp-*
		// or rontolisp:tls-connect built-in. A socket handle shares the _streams table
		// with file streams, so the stream built-ins grow socket branches
		// (JvmIoRuntimeBuilder) when this is set.
		boolean usesSockets = programUsesSymbol(program,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_CONNECT))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_LISTEN))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_ACCEPT))
				|| programUsesSymbol(program,
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_LOCAL_PORT))
				|| programUsesSymbol(program,
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_LOCAL_ADDRESS))
				|| programUsesSymbol(program,
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_PEER_ADDRESS))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_PEER_PORT))
				|| programUsesSymbol(program,
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_SET_TIMEOUT))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_CONNECT))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_UPGRADE))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_LISTEN))
				|| programUsesSymbol(program,
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_LISTEN_P12));
		// Cryptographic entropy helper: emitted only when the program references the
		// internal %random-byte primitive, so an entropy-free program never loads
		// java.security and keeps byte-identical output.
		boolean usesSecureRandom = programUsesSymbol(program,
				PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, LispNames.RANDOM_BYTE_INTERNAL));
		// Command-line helper: emitted only when the program references the internal
		// %host-argv primitive (the spliced uiop/image command-line family is its one
		// caller), so a program that does not read its arguments keeps byte-identical
		// output -- and main grows no prologue.
		boolean usesArgv = programUsesSymbol(program, LispNames.HOST_ARGV);
		// Octet-vector decode helper: emitted only when the program references the
		// internal %octets-to-string-packed primitive (the prelude's lenient octet
		// decoder is its one caller), so a program that never turns bytes into text
		// keeps byte-identical output. Gated on ITS OWN name rather than riding
		// usesAsyncRuntime: %octets-to-string is an ordinary function, reachable from a
		// program that spawns nothing.
		boolean usesOctetsPacked = programUsesSymbol(program, LispNames.OCTETS_TO_STRING_PACKED_INTERNAL_QUALIFIED);
		// Mutex helpers: emitted only when the program references one of the three
		// rontolisp:*-mutex primitives, so a lock-free program keeps byte-identical
		// output.
		boolean usesMutexes = programUsesSymbol(program,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MAKE_MUTEX))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MUTEX_ACQUIRE))
				|| programUsesSymbol(program,
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MUTEX_RELEASE));
		// Thread helpers: emitted only when the program references one of the five
		// rontolisp thread primitives (the bordeaux-threads/bt2 shim delegates here), so
		// a thread-free program keeps byte-identical output. The gate also forces every
		// special into the dynamically-bound set below: make-thread's bindings alist is
		// runtime data naming specials by string, so each needs its _d$ ThreadLocal.
		boolean usesThreads = programUsesSymbol(program,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MAKE_THREAD))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.JOIN_THREAD))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.THREADP))
				|| programUsesSymbol(program,
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.THREAD_ALIVE_P))
				|| programUsesSymbol(program,
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.DESTROY_THREAD))
				|| programUsesSymbol(program,
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.CURRENT_THREAD));
		MethodRefEntry tcpConnectHelperMethod = usesSockets ? cp.methodRef(thisClass,
				JvmSocketRuntimeBuilder.TCP_CONNECT_METHOD, JvmSocketRuntimeBuilder.TCP_CONNECT_DESC) : null;
		MethodRefEntry tcpListenHelperMethod = usesSockets ? cp.methodRef(thisClass,
				JvmSocketRuntimeBuilder.TCP_LISTEN_METHOD, JvmSocketRuntimeBuilder.TCP_LISTEN_DESC) : null;
		MethodRefEntry tcpAcceptHelperMethod = usesSockets ? cp.methodRef(thisClass,
				JvmSocketRuntimeBuilder.TCP_ACCEPT_METHOD, JvmSocketRuntimeBuilder.TCP_ACCEPT_DESC) : null;
		MethodRefEntry tcpLocalPortHelperMethod = usesSockets ? cp.methodRef(thisClass,
				JvmSocketRuntimeBuilder.TCP_LOCAL_PORT_METHOD, JvmSocketRuntimeBuilder.TCP_LOCAL_PORT_DESC) : null;
		MethodRefEntry tcpLocalAddressHelperMethod = usesSockets ? cp.methodRef(thisClass,
				JvmSocketRuntimeBuilder.TCP_LOCAL_ADDRESS_METHOD, JvmSocketRuntimeBuilder.TCP_LOCAL_ADDRESS_DESC)
				: null;
		MethodRefEntry tcpPeerAddressHelperMethod = usesSockets ? cp.methodRef(thisClass,
				JvmSocketRuntimeBuilder.TCP_PEER_ADDRESS_METHOD, JvmSocketRuntimeBuilder.TCP_PEER_ADDRESS_DESC) : null;
		MethodRefEntry tcpPeerPortHelperMethod = usesSockets ? cp.methodRef(thisClass,
				JvmSocketRuntimeBuilder.TCP_PEER_PORT_METHOD, JvmSocketRuntimeBuilder.TCP_PEER_PORT_DESC) : null;
		MethodRefEntry tcpSetTimeoutHelperMethod = usesSockets ? cp.methodRef(thisClass,
				JvmSocketRuntimeBuilder.TCP_SET_TIMEOUT_METHOD, JvmSocketRuntimeBuilder.TCP_SET_TIMEOUT_DESC) : null;
		MethodRefEntry tlsConnectHelperMethod = usesSockets ? cp.methodRef(thisClass,
				JvmSocketRuntimeBuilder.TLS_CONNECT_METHOD, JvmSocketRuntimeBuilder.TLS_CONNECT_DESC) : null;
		MethodRefEntry tlsUpgradeHelperMethod = usesSockets ? cp.methodRef(thisClass,
				JvmSocketRuntimeBuilder.TLS_UPGRADE_METHOD, JvmSocketRuntimeBuilder.TLS_UPGRADE_DESC) : null;
		MethodRefEntry tlsListenHelperMethod = usesSockets ? cp.methodRef(thisClass,
				JvmSocketRuntimeBuilder.TLS_LISTEN_METHOD, JvmSocketRuntimeBuilder.TLS_LISTEN_DESC) : null;
		MethodRefEntry tlsListenP12HelperMethod = usesSockets ? cp.methodRef(thisClass,
				JvmSocketRuntimeBuilder.TLS_LISTEN_P12_METHOD, JvmSocketRuntimeBuilder.TLS_LISTEN_P12_DESC) : null;

		// The :insecure opt-out of tls-connect AND tls-upgrade installs the generated
		// class itself as a trust-all X509TrustManager (the JVM backend cannot emit an
		// anonymous class), so when the program uses either the class implements the
		// interface, gets a no-arg constructor (for the helper's `new Prog()`) and the
		// three trust methods. JSSE calls the trust methods through the interface, an
		// edge the tree-shaker cannot see, so they are extra --optimize roots.
		boolean usesTlsConnect = programUsesSymbol(program,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_CONNECT))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_UPGRADE));
		// rontolisp:http-handler reuses the same "the generated class implements the
		// interface" mechanism: the class implements RontoHttpServer.Handler, the
		// directive stores the handler funcref in a static field and calls
		// RontoHttpServer.serve(port, new Prog()), and the injected handle() method
		// marshals the request/response plists through the _invoke_1 dispatcher.
		// The async runtime is a third user: the class implements Runnable and
		// _async_run does `new Prog()` per spawned body.
		// The whole socket group is emitted together, and the _tlsConnect/_tlsUpgrade
		// bodies instantiate the generated class as their trust-all X509TrustManager --
		// so the constructor is part of the SOCKET gate, not the narrower tls one. Only
		// the interface and its three trust methods stay on usesTlsConnect (nothing
		// calls them from bytecode; JSSE does, and only a tls-connect/tls-upgrade call
		// site can reach those helpers).
		boolean needsInstanceCtor = usesSockets || usesHttpHandler || usesAsyncRuntime || usesThreads;
		ClassEntry x509TrustManagerClass = usesTlsConnect ? cp.classEntry("javax/net/ssl/X509TrustManager") : null;
		ClassEntry x509CertificateClass = usesTlsConnect ? cp.classEntry("java/security/cert/X509Certificate") : null;
		MethodRefEntry objectInitRef = needsInstanceCtor ? cp.methodRef(objectClass, "<init>", "()V") : null;
		Utf8Entry instanceInitName = needsInstanceCtor ? cp.utf8Entry("<init>") : null;
		Utf8Entry instanceInitDesc = needsInstanceCtor ? cp.utf8Entry("()V") : null;
		Utf8Entry checkClientName = usesTlsConnect ? cp.utf8Entry("checkClientTrusted") : null;
		Utf8Entry checkServerName = usesTlsConnect ? cp.utf8Entry("checkServerTrusted") : null;
		Utf8Entry checkTrustedDesc = usesTlsConnect
				? cp.utf8Entry("([Ljava/security/cert/X509Certificate;Ljava/lang/String;)V") : null;
		Utf8Entry acceptedIssuersName = usesTlsConnect ? cp.utf8Entry("getAcceptedIssuers") : null;
		Utf8Entry acceptedIssuersDesc = usesTlsConnect ? cp.utf8Entry("()[Ljava/security/cert/X509Certificate;") : null;

		// java: interop. The sites resolve against class files, never the classes this
		// compiler runs on (compiler/JavaSiteResolver, .kb/java-interop.md); a resolved
		// site compiles to a direct call (JvmJavaDirectSites), a resolved java:reify /
		// java:proxy and a function passed where an interface is expected to an object of
		// a class generated for it (JvmJavaImplementations). The bridge runtime is
		// emitted only when a site needs it -- one left to run time, a java:reify /
		// java:proxy whose interface is not resolved -- and never under --java-static,
		// which refuses such a site. The (renamed) JavaBridgeTemplate travels beside the
		// class as its own class file, and the eval runtime is forced (the bridge applies
		// Lisp callables through _apply).
		boolean usesJava = programUsesAnyJavaOp(program);
		final JvmJavaSites javaSites = usesJava
				? new JvmJavaSites(javaClasses(), cp, thisClass, this.className, lispToStringMethod, this.javaStatic)
				: null;
		boolean usesJavaBridge = javaSites != null && !this.javaStatic
				&& (forcedGroups.contains(GROUP_JAVA_BRIDGE) || javaSites.needsBridge(program));
		final JvmJavaRuntimeBuilder.@Nullable JavaRuntime javaRuntime = usesJavaBridge
				? JvmJavaRuntimeBuilder.build(cp, thisClass, this.className) : null;
		this.bridgeClassFiles = new LinkedHashMap<>();
		if (javaRuntime != null) {
			this.bridgeClassFiles.putAll(javaRuntime.classFiles());
		}
		// A class calling members chosen against release N's API is stamped for release
		// N, so an older JRE refuses it at load rather than failing at the first call of
		// a member it lacks; a program without java: keeps the version-61 baseline.
		this.classMajorVersion = usesJava ? Math.max(CLASS_MAJOR_VERSION, 44 + javaClasses().release())
				: CLASS_MAJOR_VERSION;
		if (javaSites != null) {
			if (!javaClasses().hasPlatform()) {
				CompileWarnings.note("warning: no JDK found (java.home, JAVA_HOME or java on PATH holds no lib/ct.sym):"
						+ " java: sites that name JDK classes are resolved at run time");
			}
			javaSites.report(program, this.warnJavaReflection);
		}

		// objc: runtime: emitted only when the program calls the objc primitive layer
		// (the
		// spliced objc.lisp does, and an appkit: program through it). It ships the whole
		// am.ik.objc library plus the primitive layer beside the class, renamed after it
		// (JvmObjcRuntimeBuilder), and forces the eval runtime: a method, a block and the
		// body of objc:on-main are applied through _apply from an upcall.
		boolean usesObjc = programUsesAnyObjcOp(program);
		final JvmObjcRuntimeBuilder.@Nullable ObjcRuntime objcRuntime = usesObjc
				? JvmObjcRuntimeBuilder.build(cp, thisClass, this.className) : null;
		if (objcRuntime != null) {
			this.bridgeClassFiles.putAll(objcRuntime.classFiles());
		}

		// ffi: runtime: emitted only when the program uses one of the ffi: verbs (a
		// cffi: program does, through the spliced cffi-sys backend). It ships the whole
		// am.ik.ffi library plus the bridge and the pointer class beside the class,
		// renamed after it (JvmFfiRuntimeBuilder), and forces the eval runtime: an
		// ffi:callback's Lisp function is applied through _apply from an upcall.
		boolean usesFfi = programUsesAnyFfiOp(program);
		final JvmFfiRuntimeBuilder.@Nullable FfiRuntime ffiRuntime = usesFfi
				? JvmFfiRuntimeBuilder.build(cp, thisClass, this.className) : null;
		if (ffiRuntime != null) {
			this.bridgeClassFiles.putAll(ffiRuntime.classFiles());
		}

		ClassEntry objectArrayClass = cp.classEntry("[Ljava/lang/Object;");
		ClassEntry stringBuilderClass = cp.classEntry("java/lang/StringBuilder");
		MethodRefEntry longToString = cp.methodRef(longClass, "toString", "()Ljava/lang/String;");
		MethodRefEntry objectToString = cp.methodRef(objectClass, "toString", "()Ljava/lang/String;");
		MethodRefEntry sbInitStr = cp.methodRef(stringBuilderClass, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry sbAppendStr = cp.methodRef(stringBuilderClass, "append",
				"(Ljava/lang/String;)Ljava/lang/StringBuilder;");
		MethodRefEntry sbToString = cp.methodRef(stringBuilderClass, "toString", "()Ljava/lang/String;");
		ClassEntry ratioArrayClass = cp.classEntry("[Ljava/math/BigInteger;");
		StringEntry nilStr = cp.stringEntry("NIL");
		StringEntry slashStr = cp.stringEntry("/");
		StringEntry openParenStr = cp.stringEntry("(");
		StringEntry closeParenStr = cp.stringEntry(")");
		StringEntry spaceStr = cp.stringEntry(" ");
		StringEntry dotStr = cp.stringEntry(" . ");

		// Pass 1: Collect defun declarations and top-level expressions. Lisp-2: only a
		// real (defun ...) form defines a function; a top-level (setq name (lambda ...))
		// binds a variable to a closure like any other setq.
		List<DefunDecl> defuns = new ArrayList<>();
		List<LispVal> topLevelExprs = new ArrayList<>();
		// (rontolisp:jvm-export ...) directives: each becomes a typed, Java-callable
		// wrapper method next to the untyped defun method (JvmExportRuntimeBuilder),
		// and an extra tree-shaker root. Validated below, once the defuns are known.
		List<JvmExportDirective> exportDecls = new ArrayList<>();
		for (LispVal expr : program) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym
					&& LispNames.DEFUN.equals(sym.name())) {
				defuns.add(extractSetqLambda(LispMacroExpander.expandDefun(cons), specialVars));
			}
			else if (JvmExportDirective.isExportForm(expr)) {
				exportDecls.add(JvmExportDirective.parse((LispCons) expr));
			}
			else if (WasmImportDirective.isImportForm(expr)) {
				// rontolisp:wasm-import declares a host function that only exists in a
				// compiled WASM module. The JVM backend defines a stub of the declared
				// arity that signals an error when called, so the same source still
				// compiles (the directive itself is a no-op yielding nil).
				defuns.add(wasmImportStub(WasmImportDirective.parse((LispCons) expr)));
				topLevelExprs.add(expr);
			}
			else {
				topLevelExprs.add(expr);
			}
		}
		// main() drops every top-level form's value, so a form that is nothing BUT a
		// value has nothing to emit. The resolvers leave these behind in bulk -- an
		// in-package/defpackage directive resolves to a quoted symbol, an unselected
		// eval-when to nil (compiler/ToplevelStatements,
		// .kb/toplevel-statement-values.md).
		topLevelExprs = ToplevelStatements.prune(topLevelExprs);

		// A redefined defun keeps only its LAST definition: a class may not hold two
		// methods of the same name and descriptor (fast-http redefines 11 struct
		// readers as plain defuns, which loaded as a ClassFormatError). Dropping the
		// earlier bodies loses nothing the backend could reach -- every by-name call
		// site and #'reference resolves through the name map, which the last
		// definition wins even BETWEEN the two defuns (whole-program static
		// resolution, same as the WASM backend's).
		Map<String, Integer> lastDefinition = new HashMap<>();
		Set<String> multiplyDefinedDefuns = new HashSet<>();
		for (int i = 0; i < defuns.size(); i++) {
			if (lastDefinition.put(defuns.get(i).name, i) != null) {
				// A redefined name is excluded from fused-call substitution below: a
				// call site between the two definitions still resolves to the last one
				// (whole-program static resolution), but staying out keeps the
				// substitution's uniqueness criterion identical to the WASM backend's.
				multiplyDefinedDefuns.add(defuns.get(i).name);
			}
		}
		if (lastDefinition.size() < defuns.size()) {
			List<DefunDecl> lastOnly = new ArrayList<>(lastDefinition.size());
			for (int i = 0; i < defuns.size(); i++) {
				if (lastDefinition.getOrDefault(defuns.get(i).name, -1) == i) {
					lastOnly.add(defuns.get(i));
				}
			}
			defuns.clear();
			defuns.addAll(lastOnly);
		}

		// Inject built-in function wrappers (user defuns take priority)
		Set<String> userDefinedNames = new HashSet<>();
		for (DefunDecl defun : defuns) {
			userDefinedNames.add(defun.name);
		}
		// Whether the PROGRAM itself needs the concatenate 'string argument normalizer:
		// computed here, before the wrappers are generated, and threaded into Ctx so the
		// lowering only emits calls to a helper that is actually present. The registry
		// resolves a user deftype alias of the string family the same way the
		// CONCATENATE lowering itself will.
		boolean usesSeqString = ConcatenateForms.needsSeqString(program, closRegistry);
		// Whether the flipped string producers wrap their results into mutable character
		// vectors -- the same scan the WASM backend wraps under, and a member of the
		// array gate (programUsesAnyArrayOp), so the wrap sites always have _toMutStr.
		boolean mutableStringProducers = MutableStringProducers.programUsesAny(program);
		// Whether the packed (unsigned-byte 8|16|32) vector builder is reachable: a
		// concatenate whose result type spells a packed element type lowers to a call to
		// it, and so does the #'concatenate wrapper's own vector arm (its designator is a
		// runtime value, so it re-does the width dispatch there). Forces usesIntArray
		// below -- the helper's make-array calls are in the WRAPPER, which the source
		// scans below never see. The wrapper's CHARACTER arm calls %seq-string the same
		// way, so a #'concatenate reference forces usesSeqString on too -- otherwise the
		// wrapper body would call a helper the exclusion list just dropped.
		boolean referencesConcatenateValue = program.stream()
			.anyMatch(expr -> BuiltinFunctionWrappers.referencesFunctionValue(expr, LispNames.CONCATENATE));
		usesSeqString = usesSeqString || referencesConcatenateValue;
		boolean usesSeqIntVector = ConcatenateForms.needsSeqIntVector(program, closRegistry)
				|| referencesConcatenateValue;
		// The same for the packed FLOAT builder, on its own gate so a program that asks
		// only for packed integer vectors carries none of the float allocations. Forces
		// usesFloatArray below for the reason usesSeqIntVector forces usesIntArray: the
		// helper's make-array calls are in the WRAPPER, which the source scans never see.
		boolean usesSeqFloatVector = ConcatenateForms.needsSeqFloatVector(program, closRegistry)
				|| referencesConcatenateValue;
		// The hash-table runtime gate. Like the array gate it is a source scan that a
		// lowering can outrun -- (%class-designator x) expands into a hash-table-p test,
		// so a
		// hash-free program can still reference _hashP -- and forcedGroups carries the
		// previous run's verdict when it did (see compile(List)).
		// http-handler forces the group on: the Clack environment's :headers value is a
		// hash table (built by RontoHttpClack in the _hash* runtime's HashMap
		// representation), whether or not the program's own source names a hash op.
		// A table whose keys are FOLDED: the three extra helpers and the fold call in
		// get/put/remove ride on their own gate, so a program that writes no
		// :test 'equalp is emitted exactly as it was before the fold existed. The
		// travelling RontoHashTable rides with any hash-using output, fold or not:
		// every table's put/remove/count/values helpers call its tombstone machinery.
		boolean usesEqualpHashTables = LispMacroExpander.programMakesEqualpHashTable(program)
				|| forcedGroups.contains(GROUP_HASH_EQUALP);
		// A table whose aggregates key by identity: the makers, the test reader and
		// the test-dispatched comparison/placement ride on their own gate, so a
		// program that writes no :test 'eq or :test 'eql is emitted exactly as it was
		// before identity tables existed.
		boolean usesIdentityHashTables = LispMacroExpander.programMakesIdentityHashTable(program)
				|| forcedGroups.contains(GROUP_HASH_IDENTITY);
		boolean usesHashTables = programUsesAnyHashOp(program) || forcedGroups.contains(GROUP_HASH) || usesHttpHandler
				|| usesEqualpHashTables || usesIdentityHashTables;
		// The reader runtime is emitted for read/load; load also evaluates each form, so
		// it pulls in the eval runtime as well.
		boolean usesLoad = programUsesSymbol(program, LispNames.LOAD);
		// An :s-expr jvm-export parameter is parsed through the embedded reader
		// (_readFromString), so it forces the reader runtime exactly as
		// read-from-string in the source would.
		boolean usesRead = programUsesSymbol(program, LispNames.READ)
				|| programUsesSymbol(program, LispNames.READ_FROM_STRING) || usesLoad
				|| JvmExportRuntimeBuilder.needsReader(exportDecls);
		// Whether the program can produce a function NAME at run time that the registry
		// then has to answer -- read/load, or one of the symbol producers. Read here
		// rather than at the dispatch gate below because the wrapper gate needs it too:
		// a name manufactured at run time may be one of the wrapped built-ins.
		boolean nameResolvable = anyNameResolvable(program, usesRead, usesLoad);
		boolean symbolBuilders = RuntimeNameProducers.anySymbolBuilder(program);
		// #'funcall's wrapper body is (apply f r), which compiles to the eval runtime's
		// _apply -- a helper emitted only when the program uses eval. The wrapper is dead
		// weight unless the program takes #'funcall as a value, so it is injected exactly
		// then, and that same reference forces the eval runtime on (usesEval below).
		// Without the pairing the wrapper referenced _apply in EVERY class and
		// (reduce #'funcall fns) died with NoSuchMethodError the moment it ran.
		// A stable alias for the scans below: `program` is reassigned by the passes
		// above, so a lambda cannot close over it.
		List<LispVal> resolvedProgram = program;
		boolean usesFuncallValue = referencesFunctionDesignator(resolvedProgram, closRegistry, LispNames.FUNCALL);
		// The rest of that family -- mapcar/mapc/every/some/map/... -- has wrapper bodies
		// of exactly the same shape, and is gated the same way: naming one as a function
		// designator forces the eval runtime on, and a program that names none of them
		// gets none of those wrappers. Ungated they went into EVERY program, the finished
		// class then called an _apply it had never declared, the post-compile self-check
		// answered that with GROUP_EVAL forced on -- and so the eval runtime was switched
		// on for programs with no eval in them. Invisible while --optimize shook the
		// wrappers back out, but not once the program had a top-level global: its setq
		// then mirrored into the eval env and the class grew 8.5x.
		//
		// The scan counts 'name as well as #'name (FunctionDesignators normalizes the
		// first into the second) and gives up entirely on a program that can hand the
		// name registry a designator it cannot read: a name the program reads or builds
		// at run time. A computed funcall/apply target counts only beside a symbol
		// constant spelling one of the names (quoted data at any depth): the registry
		// answers only names the program loads as values, so without one no symbol can
		// reach a wrapper. Counting every computed target switched the whole eval
		// runtime on for any higher-order function -- (funcall f x) over a parameter
		// was 49 KB of class against 8 KB without it -- and for every program that
		// spliced the stream resolver, whose synonym arm calls a closure.
		boolean usesApplyingWrapperValue = (LispMacroExpander.usesRuntimeFunctionDesignator(program)
				&& spellsSymbolConstant(resolvedProgram, closRegistry, BuiltinFunctionWrappers.APPLY_USING_FUNCTIONS))
				|| nameResolvable || symbolBuilders || BuiltinFunctionWrappers.APPLY_USING_FUNCTIONS.stream()
					.anyMatch(op -> referencesFunctionDesignator(resolvedProgram, closRegistry, op));
		// When the program uses eval, the runtime _apply dispatches by argument count, so
		// every arity up to the maximum callable must have a dispatch method.
		// boundp/symbol-value/fboundp resolve symbols at runtime against the eval
		// runtime's global env mirror (_genv) and function registry (_lookup/_fenv), so
		// they force the eval runtime. fmakunbound writes the tombstone into that same
		// _fenv. A boundp of a global whose variable carries its bound-ness reads that
		// variable alone, so a program whose every boundp is such a literal probe does
		// not (the tracked set is checked again once the runtime is injected).
		boolean boundpReadsMirror = programUsesSymbol(program, LispNames.BOUNDP) && LispMacroExpander
			.boundpReachesMirror(program, GlobalVarCollector.collectProbedUnboundBeforeInjection(program,
					closRegistry.conditionReports().values(), specialVars, usesThreads));
		boolean usesEval = programUsesEval(program) || usesLoad || this.dynamic || usesJavaBridge || usesObjc || usesFfi
				|| boundpReadsMirror || programUsesSymbol(program, LispNames.SYMBOL_VALUE)
				|| programUsesSymbol(program, LispNames.SET) || programUsesSymbol(program, LispNames.FBOUNDP)
				|| programUsesSymbol(program, LispNames.FMAKUNBOUND)
				// (setf (symbol-function ...)) writes _fenv (the raw place shape is
				// scanned: the lowering to %set-symbol-function happens per expression,
				// after this gate).
				|| LispMacroExpander.usesSymbolFunctionWrite(program) || forcedGroups.contains(GROUP_EVAL);
		// The APPLY TIER: _apply and the spread dispatcher it hands the argument list
		// to, without the interpreter (_eval/_store/_envLookup, the _genv mirror, a
		// dispatcher for every arity). A runtime apply needs no more than that -- an
		// eval-free program holds no interpreted closure and no _fenv binding -- and it
		// used to put the whole eval runtime in: (apply f l) over a parameter was
		// 48 KB of class. The WASM backend has had the same tier since todo-315, over
		// the same scan: an apply (or a multiple-value-call, whose expansion spreads
		// through apply) whose literal #'f/'f target names a compiled function is a
		// physical direct call (JvmApplyCompiler) and needs neither. The wrapper-name set
		// counts only wrappers the #'name spelling itself injects; a misprediction is
		// caught by the post-compile self-check (GROUP_APPLY).
		Set<String> applyGateWrappers = BuiltinFunctionWrappers.wrapperNames();
		applyGateWrappers.removeAll(BuiltinFunctionWrappers.HASH_FUNCTIONS);
		applyGateWrappers.removeAll(BuiltinFunctionWrappers.ARRAY_FILL_POINTER_FUNCTIONS);
		applyGateWrappers.removeAll(BuiltinFunctionWrappers.APPLY_USING_FUNCTIONS);
		applyGateWrappers.remove(LispNames.PARSE_INTEGER);
		applyGateWrappers.remove(LispNames.READ_FROM_STRING);
		applyGateWrappers.remove(LispNames.SEQ_STRING);
		applyGateWrappers.remove(LispNames.SEQ_INT_VECTOR);
		applyGateWrappers.remove(LispNames.SEQ_FLOAT_VECTOR);
		// The injected wrapper bodies that are (apply f r) count too: the wrappers and
		// the runtime they call are gated on the same reference (see wrapperExcludes).
		// So do the functions of a generated java: interface implementation
		// (JvmJavaImplementations), which Java code calls back.
		boolean javaCallsBack = javaSites != null && javaSites.needsApply(program);
		boolean usesApplyRuntime = usesEval || LispMacroExpander.needsApplyRuntime(program, applyGateWrappers)
				|| usesApplyingWrapperValue || forcedGroups.contains(GROUP_APPLY) || javaCallsBack;
		// parse-integer / read-from-string wrappers reference runtime helpers that are
		// emitted only when the program itself uses the operator (_parseInt; the reader
		// runtime). Exclude each wrapper unless the program references the symbol, so the
		// wrapper and its helper stay gated together.
		Set<String> wrapperExcludes = new HashSet<>();
		if (!programUsesSymbol(program, LispNames.PARSE_INTEGER)) {
			wrapperExcludes.add(LispNames.PARSE_INTEGER);
		}
		if (!(programUsesSymbol(program, LispNames.READ) || programUsesSymbol(program, LispNames.READ_FROM_STRING)
				|| programUsesSymbol(program, LispNames.LOAD))) {
			wrapperExcludes.add(LispNames.READ_FROM_STRING);
		}
		// With the eval runtime on, a runtime funcall designator -- (eval '(funcall)), a
		// read or interned FUNCALL -- resolves through _lookup like any wrapper name, and
		// the _apply its body calls is there.
		if (!usesFuncallValue && !usesEval) {
			wrapperExcludes.add(LispNames.FUNCALL);
		}
		// The map*/every/some family, gated as a whole rather than on each name: with
		// neither the eval runtime nor a reachable wrapper reference nothing can reach a
		// wrapper the program does not spell (and its body would call an _apply that is
		// not there), while with eval ON an eval'd (mapcar ...) resolves the name through
		// _lookup and needs every wrapper registered. A wrapper reference alone brings
		// the apply tier the bodies call.
		if (!usesEval && !usesApplyingWrapperValue) {
			wrapperExcludes.addAll(BuiltinFunctionWrappers.APPLY_USING_FUNCTIONS);
		}
		// Hash-table wrappers reference helpers (JvmHashRuntimeBuilder) emitted only when
		// the program uses a hash table; gate the whole group together.
		if (!usesHashTables) {
			wrapperExcludes.addAll(BuiltinFunctionWrappers.HASH_FUNCTIONS);
		}
		// Fill-pointer array wrappers reference the array runtime helpers
		// (JvmArrayRuntimeBuilder), emitted only when the program uses an array
		// operator; gate the group the same way.
		if (!(programUsesAnyArrayOp(program) || forcedGroups.contains(GROUP_ARRAYS))) {
			wrapperExcludes.addAll(BuiltinFunctionWrappers.ARRAY_FILL_POINTER_FUNCTIONS);
		}
		// %seq-string is the concatenate 'string argument normalizer, not a first-class
		// value: inject it exactly when a lowering will call it
		// (.kb/concatenate-result-families.md).
		if (!usesSeqString) {
			wrapperExcludes.add(LispNames.SEQ_STRING);
		}
		// %seq-int-vector is the concatenate packed-vector builder, gated the same way,
		// and %seq-float-vector is its float twin on its own gate.
		if (!usesSeqIntVector) {
			wrapperExcludes.add(LispNames.SEQ_INT_VECTOR);
		}
		if (!usesSeqFloatVector) {
			wrapperExcludes.add(LispNames.SEQ_FLOAT_VECTOR);
		}
		// #'error/#'cerror/#'signal/#'warn wrappers forward the datum only (lite), and
		// #'format renders via the runtime control renderer; inject each only when the
		// program takes the operator as a first-class value. Condition :report lambdas
		// live only in the class registry (define-condition is rewritten out of the
		// program) but are re-injected by the error/signal expansions, so they count as
		// references too.
		// One walk for every gated operator (a condition's :report lambda counts: it
		// lives
		// only in the registry, but the error/signal expansions inject it back).
		Set<String> takenAsValues = BuiltinFunctionWrappers.functionValueNames(program);
		takenAsValues.addAll(BuiltinFunctionWrappers.functionValueNames(closRegistry.conditionReports().values()));
		// (setf (apply #'aref ...) ...) / (setf (apply #'svref ...) ...) lowers lazily,
		// during codegen (JvmExprCompiler's SETF case), to a (function
		// array-row-major-index) reference no scan of the surface program above can see
		// coming -- the scan has to be told (todo a66), the same way restartMode and
		// mayCreateInstances are told about their own lazy Pass-2 products.
		if (LispMacroExpander.usesSetfApplyArrayRowMajorIndex(program)) {
			takenAsValues.add(LispNames.ARRAY_ROW_MAJOR_INDEX);
		}
		for (String op : BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS) {
			if (!takenAsValues.contains(op)) {
				wrapperExcludes.add(op);
			}
		}
		// #'complex/#'conjugate/#'sqrt/#'phase wrappers call the gated _c* helpers, so
		// they are injected only when the program takes the operator as a
		// first-class value -- otherwise every program would carry a wrapper
		// calling a helper its gate left out (the widen-float-bits precedent in
		// BuiltinFunctionWrappers). The designator spelling counts, like the
		// reference gate above. #'upgraded-complex-part-type joins them: its body
		// probes (subtypep <var> 'real), which compiles to the gated
		// %subtypep-runtime. The cis/asinh/acosh/atanh wrappers join too: their
		// bodies call the gated _cu1 with the new selectors. So do log/asin/acos/expt:
		// their wrapper bodies take the argument from a PARAMETER, which no literal can
		// prove inside the real domain, so an ungated wrapper would open the complex
		// gate for every program in the world -- (print 1) included.
		Set<String> designated = BuiltinFunctionWrappers.functionDesignatorNames(program);
		designated.addAll(BuiltinFunctionWrappers.functionDesignatorNames(closRegistry.conditionReports().values()));
		for (String op : List.of(LispNames.COMPLEX, LispNames.CONJUGATE, LispNames.SQRT, LispNames.PHASE,
				LispNames.UPGRADED_COMPLEX_PART_TYPE, LispNames.CIS, LispNames.ASINH, LispNames.ACOSH, LispNames.ATANH,
				LispNames.LOG, LispNames.ASIN, LispNames.ACOS, LispNames.EXPT)) {
			if (!designated.contains(op)) {
				wrapperExcludes.add(op);
			}
		}
		// gethash/find-symbol/... publish their second value only in a program that
		// names them as a designator.
		Set<String> designatedProducers = BuiltinFunctionWrappers.designatedValueProducers(program,
				closRegistry.conditionReports().values());
		List<LispVal> wrappers = BuiltinFunctionWrappers.generate(userDefinedNames, wrapperExcludes);
		if (LispMacroExpander.declaresMvSpill(program)) {
			// A wrapper is a function body like any other: its tail settles the
			// multiple-value channel (the defuns' tails were settled by
			// injectMvSpillGlobal, which ran before the wrappers existed).
			wrappers = LispMacroExpander.settleWrapperLambdas(wrappers, designatedProducers);
		}
		// The injected runtime -- the wrappers and the shared sequence helpers below --
		// is compiled like the program, so a parameter of it named like a special binds
		// it too, and the dynamically-bound collection walks it beside the program.
		List<LispVal> injectedForms = new ArrayList<>();
		for (LispVal wrapper : wrappers) {
			inject(wrapper, defuns, injectedForms, specialVars);
		}
		// The shared merge sort, once per program that sorts -- from its own source or
		// from the #'sort wrapper just added, which is why this sits here beside the
		// other shared sequence helpers (.kb/sort.md). No array gate, unlike the two
		// below: its body is car/cdr/rplacd and a funcall of its predicate, so it pulls
		// nothing in. When it is absent JvmExprCompiler keeps the inline sort.
		if (!userDefinedNames.contains(LispNames.SORT_RUNTIME)
				&& (LispMacroExpander.programUsesSort(program) || LispMacroExpander.programUsesSort(wrappers))) {
			inject(LispMacroExpander.sortRuntimeWrapper(), defuns, injectedForms, specialVars);
		}
		// The shared copy-list, once per program naming copy-list (its own source or a
		// #'copy-list wrapper body), for the same reason as the sort above.
		if (!userDefinedNames.contains(LispNames.COPY_LIST_RUNTIME) && (LispMacroExpander.programUsesCopyList(program)
				|| LispMacroExpander.programUsesCopyList(wrappers))) {
			inject(LispMacroExpander.copyListRuntimeWrapper(), defuns, injectedForms, specialVars);
		}
		// The shared subseq dispatch, once per program that calls subseq -- from its own
		// source or from a wrapper body just added, which is why this is here and not in
		// expandTopLevelDefinitions (.kb/subseq-runtime.md). Gated on the array runtime
		// too, unlike the wasm backend: the helper's copy arm names aref/%aset, which is
		// what programUsesAnyArrayOp scans for, so injecting it into an array-free
		// program
		// would pull ~120 KB of array runtime into a class with no use for it. When the
		// gate is off JvmSubseqCompiler declines the rewrite anyway, so nothing calls it.
		//
		// The replace/fill/map-into runtimes sit beside it for the same reason (a
		// #'replace / #'fill wrapper body is a site of its own), and BEFORE it: their
		// bodies call subseq, so they count toward its gate
		// (.kb/sequence-op-runtimes.md).
		// The array gate covers them too -- each body's destructive arm names
		// aref/%row-major-aset -- and when it is off every one of their sites keeps the
		// inline lowering, so nothing calls the missing helper.
		boolean arrayGate = programUsesAnyArrayOp(program) || forcedGroups.contains(GROUP_ARRAYS);
		List<LispVal> seqOpHelpers = !arrayGate
				|| userDefinedNames.stream().anyMatch(LispMacroExpander.sequenceOpRuntimeNames()::contains) ? List.of()
						: LispMacroExpander.sequenceOpRuntimeWrappers(program, wrappers);
		for (LispVal helper : seqOpHelpers) {
			inject(helper, defuns, injectedForms, specialVars);
		}
		if (!userDefinedNames.contains(LispNames.SUBSEQ_RUNTIME) && arrayGate
				&& (LispMacroExpander.programUsesSubseq(program) || LispMacroExpander.programUsesSubseq(wrappers)
						|| LispMacroExpander.programUsesSubseq(seqOpHelpers))) {
			inject(LispMacroExpander.subseqRuntimeWrapper(), defuns, injectedForms, specialVars);
		}
		// The shared sequence-conversion trio, beside the subseq helper for the same
		// reason (most conversion sites live in the wrapper bodies just added). Gated on
		// the array runtime the same way: the trio's vector arms name aref/%aset/
		// make-array, so injecting it into an array-free program would pull the array
		// runtime into a class with no use for it. When the gate is off the compilers'
		// coerce case inlines the (vector-arm-free) dispatch as before, so nothing calls
		// the missing trio (.kb/seq-conversion-runtime.md).
		if (!userDefinedNames.contains(LispNames.SEQ_TO_LIST)
				&& (programUsesAnyArrayOp(program) || forcedGroups.contains(GROUP_ARRAYS))
				&& (LispMacroExpander.programUsesSeqConversion(program)
						|| LispMacroExpander.programUsesSeqConversion(wrappers))) {
			for (LispVal helper : LispMacroExpander.seqConversionWrappers()) {
				inject(helper, defuns, injectedForms, specialVars);
			}
		}
		// The shared sequence check every %check-sequence site calls: one vectorp for the
		// whole program instead of one per site. No array gate: its vectorp compiles to
		// the string test alone in an array-free class.
		if (!userDefinedNames.contains(LispNames.CHECK_SEQUENCE_RUNTIME)
				&& (LispMacroExpander.programUsesSequenceCheck(program)
						|| LispMacroExpander.programUsesSequenceCheck(wrappers)
						|| LispMacroExpander.programUsesSequenceCheck(seqOpHelpers))) {
			inject(LispMacroExpander.checkSequenceRuntimeWrapper(), defuns, injectedForms, specialVars);
		}

		// Collect top-level global variables and give each a dedicated static field.
		// A reference compiles to getstatic from any method body, so a global is
		// readable/assignable from a defun/lambda (not just from main). Field names are
		// prefixed to avoid colliding with runtime helper fields (e.g. _genv).
		SequencedSet<String> globals = new java.util.LinkedHashSet<>(GlobalVarCollector.collect(topLevelExprs));
		// A defun nested in a top-level defun's BODY needs the same store: it lowers to
		// (setq name (lambda ...)) like every other non-top-level defun, and a top-level
		// defun is not among topLevelExprs, so this is the one spelling collect() cannot
		// see.
		globals.addAll(GlobalVarCollector.collectNestedInDefunBodies(program));
		// Both spellings of a non-top-level defun, for the call sites: the function value
		// of such a name is only ever in its global variable, so a call and a #'name have
		// to reach the variable before the --dynamic late-binding fallback does.
		Set<String> nestedDefunNames = GlobalVarCollector.collectAllNestedDefunNames(program);
		// Special (dynamically bound) variables, collected before Pass 1. Each needs the
		// same global backing store (a let of a special save/restores over it), so union
		// them into the globals set before fields are minted; a let/let* of one of these
		// names becomes a dynamic binding rather than a lexical slot (JvmLetCompiler).
		if (usesThreads) {
			// make-thread's bindings alist names specials at runtime, and the canonical
			// consumer (clack's handler.lisp) binds the stream specials that way -- a
			// binding the static collector cannot see. Force them special so the
			// redirect machinery activates (the same state a source-level let-binding
			// would produce, .kb/standard-output-redirect.md).
			specialVars.add(LispNames.STANDARD_OUTPUT_VAR);
			specialVars.add(LispNames.STANDARD_INPUT_VAR);
			specialVars.add(LispNames.ERROR_OUTPUT_VAR);
		}
		globals.addAll(specialVars);
		// The shared name dispatches injected below are called only from the sites the
		// compiler lowers onto them, never through a designator: no dispatcher case
		// (registryFuncIds), so a program whose names resolve at run time does not
		// keep one alive that no reachable site calls.
		Set<String> callOnlyRuntimes = new HashSet<>();
		boolean usesSet = LispMacroExpander.programUsesSet(program) || LispMacroExpander.programUsesSet(injectedForms);
		// A computed symbol-value reads through the shared accessor injected with the
		// global set below (LispMacroExpander.dynamicFirstSymbolValue).
		boolean computedSymbolValue = LispMacroExpander.programUsesComputedSymbolValue(program)
				|| LispMacroExpander.programUsesComputedSymbolValue(injectedForms)
				|| LispMacroExpander.programUsesComputedSymbolValue(closRegistry.conditionReports().values());
		boolean symbolValueShared = computedSymbolValue && !specialVars.isEmpty()
				&& !LispMacroExpander.definesRuntimeName(userDefinedNames, LispNames.SYMBOL_VALUE_DYNAMIC);
		// The shared progv runtime, for the same reason here: a site hands its lists to
		// %progv-bind, whose per-name dispatch is over the FINAL special set. When it is
		// absent a site spells the loops inline
		// (LispMacroExpander.expandProgvForCompile).
		if (LispMacroExpander.programCallsProgv(program)
				&& !LispMacroExpander.definesRuntimeName(userDefinedNames, LispNames.PROGV_BIND_RUNTIME)
				&& !LispMacroExpander.definesRuntimeName(userDefinedNames, LispNames.PROGV_UNBIND_RUNTIME)) {
			for (LispVal definition : LispMacroExpander.progvRuntime(specialVars, usesEval)) {
				inject(definition, defuns, injectedForms, specialVars);
				callOnlyRuntimes.add(defuns.getLast().name);
			}
		}
		// *error-output*'s default is a stream VALUE, not a constant like the t the
		// other two standard stream variables hold, so a program that names it reads it
		// from a field seeded once: a read that built the value afresh would answer a new
		// object each time, and (eq *error-output* *error-output*) would be false.
		if (programUsesSymbol(program, LispNames.ERROR_OUTPUT_VAR)) {
			globals.add(LispNames.ERROR_OUTPUT_VAR);
		}
		// A name a function body or a top-level form assigns with no lexical binding in
		// scope is a global, as on the interpreter and in SBCL. Last, so a name already a
		// global (a special a local declaration proclaims) keeps its place and a program
		// with none its indices.
		globals.addAll(GlobalVarCollector.collectFreeAssigned(program));
		// The one dispatch every access of a global by name goes through -- a computed
		// symbol-value, a set, the eval runtime's variable lookup and assignment -- here
		// because its arms are the FINAL global set. A program that only reads gets the
		// arms of its specials alone. When it is absent a site spells its dispatch
		// inline (LispMacroExpander.dynamicFirstSymbolValue / expandSetForCompile) and
		// eval reaches the mirror alone. The eval runtime goes through it only in a
		// program that runs forms through eval: the runtime is also switched on by
		// boundp, symbol-value, fboundp (and on the JVM by the Java, Objective-C and FFI
		// bridges), which reach no variable through it.
		boolean evalRunsForms = programUsesEval(program) || usesLoad || this.dynamic;
		boolean accessorStores = usesSet || evalRunsForms;
		if ((accessorStores || symbolValueShared) && !globals.isEmpty()
				&& !LispMacroExpander.definesRuntimeName(userDefinedNames, LispNames.GLOBAL_ACCESS_RUNTIME)) {
			for (LispVal segment : LispMacroExpander.globalAccessRuntime(specialVars, globals, accessorStores)) {
				inject(segment, defuns, injectedForms, specialVars);
				callOnlyRuntimes.add(defuns.getLast().name);
			}
			if (symbolValueShared) {
				inject(LispMacroExpander.symbolValueDynamicRuntime(specialVars), defuns, injectedForms, specialVars);
				callOnlyRuntimes.add(defuns.getLast().name);
			}
			if (usesSet && !LispMacroExpander.definesRuntimeName(userDefinedNames, LispNames.SET_GLOBAL_RUNTIME)) {
				inject(LispMacroExpander.setGlobalRuntime(globals), defuns, injectedForms, specialVars);
				callOnlyRuntimes.add(defuns.getLast().name);
			}
		}
		Map<String, FieldRefEntry> globalFields = new HashMap<>();
		List<Utf8Entry> globalFieldNameUtfs = new ArrayList<>();
		Utf8Entry globalFieldDescUtf = cp.utf8Entry("Ljava/lang/Object;");
		for (String g : globals) {
			Utf8Entry fieldNameUtf = cp.utf8Entry("_g$" + mangleMethodName(g));
			globalFieldNameUtfs.add(fieldNameUtf);
			globalFields.put(g, cp.fieldRef(thisClass, fieldNameUtf, globalFieldDescUtf));
		}
		// Whether Lisp code of this program can run on a thread other than the one that
		// runs the program: a thread primitive's, an async body's or a served request's
		// (each a virtual thread of its own), and any thread a host or a library calls
		// in on -- through a jvm-export wrapper (a --no-main class has one; a war serves
		// through its handler), the java: bridge's Proxy, a generated java:
		// implementation's callback, an objc: method, block or main-thread hand-over, or
		// an ffi:callback's upcall. A generated implementation is predicted from the
		// forms and checked against the callbacks the attempt made (GROUP_OTHER_THREADS).
		boolean lispOnOtherThreads = usesThreads || usesAsyncSpawn || !exportDecls.isEmpty() || usesJavaBridge
				|| javaCallsBack || usesObjc || usesFfi || forcedGroups.contains(GROUP_OTHER_THREADS);
		// The %mv-spill channel: its _g$ field, or -- in a program that runs Lisp code on
		// more than one thread -- one register per thread (JvmMvChannel).
		FieldRefEntry mvSpillField = globalFields.get(LispNames.MV_SPILL);
		final @Nullable JvmMvChannel mvChannel = mvSpillField == null ? null : lispOnOtherThreads
				? JvmMvChannel.perThread(cp, thisClass, mvSpillField) : new JvmMvChannel(mvSpillField, null);
		// A special that is DYNAMICALLY BOUND somewhere is, in a program that runs Lisp
		// code on one thread only, a SHALLOW binding of its _g$ field -- saved, set, and
		// restored on every exit -- so every read stays one getstatic, in a closure as in
		// a callee (WASM's shape). Where another thread can run Lisp code it gets a
		// per-thread store instead (a _d$ ThreadLocal next to its _g$ global default), so
		// two threads binding the same special do not clobber each other and neither
		// sees the other's binding -- interpreter parity (its DynamicBindings is a
		// ThreadLocal for the same reason). A special never let-bound keeps the bare
		// static field either way, and a binding-free program compiles byte-identically.
		// The injected runtime is walked beside the program: it is compiled the same way.
		List<LispVal> compiledForms = new ArrayList<>(program);
		compiledForms.addAll(injectedForms);
		SequencedSet<String> boundSpecialVars = SpecialVarCollector.collectDynamicallyBound(compiledForms, specialVars);
		if (usesThreads) {
			// Every special becomes runtime-bindable by name through make-thread's
			// bindings alist, so each needs its _d$ ThreadLocal (the _dtl dispatch in
			// JvmThreadRuntimeBuilder). Over-collection is only a small read cost.
			boundSpecialVars.addAll(specialVars);
		}
		// The bound specials whose bound-ness the program probes and only a binding or
		// an assignment gives a value: their _g$ starts as the UNBOUND marker, so boundp
		// reads their variable instead of the eval mirror, which no binding writes
		// (JvmDynVarRuntimeBuilder). A computed boundp dispatches its name over them
		// through one shared runtime. Without the mirror, every global a literal boundp
		// names and no definer gives a value carries it the same way.
		List<LispVal> probedForms = new ArrayList<>(compiledForms);
		probedForms.addAll(closRegistry.conditionReports().values());
		SequencedSet<String> unboundSpecials = SpecialVarCollector.collectProbedValueless(probedForms, specialVars);
		unboundSpecials.retainAll(boundSpecialVars);
		SequencedSet<String> unboundGlobals = new java.util.LinkedHashSet<>(unboundSpecials);
		if (!usesEval && programUsesSymbol(program, LispNames.BOUNDP)) {
			unboundGlobals.addAll(GlobalVarCollector.collectProbedUnbound(probedForms, program, globals));
			LispMacroExpander.requireBoundpOffMirror(compiledForms, unboundGlobals);
		}
		if (!unboundSpecials.isEmpty() && LispMacroExpander.programUsesComputedBoundp(probedForms)
				&& !LispMacroExpander.definesRuntimeName(userDefinedNames, LispNames.BOUNDP_DYNAMIC)) {
			for (LispVal segment : LispMacroExpander.boundpDynamicRuntime(unboundSpecials, specialVars)) {
				inject(segment, defuns, injectedForms, specialVars);
				callOnlyRuntimes.add(defuns.getLast().name);
			}
		}
		final JvmDynVarRuntimeBuilder.@Nullable UnboundMarker unboundMarker = JvmDynVarRuntimeBuilder.unboundMarker(cp,
				thisClass, unboundGlobals, globalFields);
		final JvmDynVarRuntimeBuilder.@Nullable DynVarRuntime dynVarRuntime = boundSpecialVars.isEmpty()
				|| !lispOnOtherThreads ? null
						: JvmDynVarRuntimeBuilder.build(cp, thisClass, objectArrayClass, boundSpecialVars,
								unboundSpecials.isEmpty() ? null : Objects.requireNonNull(unboundMarker).field());

		// Assign funcIds and register in CP
		// The defuns whose tail calls to each other cycle: each such set is a tail group,
		// whose calls among themselves are jumps (JvmTailGroup).
		List<JvmTailGroup> defunGroups = JvmTailGroup.ofDefuns(defuns, specialVars);
		// The defuns whose method may answer a bounce, every caller of which checks the
		// result: a tail through a value, a direct tail call of such a defun, or a tail
		// group with one among its members -- a member's method may run any member's code
		// (JvmTailBounce). Each takes the value-tail depth after its parameters.
		Set<String> bouncingDefuns = JvmTailBounce.bouncingDefuns(defuns, defunGroups, specialVars);
		int[] nextFuncId = { 0 };
		Map<String, FunctionInfo> functions = new HashMap<>();
		for (DefunDecl defun : defuns) {
			int funcId = nextFuncId[0]++;
			boolean bounces = bouncingDefuns.contains(defun.name);
			String descriptor = "(" + "Ljava/lang/Object;".repeat(defun.paramNames.size()) + (bounces ? "I" : "")
					+ ")Ljava/lang/Object;";
			Utf8Entry nameUtf8 = cp.utf8Entry(mangleMethodName(defun.name));
			Utf8Entry descUtf8 = cp.utf8Entry(descriptor);
			MethodRefEntry methodref = cp.methodRef(thisClass, nameUtf8, descUtf8);
			functions.put(defun.name, new FunctionInfo(funcId, defun.paramNames.size(), defun.variadic, defun.optionals,
					false, methodref, nameUtf8, descUtf8, bounces));
		}
		Map<String, JvmTailGroup.Member> defunMembers = new HashMap<>();
		for (JvmTailGroup group : defunGroups) {
			for (JvmTailGroup.Member member : group.members()) {
				member.function = functions.get(member.name);
				defunMembers.put(member.name, member);
			}
		}

		final JvmHttpHandlerRuntimeBuilder.@Nullable HttpHandlerRuntime httpHandlerRuntime = usesHttpHandler
				? JvmHttpHandlerRuntimeBuilder.build(cp, thisClass, objectArrayClass, stringLength, stringConcat,
						am.ik.rontolisp.compiler.ClackEnv.usesBufferedBody(program))
				: null;
		// Validate the jvm-export directives now that every defun (and its mangled
		// method name) is known. Each names an existing, fixed-arity top-level defun;
		// each wrapper's Java name must be new in the class — a duplicate method name
		// (another export's, or a mangled defun's) is a ClassFormatError at LOAD time
		// otherwise (.kb/core-representation.md records the redefined-defun form of it).
		if (this.noMain && exportDecls.isEmpty()) {
			throw new UnsupportedOperationException("--no-main removes the only tree-shaker root an unexported"
					+ " program has, so it requires at least one (rontolisp:jvm-export ...) declaration");
		}
		Set<String> mangledDefunNames = new HashSet<>();
		for (String defunName : functions.keySet()) {
			mangledDefunNames.add(mangleMethodName(defunName));
		}
		Set<String> exportMethodNames = new HashSet<>();
		for (JvmExportDirective decl : exportDecls) {
			FunctionInfo target = functions.get(decl.name());
			if (target == null || !userDefinedNames.contains(decl.name())) {
				throw new UnsupportedOperationException(
						"rontolisp:jvm-export names an unknown function (must be a top-level defun): " + decl.name());
			}
			if (target.variadic) {
				throw new UnsupportedOperationException("rontolisp:jvm-export cannot export '" + decl.name()
						+ "': its lambda list takes &optional/&rest/&key arguments, which have no fixed Java"
						+ " signature");
			}
			if (decl.paramTypes().size() != target.paramCount) {
				throw new UnsupportedOperationException(
						"rontolisp:jvm-export arity mismatch for '" + decl.name() + "': declared "
								+ decl.paramTypes().size() + " params, but the function takes " + target.paramCount);
			}
			if (mangledDefunNames.contains(decl.methodName())) {
				throw new UnsupportedOperationException("rontolisp:jvm-export name '" + decl.methodName()
						+ "' collides with the method name of a defun; rename the export with :as");
			}
			if (!exportMethodNames.add(decl.methodName())) {
				throw new UnsupportedOperationException(
						"rontolisp:jvm-export name '" + decl.methodName() + "' is declared twice; rename one with :as");
			}
		}

		// Shared state for lambda discovery
		List<LambdaInfo> lambdaDecls = new ArrayList<>();
		Set<Integer> indirectCallArities = new HashSet<>();
		// Every funcId Pass 2 materializes as a first-class function value (see
		// Ctx.valueFuncIds), filled while the bodies are emitted and read below to size
		// the dispatchers and the name registry.
		Set<Integer> valueFuncIds = new HashSet<>();
		// Every literal spelling Pass 2 emits as a runtime value (see
		// Ctx.spelledLiterals). Filled while the bodies are emitted, read below by the
		// dispatch gate's name probes.
		Set<String> spelledLiterals = new HashSet<>();
		// The shapes a literal (apply #'f ... list) guarded at its call site; non-empty
		// is what makes _arityChk reachable (see Ctx.arityGuardShapes).
		Set<Integer> arityGuardShapes = new HashSet<>();
		// The built-in operators the wrong-count reports name (see Ctx.arityOperators).
		JvmArityOperators arityOperators = new JvmArityOperators();
		if (usesEval) {
			for (int arity = 0; arity <= JvmEvalRuntimeBuilder.MAX_CALLABLE_ARITY; arity++) {
				indirectCallArities.add(arity);
			}
		}
		// _async_run applies the body thunk through the arity-0 dispatcher and _await
		// applies async-lambda callbacks (rontolisp:then/catch/finally handlers,
		// http-handler dispatch, ...) through the arity-1 one, so their emission must
		// be forced whenever the async runtime is present.
		if (usesAsyncRuntime) {
			indirectCallArities.add(0);
			indirectCallArities.add(1);
		}
		// _thread_spawn's call() applies the thread function through the arity-0
		// dispatcher, so its emission must be forced whenever the thread runtime is
		// present.
		if (usesThreads) {
			indirectCallArities.add(0);
		}

		// Whether the program can produce a packed float array (a #d(...) literal or
		// make-array :element-type 'double-float). When true, the array op compilers
		// route through the _fv* dispatch helpers so a packed double[] and a general
		// ArrayList are both handled; when false the default build is byte-identical.
		// A runtime read can produce ANY datum -- #(...), #f(...), #d(...) -- so the
		// reader forces the array machinery on; without it a read vector would not
		// print or index correctly.
		// A declared :float-vector / :float-matrix boundary hands a packed float array to
		// a defun that may never build one itself (a library whose only contact with the
		// representation is aref/length over its argument), so the declaration forces the
		// packed float-array runtime on exactly as a #d(...) literal would.
		this.needsHandleRuntime = JvmExportRuntimeBuilder.needsFloatArray(exportDecls);
		// A served program calls the embedded server and the Clack glue, so those class
		// files travel with the output and it runs on a bare `java -cp .`.
		this.needsHttpRuntime = usesHttpHandler;
		// A fetching program calls the transport class, which travels the same way.
		this.needsFetchRuntime = usesFetch;
		// An equalp table folds its keys through RontoHashTable.equalpKey, and every
		// table's put/remove/count/values helpers call its tombstone machinery, so that
		// class travels with any hash-using output -- and with nothing else, since no
		// other program emits a call to it.
		this.needsHashTableRuntime = usesEqualpHashTables || usesHashTables;
		// widen-float-bits/narrow-float-bits (.todo/671) can touch a packed float array
		// and a packed (unsigned-byte 16) vector it received only as a parameter (never
		// a literal in THIS program's own AST, e.g. a reusable chunk-widening defun a
		// reader calls with tensors it built elsewhere) -- symbol use alone must force
		// both array gates on, the literal/make-array scan below cannot see through a
		// parameter.
		final boolean usesFloat16Bits = programUsesSymbol(program,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WIDEN_FLOAT_BITS))
				|| programUsesSymbol(program,
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.NARROW_FLOAT_BITS));
		// The block-quantized weight matrix (.kb/quantized-matrix.md): a program can hold
		// one only by naming a constructor -- quantize, or make-quantized-matrix (what
		// gguf.lisp's Q8_0 arm calls, kept by the pruner only when gguf:read is) -- so
		// the _qm* helpers, the byte[] arms of the _fv* helpers and the print branch are
		// emitted on that scan alone; dequantize and the two raw accessors compile to a
		// call-time signal without it (JvmQuantizedMatrixCompiler), and the packed float
		// tier the matrix dequantizes into is forced on with it.
		final boolean usesQuantized = programUsesSymbol(program,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.QUANTIZE))
				|| programUsesSymbol(program,
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MAKE_QUANTIZED_MATRIX));
		boolean usesFloatArray = programUsesFloatArray(program, closRegistry) || usesRead || this.needsHandleRuntime
				|| usesFloat16Bits || usesQuantized || usesSeqFloatVector;

		// Whether the program can produce a packed integer vector (a #N@(...) literal
		// or make-array :element-type '(unsigned-byte 8|16|32)). When true, the rank-1
		// array op compilers route through the _iv* dispatch helpers (which handle the
		// packed long[] and delegate any other shape down the fv/general chain); when
		// false the default build is byte-identical. The runtime reader does not read
		// #N@(...), so usesRead does not force this gate. The injected %seq-int-vector
		// wrapper allocates one, and it is not part of the scanned program, so its own
		// gate forces this one on.
		// A fetched reply's :body and a served request's :raw-body are OCTET streams
		// (their chunks long[] packed vectors built by the runtime, not by any scanned
		// make-array), so a program that fetches or serves may hold one and needs the
		// _iv* dispatch on.
		boolean usesIntArray = programUsesIntArray(program, closRegistry) || usesSeqIntVector || usesFetch
				|| usesHttpHandler || usesFloat16Bits;
		// The bulk binary transfer behind read-sequence / write-sequence over a packed
		// buffer (.kb/binary-sequence-io.md): emitted for a program that has both a
		// packed buffer to move and a sequence-I/O call to move it with -- the primitive
		// its expansion calls compiles to a declining nil otherwise, so an artifact
		// without either keeps its bytes.
		final boolean usesPackedSequenceIo = (usesFloatArray || usesIntArray)
				&& (programUsesSymbol(program, LispNames.READ_SEQUENCE)
						|| programUsesSymbol(program, LispNames.WRITE_SEQUENCE)
						|| programUsesSymbol(program, LispNames.READ_SEQUENCE_RAW_INTERNAL)
						|| programUsesSymbol(program, LispNames.WRITE_SEQUENCE_RAW_INTERNAL));
		// The bulk CHARACTER transfer behind read-sequence over a character buffer
		// (.kb/character-sequence-io.md): emitted for a program that reads a sequence at
		// all -- a character buffer needs no array gate to arrive, since a read-sequence
		// can be handed one as a parameter. Over-emitting costs bytes and under-emitting
		// costs speed, so neither direction can be wrong about behavior.
		final boolean usesCharSequenceIo = programUsesSymbol(program, LispNames.READ_SEQUENCE)
				|| programUsesSymbol(program, LispNames.READ_SEQUENCE_RAW_INTERNAL);

		// Whether the array runtime helper group is emitted (the same test that gates
		// its emission below). The mutable-character-vector consumers -- the _eqv
		// normalization, the stringp extension, the per-site _strv calls and the print
		// branch -- all key off this one gate, so an array-free program compiles
		// byte-identically to a build that never knew character vectors.
		// forcedGroups carries the verdict of a previous run whose source scan
		// under-predicted this gate (see compile(List)); it never turns the gate OFF.
		boolean usesArrays = programUsesAnyArrayOp(program) || usesFloatArray || usesIntArray
				|| forcedGroups.contains(GROUP_ARRAYS);
		// Whether any make-array in the program asks for an element type narrower than t,
		// or any array literal carries a remembered one (#*1011 is stamped bit) -- the
		// only ways a general array can carry a remembered element type, and so the
		// gate on array-element-type's general arm.
		final boolean usesTypedArray = usesArrays
				&& LispMacroExpander.makeArrayElementTypeCodes(program, closRegistry) != 0;
		MethodRefEntry strvMethod = usesArrays
				? cp.methodRef(thisClass, JvmArrayRuntimeBuilder.STRV, JvmArrayRuntimeBuilder.STRV_DESC) : null;
		if (javaSites != null) {
			// A dispatched java: site renders a mutable character vector, a sequence's
			// elements too, before it costs and converts it -- as does the conversion
			// of a value a java: interface implementation's function answers.
			javaSites.direct().strv(strvMethod != null ? strvMethod : null);
			// ... and reads a specialized vector's elements from the shapes the program
			// can hold, a bfloat16 one's through the program's own widening.
			javaSites.direct()
				.packedVectors(usesFloatArray, usesIntArray,
						usesFloatArray
								? cp.methodRef(thisClass, JvmFloatArrayRuntimeBuilder.BF16_VALUE,
										JvmFloatArrayRuntimeBuilder.BF16_VALUE_DESC)

								: null);
		}
		// Numeric runtime helpers (long arithmetic with automatic BigInteger promotion)
		// The interned layout array of an instance -- the discriminator the structural
		// _equal and _hash arms share, minted once so both see the same constant.
		ClassEntry instanceLayoutClass = mayUseInstances ? cp.classEntry("[Ljava/lang/String;") : null;
		// Complex numbers (.kb/jvm-complex.md): the _c* helpers are emitted only
		// when the program may create a complex -- a #C literal, a
		// complex/conjugate call, or a sqrt, which can root a negative into the
		// plane (cis/asinh/acosh/atanh join the sqrt case: their real arms run
		// through _cu1 and can cross into the plane). log/asin/acos/expt cross too,
		// but only for SOME arguments, so they are read through
		// mayEscapeToComplex -- the same predicate their call sites steer on, so a
		// literal that proves the escape unreachable ((log 2), (expt x 2),
		// (expt 10.0 n)) leaves the gate shut. forcedGroups carries the verdict of a
		// previous run whose scan under-predicted this gate (see compile(List)); it
		// never turns the gate OFF. The holder travels exactly then
		// (needsComplexRuntime below), so a complex-free program keeps its
		// single-file output. The scan is the one the WASM backend gates its holder
		// arms on (compiler/ComplexCapability).
		boolean usesComplex = am.ik.rontolisp.compiler.ComplexCapability.mayObserveComplex(program, closRegistry)
				|| forcedGroups.contains(GROUP_COMPLEX);
		this.needsComplexRuntime = usesComplex;
		// The holder-presence probe (.todo/757): a class the gate opened can still
		// run where its travelling RontoComplex.class file is absent (a lone
		// .class in a bare directory) when the program never observes a complex --
		// the gate over-approximates (dead sqrt arms in an unpruned splice keep it
		// open, and callers the dispatchers keep alive defeat a reachability
		// re-check), so every holder TEST consults this flag first and takes its
		// holder-less shape when the class did not load. Exact, not heuristic: no
		// holder instance can exist without its class. Only the constructor paths
		// (the _c* helpers) keep hard links -- building a complex without its
		// class is a genuinely missing file. Minted here, ahead of the constant
		// pool write, so every site below shares the deduplicated entries.
		final Utf8Entry hasComplexName = usesComplex ? cp.utf8Entry("_hasComplex") : null;
		final Utf8Entry hasComplexDesc = usesComplex ? cp.utf8Entry("Z") : null;
		final FieldRefEntry hasComplexField = usesComplex
				? cp.fieldRef(thisClass, Objects.requireNonNull(hasComplexName), Objects.requireNonNull(hasComplexDesc))
				: null;
		final MethodRefEntry hasComplexProbe = usesComplex
				? cp.methodRef(cp.classEntry("java/lang/Class"), "forName", "(Ljava/lang/String;)Ljava/lang/Class;")
				: null;
		final StringEntry hasComplexTarget = usesComplex ? cp.stringEntry("am.ik.rontolisp.runtime.RontoComplex")
				: null;
		final ClassEntry hasComplexAbsent = usesComplex ? cp.classEntry("java/lang/ClassNotFoundException") : null;
		// A java: program compares a host object by identity under eql and by equals
		// under equal, handing equals a Lisp value as Java sees it; the shared test and
		// conversion are built on first use, so a program without java: names neither.
		JvmNumericRuntimeBuilder.NumericRuntime numericRuntime = JvmNumericRuntimeBuilder.build(cp, thisClass,
				strvMethod, instanceLayoutClass, usesComplex, javaSites != null ? javaSites.direct().host() : null,
				javaSites != null ? javaSites.direct().receiver() : null);
		// A wrong-type operand's report names the operator (JvmOperandTypeRuntime); the
		// thread-local record a pad reads the datum from exists only when a pad does.
		final Utf8Entry teTlName = hasLandingPad ? cp.utf8Entry(JvmOperandTypeRuntime.TL_FIELD) : null;
		final Utf8Entry teTlDesc = hasLandingPad ? cp.utf8Entry(JvmOperandTypeRuntime.TL_DESC) : null;
		final FieldRefEntry teTlField = teTlName != null && teTlDesc != null
				? cp.fieldRef(thisClass, teTlName, teTlDesc) : null;
		// What tells a cons from the other Object[]-shaped values this program can build,
		// shared by every cons-walking runtime helper.
		final JvmOperandTypeRuntime.ConsShape consShape = JvmOperandTypeRuntime.ConsShape.of(cp, instanceLayoutClass,
				usesAsyncRuntime);
		numericRuntime.methods().addAll(JvmOperandTypeRuntime.build(cp, thisClass, teTlField, consShape));
		for (String[] check : new String[][] { { JvmOperandTypeRuntime.CAR, JvmOperandTypeRuntime.FIELD_DESC },
				{ JvmOperandTypeRuntime.CDR, JvmOperandTypeRuntime.FIELD_DESC },
				{ JvmOperandTypeRuntime.ENDP, JvmOperandTypeRuntime.FIELD_DESC },
				{ JvmOperandTypeRuntime.IS_CONS, JvmOperandTypeRuntime.IS_CONS_DESC },
				{ JvmOperandTypeRuntime.CK_IDX, JvmOperandTypeRuntime.CK_IDX_DESC },
				{ JvmOperandTypeRuntime.CK_BOUND_J, JvmOperandTypeRuntime.CK_BOUND_J_DESC },
				{ JvmOperandTypeRuntime.CK_RAT, JvmOperandTypeRuntime.CK_RAT_DESC },
				{ JvmOperandTypeRuntime.CK_TAB, JvmOperandTypeRuntime.CK_IDX_DESC },
				{ JvmOperandTypeRuntime.CK_CHR, JvmOperandTypeRuntime.CK_IDX_DESC },
				{ JvmOperandTypeRuntime.CK_RADIX, JvmOperandTypeRuntime.CK_RADIX_DESC },
				{ JvmOperandTypeRuntime.CK_LIST, JvmOperandTypeRuntime.FIELD_DESC },
				{ JvmOperandTypeRuntime.CK_CONS, JvmOperandTypeRuntime.CK_CONS_DESC } }) {
			numericRuntime.ops().put(check[0], JvmOperandTypeRuntime.self(cp, thisClass, check[0], check[1]));
		}
		if (teTlField != null) {
			numericRuntime.ops()
				.put(JvmOperandTypeRuntime.TE_SLOT, JvmOperandTypeRuntime.self(cp, thisClass,
						JvmOperandTypeRuntime.TE_SLOT, JvmOperandTypeRuntime.TE_SLOT_DESC));
		}
		final JvmOperandTypeRuntime.Wrappers operandTypeWrappers = new JvmOperandTypeRuntime.Wrappers(cp, thisClass,
				numericRuntime.methods());
		// subseq's refusal is recorded as a type-error only where a pad can catch it.
		final JvmOperandTypeRuntime.SubseqRuntime subseqRuntime = new JvmOperandTypeRuntime.SubseqRuntime(cp, thisClass,
				teTlField, numericRuntime.methods());
		final JvmComplexRuntimeBuilder.@Nullable ComplexRuntime complexRuntime = usesComplex
				? JvmComplexRuntimeBuilder.build(cp, thisClass) : null;

		// --vec: emit the Vector API acceleration bridge only when the program actually
		// references one of the six accelerated vec: kernels (directly or via a spliced
		// mean/norm body). Off by default, so the ordinary scalar vec.lisp is used. The
		// bridge is a self-contained class shipped beside the program (like the java:
		// interop bridge); the
		// packed float-array _fv* helpers still render/index its double[] results.
		boolean usesSimd = this.simdAccel && programUsesAnyAcceleratedSimdOp(program);
		final JvmSimdRuntimeBuilder.@Nullable SimdRuntime simdRuntime = usesSimd
				? JvmSimdRuntimeBuilder.build(cp, thisClass, this.parallelAccel, this.className) : null;
		if (simdRuntime != null) {
			this.bridgeClassFiles.putAll(simdRuntime.classFiles());
		}

		// --blas: emit the CBLAS bridge only when the program actually reaches a matrix
		// product the library takes -- linalg:dot (directly, or through the spliced
		// linalg:matmul / linalg:solve bodies, which call it themselves and are part of
		// the program by the time this scan runs), or either vec: GEMV form. The gate has
		// to name all three: the numeric examples are vec: programs and never mention
		// linalg:dot, so a gate on the product alone would embed no bridge for exactly
		// the programs the flag is for. Orthogonal to --simd: neither implies the other,
		// and a build with both emits both bridges.
		boolean usesBlas = false;
		if (this.blasAccel) {
			for (String member : JvmLinalgBlas.qualifiedMembers()) {
				usesBlas = usesBlas || programUsesSymbol(program, member);
			}
		}
		final JvmBlasRuntimeBuilder.@Nullable BlasRuntime blasRuntime = usesBlas
				? JvmBlasRuntimeBuilder.build(cp, this.className) : null;
		if (blasRuntime != null) {
			this.bridgeClassFiles.putAll(blasRuntime.classFiles());
		}

		// --gpu: the same gate over its own members -- the matrix by matrix case of
		// linalg:dot, the STACKED rank->=3 product behind linalg:matmul, and the twelve
		// element-wise ufuncs whose scalar cost is a libm call -- and the same
		// orthogonality. What it ships is not one template but am.ik.gpu itself, renamed
		// after this class (JvmGpuRuntimeBuilder). The gate has to name every
		// member: a transformer reaches only the stacked product and the ufuncs, so a
		// gate on dot alone would embed no bridge for exactly the program the flag is
		// for.
		boolean usesGpu = false;
		if (this.gpuAccel) {
			for (String member : JvmLinalgGpu.qualifiedMembers()) {
				usesGpu = usesGpu || programUsesSymbol(program, member);
			}
		}
		final JvmGpuRuntimeBuilder.@Nullable GpuRuntime gpuRuntime = usesGpu
				? JvmGpuRuntimeBuilder.build(cp, thisClass, stringConcat, this.className) : null;
		if (gpuRuntime != null) {
			this.bridgeClassFiles.putAll(gpuRuntime.classFiles());
		}

		// The geom: kernels: no flag in front of them (the interpreter's natives have
		// none either -- nothing here reassociates, .kb/geom.md), so the gate is the
		// CALL SITE. The scan runs over the program the library splice and
		// LibraryDefunPruner have already produced, so a program that never calls
		// read-obj / mesh / wireframe / %vertex-extremes carries no bridge and is
		// emitted byte for byte as before.
		boolean usesGeom = false;
		// --dynamic is excluded twice over: it skips LibraryDefunPruner (so the scan
		// would see the whole spliced library and the gate would be a splice gate), and
		// its whole point is that a call site honours a definition replaced at run time,
		// which a kernel emitted over the defun would not.
		if (this.geomKernels && !this.dynamic) {
			for (String member : JvmGeomKernelCompiler.members()) {
				usesGeom = usesGeom || programUsesSymbol(program, member);
			}
		}
		final JvmGeomRuntimeBuilder.@Nullable GeomRuntime geomRuntime = usesGeom
				? JvmGeomRuntimeBuilder.build(cp, thisClass, this.className) : null;
		if (geomRuntime != null) {
			this.bridgeClassFiles.putAll(geomRuntime.classFiles());
		}

		// Integer expression-tree fusion (.kb/jvm-int-fusion.md): the shared registry
		// of outlined fused-site methods, plus the fusion-inlinable defuns -- uniquely
		// defined one-liner integer wrappers (mod32+/rol32) whose bodies substitute
		// into fused trees. Never under --dynamic (late binding must keep observing
		// redefinition); the whole feature is a speed-for-size trade --optimize=size
		// declines.
		boolean intFusion = !this.optimize.prefersSizeOverSpeed();
		// The unboxed dual representation for a promoted top-level global
		// (.kb/jvm-int-fusion.md): a raw long field and an int flag beside the _g$ field,
		// which stays the boxed shadow. Same gate as the local version, plus the seams a
		// local does not have -- an eval runtime that mirrors the BOX, and anything
		// concurrent (three fields where there was one).
		SequencedSet<String> rawGlobalExcluded = new java.util.LinkedHashSet<>(boundSpecialVars);
		rawGlobalExcluded.addAll(unboundGlobals);
		Set<String> rawGlobalNames = JvmRawGlobals.collect(program, globals, rawGlobalExcluded, intFusion
				&& !this.dynamic && !usesEval && !usesThreads && !usesHttpHandler && !usesAsyncRuntime && !usesSockets);
		Map<String, JvmIntFusionCompiler.RawLocal> rawGlobals = new HashMap<>();
		List<Utf8Entry> rawGlobalLongFieldNameUtfs = new ArrayList<>();
		List<Utf8Entry> rawGlobalFlagFieldNameUtfs = new ArrayList<>();
		Utf8Entry rawGlobalLongDescUtf = rawGlobalNames.isEmpty() ? null : cp.utf8Entry("J");
		Utf8Entry rawGlobalFlagDescUtf = rawGlobalNames.isEmpty() ? null : cp.utf8Entry("I");
		for (String g : rawGlobalNames) {
			Utf8Entry longNameUtf = cp.utf8Entry("_gr$" + mangleMethodName(g));
			Utf8Entry flagNameUtf = cp.utf8Entry("_gk$" + mangleMethodName(g));
			rawGlobalLongFieldNameUtfs.add(longNameUtf);
			rawGlobalFlagFieldNameUtfs.add(flagNameUtf);
			rawGlobals.put(g,
					JvmIntFusionCompiler.RawLocal.fields(
							cp.fieldRef(thisClass, longNameUtf, Objects.requireNonNull(rawGlobalLongDescUtf)),
							Objects.requireNonNull(globalFields.get(g)),
							cp.fieldRef(thisClass, flagNameUtf, Objects.requireNonNull(rawGlobalFlagDescUtf))));
		}
		JvmIntFusionCompiler.State fusedState = new JvmIntFusionCompiler.State(this.className);
		Map<String, DefunDecl> inlinableDefuns = new HashMap<>();
		if (intFusion && !this.dynamic) {
			for (DefunDecl defun : defuns) {
				if (!multiplyDefinedDefuns.contains(defun.name) && JvmIntFusionCompiler.isInlinableDefun(defun)) {
					inlinableDefuns.put(defun.name, defun);
				}
			}
		}

		// Reusable builder template with shared constants and state
		Ctx.Builder ctxBuilder = Ctx.builder()
			.intFusion(intFusion)
			.rawGlobals(rawGlobals)
			.inlinableDefuns(inlinableDefuns)
			.fusedState(fusedState)
			.cp(cp)
			.numOps(numericRuntime.ops())
			.operandTypeWrappers(operandTypeWrappers)
			.subseqRuntime(subseqRuntime)
			.mathOps(mathOps)
			.systemOps(systemOps)
			.systemOut(systemOut)
			.printlnStr(printlnStr)
			.lispToString(lispToStringMethod)
			.printStr(printStr)
			.printlnVoid(printlnVoid)
			.lispToDisplayString(lispToDisplayStringMethod)
			.longClass(longClass)
			.longValueOf(longValueOf)
			.longValue(longValue)
			.objectClass(objectClass)
			.objectArrayClass(objectArrayClass)
			.integerClass(integerClass)
			.integerValueOf(integerValueOf)
			.integerValue(integerValue)
			.doubleClass(doubleClass)
			.doubleValueOf(doubleValueOf)
			.numberClass(numberClass)
			.numberDoubleValue(numberDoubleValue)
			.stringClass(stringClass)
			.stringCharAt(stringCharAt)
			.functions(functions)
			.lambdaDecls(lambdaDecls)
			.indirectCallArities(indirectCallArities)
			.valueFuncIds(valueFuncIds)
			.arityGuardShapes(arityGuardShapes)
			.arityOperators(arityOperators)
			.spelledLiterals(spelledLiterals)
			.nextFuncId(nextFuncId)
			.appendMethod(appendMethod)
			.mathAbsLong(mathAbsLong)
			.mathAbsDouble(mathAbsDouble)
			.mathFloor(mathFloor)
			.mathCeil(mathCeil)
			.mathRint(mathRint)
			.objectEquals(objectEquals)
			.readLineHelper(readLineHelperMethod)
			.fetchHelper(fetchHelperMethod)
			.awaitHelper(awaitHelperMethod)
			.asyncRunHelper(asyncRunHelperMethod)
			.futurepHelper(futurepHelperMethod)
			.streampHelper(streampHelperMethod)
			.makeStreamHelper(makeStreamHelperMethod)
			.streamNewHelper(streamNewHelperMethod)
			.streamReadHelper(streamReadHelperMethod)
			.streamWriteHelper(streamWriteHelperMethod)
			.streamCloseHelper(streamCloseHelperMethod)
			.drainBodyHelper(drainBodyHelperMethod)
			.waitForHelper(waitForHelperMethod)
			.flushStreams(flushStreamsMethod)
			.tcpConnectHelper(tcpConnectHelperMethod)
			.tcpListenHelper(tcpListenHelperMethod)
			.tcpAcceptHelper(tcpAcceptHelperMethod)
			.tcpLocalPortHelper(tcpLocalPortHelperMethod)
			.tcpLocalAddressHelper(tcpLocalAddressHelperMethod)
			.tcpPeerAddressHelper(tcpPeerAddressHelperMethod)
			.tcpPeerPortHelper(tcpPeerPortHelperMethod)
			.tcpSetTimeoutHelper(tcpSetTimeoutHelperMethod)
			.tlsConnectHelper(tlsConnectHelperMethod)
			.tlsUpgradeHelper(tlsUpgradeHelperMethod)
			.tlsListenHelper(tlsListenHelperMethod)
			.tlsListenP12Helper(tlsListenP12HelperMethod)
			.httpHandlerRuntime(httpHandlerRuntime)
			.javaOps(javaRuntime != null ? javaRuntime.ops() : null)
			.javaSites(javaSites)
			.objcOps(objcRuntime != null ? objcRuntime.ops() : null)
			.ffiOps(ffiRuntime != null ? ffiRuntime.ops() : null)
			.dynamic(this.dynamic)
			.servletMode(this.servletMode)
			.blockExitChannel(blockExitChannel)
			.restartMode(restartMode)
			.signalClauseMatch(signalClauseMatch)
			.printControls(printControls)
			.printControlVariables(printControlVariables)
			.usesFloatArray(usesFloatArray)
			.usesQuantized(usesQuantized)
			.typedLoops(!this.optimize.prefersSizeOverSpeed())
			.usesIntArray(usesIntArray)
			.usesTypedArray(usesTypedArray)
			.usesPackedSequenceIo(usesPackedSequenceIo)
			.usesCharSequenceIo(usesCharSequenceIo)
			.usesArrays(usesArrays)
			.usesHashTables(usesHashTables)
			.usesEqualpHashTables(usesEqualpHashTables)
			.usesIdentityHashTables(usesIdentityHashTables)
			.usesSeqString(usesSeqString)
			.mutableStringProducers(mutableStringProducers)
			.mayUseInstances(mayUseInstances)
			.usesComplex(usesComplex)
			.hasLandingPad(hasLandingPad)
			.usesSynonymStreams(programUsesSymbol(program, LispNames.MAKE_SYNONYM_STREAM))
			.usesStreamValues(usesStreamValues)
			.asksStreamDirection(programUsesSymbol(program, LispNames.INPUT_STREAM_P)
					|| programUsesSymbol(program, LispNames.OUTPUT_STREAM_P))
			.mayUseAsyncValues(usesAsyncRuntime)
			.simdOps(simdRuntime != null ? simdRuntime.ops() : null)
			.blasOps(blasRuntime != null ? blasRuntime.ops() : null)
			.gpuOps(gpuRuntime != null ? gpuRuntime.ops() : null)
			.geomOps(geomRuntime != null ? geomRuntime.ops() : null)
			.className(this.className)
			.userDefunNames(Set.copyOf(userDefinedNames))
			.builtinShapedDefuns(builtinShapedDefuns)
			.warnedClRedefinitions(new HashSet<>())
			.usesFmakunbound(programUsesSymbol(program, LispNames.FMAKUNBOUND))
			.usesRuntimePackages(packageResolver.runtimePackagesMutable())
			.packageTable(packageResolver.runtimePackageTable())
			.packageUseTable(packageResolver.runtimePackageUseTable())
			.bakedSymbolAccess(symbolAccess)
			.symbolPrintTable(symbolPrintTable)
			.globals(globals)
			.nestedDefunNames(nestedDefunNames)
			.specialVars(specialVars)
			.globalFields(globalFields)
			.mvChannel(mvChannel)
			.dynVars(dynVarRuntime)
			.threadScopedSpecials(lispOnOtherThreads)
			.unboundMarker(unboundMarker)
			.structAccessors(structAccessors)
			.closRegistry(closRegistry);

		// When eval is present, a top-level global variable binding (setq/defvar/...) is
		// mirrored into the eval runtime's global environment via _store, so an eval'd
		// expression can resolve it. Created before Pass 2a because EVERY context gets
		// the ref: the progv lowering maintains that same mirror from any position
		// (its own consumers stay top-level-gated).
		MethodRefEntry evalStoreRef = usesEval ? cp.methodRef(thisClass, "_store",
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") : null;

		// Pass 2a: Compile each defun body
		List<Ctx> funcCtxs = new ArrayList<>();
		for (DefunDecl defun : defuns) {
			Ctx funcCtx = ctxBuilder.build();
			funcCtx.evalStoreRef = evalStoreRef;
			// A defun bounces, and hands a callee's bounce on, only where its callers
			// unwrap: its registry entry says so (JvmTailBounce).
			boolean bounces = Objects.requireNonNull(functions.get(defun.name)).bounceVisible();
			funcCtx.tailBounce = bounces;
			funcCtx.passesBounces = bounces;
			funcCtx.openFunction(JvmSourceSites.reportedName(defun.name), null, defun.bodyExprs);
			funcCtx.nextLocal = defun.paramNames.size();
			for (int i = 0; i < defun.paramNames.size(); i++) {
				funcCtx.locals.put(defun.paramNames.get(i), i);
			}
			if (bounces) {
				// The value-tail depth, after the parameters (JvmTailBounce).
				funcCtx.depthSlot = funcCtx.nextLocal++;
			}
			funcCtx.maxLocals = funcCtx.nextLocal;
			// Determine which params are captured by nested lambdas
			Set<String> capturedVars = FreeVarAnalyzer.findCapturedVars(defun.bodyExprs,
					new HashSet<>(defun.paramNames), functions.keySet(), funcCtx.captureMemo);
			funcCtx.boxedVars = capturedVars;
			// The body-head float declarations (behind the sole %fn-block/block wrapper
			// too) route the body's arithmetic onto the unboxed IEEE path
			// (.kb/jvm-double-arithmetic.md); parameters stay boxed Object slots and
			// read through the strict cast. Specials are never registered.
			Set<String> funcDeclaredDoubles = new HashSet<>(am.ik.rontolisp.compiler.DeclaredScalarTypes
				.functionBodyDeclaredDoubles(defun.bodyExprs, closRegistry));
			funcDeclaredDoubles.removeAll(specialVars);
			funcCtx.declaredDoubles = funcDeclaredDoubles.isEmpty() ? Set.of() : funcDeclaredDoubles;
			// The head a self tail call jumps back to, ahead of the boxing below so each
			// round boxes its own cells; it costs nothing unless one does
			// (JvmSelfTailCall).
			funcCtx.selfLoop = JvmSelfTailCall.defun(funcCtx, Objects.requireNonNull(functions.get(defun.name)));
			// A member of a tail group: its tail calls to the other members jump
			// (JvmTailGroup).
			JvmTailGroup.Member tailMember = defunMembers.get(defun.name);
			funcCtx.tailMember = tailMember;
			// Box captured params
			for (String paramName : defun.paramNames) {
				if (capturedVars.contains(paramName)) {
					Integer slot = funcCtx.locals.get(paramName);
					if (slot != null) {
						JvmEmitHelper.emitBoxLocal(funcCtx, slot);
					}
				}
			}
			try {
				if (defun.bodyExprs.isEmpty()) {
					// (defun f ()) -- an empty body answers nil, per CL (dissect's
					// no-op interface stubs are this shape).
					JvmExprCompiler.compileExpr(LispNil.INSTANCE, funcCtx, this.className);
				}
				// Emitted through the tail-spine driver, which splits the body into
				// continuation methods if it would cross HotSpot's HugeMethodLimit
				// (JvmBodyOutliner); a body that stays under it is emitted exactly as
				// the plain loop this replaced did.
				JvmBodyOutliner.compileFunctionBody(defun.bodyExprs, funcCtx, this.className);
				// Inside the try so an underflow here (a valueless body) still reports
				// WHICH defun it was.
				funcCtx.body.areturn();
			}
			catch (UnsupportedOperationException ex) {
				// Keep the type: callers (and tests) distinguish an unsupported form
				// from an emitter invariant violation.
				throw new UnsupportedOperationException("while compiling defun " + defun.name + ": " + ex.getMessage(),
						ex);
			}
			catch (RuntimeException ex) {
				throw new IllegalStateException("while compiling defun " + defun.name + ": " + ex.getMessage(), ex);
			}
			funcCtxs.add(funcCtx);
			if (tailMember != null) {
				tailMember.ctx = funcCtx;
			}
		}

		// Pass 2b: Compile top-level expressions into one or more void helper methods
		// (_top$0, _top$1, ...) that main() invokes in order. A single method's bytecode
		// must stay under the JVM's 64 KB Code limit, so a new chunk is started whenever
		// the current one nears that ceiling; the per-method limit then bounds a single
		// chunk rather than the whole program. All chunks share the same static fields
		// (globals) and methods (defuns/lambdas), and any cross-form variable is a global
		// field (see the global-promotion step above), so the split preserves the
		// single-shared-runtime, in-order semantics of one straight-line main().
		// defvar idempotence ("bind only if not already bound") is tracked at compile
		// time
		// in definedGlobals; share one set across chunks so a defvar split into a later
		// chunk still sees an earlier binding.
		Set<String> sharedDefinedGlobals = new HashSet<>();
		Utf8Entry topChunkDesc = cp.utf8Entry("()V");
		List<Ctx> topChunks = new ArrayList<>();
		List<Utf8Entry> topChunkNames = new ArrayList<>();
		List<MethodRefEntry> topChunkRefs = new ArrayList<>();
		// Budget well under 65535 to leave room for the final form pushed past the check
		// plus the trailing RETURN; a single form larger than the difference still
		// cannot be split (a pre-existing per-form limit: chunking happens BETWEEN
		// top-level forms, so one form whose bytecode passes the 64 KB per-method cap
		// has no split point). 24000 leaves ~41 KB of per-form headroom. It has been
		// lowered twice for the same reason -- a lowering got wider and the ci-spec
		// corpus's biggest single form grew with it: first the reader's usesFloatArray
		// forcing (48000 -> 40000, ~17 KB of headroom left), then the (setf (elt s i) v)
		// string arm, which costs ~6 KB per site and took the corpus's largest form to
		// ~39 KB. Measure before re-tuning: compiling with a budget of 1 puts every
		// top-level form in its own chunk, so the debug hook above then ranks the forms
		// themselves.
		final int chunkCodeBudget = 24000;
		// A sequence proven not to be a string takes the byte arm directly
		// (compiler/SequenceIoNarrowing). After every gate scan, like the WASM twin:
		// the narrowed expansion keeps the operator spellings the scans key on out of
		// the way by running once they have all read the program.
		topLevelExprs = SequenceIoNarrowing.narrow(topLevelExprs,
				usesStreamValues && functions.containsKey(LispNames.CHARACTER_STREAM_P_INTERNAL),
				functions.containsKey(LispNames.WIDE_WIDTH_INTERNAL),
				functions.containsKey(LispNames.CHECK_SEQUENCE_BOUNDS_INTERNAL));
		Ctx chunkCtx = null;
		for (LispVal expr : topLevelExprs) {
			if (chunkCtx == null || chunkCtx.body.size() >= chunkCodeBudget) {
				if (chunkCtx != null) {
					chunkCtx.body.return_();
				}
				chunkCtx = ctxBuilder.build();
				chunkCtx.topLevel = true;
				chunkCtx.evalStoreRef = evalStoreRef;
				chunkCtx.shareDefinedGlobals(sharedDefinedGlobals);
				Utf8Entry nameUtf8 = cp.utf8Entry("_top$" + topChunks.size());
				topChunks.add(chunkCtx);
				topChunkNames.add(nameUtf8);
				topChunkRefs.add(cp.methodRef(thisClass, nameUtf8, topChunkDesc));
			}
			// Statement position: the chunk pops whatever the form returns, so a definer
			// that returns nothing but the name it just bound is offered the chance to
			// emit no name at all rather than push the symbol only to pop it
			// (compiler/ToplevelStatements; the constant-valued forms that pass leaves
			// are gone from topLevelExprs before this loop sees them). The offer goes
			// through the ordinary compileExpr so the form keeps everything that path
			// gives it; whether it was TAKEN is read back from the context, so a spelling
			// the dispatch does not route to the defvar compiler leaves its value on the
			// stack and still gets its pop.
			boolean offered = ToplevelStatements.isNameValuedDefiner(expr);
			chunkCtx.definerNameDropped = offered ? expr : null;
			try {
				JvmExprCompiler.compileExpr(expr, chunkCtx, this.className);
			}
			catch (IllegalStateException ex) {
				// Name the form like the defun wrapper above does: a per-method limit hit
				// inside a top-level form is otherwise unattributable in a large program.
				String shown = expr.print();
				if (shown.length() > 120) {
					shown = shown.substring(0, 120) + "...";
				}
				throw new IllegalStateException("while compiling top-level form " + shown + ": " + ex.getMessage(), ex);
			}
			boolean taken = offered && chunkCtx.definerNameDropped == null;
			chunkCtx.definerNameDropped = null;
			if (!taken) {
				chunkCtx.body.pop();
			}
		}
		if (chunkCtx != null) {
			chunkCtx.body.return_();
		}

		// main() simply calls each top-level chunk in order, then returns. With any
		// jvm-export, the top level moves to <clinit> instead (via the _top$run method
		// built below): a typed wrapper may be the first call into the class, and the
		// defvar/defparameter initialization in the chunks must have run by then — the
		// cross-backend precedent is the --no-wasi reactor, which runs its top level at
		// instantiation, and <clinit> is the JVM's instantiation. main (when kept) then
		// only triggers class initialization, so the top level still runs exactly once,
		// idempotent under the JVM's own class-init locking (.kb/jvm-export.md).
		// Servlet mode forces the same move with or without an export: the container's
		// initializer reaches the top level through Class.forName(name, true, loader),
		// and a war whose top level stayed in main deploys, finds the class, and 500s
		// on every request with an unfilled handler slot (the .todo/529 spike measured
		// exactly that failure).
		boolean topLevelInClinit = !exportDecls.isEmpty() || this.servletMode;
		Ctx mainCtx = ctxBuilder.build();
		mainCtx.evalStoreRef = evalStoreRef;
		if (mvChannel != null) {
			// The thread running main keeps the channel's static field (JvmMvChannel).
			mvChannel.emitClaimOwner(mainCtx);
		}
		// The command line's static home, built HERE rather than beside the other
		// runtime helpers because main's own prologue is what fills it: a defun that
		// reads the arguments is an ordinary static method and cannot see main's locals.
		final JvmArgvRuntimeBuilder.@Nullable ArgvRuntime argvRuntime = usesArgv
				? JvmArgvRuntimeBuilder.build(cp, thisClass, objectClass, stringConcat, this.className) : null;
		if (argvRuntime != null) {
			// _argv = args. In main and only in main -- with a jvm-export the top level
			// has already run in <clinit>, before any main could store one, which is the
			// null the helper answers nil for.
			mainCtx.body.aload(0);
			mainCtx.body.putstatic(argvRuntime.field());
		}
		Ctx topRunnerCtx = null;
		Ctx entryCtx = mainCtx;
		if (topLevelInClinit) {
			topRunnerCtx = ctxBuilder.build();
			topRunnerCtx.evalStoreRef = evalStoreRef;
			entryCtx = topRunnerCtx;
		}
		for (MethodRefEntry ref : topChunkRefs) {
			entryCtx.body.invokestatic(ref);
		}
		// A program that writes RAW OCTETS to standard output has to drain the
		// PrintStream itself. It auto-flushes on a newline and on every byte[] write --
		// which is why the print family never needed this, its characters go out through
		// the writer's byte[] path -- but a single-byte write only flushes on '\n', and a
		// byte-oriented filter's output need not end in one. The three other backends
		// have nothing to drain (both wasm ones call fd_write per byte, the interpreter
		// flushes at the end of the run), so without this a compiled JVM filter would
		// silently truncate where they do not. Gated on the source naming one of the two
		// operators that reach the helper, so every other artifact keeps its exact bytes:
		// ANY new path to _writeByte's standard-output branch must join this gate.
		if (programUsesSymbol(program, LispNames.WRITE_BYTE) || programUsesSymbol(program, LispNames.WRITE_SEQUENCE)) {
			entryCtx.body.getstatic(systemOut);
			entryCtx.body.invokevirtual(cp.methodRef(cp.classEntry("java/io/PrintStream"), "flush", "()V"));
		}
		if (flushStreamsMethod != null) {
			entryCtx.body.invokestatic(flushStreamsMethod);
		}
		entryCtx.body.return_();
		// A condition nobody caught reports itself on standard error instead of
		// unwinding out of main as a stack trace through mangled Lisp names. Last, so
		// every handler main already carries dispatches first. In _top$run the same
		// report-and-rethrow surfaces to a Java caller as ExceptionInInitializerError
		// (which also poisons the class permanently) — the reactor's failure shape,
		// stated in the docs rather than designed around. Appended once the lambdas are
		// compiled too: only then is it known whether anything was located to report.
		JvmUncaughtHandler.Prepared uncaughtHandler = JvmUncaughtHandler.prepare(entryCtx);
		if (topLevelInClinit) {
			// main (when kept) has nothing left to do: invoking it already triggered
			// <clinit>, which ran the top level.
			mainCtx.body.return_();
		}

		// Pass 2c: Compile lambda bodies (iteratively, new lambdas may be discovered
		// during defun compilation, top-level compilation, or even lambda compilation)
		List<Ctx> lambdaCtxs = new ArrayList<>();
		List<FunctionInfo> lambdaFuncInfos = new ArrayList<>();
		List<JvmTailGroup> lambdaGroups = new ArrayList<>();
		int lambdaIdx = 0;
		while (lambdaIdx < lambdaDecls.size()) {
			LambdaInfo lambda = lambdaDecls.get(lambdaIdx);
			// Register lambda in CP: first param is Object[] env, rest are lambda params,
			// then the value-tail depth until the body shows it reads none (below).
			String descriptor = lambdaDescriptor(lambda.paramNames.size(), true);
			Utf8Entry nameUtf8 = cp.utf8Entry(lambda.methodName);
			Utf8Entry descUtf8 = cp.utf8Entry(descriptor);
			MethodRefEntry methodref = cp.methodRef(thisClass, nameUtf8, descUtf8);
			// Any lambda may answer a bounce: a tail through a value, or a named callee's
			// passed on.
			FunctionInfo fi = new FunctionInfo(lambda.funcId, lambda.paramNames.size(), lambda.variadic,
					lambda.optionals, true, methodref, nameUtf8, descUtf8, true);
			lambdaFuncInfos.add(fi);

			Ctx lambdaCtx = ctxBuilder.build();
			lambdaCtx.evalStoreRef = evalStoreRef;
			// A lambda is only ever called through a dispatcher, _apply or a runtime
			// entry, each of which unwraps its result: its tail through a value bounces,
			// and a bounce a named callee answers passes on (JvmTailBounce).
			lambdaCtx.tailBounce = true;
			lambdaCtx.passesBounces = true;
			lambdaCtx.openFunction(
					lambda.reportName() == null ? null : JvmSourceSites.reportedName(lambda.reportName()),
					lambda.writtenIn(), lambda.bodyExprs());
			lambdaCtx.closureEnvSlot = 0; // slot 0 = env Object[]
			// Lambda params start at slot 1
			for (int i = 0; i < lambda.paramNames.size(); i++) {
				lambdaCtx.locals.put(lambda.paramNames.get(i), i + 1);
			}
			lambdaCtx.nextLocal = lambda.paramNames.size() + 1; // +1 for env
			// The value-tail depth, after the parameters (JvmTailBounce).
			lambdaCtx.depthSlot = lambdaCtx.nextLocal++;
			lambdaCtx.maxLocals = lambdaCtx.nextLocal;
			// Set up captures mapping
			Map<String, Integer> captures = new HashMap<>();
			for (int i = 0; i < lambda.freeVarNames.size(); i++) {
				captures.put(lambda.freeVarNames.get(i), i);
			}
			lambdaCtx.captures = captures;
			// Determine which locals are captured by further nested lambdas
			Set<String> lambdaLocalVars = new HashSet<>(lambda.paramNames);
			Set<String> capturedVars = FreeVarAnalyzer.findCapturedVars(lambda.bodyExprs, lambdaLocalVars,
					functions.keySet(), lambdaCtx.captureMemo);
			lambdaCtx.boxedVars = capturedVars;
			// Body-head float declarations, as in Pass 2a (.kb/jvm-double-arithmetic.md).
			Set<String> lambdaDeclaredDoubles = new HashSet<>(am.ik.rontolisp.compiler.DeclaredScalarTypes
				.functionBodyDeclaredDoubles(lambda.bodyExprs, closRegistry));
			lambdaDeclaredDoubles.removeAll(specialVars);
			lambdaCtx.declaredDoubles = lambdaDeclaredDoubles.isEmpty() ? Set.of() : lambdaDeclaredDoubles;
			if (lambda.selfVar() != null) {
				// A labels function: its calls through its own variable are self calls,
				// and the tail ones jump back here, ahead of the boxing below
				// (JvmSelfTailCall).
				lambdaCtx.selfLoop = JvmSelfTailCall.labels(lambdaCtx, lambda);
			}
			// A labels function in a tail group: its tail calls to the other members
			// jump (JvmTailGroup).
			JvmTailGroup.Member tailMember = lambda.tailMember();
			if (tailMember != null) {
				lambdaCtx.tailMember = tailMember;
				tailMember.function = fi;
			}
			// Box captured params of this lambda
			for (String paramName : lambda.paramNames) {
				if (capturedVars.contains(paramName)) {
					Integer slot = lambdaCtx.locals.get(paramName);
					if (slot != null) {
						JvmEmitHelper.emitBoxLocal(lambdaCtx, slot);
					}
				}
			}
			try {
				JvmBodyOutliner.compileFunctionBody(lambda.bodyExprs, lambdaCtx, this.className);
			}
			catch (IllegalStateException ex) {
				if (Boolean.getBoolean("rontolisp.jvm.debug-method-sizes")) {
					for (LispVal bodyExpr : lambda.bodyExprs) {
						System.err.println("[lambda-body " + lambda.methodName + "] " + bodyExpr.print());
					}
				}
				throw new IllegalStateException("while compiling lambda " + lambda.methodName + ": " + ex.getMessage(),
						ex);
			}
			if (lambda.bodyExprs.isEmpty()) {
				// An empty-body (lambda ()) returns nil.
				lambdaCtx.body.aconst_null();
			}
			lambdaCtx.body.areturn();
			if (!lambdaCtx.depthRead && tailMember == null) {
				// A body that never read the depth -- no value tail, no named callee's
				// bounce handed on, no continuation it goes to -- answers no bounce: the
				// method takes no depth, and its slot stays an unused local. Only the
				// dispatchers and the body making the value name the method, both settled
				// after this (a tail group's member keeps the depth its siblings' jumps
				// store).
				String plain = lambdaDescriptor(lambda.paramNames.size(), false);
				Utf8Entry plainUtf8 = cp.utf8Entry(plain);
				lambdaFuncInfos.set(lambdaIdx,
						new FunctionInfo(lambda.funcId, lambda.paramNames.size(), lambda.variadic, lambda.optionals,
								true, cp.methodRef(thisClass, nameUtf8, plainUtf8), nameUtf8, plainUtf8, false));
				lambda.madeIn().retargetValueOf(lambda.methodName, descriptor, plain);
			}
			lambdaCtxs.add(lambdaCtx);
			lambdaIdx++;
			if (tailMember != null) {
				tailMember.ctx = lambdaCtx;
				if (!lambdaGroups.contains(tailMember.group)) {
					lambdaGroups.add(tailMember.group);
				}
			}
		}

		// The uncaught report's location lines (JvmUncaughtHandler): every body that can
		// hold a located form has been compiled, so the site table is complete. A class
		// with nothing located gets neither the lines nor the async-boundary records.
		final JvmSourceSites sourceSites = mainCtx.sites != null && !mainCtx.sites.isEmpty() ? mainCtx.sites : null;
		final @Nullable MethodRefEntry whereRef = sourceSites == null ? null
				: cp.methodRef(thisClass, JvmUncaughtHandler.WHERE_METHOD, JvmUncaughtHandler.WHERE_DESC);
		@Nullable MethodRefEntry asyncCrossRef = null;
		if (sourceSites != null) {
			for (int i = 0; i < lambdaDecls.size(); i++) {
				String head = lambdaDecls.get(i).asyncHead();
				if (head != null) {
					if (asyncCrossRef == null) {
						asyncCrossRef = cp.methodRef(thisClass, JvmUncaughtHandler.ASYNC_CROSS_METHOD,
								JvmUncaughtHandler.ASYNC_CROSS_DESC);
					}
					JvmUncaughtHandler.appendAsyncCrossing(lambdaCtxs.get(i), head, asyncCrossRef);
				}
			}
		}
		final boolean recordsAsyncBoundaries = asyncCrossRef != null;
		uncaughtHandler.append(whereRef);

		// Pass 2d: emit the outlined fused-site method bodies (JvmIntFusionCompiler).
		// After every program body, because Pass 2 is what registers the sites; before
		// class assembly, because the bodies mint constant-pool entries. A fused body
		// compiles no Lisp expression, so the pending list cannot grow under this walk.
		List<Ctx> fusedCtxs = new ArrayList<>();
		for (JvmIntFusionCompiler.Pending pendingFused : fusedState.pending) {
			Ctx fusedCtx = ctxBuilder.build();
			JvmIntFusionCompiler.emitMethodBody(pendingFused, fusedCtx, this.className);
			fusedCtxs.add(fusedCtx);
		}

		// The tail groups, laid out now that every program body is compiled: a member
		// the program's code -- or a Java caller -- calls directly has the group rooted
		// at it as its method, the others keep their own code (JvmTailGroup).
		List<JvmTailGroup> tailGroups = new ArrayList<>(defunGroups);
		tailGroups.addAll(lambdaGroups);
		if (!tailGroups.isEmpty()) {
			Set<Integer> calledEntries = new HashSet<>();
			List<Ctx> callers = new ArrayList<>(funcCtxs);
			callers.addAll(lambdaCtxs);
			callers.addAll(topChunks);
			callers.add(mainCtx);
			if (topRunnerCtx != null) {
				callers.add(topRunnerCtx);
			}
			for (JvmBodyOutliner.OutlinedBody outlined : mainCtx.outlinedBodies) {
				callers.add(outlined.ctx());
			}
			for (Ctx caller : callers) {
				caller.body.forEachEntry(entry -> calledEntries.add(entry.index()));
			}
			Set<String> exported = new HashSet<>();
			for (JvmExportDirective decl : exportDecls) {
				exported.add(decl.name());
			}
			for (JvmTailGroup group : tailGroups) {
				group.finish(this.className, HUGE_METHOD_LIMIT,
						member -> member.function != null
								&& (calledEntries.contains(member.function.methodref().index())
										|| exported.contains(member.name)));
			}
		}

		// The invariant the whole class is measured against: no method that runs per
		// evaluated form may cross HotSpot's HugeMethodLimit, or it is never
		// JIT-compiled and nothing says so (.kb/hot-path-method-size.md). Every body
		// has been emitted by now, so this is where a REAL size exists to check --
		// which is why the cut is made by re-running the compile rather than by
		// predicting the size from the AST (bytecodes per node ranges over an order of
		// magnitude, because the surface macros expand during Pass 2). Only a defun is
		// reported: a lambda's generated name cannot be pointed back at a form for the
		// next attempt to cut.
		Map<String, AstOutliner.Budget> tooLarge = new LinkedHashMap<>();
		for (int i = 0; i < defuns.size(); i++) {
			int size = funcCtxs.get(i).emittedSize();
			if (size <= HUGE_METHOD_LIMIT) {
				continue;
			}
			String name = defuns.get(i).name;
			// Ask again only when there is something new to ask: a budget whose cut
			// differs from what this attempt compiled. A function the pass cannot cut,
			// or cannot cut any differently, is left over the limit rather than costing
			// a compile per attempt to learn that again.
			AstOutliner.Budget next = astOutlined.nextBudget(name, size, outlineBudgets.get(name), OUTLINE_TARGET_BYTES,
					OUTLINE_TARGET_FLOOR_BYTES);
			if (next != null) {
				tooLarge.put(name, next);
			}
		}
		if (!tooLarge.isEmpty()) {
			throw new MethodTooLarge(tooLarge);
		}
		// Debug hook (-Drontolisp.jvm.debug-method-sizes=true): rank the emitted
		// method bodies by code size. The JVM caps a method at 65535 code bytes and
		// a branch at a signed 16-bit offset, so this is the first thing to run when
		// a large program trips either limit.
		if (Boolean.getBoolean("rontolisp.jvm.debug-method-sizes")) {
			record Sized(String name, int size) {
			}
			List<Sized> sized = new ArrayList<>();
			for (int i = 0; i < defuns.size(); i++) {
				sized.add(new Sized(defuns.get(i).name, funcCtxs.get(i).emittedSize()));
			}
			for (int i = 0; i < lambdaCtxs.size(); i++) {
				sized.add(new Sized(lambdaDecls.get(i).methodName, lambdaCtxs.get(i).emittedSize()));
			}
			// The top-level chunks are subject to the same 64 KB cap, and unlike a defun
			// they cannot be split by the author -- chunking happens BETWEEN top-level
			// forms, so one oversized form has no split point (see chunkCodeBudget).
			for (int i = 0; i < topChunks.size(); i++) {
				sized.add(new Sized("_top$" + i, topChunks.get(i).body.size()));
			}
			for (JvmBodyOutliner.OutlinedBody outlined : mainCtx.outlinedBodies) {
				sized.add(new Sized(outlined.name(), outlined.ctx().body.size()));
			}
			sized.stream()
				.sorted(java.util.Comparator.comparingInt(Sized::size).reversed())
				.limit(40)
				.forEach(s -> System.err.println("[method-size] " + s.size() + "\t" + s.name()));
		}

		// Names of the eval runtime methods and the global-environment field (the
		// constants are cheap; the method bodies are built only when used)
		Utf8Entry evalName = cp.utf8Entry("_eval");
		Utf8Entry evalDesc = cp.utf8Entry("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		Utf8Entry applyName = cp.utf8Entry("_apply");
		// The raw apply the trampoline re-enters; _apply is its answer checked
		// (JvmTailBounce). It takes the value-tail depth, which it hands the spread
		// dispatcher.
		Utf8Entry applyRawName = cp.utf8Entry(JvmTailBounce.APPLY_RAW_NAME);
		Utf8Entry applyRawDesc = cp.utf8Entry(JvmRuntimeBuilder.dispatcherDesc(0, true));
		Utf8Entry storeName = cp.utf8Entry("_store");
		Utf8Entry storeDesc = cp
			.utf8Entry("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		Utf8Entry envLookupName = cp.utf8Entry("_envLookup");
		Utf8Entry envLookupDesc = cp.utf8Entry("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		Utf8Entry lookupName = cp.utf8Entry("_lookup");
		Utf8Entry lookupDesc = cp.utf8Entry("(Ljava/lang/Object;)[Ljava/lang/Object;");
		Utf8Entry genvName = cp.utf8Entry("_genv");
		Utf8Entry genvDesc = cp.utf8Entry("Ljava/lang/Object;");
		FieldRefEntry genvField = cp.fieldRef(thisClass, genvName, genvDesc);
		Utf8Entry fenvName = cp.utf8Entry("_fenv");
		FieldRefEntry fenvField = cp.fieldRef(thisClass, fenvName, genvDesc);
		MethodCode evalCode = new MethodCode();
		MethodCode applyCode = new MethodCode();
		MethodCode applyEntryCode = new MethodCode();
		MethodCode storeCode = new MethodCode();
		MethodCode envLookupCode = new MethodCode();
		List<MethodCode> lookupSegments = List.of();
		List<Utf8Entry> lookupSegmentNames = new ArrayList<>();
		// _lookup (the name-to-funcId registry) is needed by the eval runtime AND by
		// the indirect-call dispatchers: a funcall whose designator is a SYMBOL at run
		// time (cl-postgres passes 'list-row-reader through exec-query) resolves
		// through it, matching the interpreter's late binding. Gated on the program
		// actually having such a call, because the registry names every defun and is
		// therefore not size-neutral.
		//
		// The source scan above reads funcall/apply only, so every OTHER operator that
		// calls a designator -- mapcar, sort, remove-if, maphash, a bare (f x) whose head
		// is an expression -- is covered by the arities Pass 2 actually dispatched
		// through: a dispatcher is exactly a call site a SYMBOL can arrive at. Before the
		// eval gate stopped being forced on for programs that never mention eval, that
		// always-on gate is what covered them, and without this clause
		// (mapcar (car (list 'pred)) l) lost the registry and died on the symbol.
		boolean needsLookup = usesApplyRuntime || LispMacroExpander.usesRuntimeFunctionDesignator(program)
				|| !indirectCallArities.isEmpty()
				// A computed (symbol-function x) / (fdefinition x) boxes through _lookup,
				// and so does a (coerce v 'function) over a literal function designator
				// (or a computed result type, which can name FUNCTION at run time) --
				// even in a program with no call site spelling the registry (.todo/750).
				|| LispMacroExpander.usesRuntimeFunctionBox(program);
		// Which funcIds the _invoke_N dispatchers (and the _lookup registry) must be
		// able to reach. Every method body has been emitted by now, so valueFuncIds is
		// exactly the set of funcIds this program turns into function VALUES -- macro
		// expansions that ran during Pass 2 included. Everything else is only ever
		// called directly, and dropping its dispatcher case is what lets
		// the writer's shake reach the library code an ASDF system splices.
		// A package walk interns the baked universes' spellings at run time: names the
		// program holds without emitting them, which the probes read like its own.
		spelledLiterals.addAll(RuntimeNameProducers.packageWalkSpellings(program));
		// The registry's rows are the names a runtime designator can carry; the
		// dispatchers take those plus every funcId materialized as a value. Computed
		// together, so a row always has its case.
		Set<Integer> registryFuncIds = registryFuncIds(functions, valueFuncIds, spelledLiterals, needsLookup,
				nameResolvable, symbolBuilders, callOnlyRuntimes);
		Set<Integer> dispatchableFuncIds = new HashSet<>(valueFuncIds);
		dispatchableFuncIds.addAll(registryFuncIds);
		if (needsLookup) {
			MethodRefEntry evalRef = cp.methodRef(thisClass, evalName, evalDesc);
			MethodRefEntry applyRef = cp.methodRef(thisClass, applyName, evalDesc);
			MethodRefEntry storeRef = cp.methodRef(thisClass, storeName, storeDesc);
			MethodRefEntry envLookupRef = cp.methodRef(thisClass, envLookupName, envLookupDesc);
			MethodRefEntry lookupRef = cp.methodRef(thisClass, lookupName, lookupDesc);
			MethodRefEntry[] invoke = new MethodRefEntry[JvmEvalRuntimeBuilder.MAX_CALLABLE_ARITY + 1];
			for (int n = 0; n <= JvmEvalRuntimeBuilder.MAX_CALLABLE_ARITY; n++) {
				invoke[n] = cp.methodRef(thisClass, JvmRuntimeBuilder.dispatcherName(n, false),
						JvmRuntimeBuilder.dispatcherDesc(n, false));
			}
			// _invoke_v(funcval, argList, depth): the spread dispatcher _apply hands the
			// whole argument list to (see JvmRuntimeBuilder.buildDispatchMethods).
			MethodRefEntry invokeSpread = cp.methodRef(thisClass, JvmRuntimeBuilder.dispatcherName(0, true),
					JvmRuntimeBuilder.dispatcherDesc(0, true));
			MethodRefEntry stringLengthRef = cp.methodRef(stringClass, "length", "()I");
			JvmEvalRuntimeBuilder.EvalConstants ec = JvmEvalRuntimeBuilder.EvalConstants.builder()
				.cp(cp)
				.objectClass(objectClass)
				.objectArrayClass(objectArrayClass)
				.integerClass(integerClass)
				.longClass(longClass)
				.doubleClass(doubleClass)
				.stringClass(stringClass)
				.integerValueOf(integerValueOf)
				.integerValue(integerValue)
				.longValueOf(longValueOf)
				.longValue(longValue)
				.stringCharAt(stringCharAt)
				.stringLength(stringLengthRef)
				.objectEquals(objectEquals)
				.evalRef(evalRef)
				.applyRef(applyRef)
				.applyRawRef(cp.methodRef(thisClass, applyRawName, applyRawDesc))
				.storeRef(storeRef)
				.envLookupRef(envLookupRef)
				.lookupRef(lookupRef)
				.notFnRef(cp.methodRef(thisClass, JvmRuntimeBuilder.NOT_FN_NAME, JvmRuntimeBuilder.NOT_FN_DESC))
				.genvField(genvField)
				.fenvField(fenvField)
				.invoke(invoke)
				.invokeSpread(invokeSpread)
				.functions(functions)
				.complexValues(usesComplex)
				.hasComplexField(hasComplexField != null ? hasComplexField : null)
				// _arityChk comes with _apply (reportsCount below), and only then does
				// the
				// runtime reference it
				.arityChkRef(usesApplyRuntime
						? cp.methodRef(thisClass, JvmRuntimeBuilder.ARITY_CHK_NAME, JvmRuntimeBuilder.ARITY_CHK_DESC)
						: null)
				.arityOperators(arityOperators)
				.thisClass(thisClass)
				.globalAccessRef(evalRunsForms ? globalAccessRef(functions) : null)
				.build();
			if (usesEval) {
				evalCode = JvmEvalRuntimeBuilder.buildEval(ec);
				storeCode = JvmEvalRuntimeBuilder.buildStore(ec);
				envLookupCode = JvmEvalRuntimeBuilder.buildEnvLookup(ec);
			}
			if (usesApplyRuntime) {
				applyCode = JvmEvalRuntimeBuilder.buildApply(ec, usesEval);
				applyEntryCode = JvmEvalRuntimeBuilder.buildApplyEntry(ec);
			}
			lookupSegments = JvmEvalRuntimeBuilder.buildLookupSegments(ec, thisClass, registryFuncIds,
					this.dynamic || nameResolvable || symbolBuilders, spelledLiterals);
			for (int g = 1; g < lookupSegments.size(); g++) {
				lookupSegmentNames.add(cp.utf8Entry("_lookup$" + g));
			}
		}

		// Build dispatch functions for each needed arity. When the eval runtime is
		// present, the dispatcher falls back to _apply for interpreted closures
		// (funcId == -1) created by the runtime's lambda; a String funcval (a symbol
		// used as a function designator) resolves through _lookup.
		MethodRefEntry applyRefForDispatch = usesEval
				? cp.methodRef(thisClass, "_apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") : null;
		MethodRefEntry lookupRefForDispatch = needsLookup ? cp.methodRef(thisClass, lookupName, lookupDesc) : null;
		// The arity reporters a wrong argument COUNT is signalled through, each emitted
		// only for a program that has the site it serves: _arityErr for a per-arity
		// dispatcher's no-match arm, _arityChk for a SPREAD case or a literal apply's
		// direct call. A program with neither is byte-identical to a build that never
		// knew about the check (JvmRuntimeBuilder.ArityReporting).
		boolean reportsMiss = !indirectCallArities.isEmpty();
		boolean reportsCount = usesApplyRuntime || !arityGuardShapes.isEmpty();
		JvmRuntimeBuilder.ArityReporting arityReporting = reportsMiss || reportsCount
				? new JvmRuntimeBuilder.ArityReporting(
						reportsMiss ? cp.methodRef(thisClass, JvmRuntimeBuilder.ARITY_ERR_NAME,
								JvmRuntimeBuilder.ARITY_ERR_DESC) : null,
						reportsCount ? cp.methodRef(thisClass, JvmRuntimeBuilder.ARITY_CHK_NAME,
								JvmRuntimeBuilder.ARITY_CHK_DESC) : null,
						reportsCount ? arityOperators : null)
				: JvmRuntimeBuilder.ArityReporting.NONE;
		// The arities whose value tails call a dispatcher copy of their own, as the
		// latest build of the dispatchers left them
		// (JvmTailBounce.valueTailDispatcherName). The copy spends bytes on speed, so
		// --optimize=size declines it.
		Set<Integer> valueTailCopies = new java.util.TreeSet<>();
		boolean valueTailDispatchers = !this.optimize.prefersSizeOverSpeed();
		// The dispatchers over a set of funcIds: built here over every dispatchable one,
		// and again, once the class is assembled, over the ones a kept body can make a
		// value of (rebuildDispatchers).
		java.util.function.Function<Set<Integer>, List<DispatchMethod>> dispatchers = funcIds -> {
			List<DispatchMethod> built = new ArrayList<>();
			valueTailCopies.clear();
			if (reportsMiss || reportsCount) {
				built.addAll(JvmRuntimeBuilder.buildArityMethods(functions, lambdaDecls, cp, thisClass,
						objectArrayClass, stringClass, funcIds, reportsMiss, reportsCount, arityOperators));
			}
			// What applying a non-function raises, shared by every dispatcher and by
			// _apply (the eval runtime, which the spread dispatcher comes with).
			if (!indirectCallArities.isEmpty() || usesApplyRuntime) {
				built.add(new DispatchMethod(cp.utf8Entry(JvmRuntimeBuilder.NOT_FN_NAME),
						cp.utf8Entry(JvmRuntimeBuilder.NOT_FN_DESC),
						JvmRuntimeBuilder.buildNotFnBody(cp, stringClass, lispToStringMethod)));
			}
			for (int arity : indirectCallArities) {
				List<DispatchMethod> shared = JvmRuntimeBuilder.buildDispatchMethods(arity, functions, lambdaDecls,
						lambdaFuncInfos, cp, thisClass, objectArrayClass, integerClass, integerValue, objectClass,
						stringClass, applyRefForDispatch, lookupRefForDispatch, funcIds, arityReporting,
						mainCtx.unsupplied);
				built.addAll(shared);
				// A value tail's real call goes through a copy of its arity's
				// dispatcher, whose profile holds only what value tails reach. An arity
				// routed past one segment keeps the shared dispatcher: its copy would
				// double it.
				if (valueTailDispatchers && shared.size() == 1 && mainCtx.valueTailArities.contains(arity)) {
					built.addAll(JvmRuntimeBuilder.buildDispatchMethods(arity, functions, lambdaDecls, lambdaFuncInfos,
							cp, thisClass, objectArrayClass, integerClass, integerValue, objectClass, stringClass,
							applyRefForDispatch, lookupRefForDispatch, false,
							JvmTailBounce.valueTailDispatcherName(arity), true, funcIds, arityReporting,
							mainCtx.unsupplied));
					valueTailCopies.add(arity);
				}
			}
			// The spread dispatcher _apply calls: it takes the argument list whole, so an
			// apply through a COMPUTED designator has no arity ceiling. Emitted with
			// _apply.
			if (usesApplyRuntime) {
				built.addAll(JvmRuntimeBuilder.buildDispatchMethods(0, functions, lambdaDecls, lambdaFuncInfos, cp,
						thisClass, objectArrayClass, integerClass, integerValue, objectClass, stringClass,
						applyRefForDispatch, lookupRefForDispatch, true, JvmRuntimeBuilder.dispatcherName(0, true),
						false, funcIds, arityReporting, mainCtx.unsupplied));
			}
			return built;
		};
		List<DispatchMethod> dispatchMethods = dispatchers.apply(dispatchableFuncIds);

		// Build the runtime reader methods (read/load), only when used
		Utf8Entry readSrcName = cp.utf8Entry("_readSrc");
		Utf8Entry readSrcDesc = cp.utf8Entry("Ljava/lang/String;");
		Utf8Entry readPosName = cp.utf8Entry("_readPos");
		Utf8Entry readPosDesc = cp.utf8Entry("I");
		@Nullable Utf8Entry readFailName = usesRead ? cp.utf8Entry(JvmReadRuntimeBuilder.FAIL_FIELD) : null;
		Utf8Entry rdStructsName = cp.utf8Entry(JvmReadRuntimeBuilder.STRUCT_TABLE_FIELD);
		Utf8Entry rdStructsDesc = cp.utf8Entry(JvmReadRuntimeBuilder.STRUCT_TABLE_DESC);
		List<JvmReadRuntimeBuilder.ReadMethod> readMethods = List.of();
		@Nullable MethodCode structTableClinit = null;
		if (usesRead) {
			// The reader reads #S(...) and #P"..." only when an instance can exist at
			// all (the same gate the instance machinery uses); with it on, every struct
			// layout is interned so the runtime directory can resolve any registered
			// tag, the fixed PATHNAME layout is interned for the #P arm, and the
			// directory itself is baked into <clinit>.
			boolean readerInstances = mayUseInstances;
			FieldRefEntry pathnameLayoutField = null;
			if (readerInstances) {
				for (am.ik.rontolisp.LispLayout layout : closRegistry.layouts().values()) {
					if (layout.kind() == am.ik.rontolisp.LispLayout.Kind.STRUCT) {
						mainCtx.layoutPool.intern(cp, className, layout);
					}
				}
				pathnameLayoutField = mainCtx.layoutPool.intern(cp, className, am.ik.rontolisp.LispLayout.PATHNAME);
				structTableClinit = JvmReadRuntimeBuilder.structTableClinit(cp, thisClass, mainCtx.layoutPool,
						closRegistry, objectClass, objectArrayClass, stringClass);
			}
			readMethods = JvmReadRuntimeBuilder
				.create(cp, thisClass, objectClass, objectArrayClass, stringClass, longValueOf, doubleValueOf,
						stringCharAt, stringLength, stringSubstring, objectEquals, usesLoad, readerInstances,
						pathnameLayoutField != null ? pathnameLayoutField : null)
				.methods();
		}
		final List<JvmReadRuntimeBuilder.ReadMethod> readMethodsFinal = readMethods;
		final @Nullable MethodCode structTableClinitFinal = structTableClinit;

		// Build the hash-table runtime helpers, only when the program uses hash tables.
		final List<JvmHashRuntimeBuilder.HashMethod> hashMethods = usesHashTables ? JvmHashRuntimeBuilder.build(cp,
				thisClass, objectClass, objectArrayClass, longValueOf,
				Objects.requireNonNull(numericRuntime.ops().get(JvmNumericRuntimeBuilder.EQUAL)),
				Objects.requireNonNull(numericRuntime.ops().get(JvmNumericRuntimeBuilder.EQV)),
				strvMethod != null ? strvMethod : null, instanceLayoutClass != null ? instanceLayoutClass : null,
				usesEqualpHashTables, usesIdentityHashTables, javaSites != null ? javaSites.direct().lispTable() : null,
				javaSites != null ? javaSites.direct().host() : null) : List.of();

		// Build the array runtime helpers, only when the program uses arrays. Includes
		// the
		// The renderers' cycle-guard statics (_renderPath/_renderDepth), shared by the
		// two escape modes AND the three guarded arms (instance, cons, array): the cons
		// renderer is unconditional, so the pair is declared in every class -- one
		// path, one mechanism, the emitted twin of RenderCycleGuard.
		final Utf8Entry renderPathFieldName = cp.utf8Entry("_renderPath");
		final Utf8Entry renderPathFieldDesc = cp.utf8Entry("[Ljava/lang/Object;");
		final Utf8Entry renderDepthFieldName = cp.utf8Entry("_renderDepth");
		final Utf8Entry renderDepthFieldDesc = cp.utf8Entry("I");
		final JvmRuntimeBuilder.RenderGuardRefs renderGuard = new JvmRuntimeBuilder.RenderGuardRefs(
				cp.fieldRef(thisClass, renderPathFieldName, renderPathFieldDesc),
				cp.fieldRef(thisClass, renderDepthFieldName, renderDepthFieldDesc), cp.classEntry("java/lang/Object"),
				cp.stringEntry("#"));
		// two array-printing helpers (_arrayToString / _arrayToDisplayString) so a
		// literal
		// or make-array result prints as #(...) / #2A(...).
		final List<JvmArrayRuntimeBuilder.ArrayMethod> arrayMethods;
		if (usesArrays) {
			List<JvmArrayRuntimeBuilder.ArrayMethod> built = new ArrayList<>(JvmArrayRuntimeBuilder.build(cp,
					objectClass, objectArrayClass, thisClass, usesFloatArray, subseqRuntime));
			built.addAll(JvmArrayRuntimeBuilder.buildToStringMethods(cp, lispToStringMethod, lispToDisplayStringMethod,
					thisClass, renderGuard));
			// The packed float-array helpers (_fv*) dispatch on instanceof double[] and
			// delegate to the general _array* helpers above for a non-packed array, so
			// they are emitted alongside (and depend on) them.
			if (usesFloatArray) {
				// Under --gpu every packed store reports itself first, so a device copy
				// of
				// the array comes home if it was the authoritative one and is dropped;
				// and
				// every packed READ materializes first (.kb/gpu.md, "Device residency").
				built.addAll(JvmFloatArrayRuntimeBuilder.build(cp, objectClass, objectArrayClass, thisClass,
						gpuRuntime != null ? Objects.requireNonNull(gpuRuntime.ops().get(JvmGpuRuntimeBuilder.WRITTEN))
								: null,
						gpuRuntime != null
								? Objects.requireNonNull(gpuRuntime.ops().get(JvmGpuRuntimeBuilder.MATERIALIZE))

								: null,
						usesQuantized, usesIntArray));
				if (usesQuantized) {
					// The quantized matrix's own helpers; the _fv* byte[] arms above
					// delegate to them.
					built.addAll(JvmQuantizedMatrixRuntimeBuilder.build(cp, thisClass, usesIntArray));
				}
			}
			// The packed integer-vector helpers (_iv*) dispatch on the representation
			// (byte[] at width 8, long[] at 16/32) and delegate any other array shape
			// down the chain (to the _fv* tier when it is emitted, else straight to the
			// general helpers).
			if (usesIntArray) {
				built.addAll(JvmIntArrayRuntimeBuilder.build(cp, objectClass, objectArrayClass, thisClass,
						usesFloatArray, usesQuantized));
			}
			// widen-float-bits/narrow-float-bits (.todo/671): bulk f16/bf16 bit <->
			// packed-float conversion, over the same bare double[]/float[]/short[]/long[]
			// backing the _fv*/_iv* helpers above use. Needs both tiers (a packed float
			// array AND a packed (unsigned-byte 16) vector), which usesFloat16Bits
			// already forced on above.
			if (usesFloat16Bits) {
				built.addAll(JvmFloat16RuntimeBuilder.build(cp));
			}
			arrayMethods = built;
		}
		else {
			arrayMethods = List.of();
		}
		// The packed-array print branch: _lispToString/_lispToDisplayString render a
		// double[] by converting it to a general array (_fvToGeneral) and reusing
		// _arrayToString, then rewriting the leading #/#nA prefix to #d (via
		// String.replaceFirst) so the printed form round-trips to a packed array; the
		// PackedPrint bundle is non-null only when the program uses packed float arrays.
		JvmRuntimeBuilder.@Nullable PackedPrint packedPrint = null;
		if (usesFloatArray) {
			packedPrint = new JvmRuntimeBuilder.PackedPrint(cp.classEntry("[D"), cp.classEntry("[F"),
					cp.classEntry("[S"),
					cp.methodRef(thisClass, JvmFloatArrayRuntimeBuilder.TO_GENERAL_PRINT,
							JvmFloatArrayRuntimeBuilder.TO_GENERAL_DESC),
					cp.methodRef(stringClass, "replaceFirst",
							"(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
					cp.stringEntry("^#\\d*A?\\("), cp.stringEntry("#d("), cp.stringEntry("#f("),
					cp.stringEntry("#bf16("), usesQuantized ? cp.classEntry("[B") : null,
					usesQuantized ? cp.methodRef(thisClass, JvmQuantizedMatrixRuntimeBuilder.TO_STRING,
							JvmQuantizedMatrixRuntimeBuilder.TO_STRING_DESC) : null);
		}
		// The packed integer-vector print branch: a byte[]/long[] renders as a plain
		// #(...) vector (CL prints specialized vectors this way) by converting to a
		// general array (_ivToGeneral) and reusing the general renderer -- no prefix
		// rewrite, unlike the #d/#f float syntax.
		JvmRuntimeBuilder.@Nullable PackedIntPrint packedIntPrint = null;
		if (usesIntArray) {
			packedIntPrint = new JvmRuntimeBuilder.PackedIntPrint(cp.classEntry("[J"), cp.classEntry("[B"),
					usesQuantized, cp.methodRef(thisClass, JvmIntArrayRuntimeBuilder.TO_GENERAL,
							JvmIntArrayRuntimeBuilder.TO_GENERAL_DESC));
		}
		ClassEntry arrayListClassForPrint = usesArrays ? cp.classEntry("java/util/ArrayList") : null;
		MethodRefEntry arrayToStringMethod = usesArrays
				? cp.methodRef(thisClass, JvmArrayRuntimeBuilder.TO_STRING, JvmArrayRuntimeBuilder.TO_STRING_DESC)
				: null;
		MethodRefEntry arrayToDisplayStringMethod = usesArrays ? cp.methodRef(thisClass,
				JvmArrayRuntimeBuilder.TO_DISPLAY_STRING, JvmArrayRuntimeBuilder.TO_STRING_DESC) : null;

		// Wrapped java: host objects print as #<java class.Name> (interpreter parity);
		// the branch is emitted only when the program uses java: interop.
		final JvmRuntimeBuilder.@Nullable JavaPrint javaPrint;
		if (usesJava) {
			ClassEntry bigIntegerClassForPrint = cp.classEntry("java/math/BigInteger");
			MethodRefEntry objectGetClass = cp.methodRef(objectClass, "getClass", "()Ljava/lang/Class;");
			ClassEntry classClass = cp.classEntry("java/lang/Class");
			MethodRefEntry classGetName = cp.methodRef(classClass, "getName", "()Ljava/lang/String;");
			JvmJavaDirectSites direct = Objects.requireNonNull(javaSites).direct();
			javaPrint = new JvmRuntimeBuilder.JavaPrint(bigIntegerClassForPrint, objectGetClass, classGetName,
					stringConcat, cp.stringEntry("#<java "), cp.stringEntry(">"),
					usesArrays ? direct.lispArray() : null, usesHashTables ? direct.lispTable() : null);
		}
		else {
			javaPrint = null;
		}
		// A foreign pointer prints as #<pointer #x...> (interpreter parity) through the
		// ffi bridge's print hook -- emitted AHEAD of the java: branch, which would
		// otherwise print the pointer as a host object; guarded by _ffiInited, so the
		// printer never names the bridge class before _ffiInit defined it.
		final JvmRuntimeBuilder.@Nullable BridgePrint ffiPrint = ffiRuntime != null
				? new JvmRuntimeBuilder.BridgePrint(ffiRuntime.initedField(),
						Objects.requireNonNull(ffiRuntime.ops().get(JvmFfiRuntimeBuilder.PRINT)))
				: null;

		// A hash table prints as the unreadable #<HASH-TABLE :TEST EQUAL :COUNT n> tag,
		// the same text the interpreter and both WASM backends emit; the branch is
		// emitted only when the program uses hash tables.
		final JvmRuntimeBuilder.@Nullable HashPrint hashPrint;
		if (usesHashTables) {
			ClassEntry mapClassForPrint = cp.classEntry(JvmHashRuntimeBuilder.MAP_CLASS);
			// The live entry count comes from the same helper hash-table-count reads, so
			// the printed :COUNT and the accessor cannot disagree (the map's own size()
			// counts buckets, not entries).
			MethodRefEntry mapSize = cp.methodRef(thisClass, JvmHashRuntimeBuilder.SIZE,
					JvmHashRuntimeBuilder.SIZE_DESC);
			MethodRefEntry intToString = cp.methodRef(cp.classEntry("java/lang/Integer"), "toString",
					"(I)Ljava/lang/String;");
			// The :TEST field is the test lookup implements. Only a program that can
			// build an equalp table interns the second tag and asks the table which one
			// it is; only a program that can build an identity table interns the eql
			// and eq tags and reads the test code. In every other program no table
			// folds or keys by identity, so the EQUAL tag is a constant exactly as it
			// was.
			hashPrint = new JvmRuntimeBuilder.HashPrint(mapClassForPrint,
					cp.stringEntry(LispHashTable.HASH_TABLE_PREFIX), mapSize, intToString, stringConcat,
					cp.stringEntry(">"),
					usesEqualpHashTables ? cp.stringEntry(LispHashTable.HASH_TABLE_PREFIX_EQUALP) : null,
					usesEqualpHashTables ? cp.methodRef(thisClass, JvmHashRuntimeBuilder.EQUALP_P,
							JvmHashRuntimeBuilder.EQUALP_P_DESC) : null,
					usesIdentityHashTables ? cp.stringEntry(LispHashTable.HASH_TABLE_PREFIX_EQL) : null,
					usesIdentityHashTables ? cp.stringEntry(LispHashTable.HASH_TABLE_PREFIX_EQ) : null,
					usesIdentityHashTables
							? cp.methodRef(thisClass, JvmHashRuntimeBuilder.TEST, JvmHashRuntimeBuilder.TEST_DESC)
							: null);
		}
		else {
			hashPrint = null;
		}

		// Futures (CompletableFutures / stream-read tokens at runtime) print as
		// #<FUTURE> and streams as #<STREAM> (interpreter parity); the branches are
		// emitted only when the program can create them.
		final JvmRuntimeBuilder.@Nullable FuturePrint futurePrint = usesAsyncRuntime
				? new JvmRuntimeBuilder.FuturePrint(cp.classEntry("java/util/concurrent/CompletableFuture"),
						cp.stringEntry("#<FUTURE>"), objectArrayClass, cp.stringEntry(JvmAsyncRuntimeBuilder.SMARKER),
						cp.stringEntry(JvmAsyncRuntimeBuilder.RMARKER), cp.stringEntry("#<STREAM>"))
				: null;

		// A function value prints as the interpreter's #<function NAME>, or #<lambda>
		// when the funcId has no name (anonymous lambdas, and the runtime sentinel).
		// _funName is the funcId -> name table, carrying a row for exactly the funcIds
		// the dispatchers take: a name the registry answers (registryFuncIds) or a
		// function materialized as a value -- every funcId a function object of this
		// run can carry. A program that never lets a NAMED function become a value
		// emits no table at all, and its closures print anonymous from a constant.
		TreeMap<Integer, StringEntry> funNameEntries = new TreeMap<>();
		for (Map.Entry<String, FunctionInfo> entry : functions.entrySet()) {
			if (dispatchableFuncIds.contains(entry.getValue().funcId())) {
				funNameEntries.put(entry.getValue().funcId(), cp.stringEntry(entry.getKey()));
			}
		}
		Utf8Entry funNameName = funNameEntries.isEmpty() ? null : cp.utf8Entry(JvmRuntimeBuilder.FUN_NAME_NAME);
		Utf8Entry funNameDescUtf = funNameEntries.isEmpty() ? null : cp.utf8Entry(JvmRuntimeBuilder.FUN_NAME_DESC);
		final JvmRuntimeBuilder.FuncPrint funcPrint = new JvmRuntimeBuilder.FuncPrint(
				funNameName == null ? null
						: cp.methodRef(thisClass, Objects.requireNonNull(funNameName),
								Objects.requireNonNull(funNameDescUtf)),
				integerClass, integerValue, stringConcat, cp.stringEntry("#<function "), cp.stringEntry(">"),
				cp.stringEntry("#<lambda>"));
		List<DispatchMethod> funNameMethods = funNameEntries.isEmpty() ? List.of()
				: JvmRuntimeBuilder.buildFunNameMethods(funNameEntries, cp, thisClass);

		// Instances print as #S(NAME :SLOT v ...) / #<NAME :SLOT v ...>. Every constant
		// is minted here, AFTER the body passes have interned whatever layouts the
		// program references and BEFORE .writeConstantPool(cp) freezes the pool, so an
		// instance-free program's pool -- and therefore its whole class -- is unchanged.
		final boolean usesInstances = !mainCtx.layoutPool.isEmpty();
		Utf8Entry instToStringName = usesInstances ? cp.utf8Entry("_instToString") : null;
		Utf8Entry instToDisplayStringName = usesInstances ? cp.utf8Entry("_instToDisplayString") : null;
		final JvmRuntimeBuilder.@Nullable InstPrint instPrint = usesInstances
				? new JvmRuntimeBuilder.InstPrint(mainCtx.layoutPool.stringArrayClass(cp),
						cp.methodRef(thisClass, Objects.requireNonNull(instToStringName), consToStringDescUtf),
						cp.methodRef(thisClass, Objects.requireNonNull(instToDisplayStringName), consToStringDescUtf))
				: null;
		// _strEsc: the *print-escape* escaping the readable renderer applies to a string
		// value's content (todo 216). Always emitted -- _lispToString is unconditional.
		Utf8Entry strEscName = cp.utf8Entry("_strEsc");
		Utf8Entry strEscDescUtf = cp.utf8Entry("(Ljava/lang/String;)Ljava/lang/String;");
		MethodRefEntry strEscMethod = cp.methodRef(thisClass, strEscName, strEscDescUtf);
		MethodRefEntry stringIndexOf = cp.methodRef(stringClass, "indexOf", "(I)I");
		MethodRefEntry stringIndexOfFrom = cp.methodRef(stringClass, "indexOf", "(II)I");
		MethodRefEntry stringReplace = cp.methodRef(stringClass, "replace",
				"(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;");
		// _symEsc: the |...|-escaping half of *print-escape* = t (todo 626), for a bare
		// symbol name -- _strEsc's own "this value is a symbol" arm routes here instead
		// of returning the name verbatim.
		Utf8Entry symEscName = cp.utf8Entry("_symEsc");
		MethodRefEntry symEscMethod = cp.methodRef(thisClass, symEscName, strEscDescUtf);
		MethodCode symEscCode = JvmRuntimeBuilder.buildSymEscBody(cp, stringLength, stringCharAt, stringIndexOf,
				stringSubstring, stringReplace, stringConcat);
		MethodCode strEscCode = JvmRuntimeBuilder.buildStrEscBody(cp, stringLength, stringCharAt, stringIndexOf,
				stringIndexOfFrom, stringSubstring, stringReplace, stringConcat, symEscMethod);
		// The quote/function abbreviation _consToString(Display) applies ahead of the
		// general list loop (todo 626): a 2-element (QUOTE x)/(FUNCTION x) cell prints
		// as 'x/#'x. objectEquals (Object.equals(Object)) is reused as the String
		// receiver's equals -- invokevirtual dispatches virtually regardless of the
		// methodref's declaring class.
		JvmRuntimeBuilder.QuoteAbbrevRefs quoteAbbrev = new JvmRuntimeBuilder.QuoteAbbrevRefs(stringClass, objectEquals,
				stringConcat, cp.stringEntry(LispNames.QUOTE), cp.stringEntry(LispNames.FUNCTION), cp.stringEntry("'"),
				cp.stringEntry("#'"));

		// Float text: every double spelling gets the lowercase exponent marker (the
		// FloatText contract), and a packed single-float array element prints through a
		// transient Float box at its f32 width.
		ClassEntry floatBoxClass = cp.classEntry("java/lang/Float");
		MethodRefEntry floatToString = cp.methodRef(floatBoxClass, "toString", "()Ljava/lang/String;");
		JvmRuntimeBuilder.FloatPrint floatPrint = new JvmRuntimeBuilder.FloatPrint(floatBoxClass, floatToString,
				stringReplace, cp.stringEntry("E"), cp.stringEntry("e"));

		// Build _lispToString and _consToString helper method bodies
		// The complex print branch names the travelling holder, so its
		// references are created only for a complex-capable program (null
		// otherwise, keeping the branch -- and the class -- out entirely).
		ClassEntry rcClass = usesComplex ? cp.classEntry("am/ik/rontolisp/runtime/RontoComplex") : null;
		FieldRefEntry rcReal = usesComplex ? cp.fieldRef(Objects.requireNonNull(rcClass), "real", "Ljava/lang/Object;")
				: null;
		FieldRefEntry rcImag = usesComplex ? cp.fieldRef(Objects.requireNonNull(rcClass), "imag", "Ljava/lang/Object;")
				: null;
		JvmRuntimeBuilder.@Nullable ComplexPrintRefs prin1Complex = usesComplex
				? new JvmRuntimeBuilder.ComplexPrintRefs(rcClass, rcReal, rcImag, lispToStringMethod,
						cp.stringEntry("#C("), spaceStr, cp.stringEntry(")"), hasComplexField)
				: null;
		JvmRuntimeBuilder.@Nullable ComplexPrintRefs princComplex = usesComplex
				? new JvmRuntimeBuilder.ComplexPrintRefs(rcClass, rcReal, rcImag, lispToDisplayStringMethod,
						cp.stringEntry("#C("), spaceStr, cp.stringEntry(")"), hasComplexField)
				: null;
		MethodCode ltsCode = JvmRuntimeBuilder.buildLispToStringBody(longClass, doubleClass, stringClass,
				objectArrayClass, integerClass, longToString, doubleToString, floatPrint, objectToString,
				consToStringMethod, nilStr, funcPrint, ratioArrayClass, stringConcat, slashStr, charBoxClass,
				charPrin1Method, arrayListClassForPrint, arrayToStringMethod, strvMethod, javaPrint, ffiPrint,
				futurePrint, packedPrint, packedIntPrint, instPrint, strEscMethod, hashPrint, prin1Complex);
		MethodCode ctsCode = JvmRuntimeBuilder.buildConsToStringBody(objectArrayClass, stringBuilderClass, sbInitStr,
				sbAppendStr, sbToString, lispToStringMethod, openParenStr, closeParenStr, spaceStr, dotStr,
				ratioArrayClass, renderGuard, quoteAbbrev);
		MethodCode ltdsCode = JvmRuntimeBuilder.buildLispToDisplayStringBody(longClass, doubleClass, stringClass,
				objectArrayClass, integerClass, longToString, doubleToString, floatPrint, objectToString,
				consToDisplayStringMethod, nilStr, funcPrint, stringCharAt, stringLength, stringSubstring,
				stringLastIndexOf, ratioArrayClass, stringConcat, slashStr, charBoxClass, characterToString,
				arrayListClassForPrint, arrayToDisplayStringMethod, strvMethod, javaPrint, ffiPrint, futurePrint,
				packedPrint, packedIntPrint, instPrint, hashPrint, princComplex);
		@Nullable MethodCode instCode = usesInstances ? JvmRuntimeBuilder.buildInstToStringBody(objectArrayClass,
				mainCtx.layoutPool.stringArrayClass(cp), stringBuilderClass, sbInitStr, sbAppendStr, sbToString,
				objectEquals, lispToStringMethod, cp.stringEntry("S"), cp.stringEntry("#S("), cp.stringEntry("#<"),
				closeParenStr, cp.stringEntry(">"), cp.stringEntry(" :"), spaceStr, cp.stringEntry("P"),
				cp.stringEntry("#P"), cp.stringEntry("O"), renderGuard) : null;
		@Nullable MethodCode instDisplayCode = usesInstances ? JvmRuntimeBuilder.buildInstToStringBody(objectArrayClass,
				mainCtx.layoutPool.stringArrayClass(cp), stringBuilderClass, sbInitStr, sbAppendStr, sbToString,
				objectEquals, lispToDisplayStringMethod, cp.stringEntry("S"), cp.stringEntry("#S("),
				cp.stringEntry("#<"), closeParenStr, cp.stringEntry(">"), cp.stringEntry(" :"), spaceStr,
				cp.stringEntry("P"), null, cp.stringEntry("O"), renderGuard) : null;
		MethodCode charPrin1Code = JvmRuntimeBuilder.buildCharPrin1Body(cp, stringConcat, characterToString);
		MethodCode ctdsCode = JvmRuntimeBuilder.buildConsToDisplayStringBody(objectArrayClass, stringBuilderClass,
				sbInitStr, sbAppendStr, sbToString, lispToDisplayStringMethod, openParenStr, closeParenStr, spaceStr,
				dotStr, ratioArrayClass, renderGuard, quoteAbbrev);
		MethodCode appendCode = JvmRuntimeBuilder.buildAppendBody(cp, thisClass, objectArrayClass, objectClass);
		StringEntry quoteStr = cp.stringEntry("\"");
		MethodCode readLineCode = JvmRuntimeBuilder.buildReadLineBody(bufferedReaderClass, inputStreamReaderClass,
				brInit, brReadLine, isrInit, systemIn, stdinReaderField, quoteStr, stringConcat);

		// File-stream runtime (open/close/write-line/read-line with a stream)
		MethodRefEntry stringLengthForIo = cp.methodRef(stringClass, "length", "()I");
		// TCP/TLS socket runtime (only when the program uses a rontolisp:tcp-* or
		// rontolisp:tls-connect built-in); built before the IO runtime so the stream
		// built-ins can grow socket branches.
		final JvmSecureRandomRuntimeBuilder.@Nullable SecureRandomRuntime secureRandomRuntime = usesSecureRandom
				? JvmSecureRandomRuntimeBuilder.build(cp, thisClass, longValueOf) : null;
		final JvmAsyncRuntimeBuilder.@Nullable AsyncMethod octetsPackedRuntime = usesOctetsPacked
				? JvmAsyncRuntimeBuilder.buildOctetsToString(cp) : null;
		final List<JvmMutexRuntimeBuilder.MutexMethod> mutexMethods = usesMutexes ? JvmMutexRuntimeBuilder.build(cp)
				: List.of();
		final JvmSocketRuntimeBuilder.@Nullable SocketRuntime socketRuntime = usesSockets
				? JvmSocketRuntimeBuilder.build(cp, thisClass, stringClass, longClass, longValueOf, longValue,
						stringLengthForIo, stringSubstring, stringConcat, usesArrays)
				: null;
		// *error-output* is the reserved stream handle 2 (the process standard error), so
		// a program that can name it -- explicitly, or through the warn redirect -- gets
		// the stream built-ins' stderr branch and the reserved table handles; one that
		// never mentions it keeps its original bytes. The warn redirect is taken whenever
		// the variable is a GLOBAL, which a thread-using program forces without naming it
		// (the make-thread bindings above), so that counts too: otherwise a warn wrote
		// through a stream table and a stderr branch this gate had left out.
		final boolean usesErrorOutput = programUsesSymbol(program, LispNames.ERROR_OUTPUT_VAR)
				|| globals.contains(LispNames.ERROR_OUTPUT_VAR);
		// The directory-LISTING helper joins the stream runtime only for a program that
		// calls the primitive, so every artifact compiled without one keeps its bytes.
		final boolean usesListDirectory = programUsesSymbol(program, LispNames.LIST_DIRECTORY);
		// The file-metadata helpers ride the same rule, one gate each: file-length also
		// grows _open and adds the _streamPaths side table, so a program that never asks
		// for it must not pay for either.
		// An indexed with-input-from-string asks its stream's position in an expansion
		// built after this census, so it counts as asking file-position.
		final boolean asksFilePosition = programUsesSymbol(program, LispNames.FILE_POSITION)
				|| (programUsesSymbol(program, LispNames.WITH_INPUT_FROM_STRING)
						&& LispMacroExpander.indexesStringInput(program));
		final JvmIoRuntimeBuilder.FileMeta fileMeta = new JvmIoRuntimeBuilder.FileMeta(
				programUsesSymbol(program, LispNames.FILE_WRITE_DATE),
				programUsesSymbol(program, LispNames.MAKE_DIRECTORIES),
				// file-position's :end (or a computed position that may be :end) resolves
				// through file-length (LispMacroExpander.rewriteFilePositionArg).
				programUsesSymbol(program, LispNames.FILE_LENGTH)
						|| LispMacroExpander.filePositionMayNeedLength(program),
				programUsesSymbol(program, LispNames.DELETE_FILE_INTERNAL),
				programUsesSymbol(program, LispNames.RENAME_FILE_INTERNAL), asksFilePosition,
				// A character file stream's position is real only through the travelling
				// positioned reader/writer, which a program whose every open is binary
				// does not need.
				programUsesSymbol(program, LispNames.FILE_POSITION)
						&& LispMacroExpander.mayOpenCharacterFileStream(program),
				// A string INPUT stream answers file-position only as the travelling
				// RontoStringInputStream, so both facts gate the position machinery
				// (and the class file travels with any string input stream, below).
				asksFilePosition && (programUsesSymbol(program, LispNames.WITH_INPUT_FROM_STRING)
						|| programUsesSymbol(program, LispNames.MAKE_STRING_INPUT_STREAM)
						|| programUsesSymbol(program, LispNames.MAKE_STRING_INPUT_STREAM_INTERNAL)),
				programUsesSymbol(program, LispNames.WITH_INPUT_FROM_STRING)
						|| programUsesSymbol(program, LispNames.MAKE_STRING_INPUT_STREAM)
						|| programUsesSymbol(program, LispNames.MAKE_STRING_INPUT_STREAM_INTERNAL));
		this.needsCharFileRuntime = fileMeta.characterPosition();
		// The BIDIRECTIONAL stream arm of _open, and with it the travelling
		// RontoIoFileStream class file, ride the surface fact that the program can ask
		// for one: every other artifact stays exactly one class file.
		this.needsIoStreamRuntime = LispMacroExpander.opensBidirectionally(program);
		this.needsStringInputRuntime = fileMeta.stringInputs();
		List<JvmIoRuntimeBuilder.IoMethod> ioMethods = JvmIoRuntimeBuilder
			.create(cp, thisClass, objectClass, stringClass, longClass, longValueOf, longValue, stringLengthForIo,
					stringSubstring, stringConcat, systemOut, printlnStr, readLineHelperMethod, socketRuntime,
					usesErrorOutput, usesListDirectory, fileMeta, usesPackedSequenceIo, usesCharSequenceIo, usesArrays,
					usesQuantized, this.needsIoStreamRuntime)
			.methods();
		if (flushStreamsMethod != null) {
			ioMethods.add(JvmFlushStreamsBuilder.build(cp, thisClass));
		}
		Utf8Entry streamsFieldName = cp.utf8Entry(JvmIoRuntimeBuilder.STREAMS_FIELD);
		Utf8Entry streamsFieldDesc = cp.utf8Entry(JvmIoRuntimeBuilder.STREAMS_DESC);
		final @Nullable Utf8Entry streamPathsFieldName = fileMeta.streamPaths()
				? cp.utf8Entry(JvmIoRuntimeBuilder.STREAM_PATHS_FIELD) : null;
		final @Nullable Utf8Entry streamPathsFieldDesc = fileMeta.streamPaths()
				? cp.utf8Entry(JvmIoRuntimeBuilder.STREAM_PATHS_DESC) : null;
		final @Nullable Utf8Entry streamPositionsFieldName = fileMeta.position()
				? cp.utf8Entry(JvmIoRuntimeBuilder.STREAM_POSITIONS_FIELD) : null;
		final @Nullable Utf8Entry streamPositionsFieldDesc = fileMeta.position()
				? cp.utf8Entry(JvmIoRuntimeBuilder.STREAM_POSITIONS_DESC) : null;
		Utf8Entry streamCountFieldName = cp.utf8Entry(JvmIoRuntimeBuilder.STREAM_COUNT_FIELD);
		Utf8Entry streamCountFieldDesc = cp.utf8Entry(JvmIoRuntimeBuilder.STREAM_COUNT_DESC);
		// Tracks whether stdout is at the start of a line (0 = at line start), so
		// fresh-line
		// can decide whether to emit a newline. A static int defaults to 0 (at line
		// start).
		Utf8Entry colFieldName = cp.utf8Entry(JvmFreshLineCompiler.COL_FIELD);
		Utf8Entry colFieldDesc = cp.utf8Entry(JvmFreshLineCompiler.COL_DESC);
		Utf8Entry gensymCtrFieldName = cp.utf8Entry(JvmGensymCompiler.CTR_FIELD);
		Utf8Entry gensymCtrFieldDesc = cp.utf8Entry(JvmGensymCompiler.CTR_DESC);

		// fetch runtime helper body (only when the program uses rontolisp:fetch; the
		// generic _await lives in the async runtime).
		final JvmFetchRuntimeBuilder.@Nullable FetchRuntime fetchRuntimeBodies = usesFetch ? JvmFetchRuntimeBuilder
			.build(cp, objectArrayClass, stringClass, stringLength, stringSubstring, usesArrays
					? cp.methodRef(thisClass, JvmArrayRuntimeBuilder.STRV, JvmArrayRuntimeBuilder.STRV_DESC) : null)
				: null;

		// The sized-stack launcher (.kb/interpreter-stack.md): main runs the program on a
		// thread of its own, sized by -Drontolisp.stack, and the old main body becomes
		// _main$body. Not where there is no main, not where the top level runs in
		// <clinit> (a jvm-export library, a war: the JVM initializes the class on the
		// caller's thread before main could move anything). A program that reaches objc:
		// also hands thread 0 to the run loop when it is a native image's main
		// (.kb/objc.md, "AppKit belongs to thread 0").
		final JvmSizedMainBuilder.@Nullable SizedMain sizedMain = !this.noMain && !topLevelInClinit
				? JvmSizedMainBuilder.build(cp, thisClass, this.className, cp.methodRef(thisClass, "<init>", "()V"),
						cp.utf8Entry("([Ljava/lang/String;)V"),
						usesObjc ? JvmObjcRuntimeBuilder.mainThreadName(this.className) : null)
				: null;
		final MethodRefEntry ctorObjectInitRef = objectInitRef != null ? objectInitRef
				: sizedMain != null ? cp.methodRef(objectClass, "<init>", "()V") : null;
		final Utf8Entry ctorName = instanceInitName != null ? instanceInitName
				: sizedMain != null ? cp.utf8Entry("<init>") : null;
		final Utf8Entry ctorDesc = instanceInitDesc != null ? instanceInitDesc
				: sizedMain != null ? cp.utf8Entry("()V") : null;

		// The async/await runtime: %async-run + run() (the class implements Runnable),
		// the generic _await, streams and predicates. It rides the condition channel
		// (the error payload re-signals typed conditions across the await), so the
		// channel is forced on.
		final JvmAsyncRuntimeBuilder.@Nullable AsyncRuntime asyncRuntimeBodies;
		final @Nullable ClassEntry runnableClass;
		if (usesAsyncRuntime) {
			mainCtx.conditionChannel.ensure(cp, this.className);
			MethodRefEntry progInitForAsync = cp.methodRef(thisClass, "<init>", "()V");
			asyncRuntimeBodies = JvmAsyncRuntimeBuilder.build(cp, thisClass, objectClass, objectArrayClass, stringClass,
					mainCtx.conditionChannel, progInitForAsync, longValueOf, stringLength, stringSubstring,
					stringConcat, sizedMain != null ? sizedMain.runRef() : null, mainCtx.mvChannel,
					recordsAsyncBoundaries ? cp.methodRef(thisClass, JvmUncaughtHandler.ASYNC_AWAITED_METHOD,
							JvmUncaughtHandler.ASYNC_AWAITED_DESC) : null);
			runnableClass = cp.classEntry("java/lang/Runnable");
		}
		else {
			asyncRuntimeBodies = null;
			runnableClass = sizedMain != null ? sizedMain.runnableClass() : null;
		}
		// The launcher's instance run(): the async runtime's run() carries the same
		// dispatch as a prefix when both exist.
		final JvmSizedMainBuilder.@Nullable Method sizedMainInstanceRun = sizedMain != null
				&& asyncRuntimeBodies == null ? sizedMain.instanceRun(cp) : null;
		// The thread runtime: _thread_spawn + call() (the class implements Callable),
		// join/alive/destroy/threadp and the _dtl name-to-ThreadLocal dispatch. It rides
		// the condition channel (call()'s error payload re-signals typed conditions
		// across the join, the _await pattern).
		final JvmThreadRuntimeBuilder.@Nullable ThreadRuntime threadRuntimeBodies;
		final @Nullable ClassEntry callableClass;
		final @Nullable FieldRefEntry curThreadTlFieldRef;
		final @Nullable Utf8Entry curThreadTlFieldName;
		final @Nullable Utf8Entry curThreadTlFieldDesc;
		if (usesThreads) {
			mainCtx.conditionChannel.ensure(cp, this.className);
			MethodRefEntry progInitForThread = cp.methodRef(thisClass, "<init>", "()V");
			// The per-thread handle cache behind _thread_current (declared below,
			// initialized in <clinit> next to the condition channel's ThreadLocals).
			curThreadTlFieldName = cp.utf8Entry(JvmThreadRuntimeBuilder.CURRENT_TL_FIELD);
			curThreadTlFieldDesc = cp.utf8Entry("Ljava/lang/ThreadLocal;");
			curThreadTlFieldRef = cp.fieldRef(thisClass, curThreadTlFieldName, curThreadTlFieldDesc);
			threadRuntimeBodies = JvmThreadRuntimeBuilder.build(cp, thisClass, objectClass, objectArrayClass,
					stringClass, mainCtx.conditionChannel, progInitForThread, stringConcat,
					java.util.Objects.requireNonNull(dynVarRuntime), curThreadTlFieldRef);
			callableClass = cp.classEntry("java/util/concurrent/Callable");
		}
		else {
			threadRuntimeBodies = null;
			callableClass = null;
			curThreadTlFieldRef = null;
			curThreadTlFieldName = null;
			curThreadTlFieldDesc = null;
		}
		final @Nullable Utf8Entry threadFnFieldName = usesThreads ? cp.utf8Entry(JvmThreadRuntimeBuilder.FN_FIELD)
				: null;
		final @Nullable Utf8Entry threadBindingsFieldName = usesThreads
				? cp.utf8Entry(JvmThreadRuntimeBuilder.BINDINGS_FIELD) : null;
		final @Nullable Utf8Entry threadInstanceFieldDesc = usesThreads ? cp.utf8Entry("Ljava/lang/Object;") : null;
		final @Nullable Utf8Entry handoffFieldName = usesAsyncRuntime
				? cp.utf8Entry(JvmAsyncRuntimeBuilder.HANDOFF_FIELD) : null;
		final @Nullable Utf8Entry handoffFieldDesc = usesAsyncRuntime ? cp.utf8Entry("Ljava/lang/ThreadLocal;") : null;
		final @Nullable Utf8Entry asyncFnFieldName = usesAsyncRuntime ? cp.utf8Entry(JvmAsyncRuntimeBuilder.FN_FIELD)
				: null;
		final @Nullable Utf8Entry asyncFutureFieldName = usesAsyncRuntime
				? cp.utf8Entry(JvmAsyncRuntimeBuilder.FUTURE_FIELD) : null;
		final @Nullable Utf8Entry asyncLatchFieldName = usesAsyncRuntime
				? cp.utf8Entry(JvmAsyncRuntimeBuilder.LATCH_FIELD) : null;
		final @Nullable Utf8Entry asyncInstanceFieldDesc = usesAsyncRuntime ? cp.utf8Entry("Ljava/lang/Object;") : null;
		final @Nullable FieldRefEntry handoffFieldRef = usesAsyncRuntime ? cp.fieldRef(thisClass,
				java.util.Objects.requireNonNull(handoffFieldName), java.util.Objects.requireNonNull(handoffFieldDesc))
				: null;

		// length runtime helper. Emitted unconditionally (it is small and lives in its
		// own
		// method): length is also generated internally by other compilers (e.g. format
		// padding), so a source-symbol gate would miss those call sites. The whole
		// computation lives in one method so each call site is a single invokestatic,
		// keeping main within the JVM's 64 KB per-method limit.
		final JvmLengthRuntimeBuilder.LengthMethod lengthMethodBody = JvmLengthRuntimeBuilder.build(cp,
				objectArrayClass, stringClass, longValueOf, thisClass,
				javaSites != null ? javaSites.direct().lispArray() : null);

		// nthcdr runtime helper. Emitted unconditionally for the same reason _length is:
		// nthcdr is generated internally by a long tail of expanders (nth, elt, loop's
		// list stepping, destructuring-bind, format's ~* family), so a source-symbol gate
		// would miss those call sites -- and the body is a few dozen bytes. It exists as
		// a method
		// at all so its loop's backedge sits at operand stack depth 0, the only shape
		// HotSpot will OSR-compile (JvmNthcdrRuntimeBuilder).
		final JvmNthcdrRuntimeBuilder.NthcdrMethod nthcdrMethodBody = JvmNthcdrRuntimeBuilder.build(cp, consShape,
				thisClass);
		// elt's list walk (%elt-cell), unconditional for the same reason: every elt and
		// (setf elt) expansion reaches it, and so do the built-in wrappers and library
		// bodies that read with (elt seq i). The class shaker drops it when unused.
		final JvmEltCellRuntimeBuilder.EltCellMethod eltCellMethodBody = JvmEltCellRuntimeBuilder.build(cp, consShape,
				thisClass);

		// The &optional surplus-argument message (%arity-surplus-message). Emitted
		// unconditionally like _nthcdr: its sites are the lambda-list prologue, which the
		// built-in wrappers and several expansions produce while this backend compiles,
		// and the class shaker drops it from a program that never checks.
		final JvmAritySurplusRuntimeBuilder.AritySurplusMethod aritySurplusMethodBody = JvmAritySurplusRuntimeBuilder
			.build(cp, objectArrayClass);

		// The destructuring missing-element message (%arity-missing-message). Emitted
		// unconditionally beside _aritySurplus: its sites are the destructuring
		// prologue, which destructuring-bind expansions produce while this backend
		// compiles, and the class shaker drops it from a program that never checks.
		final JvmAritySurplusRuntimeBuilder.ArityMissingMethod arityMissingMethodBody = JvmAritySurplusRuntimeBuilder
			.buildMissing(cp);

		// The character-index helpers (_cpoff / _scount) every string index and every
		// string length reads through. Emitted unconditionally for the same reason
		// _length is: the sites are generated internally too, and the pair is ~60 bytes.
		final List<JvmStringIndexRuntimeBuilder.StringIndexMethod> stringIndexMethods = JvmStringIndexRuntimeBuilder
			.build(cp, thisClass, stringClass, usesArrays);
		final List<Utf8Entry> stringIndexFieldNames = java.util.Arrays.stream(JvmStringIndexRuntimeBuilder.FIELDS)
			.map(cp::utf8Entry)
			.toList();
		final Utf8Entry stringIndexFieldDesc = cp.utf8Entry(JvmStringIndexRuntimeBuilder.FIELD_DESC);
		final List<Utf8Entry> stringIndexWideFieldNames = java.util.Arrays
			.stream(JvmStringIndexRuntimeBuilder.WIDE_FIELDS)
			.map(cp::utf8Entry)
			.toList();
		final Utf8Entry stringIndexWideFieldDesc = cp.utf8Entry(JvmStringIndexRuntimeBuilder.WIDE_FIELD_DESC);

		Utf8Entry mainUtf8 = cp.utf8Entry("main");
		Utf8Entry mainDesc = cp.utf8Entry("([Ljava/lang/String;)V");
		Utf8Entry codeUtf8 = cp.utf8Entry("Code");
		// The typed jvm-export wrapper methods (and their marshalling helpers), plus
		// the _top$run method <clinit> calls to run the top level (see mainCtx above).
		// Built here so every constant they mint precedes the pool serialization below.
		final Utf8Entry topRunnerName = topLevelInClinit ? cp.utf8Entry("_top$run") : null;
		final MethodRefEntry topRunnerRef = topRunnerName == null ? null
				: cp.methodRef(thisClass, topRunnerName, topChunkDesc);
		final List<JvmExportRuntimeBuilder.BuiltMethod> exportMethods = exportDecls.isEmpty() ? List.of()
				: JvmExportRuntimeBuilder.build(cp, thisClass, exportDecls, functions, usesArrays);

		// Effectively-final aliases for capture in the writer lambda
		final Ctx topRunnerCtxFinal = topRunnerCtx;
		final MethodCode evalBody = evalCode;
		final MethodCode applyBody = applyCode;
		final MethodCode applyEntryBody = applyEntryCode;
		final MethodCode storeBody = storeCode;
		final MethodCode envLookupBody = envLookupCode;
		final List<MethodCode> lookupBodies = lookupSegments;

		// A program that redirects *standard-output* (the variable is in globals only
		// then) seeds its global default from StreamDesignators' table -- the designator
		// t = stdout for the two stdio variables, a stream VALUE over handle 2 for
		// *error-output*, which t cannot name; the constants are minted here for the
		// same serialization-order reason as the layout half below.
		final Map<String, FieldRefEntry> streamGlobalSeeds = new LinkedHashMap<>();
		// The eval runtime's global-environment mirror is the SECOND home of the same
		// value, and symbol-value/boundp/eval read only that one -- so it seeds from the
		// same table, or a variable the field seeding just bound reads back as unbound
		// there (.kb/symbol-runtime-api.md). Gated on the name appearing in the source,
		// which keeps a program that never mentions one byte-identical AND is the very
		// scan the --component stderr narrowing uses, so the two cannot disagree about
		// whether the reserved handle 2 is reachable.
		final Map<String, StringEntry> streamGenvSeeds = new LinkedHashMap<>();
		boolean seedsTDesignator = false;
		for (Map.Entry<String, LispVal> streamVar : StreamDesignators.standardStreamDefaults().entrySet()) {
			FieldRefEntry globalField = globalFields.get(streamVar.getKey());
			if (globalField != null) {
				streamGlobalSeeds.put(streamVar.getKey(), globalField);
			}
			if (usesEval && programUsesSymbol(program, streamVar.getKey())) {
				streamGenvSeeds.put(streamVar.getKey(), cp.stringEntry(streamVar.getKey()));
			}
			seedsTDesignator |= streamVar.getValue() instanceof LispTrue
					&& (globalField != null || streamGenvSeeds.containsKey(streamVar.getKey()));
		}
		final boolean seedsStandardStream = !streamGlobalSeeds.isEmpty() || !streamGenvSeeds.isEmpty();
		final StringEntry standardOutputTStr = seedsTDesignator ? cp.stringEntry("T") : null;
		// *error-output*'s default is a stream VALUE, so its layout constant has to be
		// interned BEFORE the layout half of <clinit> is assembled just below -- and
		// emitted before the seed reads it, which is why the seed loop comes after
		// layoutClinitCode in the <clinit> assembly.
		boolean seedsStreamValue = false;
		for (Map.Entry<String, LispVal> streamVar : StreamDesignators.standardStreamDefaults().entrySet()) {
			seedsStreamValue |= streamVar.getValue() instanceof LispCons
					&& (streamGlobalSeeds.containsKey(streamVar.getKey())
							|| streamGenvSeeds.containsKey(streamVar.getKey()));
		}
		final @Nullable FieldRefEntry streamLayoutField = seedsStreamValue
				? mainCtx.layoutPool.intern(cp, className, am.ik.rontolisp.LispLayout.STREAM) : null;
		final @Nullable StringEntry streamKindStandardStr = seedsStreamValue
				? cp.stringEntry(am.ik.rontolisp.LispLayout.Kinds.STANDARD) : null;
		// The layout half of <clinit>, assembled HERE because it mints CONSTANT_String
		// entries and the constant pool is serialized by .writeConstantPool(cp) below,
		// before the writeFields/writeMethods lambdas run.
		final MethodCode layoutClinitCode = new MethodCode();
		mainCtx.layoutPool.emitClinitInit(layoutClinitCode, cp);
		// The bignum-literal half of <clinit>, assembled HERE for the same reason: it
		// mints the CONSTANT_String decimal forms, and the pool is complete because
		// every body (defun, top-level chunk, lambda, outlined fused site) has been
		// compiled by now. Empty for a program with no bignum literal, which is then
		// emitted byte for byte as before.
		final MethodCode bigIntClinitCode = new MethodCode();
		mainCtx.bigIntPool.emitClinitInit(bigIntClinitCode, cp);
		// When the standard-stream handles are reserved, the stream table must EXIST
		// from the start with those slots empty. _addStream reserves the COUNT, but it
		// runs only when something is opened -- while the reserved handle 2 is a live
		// stream designator in a program that opens nothing. A helper that indexes the
		// table from a raw handle ahead of its stderr branch (the socket probes in
		// _writeString) then reads an empty slot instead of a null table.
		final @Nullable FieldRefEntry streamsFieldRef = usesErrorOutput
				? cp.fieldRef(thisClass, streamsFieldName, streamsFieldDesc) : null;
		final @Nullable FieldRefEntry streamCountFieldRef = usesErrorOutput
				? cp.fieldRef(thisClass, streamCountFieldName, streamCountFieldDesc) : null;
		final boolean initsClinit = seedsStandardStream || usesErrorOutput || topLevelInClinit || usesComplex;
		final Utf8Entry standardOutputClinitName = initsClinit ? cp.utf8Entry("<clinit>") : null;
		final Utf8Entry standardOutputClinitDesc = initsClinit ? cp.utf8Entry("()V") : null;

		// The fusion helpers, built HERE (before assembly) because their bodies mint
		// constant-pool entries: _ubRead whenever a raw local exists, _fxAsh whenever a
		// fused fast path shifts.
		final List<JvmNumericRuntimeBuilder.NumericMethod> fusedHelperMethods = new ArrayList<>();
		if (fusedState.usesUbRead) {
			fusedHelperMethods.add(JvmIntFusionCompiler.buildUbRead(cp, longValueOf));
		}
		if (fusedState.usesFxAsh) {
			fusedHelperMethods.add(JvmIntFusionCompiler.buildFxAsh(cp));
		}

		// The java: sites' failure helpers, made now that every body is compiled: whether
		// a function can be called back from Java -- a generated implementation's
		// callback, the bridge's Proxy -- decides the per-thread record _jsigTl and what
		// _jfail reads (JvmJavaDirectSites#finishHelpers).
		final JvmJavaDirectSites.@Nullable Signals javaSignals = javaSites != null
				? javaSites.direct().finishHelpers(usesJavaBridge, mainCtx.conditionChannel) : null;
		if (javaSignals != null) {
			mainCtx.conditionChannel.ensureThreadLocalInfra(cp);
		}

		// The class as data first: a program whose pool fits one class file is written
		// from it as it always was; one whose pool outgrew it is split from it
		// (.kb/jvm-method-size-limits.md).
		ClassDefinition.Builder definition = ClassDefinition.builder(cp, AccessFlag.ACC_PUBLIC | AccessFlag.ACC_SUPER,
				thisClass, objectClass, codeUtf8);
		if (x509TrustManagerClass != null) {
			definition.addInterface(x509TrustManagerClass);
		}
		if (httpHandlerRuntime != null) {
			definition.addInterface(httpHandlerRuntime.handlerInterface());
		}
		if (runnableClass != null) {
			definition.addInterface(runnableClass);
		}
		if (callableClass != null) {
			definition.addInterface(callableClass);
		}

		definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, stdinReaderFieldName, stdinReaderFieldDesc);
		if (usesComplex) {
			// The holder-presence probe (.todo/757): whether the travelling
			// RontoComplex class resolved, set once in <clinit> below.
			// Final (a JIT constant after class init), and attribute-free
			// like every field a ClassDefinition declares. The <clinit> store
			// keeps the field alive for the shake exactly when the class needs
			// it.
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_FINAL,
					java.util.Objects.requireNonNull(hasComplexName), java.util.Objects.requireNonNull(hasComplexDesc));
		}
		if (secureRandomRuntime != null) {
			definition.addField(JvmSecureRandomRuntimeBuilder.fieldAccessFlags(), secureRandomRuntime.fieldName(),
					secureRandomRuntime.fieldDesc());
		}
		if (argvRuntime != null) {
			definition.addField(JvmArgvRuntimeBuilder.fieldAccessFlags(), argvRuntime.fieldName(),
					argvRuntime.fieldDesc());
		}
		// VOLATILE: the synchronized _addStream writes the table back on every
		// call, and that store is what publishes a new entry to the reader
		// threads (one virtual thread per served request).
		definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_VOLATILE, streamsFieldName,
				streamsFieldDesc);
		definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, streamCountFieldName, streamCountFieldDesc);
		if (streamPathsFieldName != null) {
			// VOLATILE for the same reason _streams is: _setStreamPath is
			// synchronized and its write-back publishes the table.
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_VOLATILE,
					streamPathsFieldName, java.util.Objects.requireNonNull(streamPathsFieldDesc));
		}
		if (streamPositionsFieldName != null) {
			// VOLATILE for the same reason _streams is: _storeStreamPosition is
			// synchronized and its write-back publishes the table.
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_VOLATILE,
					streamPositionsFieldName, java.util.Objects.requireNonNull(streamPositionsFieldDesc));
		}
		// The renderers' cycle guard: the current rendering path (lazily
		// allocated) and its depth, shared by the two escape modes of the
		// instance, cons and array renderers. Unconditional -- the cons
		// renderer is in every class. Not volatile: the guard's emitted reads
		// are bounds-checked so a rendering race between request threads can at
		// worst misplace a "#" marker; the interpreter twin is a ThreadLocal
		// (RenderCycleGuard).
		definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, renderPathFieldName, renderPathFieldDesc);
		definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, renderDepthFieldName, renderDepthFieldDesc);
		definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, colFieldName, colFieldDesc);
		definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, gensymCtrFieldName, gensymCtrFieldDesc);
		// The two strings last PROVEN to hold no surrogate pair, so a character
		// index into one is 1 + i. Deliberately NOT volatile: a String is
		// immutable and a reference field is written atomically, so a racing
		// reader sees an older string (a re-probe) but never a torn pair.
		for (Utf8Entry siName : stringIndexFieldNames) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, siName, stringIndexFieldDesc);
		}
		// The two breakpoint tables, for the strings that DO hold a surrogate
		// pair. VOLATILE, unlike the pair above: each slot is an Object[]{string,
		// table} whose table was FILLED before the slot was stored, and a plain
		// store publishes neither the second element nor the table's contents --
		// a racing reader could match the string and then read an unwritten
		// offset, answering a position inside the framing quote. The release
		// fence is the publication (.kb/string-index-cost.md).
		for (Utf8Entry siName : stringIndexWideFieldNames) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_VOLATILE, siName,
					stringIndexWideFieldDesc);
		}
		if (httpHandlerRuntime != null) {
			// VOLATILE: the handler slot is written once by the thread that runs
			// the top level and read by every request thread afterwards. On the
			// socket transports the server thread is started AFTER the write, so
			// Thread.start() published it; a SERVLET war has no such edge -- the
			// container's request threads exist already, and a clack:clackup left
			// at :use-thread t writes the slot from a thread of its own -- so the
			// field carries the publication itself. One volatile read per request
			// is not measurable against an HTTP round trip.
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_VOLATILE,
					httpHandlerRuntime.handlerFieldName(), httpHandlerRuntime.handlerFieldDesc());
		}
		if (sizedMain != null) {
			// The launcher instance's two fields: main's arguments in, the
			// body's throwable out (published by Thread.join).
			definition.addField(AccessFlag.ACC_PRIVATE, sizedMain.argsName(), sizedMain.argsDesc());
			definition.addField(AccessFlag.ACC_PRIVATE, sizedMain.thrownName(), sizedMain.thrownDesc());
			if (sizedMain.exitName() != null) {
				definition.addField(AccessFlag.ACC_PRIVATE, sizedMain.exitName(),
						java.util.Objects.requireNonNull(sizedMain.exitDesc()));
			}
		}
		if (asyncRuntimeBodies != null) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC,
					java.util.Objects.requireNonNull(handoffFieldName),
					java.util.Objects.requireNonNull(handoffFieldDesc));
			for (Utf8Entry instField : List.of(java.util.Objects.requireNonNull(asyncFnFieldName),
					java.util.Objects.requireNonNull(asyncFutureFieldName),
					java.util.Objects.requireNonNull(asyncLatchFieldName))) {
				definition.addField(AccessFlag.ACC_PRIVATE, Objects.requireNonNull(instField),
						Objects.requireNonNull(asyncInstanceFieldDesc));
			}
		}
		if (mvChannel != null && mvChannel.perThread() != null) {
			JvmMvChannel.PerThread mvPerThread = mvChannel.perThread();
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, mvPerThread.threadLocalName(),
					mvPerThread.threadLocalDesc());
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, mvPerThread.ownerName(),
					mvPerThread.ownerDesc());
		}
		if (threadRuntimeBodies != null) {
			for (Utf8Entry instField : List.of(java.util.Objects.requireNonNull(threadFnFieldName),
					java.util.Objects.requireNonNull(threadBindingsFieldName))) {
				definition.addField(AccessFlag.ACC_PRIVATE, Objects.requireNonNull(instField),
						Objects.requireNonNull(threadInstanceFieldDesc));
			}
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC,
					java.util.Objects.requireNonNull(curThreadTlFieldName),
					java.util.Objects.requireNonNull(curThreadTlFieldDesc));
		}
		// One static Object field per top-level global variable (default null =
		// nil); written by setq/defvar, read by getstatic from any method body.
		for (Utf8Entry gfName : globalFieldNameUtfs) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, gfName, globalFieldDescUtf);
		}
		// The raw long half and the int flag of an unboxed global's triple; the
		// _g$ field above is its boxed shadow. Both default to 0, so the flag
		// starts clear and the shadow's null (nil) is authoritative -- exactly
		// the state a plain global starts in.
		for (Utf8Entry rgName : rawGlobalLongFieldNameUtfs) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, rgName,
					Objects.requireNonNull(rawGlobalLongDescUtf));
		}
		for (Utf8Entry rkName : rawGlobalFlagFieldNameUtfs) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, rkName,
					Objects.requireNonNull(rawGlobalFlagDescUtf));
		}
		if (dynVarRuntime != null) {
			// One static ThreadLocal per dynamically-bound special: the thread's
			// innermost dynamic binding as a one-element Object[] cell (see
			// JvmDynVarRuntimeBuilder); created in <clinit>.
			for (Utf8Entry dfName : dynVarRuntime.fieldNameUtfs()) {
				definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, dfName,
						dynVarRuntime.fieldDescUtf());
			}
		}
		if (unboundMarker != null) {
			// The UNBOUND marker a probed valueless global's _g$ starts as; set in
			// <clinit> after the ThreadLocals.
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, unboundMarker.fieldName(),
					unboundMarker.fieldDesc());
		}
		if (javaRuntime != null) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, javaRuntime.initedFieldName(),
					javaRuntime.initedFieldDesc());
		}
		if (objcRuntime != null) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, objcRuntime.initedFieldName(),
					objcRuntime.initedFieldDesc());
		}
		if (ffiRuntime != null) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, ffiRuntime.initedFieldName(),
					ffiRuntime.initedFieldDesc());
		}
		if (simdRuntime != null) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, simdRuntime.initedFieldName(),
					simdRuntime.initedFieldDesc());
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, simdRuntime.availableFieldName(),
					simdRuntime.availableFieldDesc());
		}
		if (gpuRuntime != null) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, gpuRuntime.initedFieldName(),
					gpuRuntime.initedFieldDesc());
		}
		if (geomRuntime != null) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, geomRuntime.initedFieldName(),
					geomRuntime.initedFieldDesc());
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, geomRuntime.availableFieldName(),
					geomRuntime.availableFieldDesc());
		}
		if (usesEval) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, genvName, genvDesc);
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, fenvName, genvDesc);
		}
		if (usesRead) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, readSrcName, readSrcDesc);
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, readPosName, readPosDesc);
			// How the last read-from-string parse ended
			// (JvmReadRuntimeBuilder.FAIL_FIELD).
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, Objects.requireNonNull(readFailName),
					readPosDesc);
		}
		if (structTableClinitFinal != null) {
			// The runtime struct-layout directory for #S(...) read at run time.
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, rdStructsName, rdStructsDesc);
		}
		if (mainCtx.conditionChannel.used) {
			// The per-thread condition carrier from a %error-cond throw site to a
			// handler-case catch handler; initialized in <clinit>.
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC,
					java.util.Objects.requireNonNull(mainCtx.conditionChannel.fieldName),
					java.util.Objects.requireNonNull(mainCtx.conditionChannel.fieldDesc));
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC,
					java.util.Objects.requireNonNull(mainCtx.conditionChannel.depthFieldName),
					java.util.Objects.requireNonNull(mainCtx.conditionChannel.fieldDesc));
		}
		if (teTlName != null && teTlDesc != null) {
			// The per-thread record of the last wrong-type operand's exception, datum
			// and type (JvmOperandTypeRuntime); initialized in <clinit>.
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, teTlName, teTlDesc);
		}
		if (mainCtx.conditionChannel.nleUsed) {
			// The per-thread non-local-exit carrier from a %nlx-throw site to the
			// matching %nlx-catch (a {throwable, id, value} Object[]);
			// initialized
			// in <clinit>.
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC,
					java.util.Objects.requireNonNull(mainCtx.conditionChannel.nleFieldName),
					java.util.Objects.requireNonNull(mainCtx.conditionChannel.fieldDesc));
		}
		if (javaSignals != null) {
			// The per-thread record of what functions called back from Java raised, from
			// _jsig to the _jfail of the site the Java call was made from; initialized in
			// <clinit>.
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, javaSignals.name(), javaSignals.desc());
		}
		if (mainCtx.conditionChannel.javaExceptionsField != null) {
			// What each java: member that threw threw, keyed by _jfail's wrapper;
			// initialized in <clinit>.
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC,
					java.util.Objects.requireNonNull(mainCtx.conditionChannel.javaExceptionsName),
					java.util.Objects.requireNonNull(mainCtx.conditionChannel.javaExceptionsDesc));
		}
		// One private static String[] per instance layout the program references:
		// {tag, printName, "S"|"C", slot0, ...}. Initialized in <clinit>; the
		// array in slot 0 of an instance is also its type discriminator.
		for (LayoutPool.LayoutField lf : mainCtx.layoutPool.fields()) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, lf.name(),
					java.util.Objects.requireNonNull(mainCtx.layoutPool.fieldDesc));
		}
		// One private static BigInteger per DISTINCT bignum literal, built once
		// in <clinit> so a use site is a GETSTATIC. The field is not marked
		// ACC_FINAL-with-ConstantValue: a BigInteger has no constant-pool form.
		for (BigIntPool.BigIntField bf : mainCtx.bigIntPool.fields()) {
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, bf.name(),
					java.util.Objects.requireNonNull(mainCtx.bigIntPool.fieldDesc));
		}
		// The quoted-datum table (JvmQuotePool): its field and the two helpers that
		// read and fill it, in a class with a quoted aggregate. Every body is built by
		// now, so no new slot may be interned past this point (the table's size is
		// baked into _qdSet); the shake drops all three with the last site.
		mainCtx.quotePool.freeze();
		if (mainCtx.quotePool.used()) {
			JvmQuotePool.Members table = mainCtx.quotePool.members();
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, table.fieldName(), table.fieldDesc());
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, table.getName(), table.getDesc(),
					table.getCode());
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED,
					table.setName(), table.setDesc(), table.setCode());
		}
		// The UNSUPPLIED marker (JvmUnsupplied): its field and the two helpers that
		// create it on first use, in a class with a callee that takes physical
		// optionals. Every body and dispatcher is built by now, so nothing may reference
		// it for the first time past this point; the shake drops all three with the last
		// caller.
		mainCtx.unsupplied.freeze();
		if (mainCtx.unsupplied.used()) {
			JvmUnsupplied.Members marker = mainCtx.unsupplied.members();
			definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, marker.fieldName(), marker.fieldDesc());
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, marker.accessorName(),
					marker.methodDesc(), marker.accessorCode());
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED,
					marker.initName(), marker.methodDesc(), marker.initCode());
			MethodCode optArgCode = marker.optArgCode();
			if (optArgCode != null) {
				definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC,
						Objects.requireNonNull(marker.optArgName()), Objects.requireNonNull(marker.optArgDesc()),
						optArgCode);
			}
		}

		if (sizedMain != null) {
			// The launcher is main; the program body keeps its code under
			// _main$body, reached from the worker through run().
			for (JvmSizedMainBuilder.Method sm : java.util.Arrays.asList(sizedMain.main(), sizedMain.run(),
					sizedMainInstanceRun)) {
				if (sm == null) {
					continue;
				}
				int access = sm == sizedMain.main() ? AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC
						: sm == sizedMain.run() ? AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC
								: AccessFlag.ACC_PUBLIC;
				definition.addMethod(access, sm.name(), sm.desc(), sm.code());
			}
		}
		if (!this.noMain) {
			mainCtx.addTo(definition,
					sizedMain != null ? AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC
							: AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC,
					sizedMain != null ? sizedMain.bodyName() : mainUtf8, mainDesc);
		}
		if (topRunnerCtxFinal != null) {
			// _top$run: the top-level body <clinit> runs (see mainCtx above).
			topRunnerCtxFinal.addTo(definition, AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC,
					java.util.Objects.requireNonNull(topRunnerName), topChunkDesc);
		}
		for (JvmExportRuntimeBuilder.BuiltMethod em : exportMethods) {
			definition.addMethod(
					(em.isPublic() ? AccessFlag.ACC_PUBLIC : AccessFlag.ACC_PRIVATE) | AccessFlag.ACC_STATIC, em.name(),
					em.desc(), em.code());
		}
		// The top-level body, split into one or more void chunk methods main()
		// calls.
		for (int i = 0; i < topChunks.size(); i++) {
			topChunks.get(i)
				.addTo(definition, AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, topChunkNames.get(i), topChunkDesc);
		}
		for (int i = 0; i < defuns.size(); i++) {
			FunctionInfo fi = java.util.Objects.requireNonNull(functions.get(defuns.get(i).name));
			funcCtxs.get(i).addTo(definition, AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, fi.nameUtf8, fi.descUtf8);
		}
		for (int i = 0; i < lambdaCtxs.size(); i++) {
			FunctionInfo fi = lambdaFuncInfos.get(i);
			lambdaCtxs.get(i)
				.addTo(definition, AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, fi.nameUtf8, fi.descUtf8);
		}
		for (DispatchMethod dm : dispatchMethods) {
			definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, dm.nameUtf8, dm.descUtf8, dm.code);
		}
		// The trampoline loop the unwrap checks share: re-enters the dispatcher of the
		// bounce's arity until a real value comes back (JvmTailBounce). _unw, which every
		// caller of a compiled function's result calls, is left empty here and written
		// once the class is assembled: it hands a bounce to _tramp only when a method
		// the shake keeps can bounce, and answers its argument otherwise -- _tramp, then
		// reached from nothing, is shaken away with everything only it reached.
		MethodCode unwCode = new MethodCode();
		// A tail call through a value calls _vtc<n> (_vtcv for an apply) with its
		// method's depth: a real call while the depth stays under the limit, a bounce
		// otherwise -- _vtcb<n> makes it. _tramp re-enters with the limit as the depth,
		// so the chain it drives keeps bouncing. Only the value tails' bodies reach
		// these, so the shake drops them with _tramp.
		definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, mainCtx.trampName(), mainCtx.trampDesc(),
				JvmTailBounce.trampBody(indirectCallArities, mainCtx.spreadBounces[0], cp, thisClass));
		definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, cp.utf8Entry(JvmTailBounce.UNW_NAME),
				cp.utf8Entry(JvmTailBounce.UNW_DESC), unwCode);
		for (int arity : mainCtx.valueTailArities) {
			definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC,
					cp.utf8Entry(JvmTailBounce.valueTailName(arity)), cp.utf8Entry(JvmTailBounce.valueTailDesc(arity)),
					JvmTailBounce.valueTailBody(arity, false, valueTailCopies.contains(arity), cp, thisClass));
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC,
					cp.utf8Entry(JvmTailBounce.valueTailBounceName(arity)),
					cp.utf8Entry(JvmTailBounce.valueTailBounceDesc(arity)),
					JvmTailBounce.valueTailBounceBody(arity, cp));
		}
		if (mainCtx.spreadBounces[0]) {
			definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC,
					cp.utf8Entry(JvmTailBounce.VALUE_TAIL_SPREAD_NAME),
					cp.utf8Entry(JvmRuntimeBuilder.dispatcherDesc(0, true)),
					JvmTailBounce.valueTailBody(1, true, false, cp, thisClass));
		}
		if (mainCtx.conditionChannel.used || mainCtx.conditionChannel.nleUsed || teTlField != null
				|| !mainCtx.layoutPool.isEmpty() || !mainCtx.bigIntPool.isEmpty() || structTableClinitFinal != null
				|| dynVarRuntime != null || unboundMarker != null || initsClinit
				|| (mvChannel != null && mvChannel.perThread() != null) || javaSignals != null) {
			// <clinit>: _condTl = new ThreadLocal(); (initialValue null, so get()
			// on a thread with no pending condition returns null). The async
			// runtime's _handoffTl (the eager-start handoff) joins the same
			// initializer when present, as does _nleTl (the cross-lambda exit
			// carrier) -- appended last so a condition-only program is unchanged.
			// The instance-layout constants join the SAME method (a class may
			// have
			// only one <clinit>), appended after the ThreadLocals for the same
			// reason.
			ConditionChannel channel = mainCtx.conditionChannel;
			List<FieldRefEntry> tlFields = new java.util.ArrayList<>();
			if (channel.used) {
				tlFields.add(java.util.Objects.requireNonNull(channel.condTlField));
				tlFields.add(java.util.Objects.requireNonNull(channel.depthTlField));
				if (handoffFieldRef != null) {
					tlFields.add(handoffFieldRef);
				}
			}
			if (channel.nleUsed) {
				tlFields.add(java.util.Objects.requireNonNull(channel.nleTlField));
			}
			if (teTlField != null) {
				tlFields.add(teTlField);
				channel.ensureThreadLocalInfra(cp);
			}
			if (curThreadTlFieldRef != null) {
				// The _thread_current handle cache joins the same initializer.
				tlFields.add(curThreadTlFieldRef);
			}
			if (mvChannel != null && mvChannel.perThread() != null) {
				// The per-thread %mv-spill store joins the same initializer: every
				// thread's register starts null, nil. A program no thread runtime
				// brought the channel's ThreadLocal constants to (an export's, a
				// callback's) makes them here.
				tlFields.add(java.util.Objects.requireNonNull(mvChannel.perThread()).threadLocal());
				channel.ensureThreadLocalInfra(cp);
			}
			if (javaSignals != null) {
				// ... as does the record of what functions called back from Java raised.
				tlFields.add(javaSignals.field());
			}
			MethodCode clinitCode = new MethodCode();
			// The holder-presence probe's single initialization (.todo/757):
			// _hasComplex is true when the travelling RontoComplex class
			// loads, false when a lone class runs without it beside it (then
			// every holder test takes its holder-less shape, which is exact
			// because no holder can exist). First, so the top level a
			// <clinit> may run already sees the settled value.
			if (usesComplex) {
				MethodCode.Label tryStart = clinitCode.newBoundLabel();
				clinitCode.ldc(java.util.Objects.requireNonNull(hasComplexTarget));
				clinitCode.invokestatic(java.util.Objects.requireNonNull(hasComplexProbe));
				clinitCode.pop();
				clinitCode.iconst_1();
				clinitCode.putstatic(java.util.Objects.requireNonNull(hasComplexField));
				MethodCode.Label tryEnd = clinitCode.newBoundLabel();
				MethodCode.Label done = clinitCode.newLabel();
				clinitCode.goto_(done);
				MethodCode.Label handler = clinitCode.newBoundLabel();
				// The caught exception is on the stack on handler entry.
				clinitCode.pop();
				clinitCode.iconst_0();
				clinitCode.putstatic(java.util.Objects.requireNonNull(hasComplexField));
				clinitCode.labelBinding(done);
				clinitCode.exceptionCatch(tryStart, tryEnd, handler,
						java.util.Objects.requireNonNull(hasComplexAbsent));
			}
			for (FieldRefEntry tlField : tlFields) {
				clinitCode.new_(java.util.Objects.requireNonNull(channel.threadLocalClass));
				clinitCode.dup();
				clinitCode.invokespecial(java.util.Objects.requireNonNull(channel.tlCtor));
				clinitCode.putstatic(tlField);
			}
			channel.initJavaExceptions(clinitCode);
			if (dynVarRuntime != null) {
				// The dynamic-binding ThreadLocals (one per bound special) join
				// the
				// same initializer -- never lazily: a racy first binding from two
				// request threads would mint two ThreadLocals and lose one
				// binding.
				clinitCode.append(dynVarRuntime.clinitCode());
			}
			if (unboundMarker != null) {
				// The UNBOUND marker and the globals that start with it.
				clinitCode.append(unboundMarker.clinitCode());
			}
			if (streamsFieldRef != null) {
				// _streams = new Object[16]; _streamCount = 3 -- the reserved
				// standard-stream handles as empty table slots (see above).
				clinitCode.loadConstant(16);
				clinitCode.anewarray(objectClass);
				clinitCode.putstatic(streamsFieldRef);
				clinitCode.iconst_3();
				clinitCode.putstatic(java.util.Objects.requireNonNull(streamCountFieldRef));
			}
			// The bignum literals go in before the layouts: they are plain
			// values with no dependency of their own, and every later fragment
			// (and the top-level runner, invoked last) may read them.
			clinitCode.append(bigIntClinitCode);
			clinitCode.append(layoutClinitCode);
			// The standard stream variables' defaults, one table
			// (StreamDesignators) feeding BOTH homes: the per-name global field
			// a direct read uses, and the eval runtime's _genv mirror that
			// symbol-value / boundp / eval probe.
			for (Map.Entry<String, LispVal> streamVar : StreamDesignators.standardStreamDefaults().entrySet()) {
				FieldRefEntry globalField = streamGlobalSeeds.get(streamVar.getKey());
				if (globalField != null) {
					emitStreamDefault(clinitCode, streamVar.getValue(), standardOutputTStr, longValueOf, objectClass,
							streamLayoutField, streamKindStandardStr);
					clinitCode.putstatic(globalField);
				}
				StringEntry seedName = streamGenvSeeds.get(streamVar.getKey());
				if (seedName != null) {
					// _genv = {{name, default}, _genv} -- the binding shape
					// _store prepends, so a later top-level assignment MUTATES
					// this cell rather than shadowing it.
					clinitCode.iconst_2();
					clinitCode.anewarray(objectClass);
					clinitCode.dup();
					clinitCode.iconst_0();
					clinitCode.iconst_2();
					clinitCode.anewarray(objectClass);
					clinitCode.dup();
					clinitCode.iconst_0();
					clinitCode.ldc(seedName);
					clinitCode.aastore();
					clinitCode.dup();
					clinitCode.iconst_1();
					if (globalField != null) {
						// The very value the field was just seeded with: a second
						// construction would make symbol-value answer an object that
						// is not eq to what a direct read answers.
						clinitCode.getstatic(globalField);
					}
					else {
						emitStreamDefault(clinitCode, streamVar.getValue(), standardOutputTStr, longValueOf,
								objectClass, streamLayoutField, streamKindStandardStr);
					}
					clinitCode.aastore();
					clinitCode.aastore();
					clinitCode.dup();
					clinitCode.iconst_1();
					clinitCode.getstatic(genvField);
					clinitCode.aastore();
					clinitCode.putstatic(genvField);
				}
			}
			if (structTableClinitFinal != null) {
				clinitCode.append(structTableClinitFinal);
			}
			if (topRunnerRef != null) {
				// Run the top level last, after every piece of runtime infra
				// above is seeded — this is the export-carrying class's
				// "top level at instantiation" (see mainCtx above).
				clinitCode.invokestatic(topRunnerRef);
			}
			clinitCode.return_();
			// A layout-only program never runs ensureThreadLocalInfra, so the
			// channel's <clinit> name constants are null there; a
			// bound-special-only
			// program has neither, so the dyn-var runtime carries its own.
			Utf8Entry clinitNameUtf = channel.clinitName != null ? channel.clinitName
					: mainCtx.layoutPool.clinitName != null ? mainCtx.layoutPool.clinitName
							: dynVarRuntime != null ? dynVarRuntime.clinitName()
									: unboundMarker != null ? unboundMarker.clinitName()
											: standardOutputClinitName != null ? standardOutputClinitName
													: java.util.Objects.requireNonNull(mainCtx.bigIntPool.clinitName);
			Utf8Entry clinitDescUtf = channel.clinitDesc != null ? channel.clinitDesc
					: mainCtx.layoutPool.clinitDesc != null ? mainCtx.layoutPool.clinitDesc
							: dynVarRuntime != null ? dynVarRuntime.clinitDesc()
									: unboundMarker != null ? unboundMarker.clinitDesc()
											: standardOutputClinitDesc != null ? standardOutputClinitDesc
													: java.util.Objects.requireNonNull(mainCtx.bigIntPool.clinitDesc);
			definition.addMethod(AccessFlag.ACC_STATIC, clinitNameUtf, clinitDescUtf, clinitCode);
		}
		if (dynVarRuntime != null) {
			// _dget/_dbind/_dset: the shared thread-scoped dynamic-binding
			// helpers.
			for (JvmDynVarRuntimeBuilder.HelperMethod hm : dynVarRuntime.methods()) {
				definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, hm.nameUtf8(), hm.descUtf8(),
						hm.code());
			}
		}
		if (mainCtx.conditionChannel.used || teTlField != null) {
			// _tlMap, and _condTake/_condPut: the records a throwable carries, keyed by
			// it (JvmThrowableRecords).
			for (JvmNumericRuntimeBuilder.NumericMethod rm : JvmThrowableRecords.build(cp, thisClass,
					mainCtx.conditionChannel.used ? Objects.requireNonNull(mainCtx.conditionChannel.condTlField) : null,
					mainCtx.conditionChannel.condRan != null)) {
				definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, rm.nameUtf8(), rm.descUtf8(),
						rm.code());
			}
		}
		definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, strEscName, strEscDescUtf, strEscCode);
		definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, symEscName, strEscDescUtf, symEscCode);
		for (DispatchMethod fm : funNameMethods) {
			// _funName (and its _funName$k segments): the funcId -> name table behind
			// #<function NAME>. Emitted only when the gate found a nameable function
			// value (see above).
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, fm.nameUtf8(), fm.descUtf8(),
					fm.code());
		}
		definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, lispToStringName, lispToStringDescUtf,
				ltsCode);
		if (usesInstances) {
			// _instToString / _instToDisplayString: one body builder, two element
			// formatters, so the readable and display renderings cannot drift.
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC,
					Objects.requireNonNull(instToStringName), consToStringDescUtf, Objects.requireNonNull(instCode));
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC,
					Objects.requireNonNull(instToDisplayStringName), consToStringDescUtf,
					Objects.requireNonNull(instDisplayCode));
		}
		definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, consToStringName, consToStringDescUtf,
				ctsCode);
		definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, appendName, appendDescUtf, appendCode);
		definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, readLineHelperName, readLineHelperDesc,
				readLineCode);
		for (JvmIoRuntimeBuilder.IoMethod im : ioMethods) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | im.extraFlags(), im.name(), im.desc(),
					im.code());
		}
		// The lazy _*Init methods below bind a callback, hand over kernel text or
		// initialize a bridge behind a plain int guard, and a served program runs
		// one virtual thread per request -- two first calls arriving together
		// both passed the guard (when these still defined classes, the second
		// defineClass died with a LinkageError; found by WarE2eTest's concurrent
		// burst; the exact bug
		// family .kb/concurrent-served-requests.md records for the
		// interpreter's lazy loads, whose rule is: take the lock, check the
		// flag, set it, evaluate). ACC_SYNCHRONIZED is that rule in bytecode;
		// steady state pays one uncontended class monitor per call, which every
		// one of these paths (reflection, FFM, a kernel) dwarfs.
		if (javaRuntime != null) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED,
					javaRuntime.initName(), javaRuntime.initDesc(), javaRuntime.initCode());
		}
		// The direct java: calls (JvmJavaDirectSites), each site shape's method and the
		// helpers they share, made while the bodies above were compiled; then the
		// program side of the generated interface implementations
		// (JvmJavaImplementations): the callbacks their classes call, package-private,
		// and the conversions those use. The classes travel beside the program.
		if (javaSites != null) {
			for (JvmJavaDirectSites.Method site : javaSites.direct().methods()) {
				definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, site.name(), site.desc(),
						site.code());
			}
			JvmJavaImplementations implementations = javaSites.implementations();
			Set<String> callbacks = implementations.callbackNames();
			for (JvmJavaDirectSites.Method method : implementations.methods()) {
				boolean callback = callbacks.contains(method.name().stringValue());
				definition.addMethod(callback ? AccessFlag.ACC_STATIC : AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC,
						method.name(), method.desc(), method.code());
			}
			this.implementationCallbacks = callbacks;
			this.bridgeClassFiles.putAll(implementations.classFiles(this.writeTarget()));
			if (!callbacks.isEmpty() && !lispOnOtherThreads) {
				// Java calls these back from a thread of its choosing, so a special's
				// binding and the multiple-value channel must be per thread after all:
				// the attempt is redone with the gate forced on rather than shipped
				// single-threaded.
				throw new GateUnderpredicted(Set.of(GROUP_OTHER_THREADS));
			}
		}
		else {
			this.implementationCallbacks = Set.of();
		}
		if (objcRuntime != null) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED,
					objcRuntime.initName(), objcRuntime.initDesc(), objcRuntime.initCode());
		}
		if (ffiRuntime != null) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED,
					ffiRuntime.initName(), ffiRuntime.initDesc(), ffiRuntime.initCode());
		}
		if (simdRuntime != null) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED,
					simdRuntime.initName(), simdRuntime.initDesc(), simdRuntime.initCode());
			// _simdReady(): returns whether the bridge linked --
			// _simdInit must have run first, same as every ops.get(member)
			// call site. False on a runtime without jdk.incubator.vector, so
			// the accelerated call sites (JvmSimdCompiler, the --simd rung of
			// JvmLinalgKernelCompiler's chain) can decline to the scalar defun
			// instead of resolving a method reference into a bridge class that
			// cannot link.
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, simdRuntime.readyName(),
					simdRuntime.readyDesc(), simdRuntime.readyCode());
		}
		if (gpuRuntime != null) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED,
					gpuRuntime.initName(), gpuRuntime.initDesc(), gpuRuntime.initCode());
			// The residency invalidation guard, called from every in-place write
			// to a packed float array, answering the array to write into
			// (JvmGpuRuntimeBuilder.WRITTEN_METHOD).
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, gpuRuntime.writtenName(),
					gpuRuntime.writtenDesc(), gpuRuntime.writtenCode());
			// Its read-side twin, called before every host read of one and
			// answering the array to read
			// (JvmGpuRuntimeBuilder.MATERIALIZE_METHOD).
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, gpuRuntime.materializeName(),
					gpuRuntime.materializeDesc(), gpuRuntime.materializeCode());
			// And the one a call site runs over a host rung's answer, per
			// argument it handed over (JvmGpuRuntimeBuilder.UNSWAP_METHOD).
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, gpuRuntime.unswapName(),
					gpuRuntime.unswapDesc(), gpuRuntime.unswapCode());
		}
		if (geomRuntime != null) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED,
					geomRuntime.initName(), geomRuntime.initDesc(), geomRuntime.initCode());
			// _geomReady(): whether the bridge define succeeded. False on a JRE
			// older than the template's class version, so every accelerated call
			// site declines to the spliced geom.lisp defun instead of resolving a
			// method reference into a class that was never defined.
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, geomRuntime.readyName(),
					geomRuntime.readyDesc(), geomRuntime.readyCode());
		}
		if (fetchRuntimeBodies != null) {
			JvmFetchRuntimeBuilder.FetchMethod fm = fetchRuntimeBodies.fetch();
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, fm.name(), fm.desc(), fm.code());
		}
		if (asyncRuntimeBodies != null) {
			for (JvmAsyncRuntimeBuilder.AsyncMethod am : asyncRuntimeBodies.staticMethods()) {
				definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, am.name(), am.desc(), am.code());
			}
			JvmAsyncRuntimeBuilder.AsyncMethod runBody = asyncRuntimeBodies.runMethod();
			definition.addMethod(AccessFlag.ACC_PUBLIC, runBody.name(), runBody.desc(), runBody.code());
		}
		if (mvChannel != null && mvChannel.perThread() != null) {
			JvmMvChannel.PerThread mvPerThread = mvChannel.perThread();
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, mvPerThread.getName(),
					mvPerThread.getDesc(), mvPerThread.getCode(mvChannel.field()));
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, mvPerThread.setName(),
					mvPerThread.setDesc(), mvPerThread.setCode(mvChannel.field()));
		}
		if (octetsPackedRuntime != null) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, octetsPackedRuntime.name(),
					octetsPackedRuntime.desc(), octetsPackedRuntime.code());
		}
		if (secureRandomRuntime != null) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, secureRandomRuntime.name(),
					secureRandomRuntime.desc(), secureRandomRuntime.code());
		}
		if (argvRuntime != null) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, argvRuntime.name(), argvRuntime.desc(),
					argvRuntime.code());
		}
		for (JvmMutexRuntimeBuilder.MutexMethod mm : mutexMethods) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, mm.name(), mm.desc(), mm.code());
		}
		if (threadRuntimeBodies != null) {
			for (JvmThreadRuntimeBuilder.ThreadMethod tm : threadRuntimeBodies.staticMethods()) {
				definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, tm.name(), tm.desc(), tm.code());
			}
			JvmThreadRuntimeBuilder.ThreadMethod callBody = threadRuntimeBodies.callMethod();
			definition.addMethod(AccessFlag.ACC_PUBLIC, callBody.name(), callBody.desc(), callBody.code());
		}
		if (socketRuntime != null) {
			for (JvmSocketRuntimeBuilder.SocketMethod sm : socketRuntime.methods()) {
				definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, sm.name(), sm.desc(), sm.code());
			}
		}
		if (ctorName != null) {
			// No-arg constructor: super(). _tlsConnect does `new Prog()` for the
			// :insecure trust-all manager; the http-handler directive does the
			// same for the RontoHttpServer.Handler instance.
			// The sized-stack launcher does it for its Runnable.
			Utf8Entry initName = ctorName;
			Utf8Entry initDesc = java.util.Objects.requireNonNull(ctorDesc);
			MethodCode instanceInitCode = new MethodCode();
			instanceInitCode.aload(0);
			instanceInitCode.invokespecial(java.util.Objects.requireNonNull(ctorObjectInitRef));
			instanceInitCode.return_();
			definition.addMethod(AccessFlag.ACC_PUBLIC, initName, initDesc, instanceInitCode);
		}
		if (usesTlsConnect) {
			Utf8Entry clientName = java.util.Objects.requireNonNull(checkClientName);
			Utf8Entry serverName = java.util.Objects.requireNonNull(checkServerName);
			Utf8Entry trustedDesc = java.util.Objects.requireNonNull(checkTrustedDesc);
			Utf8Entry issuersName = java.util.Objects.requireNonNull(acceptedIssuersName);
			Utf8Entry issuersDesc = java.util.Objects.requireNonNull(acceptedIssuersDesc);
			// X509TrustManager: trust-all client/server checks (empty bodies) and
			// an empty accepted-issuers array.
			definition.addMethod(AccessFlag.ACC_PUBLIC, clientName, trustedDesc, new MethodCode().return_());
			definition.addMethod(AccessFlag.ACC_PUBLIC, serverName, trustedDesc, new MethodCode().return_());
			MethodCode acceptedIssuersCode = new MethodCode();
			acceptedIssuersCode.iconst_0();
			acceptedIssuersCode.anewarray(java.util.Objects.requireNonNull(x509CertificateClass));
			acceptedIssuersCode.areturn();
			definition.addMethod(AccessFlag.ACC_PUBLIC, issuersName, issuersDesc, acceptedIssuersCode);
		}
		if (httpHandlerRuntime != null) {
			// handle(Request): the RontoHttpServer.Handler implementation
			// adapting each incoming request to the compiled Lisp handler.
			JvmHttpHandlerRuntimeBuilder.HandleMethod hm = httpHandlerRuntime.handle();
			definition.addMethod(AccessFlag.ACC_PUBLIC, hm.name(), hm.desc(), hm.code());
		}
		{
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, lengthMethodBody.name(),
					lengthMethodBody.desc(), lengthMethodBody.code());
		}
		{
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, nthcdrMethodBody.name(),
					nthcdrMethodBody.desc(), nthcdrMethodBody.code());
		}
		{
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, eltCellMethodBody.name(),
					eltCellMethodBody.desc(), eltCellMethodBody.code());
		}
		{
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, aritySurplusMethodBody.name(),
					aritySurplusMethodBody.desc(), aritySurplusMethodBody.code());
		}
		{
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, arityMissingMethodBody.name(),
					arityMissingMethodBody.desc(), arityMissingMethodBody.code());
		}
		for (JvmStringIndexRuntimeBuilder.StringIndexMethod sm : stringIndexMethods) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, sm.name(), sm.desc(), sm.code());
		}
		for (JvmReadRuntimeBuilder.ReadMethod rm : readMethodsFinal) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, rm.name(), rm.desc(), rm.code());
		}
		for (JvmHashRuntimeBuilder.HashMethod hm : hashMethods) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, hm.name(), hm.desc(), hm.code());
		}
		for (JvmArrayRuntimeBuilder.ArrayMethod am : arrayMethods) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, am.name(), am.desc(), am.code());
		}
		for (JvmNumericRuntimeBuilder.NumericMethod nm : numericRuntime.methods()) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, nm.nameUtf8(), nm.descUtf8(),
					nm.code());
		}
		// The gated complex helpers, present only when the program may
		// create a complex -- a complex-free program keeps its bytes.
		if (complexRuntime != null) {
			for (JvmComplexRuntimeBuilder.ComplexMethod cm : complexRuntime.methods()) {
				definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, cm.nameUtf8(), cm.descUtf8(),
						cm.code());
			}
		}
		// The outlined fused-site methods (.kb/jvm-int-fusion.md) and their
		// two shared helpers, present only when Pass 2 registered a site / a
		// raw local -- a program without one is byte-identical to before.
		for (int i = 0; i < fusedCtxs.size(); i++) {
			JvmIntFusionCompiler.Pending pendingFused = fusedState.pending.get(i);
			fusedCtxs.get(i)
				.addTo(definition, AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, pendingFused.nameUtf8(),
						pendingFused.descUtf8());
		}
		// The outlined tail continuations of a body that would have compiled
		// past HotSpot's HugeMethodLimit (JvmBodyOutliner); empty for every
		// program whose bodies stay under the budget.
		for (JvmBodyOutliner.OutlinedBody outlined : mainCtx.outlinedBodies) {
			outlined.ctx()
				.addTo(definition, AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, outlined.nameUtf8(),
						outlined.descUtf8());
		}
		// The uncaught report's location lines and the async-boundary records their
		// hops are read from (JvmUncaughtHandler): only in a class something was
		// located in.
		if (sourceSites != null) {
			definition.lineNumberTableName(cp.utf8Entry("LineNumberTable"));
			List<JvmUncaughtHandler.Built> reportMethods = new ArrayList<>();
			reportMethods.add(JvmUncaughtHandler.buildWhere(cp, this.className, sourceSites, mainCtx.printlnStr));
			if (recordsAsyncBoundaries) {
				reportMethods.add(JvmUncaughtHandler.buildAsyncCross(cp));
				reportMethods.add(JvmUncaughtHandler.buildAsyncAwaited(cp));
			}
			for (JvmUncaughtHandler.Built built : reportMethods) {
				definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, built.name(), built.desc(),
						built.code());
			}
		}
		for (JvmNumericRuntimeBuilder.NumericMethod nm : fusedHelperMethods) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, nm.nameUtf8(), nm.descUtf8(),
					nm.code());
		}
		definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, lispToDisplayStringName,
				lispToStringDescUtf, ltdsCode);
		definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, consToDisplayStringName,
				consToStringDescUtf, ctdsCode);
		definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, charPrin1Name, charPrin1Desc,
				charPrin1Code);
		for (int g = 0; g < lookupBodies.size(); g++) {
			Utf8Entry segName = g == 0 ? lookupName : lookupSegmentNames.get(g - 1);
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, segName, lookupDesc,
					lookupBodies.get(g));
		}
		if (usesApplyRuntime) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, applyName, evalDesc, applyEntryBody);
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, applyRawName, applyRawDesc, applyBody);
		}
		if (usesEval) {
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, envLookupName, envLookupDesc,
					envLookupBody);
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, evalName, evalDesc, evalBody);
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, storeName, storeDesc, storeBody);
		}

		// --java-static: every site that would have needed the bridge, at once, before
		// anything is written.
		if (javaSites != null && !javaSites.refusals().isEmpty()) {
			List<String> refusals = javaSites.refusals();
			throw new UnsupportedOperationException(
					"--java-static: " + refusals.size() + " java: call" + (refusals.size() == 1 ? "" : "s")
							+ " cannot be compiled without reflection:\n  " + String.join("\n  ", refusals));
		}
		ClassDefinition classDefinition = definition.build();
		long writeStart = System.nanoTime();
		// Check the runtime-helper gates against what the bodies turned out to reference,
		// rather than trusting the source scans that predicted them (see compile(List)).
		// An unresolved own-class call is either a mispredicted gate -- re-run with that
		// group forced on -- or an internal inconsistency no re-run can fix, and then it
		// is far better to say so here than to hand the user a class that throws
		// NoSuchMethodError the first time the branch is taken. Every caller counts,
		// including the injected built-in wrappers: their bodies no longer carry an arm
		// for a value the absent runtime cannot construct, because each lowering behind
		// them is gated on Ctx.usesArrays (.kb/adjustable-arrays.md).
		List<JvmClassSplitter.UnresolvedSelfMethod> unresolved = JvmClassSplitter
			.unresolvedSelfMethods(classDefinition);
		Set<String> underpredicted = new LinkedHashSet<>();
		List<JvmClassSplitter.UnresolvedSelfMethod> unrecoverable = new ArrayList<>();
		for (JvmClassSplitter.UnresolvedSelfMethod missing : unresolved) {
			String group = gateGroupFor(missing.name());
			if (group == null || forcedGroups.contains(group)) {
				unrecoverable.add(missing);
			}
			else {
				underpredicted.add(group);
			}
		}
		if (!unrecoverable.isEmpty()) {
			throw new IllegalStateException(
					"JvmLispCompiler: the generated class calls own methods it does not declare: " + unrecoverable);
		}
		if (!underpredicted.isEmpty()) {
			if (Boolean.getBoolean("rontolisp.debug.gate")) {
				System.err.println("[gate] underpredicted=" + underpredicted + " unresolved=" + unresolved);
			}
			throw new GateUnderpredicted(underpredicted);
		}
		java.util.Set<String> roots = null;
		if (this.optimize.eliminatesDeadCode()) {
			// Drop every method unreachable from main (and compact the constant pool).
			// Dispatch methods contain real invokestatic calls to every registered
			// function, so dynamically-reached methods (eval/apply/funcall targets) stay
			// alive through ordinary call-graph reachability. The one edge the bytecode
			// cannot show is the java: bridge's reflective getDeclaredMethod("_apply",
			// ..)
			// lookup, so _apply is an extra root when the program uses java: interop.
			// JSSE invokes the X509TrustManager methods through the interface, an edge
			// the
			// call-graph tree-shaker cannot see, so they are extra roots when
			// tls-connect's
			// :insecure trust-all manager is present.
			roots = new java.util.HashSet<>();
			if (!this.noMain) {
				roots.add("main");
			}
			// Every jvm-export wrapper is a root: a host calls it directly, an edge no
			// bytecode in the class can show. This is the directive's whole point under
			// --optimize — without it a library's defuns are unreachable from main and
			// shaken away (.kb/optimize-dead-code-elimination.md names this as the
			// third liveness source, next to main and the dispatchable-funcId set).
			for (JvmExportDirective decl : exportDecls) {
				roots.add(decl.methodName());
			}
			// ... and an objc: callback (a run-time class's method, objc:on-main's
			// body) or an ffi:callback's Lisp function reaches it from an upcall, the
			// same invisible edge.
			if (usesJavaBridge || usesObjc || usesFfi) {
				roots.add("_apply");
			}
			// The java: bridge shows a value in a message through the program's printer,
			// found by name like _apply.
			if (usesJavaBridge) {
				roots.add("_lispToString");
				// ... and reads a bfloat16 vector's elements through the program's own
				// widening, found by name too.
				if (usesFloatArray) {
					roots.add(JvmFloatArrayRuntimeBuilder.BF16_VALUE);
				}
				// ... and records what its Proxy's callback raised, and passes it on from
				// a call, through the program's _jsig and _jfail.
				roots.add(JvmJavaDirectSites.SIGNAL);
				roots.add(JvmJavaDirectSites.FAIL);
			}
			// A generated java: interface implementation calls its program-side
			// callbacks from its own class: an edge this class's bytecode cannot show.
			roots.addAll(this.implementationCallbacks);
			if (usesTlsConnect) {
				roots.add("checkClientTrusted");
				roots.add("checkServerTrusted");
				roots.add("getAcceptedIssuers");
			}
			// RontoHttpServer invokes handle through the Handler interface, another
			// edge the call-graph tree-shaker cannot see.
			if (usesHttpHandler) {
				roots.add("handle");
			}
			// The async runtime's virtual thread invokes run() through the Runnable
			// interface -- the same invisible edge; shaking it away would strand
			// _async_run's eager-start latch forever.
			if (usesAsyncRuntime || sizedMain != null) {
				roots.add("run");
			}
			// The thread runtime's FutureTask invokes call() through the Callable
			// interface -- the same invisible edge as run() above.
			if (usesThreads) {
				roots.add("call");
			}
		}
		JvmTailBounce.unwBody(unwCode, cp, thisClass, trampolineLive(classDefinition, roots, mainCtx.bouncingBodies));
		if (roots != null) {
			Set<Integer> assembledCopies = Set.copyOf(valueTailCopies);
			classDefinition = rebuildDispatchers(classDefinition, roots, dispatchMethods, dispatchers,
					dispatchableFuncIds, functions, lambdaFuncInfos);
			// Fewer cases can bring an arity under one segment: its value tails then call
			// the copy the rebuild made.
			if (!valueTailCopies.equals(assembledCopies)) {
				classDefinition = retargetValueTails(classDefinition, mainCtx.valueTailArities, valueTailCopies, cp,
						thisClass);
			}
		}
		return this.write(classDefinition, roots, exportDecls, writeStart);
	}

	/**
	 * The class with its dispatchers rebuilt over the funcIds a kept body can make a
	 * value of. A funcId is dispatchable once Pass 2 compiled a {@code (lambda ...)} or a
	 * {@code #'name} of it, wherever that code sits -- a body the shake drops included --
	 * and the dispatcher's case would then keep the method, and all it reaches, for a
	 * value nothing can make. The shake already follows a case only when a kept body
	 * makes its value ({@link MethodCode#invokestaticThroughValue}); this drops the dead
	 * case's code too, and the {@code _arityErr} row with it. The {@code _lookup} rows
	 * are values too, so a name the registry answers keeps its case while the registry is
	 * kept. Asked after {@code _unw} is written, so the trampoline's re-entries count.
	 * @param definition the class as assembled
	 * @param roots the shake's roots
	 * @param built the dispatch methods the class holds, all of them in one run
	 * @param dispatchers builds the dispatch methods over a set of funcIds
	 * @param dispatchable the funcIds {@code built} covers
	 * @param functions the program's functions by name
	 * @param lambdaFuncInfos the lambdas' registry entries
	 * @return the class, or {@code definition} itself when every case stays
	 */
	private static ClassDefinition rebuildDispatchers(ClassDefinition definition, java.util.Set<String> roots,
			List<DispatchMethod> built, java.util.function.Function<Set<Integer>, List<DispatchMethod>> dispatchers,
			Set<Integer> dispatchable, Map<String, FunctionInfo> functions, List<FunctionInfo> lambdaFuncInfos) {
		JvmClassSplitter.Reach reach = JvmClassSplitter.reach(definition, roots);
		List<ClassDefinition.Method> methods = definition.methods();
		Set<String> valued = new HashSet<>();
		for (int m = 0; m < methods.size(); m++) {
			if (reach.valued()[m]) {
				valued.add(methods.get(m).name().stringValue() + methods.get(m).descriptor().stringValue());
			}
		}
		// Per dispatchable funcId, whether a kept body makes a value of its method.
		Map<Integer, Boolean> made = new HashMap<>();
		List<FunctionInfo> infos = new ArrayList<>(functions.values());
		infos.addAll(lambdaFuncInfos);
		for (FunctionInfo fi : infos) {
			if (dispatchable.contains(fi.funcId())) {
				made.merge(fi.funcId(), valued.contains(fi.nameUtf8().stringValue() + fi.descUtf8().stringValue()),
						Boolean::logicalOr);
			}
		}
		Set<Integer> live = new HashSet<>(dispatchable);
		made.forEach((funcId, isMade) -> {
			if (!isMade) {
				live.remove(funcId);
			}
		});
		if (Boolean.getBoolean("rontolisp.debug.dispatchgate")) {
			System.err.println("[dispatch-gate] " + (dispatchable.size() - live.size()) + " of " + dispatchable.size()
					+ " dispatchable funcIds have no value a kept body makes");
		}
		if (live.size() == dispatchable.size() || built.isEmpty()) {
			return definition;
		}
		Set<MethodCode> old = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (DispatchMethod dm : built) {
			old.add(dm.code());
		}
		List<ClassDefinition.Method> swapped = new ArrayList<>();
		boolean placed = false;
		for (ClassDefinition.Method method : methods) {
			if (!old.contains(method.body())) {
				swapped.add(method);
			}
			else if (!placed) {
				placed = true;
				for (DispatchMethod dm : dispatchers.apply(live)) {
					swapped.add(new ClassDefinition.Method(method.access(), dm.nameUtf8(), dm.descUtf8(), dm.code(),
							List.of()));
				}
			}
		}
		return definition.withMethods(swapped);
	}

	/**
	 * The class with every {@code _vtc<n>} rewritten to call the dispatcher its arity has
	 * now: the copy only value tails call when the arity is in {@code copies}, the shared
	 * one otherwise ({@link JvmTailBounce#valueTailDispatcherName}). The copies are
	 * decided where the dispatchers are built, and the rebuild over the funcIds a kept
	 * body can make a value of can bring a routed arity under one segment.
	 * @param definition the class, its dispatchers rebuilt
	 * @param arities the argument counts of the class's value tails
	 * @param copies the arities with a copy
	 * @param cp the class's constant pool
	 * @param thisClass the class
	 * @return the class
	 */
	private static ClassDefinition retargetValueTails(ClassDefinition definition, Set<Integer> arities,
			Set<Integer> copies, ConstantPool cp, ClassEntry thisClass) {
		Map<String, Integer> byName = new HashMap<>();
		for (int arity : arities) {
			byName.put(JvmTailBounce.valueTailName(arity), arity);
		}
		List<ClassDefinition.Method> methods = new ArrayList<>();
		for (ClassDefinition.Method method : definition.methods()) {
			Integer arity = byName.get(method.name().stringValue());
			methods.add(arity == null ? method
					: new ClassDefinition.Method(method.access(), method.name(), method.descriptor(),
							JvmTailBounce.valueTailBody(arity, false, copies.contains(arity), cp, thisClass),
							method.lineNumbers()));
		}
		return definition.withMethods(methods);
	}

	/**
	 * The descriptor of a lambda's method: its closure environment, then its parameters,
	 * then -- when its body may answer a bounce -- the value-tail depth
	 * ({@link JvmTailBounce}). Every lambda is compiled with the depth's slot; one whose
	 * body never reads it is written without the parameter.
	 * @param params the lambda's physical parameter count
	 * @param depth whether it takes the value-tail depth
	 * @return the descriptor
	 */
	static String lambdaDescriptor(int params, boolean depth) {
		return "([Ljava/lang/Object;" + "Ljava/lang/Object;".repeat(params) + (depth ? "I" : "")
				+ ")Ljava/lang/Object;";
	}

	/**
	 * Whether a method the class keeps can bounce, so its unwrap checks must hand a
	 * bounce to {@code _tramp} ({@link JvmTailBounce}). Asked while {@code _unw} is still
	 * empty: the trampoline is then reachable from nothing, so a method only it would
	 * reach -- every dispatcher arity it re-enters, and the closures only those name --
	 * does not count.
	 * @param definition the class, its {@code _unw} empty
	 * @param roots the shake's roots, or null when nothing is shaken
	 * @param bouncing the bodies that bounce, by identity
	 * @return whether a kept method bounces
	 */
	private static boolean trampolineLive(ClassDefinition definition, java.util.@Nullable Set<String> roots,
			Set<MethodCode> bouncing) {
		if (bouncing.isEmpty()) {
			return false;
		}
		List<ClassDefinition.Method> methods = definition.methods();
		boolean @Nullable [] kept = roots == null ? null : JvmClassSplitter.reachable(definition, roots);
		for (int m = 0; m < methods.size(); m++) {
			if ((kept == null || kept[m]) && bouncing.contains(methods.get(m).body())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Writes the class: one class file when what it keeps fits one pool, else the class
	 * itself plus the {@code $PartN} classes the rest of its methods need
	 * ({@code .kb/jvm-method-size-limits.md}), keeping in the class every method
	 * something finds by NAME: {@code main}, the {@code rontolisp:jvm-export} wrappers
	 * and the defuns behind them (a Java caller's API), and the helpers an shipped bridge
	 * or the travelling float-array handle looks up reflectively
	 * ({@link #REFLECTIVELY_FOUND_METHODS}). {@link JvmClassSplitter} keeps the rest of
	 * what cannot move by itself. The parts join {@link #runtimeClassFiles()}, the list
	 * every output shape already writes beside the class.
	 * @param definition the class as data
	 * @param roots the tree-shaker roots, or null under {@code --optimize=off}
	 * @param exportDecls the program's export directives
	 * @param writeStart when the class was complete as data ({@link System#nanoTime}),
	 * for {@code -Drontolisp.jvm.debug-write=true}
	 * @return the class's own bytes
	 */
	private byte[] write(ClassDefinition definition, java.util.@Nullable Set<String> roots,
			List<JvmExportDirective> exportDecls, long writeStart) {
		Set<String> pinnedNames = new HashSet<>(REFLECTIVELY_FOUND_METHODS);
		pinnedNames.add("main");
		// The generated java: interface implementations name the program class as the
		// owner of their callbacks.
		pinnedNames.addAll(this.implementationCallbacks);
		for (JvmExportDirective decl : exportDecls) {
			pinnedNames.add(decl.methodName());
			pinnedNames.add(mangleMethodName(decl.name()));
		}
		ConstantPool cp = definition.cp();
		JvmClassSplitter.Split split = JvmClassSplitter.write(definition, roots,
				method -> pinnedNames.contains(method.name().stringValue()), this.classPoolLimit, this.writeTarget());
		Map<String, byte[]> parts = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> part : split.parts().entrySet()) {
			parts.put(part.getKey() + ".class", part.getValue());
		}
		this.partClassFiles = Map.copyOf(parts);
		if (Boolean.getBoolean("rontolisp.jvm.debug-write")) {
			// The gate check, the shake, the placement and the write of every class, with
			// what each class came to.
			StringBuilder report = new StringBuilder("[write] ").append((System.nanoTime() - writeStart) / 1_000_000)
				.append(" ms; ")
				.append(this.className)
				.append(": ")
				.append(split.mainClass().length)
				.append(" B, ")
				.append(poolEntries(split.mainClass()))
				.append(" entries");
			split.parts()
				.forEach((name, bytes) -> report.append("; ")
					.append(name)
					.append(": ")
					.append(bytes.length)
					.append(" B, ")
					.append(poolEntries(bytes))
					.append(" entries"));
			System.err.println(report);
		}
		return split.mainClass();
	}

	private static int poolEntries(byte[] classFile) {
		return ((classFile[8] & 0xFF) << 8 | (classFile[9] & 0xFF)) - 1;
	}

	/**
	 * What every class this compile writes is stamped and framed against: version
	 * {@link #classMajorVersion}, and for a {@code java:} program the class files its
	 * sites resolved against, through which the frames merge host types.
	 * @return the target
	 */
	JvmClassSplitter.Target writeTarget() {
		JvmClassFileLookup classes = this.javaClasses;
		return classes == null ? JvmClassSplitter.Target.of(this.classMajorVersion)
				: new JvmClassSplitter.Target(this.classMajorVersion, classes::classInfo);
	}

	/**
	 * The funcIds the {@code _lookup} name registry answers -- the names a runtime SYMBOL
	 * designator can resolve. The {@code _invoke_N}/{@code _invoke_v} dispatchers take
	 * these plus {@code valueFuncIds} (what Pass 2 actually materialized as a closure, so
	 * a {@code #'name} a macro synthesized during Pass 2 counts); everything else in
	 * {@code functions} is called only through a direct {@code invokestatic}, so naming
	 * it in a dispatcher would do nothing except keep it alive for the writer's shake
	 * ({@code .kb/optimize-dead-code-elimination.md}). The WASM twin is
	 * {@code WasmLispCompiler.registryFuncIds}, and the two must agree: a name that stops
	 * resolving here has to stop resolving there too, or the backends disagree about
	 * which forged designator still works.
	 *
	 * <p>
	 * A function VALUE never reaches the registry: it carries its funcId to the
	 * dispatcher. So a function materialized as a value has a row only when its name is
	 * reachable too -- every row makes its value ({@link MethodCode#makesValueOf}), and a
	 * row for a {@code #'name} compiled only in dead code would keep the function alive
	 * for a value no kept body makes.
	 *
	 * <p>
	 * {@code _lookup} compares the designator against string CONSTANTS, so a registry row
	 * is reachable only when the program already loads that name as a string VALUE -- a
	 * quoted symbol, a string literal, an {@code intern} of a literal. The probe reads
	 * {@code Ctx.spelledLiterals} -- the spellings Pass 2 emitted as values -- not the
	 * whole constant pool: the pool also holds strings the compiler added for its own
	 * machinery (layout tables, runtime error messages), and no run-time path turns those
	 * into a designator the program did not spell itself. This is the constant-pool
	 * counterpart of the WASM side's spelled-literal test, and the two must classify
	 * alike -- which is why the spellings themselves come from the shared
	 * {@link DesignatorSpellings} rather than from a list repeated on each side.
	 *
	 * <p>
	 * The carve-out is {@link am.ik.rontolisp.eval.LibraryDefunPruner}'s, verbatim: a
	 * program that FORGES a function name at run time out of computed strings loses it
	 * (compile with {@code --dynamic}, which turns this gate off, to keep every function
	 * dispatchable).
	 * @param functions the program's functions by name
	 * @param valueFuncIds the funcIds Pass 2 materialized as function values
	 * @param spelledLiterals the literal spellings Pass 2 emitted as runtime values
	 * @param registryLive whether a real {@code _lookup} registry is emitted at all
	 * @param anyNameResolvable whether a data evaluator can produce any name at run time
	 * @param symbolBuilders whether the program contains a symbol BUILDER
	 * ({@code RuntimeNameProducers.anySymbolBuilder}) -- only then can a framed string
	 * literal or keyword spelling become a designator, so only then are those probes
	 * applied
	 * @param callOnly the compiler's shared name dispatches, called only from the sites
	 * lowered onto them -- never a designator's target, however names resolve
	 * @return the funcIds that get a registry row (and a dispatcher case)
	 */
	private Set<Integer> registryFuncIds(Map<String, FunctionInfo> functions, Set<Integer> valueFuncIds,
			Set<String> spelledLiterals, boolean registryLive, boolean anyNameResolvable, boolean symbolBuilders,
			Set<String> callOnly) {
		if (this.dynamic || anyNameResolvable) {
			// Late binding, or an operator that can produce a name this compile never
			// sees spelled: any name can be resolved at run time.
			Set<Integer> all = new HashSet<>(valueFuncIds);
			for (Map.Entry<String, FunctionInfo> entry : functions.entrySet()) {
				if (!callOnly.contains(entry.getKey())) {
					all.add(entry.getValue().funcId());
				}
			}
			return all;
		}
		Set<Integer> rows = new HashSet<>();
		if (registryLive) {
			for (Map.Entry<String, FunctionInfo> entry : functions.entrySet()) {
				if (callOnly.contains(entry.getKey())) {
					continue;
				}
				// Every spelling a runtime designator can carry for the name --
				// canonical, the alias row's, the bare member, and (only with a symbol
				// BUILDER present) the framed string literal and the two package-less
				// symbol spellings. The list is shared with the WASM twin
				// (compiler.DesignatorSpellings) so the two cannot drift.
				if (DesignatorSpellings.anySpelled(entry.getKey(), spelledLiterals, symbolBuilders)) {
					rows.add(entry.getValue().funcId());
				}
			}
		}
		if (Boolean.getBoolean("rontolisp.debug.dispatchgate")) {
			Set<Integer> dispatchable = new HashSet<>(valueFuncIds);
			dispatchable.addAll(rows);
			System.err.println("[dispatch-gate] " + dispatchable.size() + " of " + functions.size()
					+ " functions dispatchable (" + valueFuncIds.size() + " funcIds materialized as values, "
					+ rows.size() + " registry rows)");
			for (Map.Entry<String, FunctionInfo> entry : functions.entrySet()) {
				if (rows.contains(entry.getValue().funcId())) {
					System.err.println("[dispatch-gate] name-armed\t" + entry.getKey() + "\tby\t"
							+ DesignatorSpellings.matched(entry.getKey(), spelledLiterals, symbolBuilders));
				}
			}
		}
		return rows;
	}

	/**
	 * Whether the program can produce a function NAME this compile never sees spelled out
	 * -- in which case {@link #registryFuncIds} must keep every function dispatchable.
	 * Only the data evaluators ({@code eval}/{@code read}/
	 * {@code read-from-string}/{@code load}) answer yes; the symbol builders
	 * ({@code intern}, {@code find-symbol}, ...) are covered by the probes instead -- see
	 * the WASM twin {@code WasmLispCompiler.anyNameResolvable}, whose doc carries the
	 * reasoning. The two trigger lists must stay identical, or the backends disagree
	 * about which program still resolves a run-time-built designator.
	 * @param program the program, after every AST pass
	 * @param usesRead whether the reader runtime is emitted
	 * @param usesLoad whether a runtime load survived the inliner
	 * @return true when the gate must keep every function dispatchable
	 */
	private static boolean anyNameResolvable(List<LispVal> program, boolean usesRead, boolean usesLoad) {
		// RuntimeNameProducers first, so the -Drontolisp.debug.dispatchgate report names
		// every operator holding the gate open rather than only the first one.
		boolean producer = RuntimeNameProducers.anyNameResolvable(program);
		if ((usesRead || usesLoad) && Boolean.getBoolean("rontolisp.debug.dispatchgate")) {
			System.err.println("[dispatch-gate] every function stays dispatchable because of: "
					+ (usesRead ? "read/read-from-string" : "load"));
		}
		return producer || usesRead || usesLoad;
	}

	/**
	 * The shared accessor {@link LispNames#GLOBAL_ACCESS_RUNTIME} the eval runtime
	 * reaches a variable by name through, or null when the program does not carry it.
	 */
	private static @Nullable MethodRefEntry globalAccessRef(Map<String, FunctionInfo> functions) {
		FunctionInfo fi = functions.get(LispNames.GLOBAL_ACCESS_RUNTIME);
		if (fi == null) {
			return null;
		}
		// A method that may bounce takes the value-tail depth too, which the eval
		// runtime's call does not pass.
		if (fi.paramCount() != 4 || fi.variadic() || fi.optionals() != 0 || fi.isClosure() || fi.bounceVisible()) {
			throw new IllegalStateException(
					LispNames.GLOBAL_ACCESS_RUNTIME + " is not the accessor the eval runtime calls");
		}
		return fi.methodref();
	}

	private static boolean programUsesEval(List<LispVal> program) {
		for (LispVal expr : program) {
			if (usesEval(expr)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Pushes a standard stream variable's seeded default onto a {@code <clinit>} body:
	 * the designator {@code t} (a bare {@code "T"} symbol) for the two stdio variables, a
	 * boxed stream handle for {@code *error-output*}. The two homes that need the value
	 * -- the variable's global field and the eval runtime's {@code _genv} mirror -- both
	 * push it through here, so neither can drift from {@code StreamDesignators}' table.
	 */
	private static void emitStreamDefault(MethodCode code, LispVal value, @Nullable StringEntry tDesignator,
			MethodRefEntry longValueOf, ClassEntry objectClass, @Nullable FieldRefEntry streamLayoutField,
			@Nullable StringEntry streamKindStr) {
		if (value instanceof LispCons) {
			// *error-output*'s default is the stream VALUE over the reserved handle 2:
			// Object[]{layout, Long(2), ":STANDARD"} -- the same shape
			// JvmObjCompiler.emitWrapStream builds at a producer, written out here
			// because <clinit> has no expression compiler.
			code.loadConstant(1 + am.ik.rontolisp.LispLayout.STREAM.capacity());
			code.anewarray(objectClass);
			code.dup();
			code.iconst_0();
			code.getstatic(java.util.Objects.requireNonNull(streamLayoutField));
			code.aastore();
			code.dup();
			code.iconst_1();
			code.loadConstant((int) StreamDesignators.STANDARD_ERROR_HANDLE);
			code.i2l();
			code.invokestatic(longValueOf);
			code.aastore();
			code.dup();
			code.iconst_2();
			code.ldc(java.util.Objects.requireNonNull(streamKindStr));
			code.aastore();
			return;
		}
		if (value instanceof LispInteger handle) {
			code.loadConstant((int) handle.value());
			code.i2l();
			code.invokestatic(longValueOf);
			return;
		}
		code.ldc(java.util.Objects.requireNonNull(tDesignator));
	}

	private static boolean programUsesSymbol(List<LispVal> program, String name) {
		for (LispVal expr : program) {
			if (usesSymbol(expr, name)) {
				return true;
			}
		}
		return false;
	}

	// The host types the program text gives its variables lowered onto the java: sites
	// they type, every top-level form in order (compiler/JavaDeclarations); a program
	// that mentions no java: symbol comes back unchanged without opening the classes.
	private List<LispVal> lowerJavaDeclarations(List<LispVal> program) {
		if (program.stream().noneMatch(am.ik.rontolisp.compiler.JavaDeclarations::mentionsJava)) {
			return program;
		}
		am.ik.rontolisp.compiler.JavaDeclarations declarations = new am.ik.rontolisp.compiler.JavaDeclarations(
				javaClasses());
		List<LispVal> lowered = null;
		for (int i = 0; i < program.size(); i++) {
			LispVal form = program.get(i);
			LispVal result = declarations.lower(form, null);
			if (result != form) {
				if (lowered == null) {
					lowered = new ArrayList<>(program);
				}
				lowered.set(i, result);
			}
		}
		return lowered == null ? program : lowered;
	}

	// The class files java: sites resolve against, opened once per compile.
	private JvmClassFileLookup javaClasses() {
		JvmClassFileLookup classes = this.javaClasses;
		if (classes == null) {
			classes = JvmClassFileLookup.forJdk(this.javaRelease, this.javaClasspath);
			this.javaClasses = classes;
		}
		return classes;
	}

	// True when the program references any of the six java: interop functions, so its
	// sites are resolved against the class files (and the bridge emitted when one needs
	// it).
	private static boolean programUsesAnyJavaOp(List<LispVal> program) {
		for (String operator : LispNames.JAVA_OPERATORS_QUALIFIED) {
			if (programUsesSymbol(program, operator)) {
				return true;
			}
		}
		return false;
	}

	// True when the program calls the objc primitive layer (objc.lisp's calls, and
	// objc:on-main), so the shipped am.ik.objc copy (and the eval runtime its callbacks
	// need) is emitted. A program that uses appkit: qualifies through the spliced
	// appkit.lisp and objc.lisp.
	private static boolean programUsesAnyObjcOp(List<LispVal> program) {
		for (String name : JvmObjcPrimitivesCompiler.names()) {
			if (programUsesSymbol(program, name)) {
				return true;
			}
		}
		return false;
	}

	// True when the program references any of the ffi: verbs, so the shipped
	// am.ik.ffi copy (and the eval runtime an ffi:callback needs) is emitted. A
	// program that uses cffi: qualifies through the spliced cffi-sys backend, whose
	// primitives are ffi: calls.
	private static boolean programUsesAnyFfiOp(List<LispVal> program) {
		for (String member : JvmFfiInteropCompiler.members()) {
			if (programUsesSymbol(program, PackageRegistry.qualify(LispNames.FFI_PKG, member))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the program references any of the seven vectorizable {@code vec:} kernels
	 * ({@code add}/{@code sub}/{@code mul}/{@code scale}/{@code dot}/{@code sum}/
	 * {@code matvec}) or any of the thirty-six accelerated {@code linalg:} ones, so that
	 * {@code --simd} actually emits the Vector API bridge (one bridge class serves both
	 * packages). {@code vec:mean}/{@code norm} and {@code linalg:mean}/{@code matmul}/
	 * {@code flatten}/{@code solve} are intercepted transitively via their spliced
	 * {@code sum}/{@code dot}/{@code reshape} calls, so they need not be listed here.
	 */
	private static boolean programUsesAnyAcceleratedSimdOp(List<LispVal> program) {
		for (String member : JvmSimdCompiler.members()) {
			if (programUsesSymbol(program, PackageRegistry.qualify(LispNames.VEC_PKG, member))) {
				return true;
			}
		}
		for (String member : JvmLinalgKernelCompiler.members()) {
			if (programUsesSymbol(program, JvmLinalgKernelCompiler.qualifiedName(member))) {
				return true;
			}
		}
		return false;
	}

	// True when the program references any hash-table operator (including (setf (gethash
	// ...)) which contains gethash). Gates both the runtime helpers and the first-class
	// wrappers so they stay emitted together.
	private static boolean programUsesAnyHashOp(List<LispVal> program) {
		return programUsesSymbol(program, LispNames.MAKE_HASH_TABLE) || programUsesSymbol(program, LispNames.GETHASH)
				|| programUsesSymbol(program, LispNames.REMHASH) || programUsesSymbol(program, LispNames.CLRHASH)
				|| programUsesSymbol(program, LispNames.HASH_TABLE_COUNT)
				|| programUsesSymbol(program, LispNames.HASH_TABLE_P) || programUsesSymbol(program, LispNames.MAPHASH);
	}

	private static boolean programUsesAnyArrayOp(List<LispVal> program) {
		// The operator/literal half is LispMacroExpander.programUsesGeneralArrayOp, which
		// the shared %subseq-runtime injection gates on too -- one list, so a program
		// that
		// carries the helper is exactly a program this returns true for, and a subseq
		// site
		// never routes to a helper that was not injected.
		//
		// subseq/copy-seq/replace join the gate because subseq's string lane ANSWERS a
		// mutable character vector now (.todo/559 step 2, _subseqCv): the result needs
		// the array runtime everywhere it flows, and replace's destructive arm is what
		// writes through it. Without any of them (and none of the list's own producers)
		// no character vector can exist and the immutable slice path still compiles.
		return LispMacroExpander.programUsesGeneralArrayOp(program) || programBuildsConcatenateSequence(program)
				|| programTakesSequenceBuilderValue(program) || programUsesSymbol(program, LispNames.SUBSEQ)
				|| programUsesSymbol(program, LispNames.COPY_SEQ) || programUsesSymbol(program, LispNames.REPLACE)
				// The flipped string producers (concatenate 'string, the case family,
				// format nil, the string-stream capture, read-line) answer a mutable
				// character vector through _toMutStr, which needs the array runtime
				// everywhere the result flows -- same reasoning as subseq's line above.
				|| MutableStringProducers.programUsesAny(program);
	}

	// True when the program takes #'map or #'map-into as a value. Both wrappers do
	// STATICALLY in call position what is a runtime value here -- map's result type
	// (so its conversion goes through the computed coerce, which always carries the
	// vector-building arm) and map-into's element store (an (setf (elt ...)) that can
	// land in an array) -- so the wrapper body reaches the array runtime even though
	// the source scan above, which never sees the injected wrapper, finds no array op.
	// The #'concatenate precedent one method up.
	private static boolean programTakesSequenceBuilderValue(List<LispVal> program) {
		for (LispVal expr : program) {
			if (BuiltinFunctionWrappers.referencesFunctionValue(expr, LispNames.MAP)
					|| BuiltinFunctionWrappers.referencesFunctionValue(expr, LispNames.MAP_INTO)) {
				return true;
			}
		}
		return false;
	}

	// True when concatenate can build a list / vector here, which lowers through coerce
	// (the array runtime): a call whose literal result type is not the string family, a
	// computed one (which the lowering rejects, but not before this gate), or a
	// first-class #'concatenate, whose wrapper dispatches on a runtime result type. A
	// plain (concatenate 'string ...) program stays array-runtime-free.
	private static boolean programBuildsConcatenateSequence(List<LispVal> program) {
		for (LispVal expr : program) {
			if (BuiltinFunctionWrappers.referencesFunctionValue(expr, LispNames.CONCATENATE)
					|| buildsConcatenateSequence(expr)) {
				return true;
			}
		}
		return false;
	}

	private static boolean buildsConcatenateSequence(LispVal form) {
		while (form instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol op && LispNames.CONCATENATE.equals(op.name())) {
				LispVal typeForm = (cons.cdr() instanceof LispCons rest) ? rest.car() : LispNil.INSTANCE;
				if (ConcatenateForms.literalResultFamily(typeForm) != ConcatenateForms.ResultFamily.STRING) {
					return true;
				}
			}
			if (buildsConcatenateSequence(cons.car())) {
				return true;
			}
			form = cons.cdr();
		}
		return false;
	}

	// True when the program can produce a packed float array: a #d(...) literal
	// (LispFloatArray), a (make-array ... :element-type 'double-float ...) form, or a
	// (make-array ... :element-type <runtime designator> ...) form whose inline
	// dispatch (LispMacroExpander.lowerRuntimeElementTypeMakeArray) can still reach one
	// of its float arms -- a run-time designator combined with :initial-contents spells
	// that dispatch inline, at codegen time, after this scan has already run. Gates the
	// _fv* dispatch helpers and their routing; when false the
	// array op compilers call the general _array* helpers directly, keeping the default
	// build byte-identical.
	private static boolean programUsesFloatArray(List<LispVal> program, ClosRegistry closRegistry) {
		for (LispVal expr : program) {
			if (usesFloatArray(expr, closRegistry)) {
				return true;
			}
		}
		return false;
	}

	private static boolean usesFloatArray(LispVal val, ClosRegistry closRegistry) {
		while (true) {
			if (val instanceof am.ik.rontolisp.LispFloatArray) {
				return true;
			}
			if (!(val instanceof LispCons cons)) {
				return false;
			}
			if (cons.car() instanceof LispSymbol head && LispNames.MAKE_ARRAY.equals(head.name())
					&& (makeArrayIsPackedFloat(cons, closRegistry)
							|| LispMacroExpander.runtimeElementTypeMakeArrayCanPackFloat(cons))) {
				return true;
			}
			if (usesFloatArray(cons.car(), closRegistry)) {
				return true;
			}
			val = cons.cdr();
		}
	}

	// True when the program can produce a packed integer vector: a #N@(...) literal
	// (LispIntVector, which also arrives as a macro-time value) or a
	// (make-array ... :element-type '(unsigned-byte 8|16|32) ...) form. Gates the _iv*
	// dispatch helpers and their routing; when false the array op compilers keep the
	// fv/general dispatch, so the default build is byte-identical.
	private static boolean programUsesIntArray(List<LispVal> program, ClosRegistry closRegistry) {
		for (LispVal expr : program) {
			if (usesIntArray(expr, closRegistry)) {
				return true;
			}
		}
		return false;
	}

	private static boolean usesIntArray(LispVal val, ClosRegistry closRegistry) {
		while (true) {
			if (val instanceof am.ik.rontolisp.LispIntVector) {
				return true;
			}
			if (!(val instanceof LispCons cons)) {
				return false;
			}
			if (cons.car() instanceof LispSymbol head && LispNames.MAKE_ARRAY.equals(head.name())
					&& makeArrayIsPackedInt(cons, closRegistry)) {
				return true;
			}
			if (usesIntArray(cons.car(), closRegistry)) {
				return true;
			}
			val = cons.cdr();
		}
	}

	// Whether a (make-array ...) call carries :element-type '(unsigned-byte 8|16|32) --
	// a literal quoted list at the call site, or a deftype alias of one -- the packed
	// integer-vector shape. The gate resolves the alias for the same reason
	// JvmArrayCompiler.compileMake does: a gate that missed it would leave the _iv*
	// helpers unemitted and send salza2's (make-array n :element-type 'octet) to the
	// general boxed path, whose elements read back as nil rather than 0.
	private static boolean makeArrayIsPackedInt(LispCons makeArray, ClosRegistry closRegistry) {
		List<LispVal> args = makeArray.toList();
		for (int i = 2; i + 1 < args.size(); i++) {
			if (args.get(i) instanceof LispSymbol kw && LispNames.ELEMENT_TYPE_KEYWORD.equals(kw.name())) {
				return JvmArrayCompiler.packedIntElementWidth(
						LispMacroExpander.resolveElementTypeAlias(args.get(i + 1), closRegistry)) > 0;
			}
		}
		return false;
	}

	// Whether a (make-array ...) call carries a :element-type naming a packed float
	// width (a literal quoted symbol at the call site or a deftype alias of one -- the
	// alias resolves to the BARE symbol, and prototypeFor accepts both shapes) --
	// whatever produces a packed float array. Resolved through the SAME table as
	// JvmArrayCompiler.compileMake's gate, so the gate that emits the _*fvMake helpers
	// and the gate that runs them can never disagree about a width.
	private static boolean makeArrayIsPackedFloat(LispCons makeArray, ClosRegistry closRegistry) {
		List<LispVal> args = makeArray.toList();
		for (int i = 2; i + 1 < args.size(); i++) {
			if (args.get(i) instanceof LispSymbol kw && LispNames.ELEMENT_TYPE_KEYWORD.equals(kw.name())) {
				return am.ik.rontolisp.LispFloatArray
					.prototypeFor(LispMacroExpander.resolveElementTypeAlias(args.get(i + 1), closRegistry)) != null;
			}
		}
		return false;
	}

	private static boolean usesSymbol(LispVal val, String name) {
		while (val instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol sym && name.equals(sym.name())) {
				return true;
			}
			if (usesSymbol(cons.car(), name)) {
				return true;
			}
			val = cons.cdr();
		}
		return false;
	}

	/**
	 * Whether the program names the built-in as a function designator -- {@code #'op} or
	 * {@code 'op}, see {@link BuiltinFunctionWrappers#referencesFunctionDesignator} --
	 * i.e. whether its injected wrapper can be reached at all. A condition's
	 * {@code :report} lambda counts: {@code define-condition} is rewritten out of the
	 * program, so the lambda lives only in the registry, but the error/signal expansions
	 * inject it back.
	 * @param program the resolved top-level forms
	 * @param closRegistry the registry holding the condition reports
	 * @param op the built-in's name
	 * @return {@code true} when a {@code (function op)} or {@code (quote op)} occurs
	 */
	private static boolean referencesFunctionDesignator(List<LispVal> program, ClosRegistry closRegistry, String op) {
		return program.stream().anyMatch(expr -> BuiltinFunctionWrappers.referencesFunctionDesignator(expr, op))
				|| closRegistry.conditionReports()
					.values()
					.stream()
					.anyMatch(report -> BuiltinFunctionWrappers.referencesFunctionDesignator(report, op));
	}

	private static boolean spellsSymbolConstant(List<LispVal> program, ClosRegistry closRegistry, Set<String> names) {
		return program.stream().anyMatch(expr -> BuiltinFunctionWrappers.spellsSymbolConstant(expr, names))
				|| closRegistry.conditionReports()
					.values()
					.stream()
					.anyMatch(report -> BuiltinFunctionWrappers.spellsSymbolConstant(report, names));
	}

	private static boolean usesEval(LispVal val) {
		while (val instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol sym && LispNames.EVAL.equals(sym.name())) {
				return true;
			}
			if (usesEval(cons.car())) {
				return true;
			}
			val = cons.cdr();
		}
		return false;
	}

	static boolean hasDoubleLiteral(List<LispVal> args, Ctx ctx) {
		for (int i = 1; i < args.size(); i++) {
			if (containsDouble(args.get(i), ctx)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a complex value is syntactically visible in a call's arguments: a
	 * {@code LispComplex} literal, or a {@code complex}/{@code conjugate} form. Anything
	 * answering complex steers off the unboxed double fast path onto the object path
	 * (and, for the constructors, onto the {@code _c*} helpers), so a complex operand
	 * never reaches an unboxing ({@code .kb/jvm-complex.md}). A complex arriving only
	 * through a variable is invisible here -- it takes the guarded object path's funnels
	 * instead.
	 * @param args the call with its operator at index 0
	 * @return true when a complex producer occurs in the arguments
	 */
	static boolean hasComplexOperand(List<LispVal> args) {
		for (int i = 1; i < args.size(); i++) {
			if (LispMacroExpander.containsComplex(args.get(i))) {
				return true;
			}
		}
		return false;
	}

	/** The forms whose value is an integer whatever their argument types. */
	private static final java.util.Set<String> INTEGER_VALUED_FORMS = java.util.Set.of(LispNames.ROUND,
			LispNames.TRUNCATE, LispNames.FLOOR, LispNames.CEILING);

	/**
	 * The SYNTACTIC half of {@link #containsDouble(LispVal, Ctx)} alone -- a double
	 * literal in the tree, no scope. For the program-wide pre-passes
	 * ({@link JvmRawGlobals}) that run before any lexical scope exists; everything inside
	 * a method body asks the scoped form.
	 * @param val the expression tree
	 * @return true when a double literal occurs outside an integer-valued form
	 */
	static boolean containsDoubleLiteral(LispVal val) {
		if (val instanceof LispDouble) {
			return true;
		}
		if (val instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head && INTEGER_VALUED_FORMS.contains(head.name())) {
				return false;
			}
			for (LispVal element : cons.toList()) {
				if (containsDoubleLiteral(element)) {
					return true;
				}
			}
		}
		return false;
	}

	static boolean containsDouble(LispVal val, Ctx ctx) {
		if (val instanceof LispDouble) {
			return true;
		}
		// A lexical variable declared to be a float counts like a double literal: its
		// value -- when the declaration is true -- is always a Double, so the unboxed
		// path answers the same bits the generic helpers would. A FALSE declaration is
		// undefined behavior; the routed emission reads the variable through a strict
		// cast, so it fails as a deterministic ClassCastException, never a silently
		// different value (.kb/declarations-type-checks.md).
		if (val instanceof LispSymbol sym
				&& (ctx.declaredDoubles.contains(sym.name()) || ctx.rawDoubleLocals.containsKey(sym.name()))) {
			return true;
		}
		if (val instanceof LispCons cons) {
			// A rounding form yields an integer whatever its argument types, so a
			// double literal inside it must not drag the ENCLOSING arithmetic onto
			// the double path: (- 0 (round (* v 100.0))) is integer work.
			if (cons.car() instanceof LispSymbol head && INTEGER_VALUED_FORMS.contains(head.name())) {
				return false;
			}
			for (LispVal element : cons.toList()) {
				if (containsDouble(element, ctx)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The arithmetic operators CLHS itself defines as float whenever ANY argument is
	 * float, regardless of what the other arguments are: recursing into one of these is a
	 * real PROOF of the node's result type, not a guess about a subtree that happens to
	 * contain a double literal somewhere.
	 */
	private static final java.util.Set<String> CONTAGIOUS_ARITHMETIC_FORMS = java.util.Set.of(LispNames.ADD,
			LispNames.SUB, LispNames.MUL, LispNames.MOD, LispNames.REM);

	/**
	 * True when {@code val}'s VALUE is PROVEN to be a double, unlike
	 * {@link #containsDouble}, which only asks whether a double literal occurs ANYWHERE
	 * in the subtree. That guess is sound for the force-coercing operators themselves
	 * ({@code compileUnboxedOperand} widens any Number it is handed, so a wrong guess
	 * about an unrelated nested form still lands on the right answer), but it is NOT
	 * sound as a basis for choosing min/max's result type: their result is exactly one of
	 * the two operands, so treating the wrong one as a double changes its TYPE, not just
	 * its box -- {@code (min 1 2.0)} would answer the double {@code 1.0} instead of the
	 * rational {@code 1}. Recursion here is bounded to
	 * {@link #CONTAGIOUS_ARITHMETIC_FORMS} -- true contagion, not a guess -- and never
	 * crosses into an arbitrary function call (whose return type this pass cannot see) or
	 * into {@code min}/{@code max} themselves (whose own result is exactly this same
	 * ambiguity).
	 * @param val the expression tree
	 * @param ctx the compiler context (declared/raw double locals)
	 * @return true only when val is guaranteed to evaluate to a double
	 */
	static boolean isDefinitelyDouble(LispVal val, Ctx ctx) {
		if (val instanceof LispDouble) {
			return true;
		}
		if (val instanceof LispSymbol sym
				&& (ctx.declaredDoubles.contains(sym.name()) || ctx.rawDoubleLocals.containsKey(sym.name()))) {
			return true;
		}
		if (val instanceof LispCons cons && cons.isProperList() && cons.car() instanceof LispSymbol head
				&& CONTAGIOUS_ARITHMETIC_FORMS.contains(head.name())) {
			List<LispVal> parts = cons.toList();
			for (LispVal operand : parts.subList(1, parts.size())) {
				if (isDefinitelyDouble(operand, ctx)) {
					return true;
				}
			}
		}
		return false;
	}

	static DefunDecl extractSetqLambda(LispVal expr, Set<String> specials) {
		List<LispVal> parts = ((LispCons) expr).toList();
		String funcName = ((LispSymbol) parts.get(1)).name();
		List<LispVal> lambdaParts = ((LispCons) parts.get(2)).toList();
		LambdaLists.NativeForm nf = LambdaLists.toNative(lambdaParts.get(1), lambdaParts.subList(2, lambdaParts.size()),
				LambdaLists.MAX_PHYSICAL_PARAMS, funcName, specials);
		return new DefunDecl(funcName, nf.paramNames(), nf.variadic(), nf.optionals(), nf.body());
	}

	// One injected runtime defun -- a wrapper or a shared sequence helper, a (setq name
	// (lambda ...)) form -- into Pass 1's defuns, its form kept for the
	// dynamically-bound collection.
	private static void inject(LispVal setqLambda, List<DefunDecl> defuns, List<LispVal> injectedForms,
			Set<String> specials) {
		defuns.add(extractSetqLambda(setqLambda, specials));
		injectedForms.add(setqLambda);
	}

	// A (rontolisp:wasm-import ...) stub: a defun of the declared arity whose body
	// signals an error, since the imported host function only exists in WASM output.
	private static DefunDecl wasmImportStub(WasmImportDirective directive) {
		List<String> paramNames = new ArrayList<>();
		// lispParamCount, not the declared parameter count: a :returns :bytes import
		// takes one extra trailing argument (the caller-passed receive buffer), and the
		// stub must load with the arity the WASM wrapper will have.
		for (int i = 0; i < directive.lispParamCount(); i++) {
			paramNames.add("%wasm-import-p" + i);
		}
		LispVal body = new LispCons(new LispSymbol(LispNames.ERROR),
				new LispCons(new am.ik.rontolisp.LispString(
						directive.name() + " is a host function declared by rontolisp:wasm-import; "
								+ "it can only be called from a compiled WASM module"),
						LispNil.INSTANCE));
		return new DefunDecl(directive.name(), paramNames, false, List.of(body));
	}

	/**
	 * Mangles a Lisp function name into a valid JVM method name. The JVM spec forbids
	 * {@code /}, {@code <}, {@code >}, {@code .}, {@code ;}, {@code [} in unqualified
	 * names; {@code %} is legal but is mapped too, to work around a JVMCI bug (see
	 * below).
	 */
	static String mangleMethodName(String name) {
		String mangled = switch (name) {
			case "/" -> "$div";
			case "<" -> "$lt";
			case ">" -> "$gt";
			case "<=" -> "$le";
			case ">=" -> "$ge";
			default -> name;
		};
		// Package-qualified names (e.g. rontolisp:foo) cannot contain ':' in a JVM method
		// name; map it so user-defined symbols of non-default packages compile. The same
		// applies to any residual '<'/'>' the exact-match switch above did not consume
		// (e.g. the char</char<= wrapper names), which the JVM reserves for
		// <init>/<clinit>.
		if (mangled.indexOf(':') >= 0) {
			mangled = mangled.replace(":", "$colon");
		}
		if (mangled.indexOf('<') >= 0) {
			mangled = mangled.replace("<", "$lt");
		}
		if (mangled.indexOf('>') >= 0) {
			mangled = mangled.replace(">", "$gt");
		}
		// '.' is illegal in JVM method/field names; dotted package names (e.g.
		// parse-number's org.mapcar.parse-number) reach here through qualified
		// defun/global names. A residual '/' (mid-name, e.g. make-float/frac) is a
		// package separator to the JVM, so it must go too.
		if (mangled.indexOf('.') >= 0) {
			mangled = mangled.replace(".", "$dot");
		}
		if (mangled.indexOf('/') >= 0) {
			mangled = mangled.replace("/", "$div");
		}
		// '[' and ';' are the rest of JVMS 4.2.2's list: a name spelling either (a Scheme
		// internal record type's, s%%[f node]) failed to load as "Illegal method name".
		if (mangled.indexOf('[') >= 0) {
			mangled = mangled.replace("[", "$lbrack");
		}
		if (mangled.indexOf(';') >= 0) {
			mangled = mangled.replace(";", "$semi");
		}
		// '%' is legal in a JVM method name, but JVMCI (HotSpotSpeculationLog:201)
		// passes a message containing the method name as the FORMAT string of
		// BailoutException, where a '%' starts a format conversion: under a JVMCI
		// compiler, a hot method named e.g. linalg::%la-matmul aborts its JIT
		// compilation with UnknownFormatConversionException ('%l'). Internal names use
		// the '%' prefix by convention, so map it away.
		if (mangled.indexOf('%') >= 0) {
			mangled = mangled.replace("%", "$pct");
		}
		return mangled;
	}

	/**
	 * A parsed defun. {@code paramNames} are the physical parameters (when
	 * {@code variadic}, the last one is the {@code &rest} parameter receiving the
	 * remaining arguments as a cons list, and the {@code optionals} names before it are
	 * physical optionals, {@link LambdaLists.NativeForm}).
	 */
	record DefunDecl(String name, List<String> paramNames, boolean variadic, int optionals, List<LispVal> bodyExprs) {

		/** A defun without physical optionals. */
		DefunDecl(String name, List<String> paramNames, boolean variadic, List<LispVal> bodyExprs) {
			this(name, paramNames, variadic, 0, bodyExprs);
		}

	}

	/**
	 * Registry entry for a compiled function. {@code paramCount} is the physical Lisp
	 * parameter count; when {@code variadic}, the last parameter is the rest list, the
	 * {@code optionals} before it are physical optionals (an argument or the UNSUPPLIED
	 * marker, {@link JvmPhysicalArgs}), and the callable minimum is {@link #required()}
	 * arguments. A function whose method may answer a trampoline bounce
	 * ({@code bounceVisible}: the defuns {@link JvmTailBounce} finds, a lambda whose body
	 * reads its depth) takes one {@code int} more after them, the value-tail depth.
	 */
	record FunctionInfo(int funcId, int paramCount, boolean variadic, int optionals, boolean isClosure,
			MethodRefEntry methodref, Utf8Entry nameUtf8, Utf8Entry descUtf8, boolean bounceVisible) {

		/**
		 * {@return the arguments a call must pass at least}
		 */
		int required() {
			return this.paramCount - (this.variadic ? 1 : 0) - this.optionals;
		}

		/**
		 * {@return the parameters before the rest list} -- the required ones and the
		 * physical optionals
		 */
		int positional() {
			return this.paramCount - (this.variadic ? 1 : 0);
		}

	}

	/**
	 * A lambda awaiting Pass 2c.
	 * @param funcId its function id
	 * @param methodName its method's name
	 * @param paramNames its parameters
	 * @param variadic whether the last parameter takes the rest
	 * @param bodyExprs its body forms
	 * @param freeVarNames the variables it captures, in environment order
	 * @param reportName the name the uncaught report calls it by -- a non-top-level
	 * defun's, which the interpreter installs as a named function -- or {@code null} for
	 * an anonymous one
	 * @param asyncHead the report head of the async body this lambda is the
	 * {@code %async-run} thunk of ({@link JvmUncaughtHandler#appendAsyncCrossing}), or
	 * {@code null}
	 */
	/**
	 * A lambda Pass 2c compiles into a method of its own.
	 *
	 * @param reportName the name a non-top-level defun installs it under, or {@code null}
	 * @param asyncHead the report head of an {@code %async-run} thunk, or {@code null}
	 * @param writtenIn the name the report calls the program function its code is written
	 * in, or {@code null} for none (the top level, an async body)
	 * @param selfVar the {@code labels} variable this lambda is the value of, through
	 * which a call is a self call ({@link JvmSelfTailCall}), or {@code null}
	 * @param tailMember the tail group this {@code labels} function is a member of
	 * ({@link JvmTailGroup}), or {@code null}
	 * @param madeIn the body that makes the lambda's value, whose record of it
	 * ({@link MethodCode#makesValueOf}) names the method by the descriptor it gets in the
	 * end: with the value-tail depth, unless its body never reads it
	 */
	record LambdaInfo(int funcId, String methodName, List<String> paramNames, boolean variadic, int optionals,
			List<LispVal> bodyExprs, List<String> freeVarNames, @Nullable String reportName, @Nullable String asyncHead,
			@Nullable String writtenIn, @Nullable String selfVar, JvmTailGroup.@Nullable Member tailMember,
			MethodCode madeIn) {
	}

	record DispatchMethod(Utf8Entry nameUtf8, Utf8Entry descUtf8, MethodCode code) {
	}

	/**
	 * An active block return boundary during compilation ({@code %block}, a named
	 * {@code block} or the {@code %fn-block} function boundary). {@code rvSlot} is the
	 * local that holds the block's value; {@code exit} is the label the {@code goto}
	 * instructions emitted by {@code return}/{@code return-from} forms jump to, bound at
	 * the block's exit once its body has been compiled; {@code entryStack} is the operand
	 * stack the block was entered with, which is the shape its exit is reached with on
	 * every path -- an exit discards whatever the body had pushed on top of it (see
	 * {@link JvmReturnCompiler}). {@code name} is the block name a {@code return-from}
	 * matches against ({@code null} for {@code %block} and the {@code nil} block);
	 * {@code catchesPlain} marks the targets a plain {@code return} exits ({@code %block}
	 * and {@code (block nil ...)}); {@code functionBoundary} marks the {@code %fn-block}
	 * wrap -- the fallback target for a {@code return-from} whose name matches no
	 * enclosing block. {@code tail} marks a block whose value is the method's result
	 * ({@code Ctx.tailMark}), so an exit to it from the {@code Ctx.exitMark} chain hands
	 * its value form on as the method's tail.
	 */
	record BlockTarget(int rvSlot, MethodCode.Label exit, List<OperandStack.Slot> entryStack, @Nullable String name,
			boolean catchesPlain, boolean functionBoundary, boolean tail) {
	}

	/**
	 * An active {@code tagbody} during compilation. {@code labels} maps each of the
	 * tagbody's tags to the label a {@code go} to it jumps to -- bound where the tag
	 * stands, so a {@code go} past it jumps backward and one before it waits for the
	 * binding (its key set is the tagbody's full tag set, registered up front so
	 * {@code JvmGoCompiler} can resolve the innermost tagbody declaring a tag).
	 * {@code entryStack} is the operand stack at tagbody entry -- every label is reached
	 * with exactly that shape ({@code go} discards anything above it);
	 * {@code unwindDepth}/{@code spillDepth} are the scope-stack sizes at entry, so a
	 * {@code go} can tell which {@code unwind-protect}/{@code handler-case} scopes it
	 * escapes.
	 */
	record TagbodyScope(List<OperandStack.Slot> entryStack, int unwindDepth, int spillDepth,
			java.util.Map<String, MethodCode.Label> labels) {
	}

	/**
	 * The shared condition-channel state of one compilation: the constants of the
	 * {@code private static ThreadLocal _condTl} field that carries a condition object (a
	 * tagged-list instance) from a {@code %error-cond} throw site to a
	 * {@code handler-case} catch handler on the same thread of control (thread-scoped so
	 * concurrent {@code rontolisp:http-handler} requests do not clobber each other),
	 * keyed by the throwable it travels with ({@link JvmThrowableRecords}: a site records
	 * through {@link #condPut}, a landing takes through {@link #condTake}). One instance
	 * is shared by every {@link Ctx} of a compilation (the {@code nextFuncId} pattern);
	 * the field, its {@code <clinit>} initializer and the two helpers are emitted only
	 * when a compiler marked it {@link #used}.
	 */
	static final class ConditionChannel {

		boolean used = false;

		@Nullable FieldRefEntry condTlField;

		/** {@code _condTake(Throwable)Object}: a landing's read of what it caught. */
		@Nullable MethodRefEntry condTake;

		/**
		 * {@code _condPut(Throwable, Object)Throwable}: a throw site's record of the
		 * condition its throwable carries.
		 */
		@Nullable MethodRefEntry condPut;

		/**
		 * {@code _condRan(Throwable, Object)Throwable}: the record of a condition whose
		 * {@code handler-bind} handlers already ran -- a signal hook's terminal, a
		 * {@code %hb-guard} pad's rethrow. Null until a site asks
		 * ({@link #ensureHandlersRan}), so a program without either stays byte-identical.
		 */
		@Nullable MethodRefEntry condRan;

		/**
		 * {@code _condOf(Object)Object}: the condition a record names, whichever of the
		 * two shapes it has. Minted with {@link #condRan}.
		 */
		@Nullable MethodRefEntry condOf;

		@Nullable Utf8Entry fieldName;

		@Nullable Utf8Entry fieldDesc;

		@Nullable ClassEntry threadLocalClass;

		@Nullable MethodRefEntry tlCtor;

		@Nullable MethodRefEntry tlSet;

		@Nullable MethodRefEntry tlGet;

		@Nullable Utf8Entry clinitName;

		@Nullable Utf8Entry clinitDesc;

		/**
		 * The per-thread {@code handler-case} handler-depth counter (a
		 * {@code ThreadLocal} of {@code Integer}, null = 0), consulted by
		 * {@code %signal-cond} -- {@code signal} raises only when a handler is
		 * established.
		 */
		@Nullable FieldRefEntry depthTlField;

		@Nullable Utf8Entry depthFieldName;

		/**
		 * True when the program lowers a cross-lambda {@code return-from}: the
		 * {@code _nleTl} ThreadLocal channel carries the pending non-local exit's
		 * {@code {throwable, id, value}} triple. Tracked independently of {@link #used}
		 * so a program that only lowers a cross-lambda exit (no typed conditions) still
		 * emits the field and its {@code <clinit>}, and a program that only uses
		 * conditions stays byte-identical.
		 */
		boolean nleUsed = false;

		@Nullable FieldRefEntry nleTlField;

		@Nullable Utf8Entry nleFieldName;

		/**
		 * The shared {@code %hb-guard} landing pad ({@code _hbGuard}), built on the first
		 * {@code handler-bind} the program compiles and called by every one after it.
		 * Held here rather than per method because the pad reads only the caught
		 * throwable and the class-wide channel, so one copy serves the whole class.
		 */
		@Nullable MethodRefEntry hbGuardPad;

		/**
		 * The shared synthesis of a condition-less throw's instance ({@code _hcSynth}),
		 * built on the first landing that needs it and called by every
		 * {@code handler-case} landing and the {@code _hbGuard} pad. Class-wide for the
		 * same reason as {@link #hbGuardPad}: it reads only the caught throwable.
		 */
		@Nullable MethodRefEntry conditionSynthesizer;

		/**
		 * {@code _jexMap}: what each {@code java:} member that threw threw, keyed weakly
		 * by the {@code RuntimeException} {@code _jfail} wrapped it in -- a
		 * {@code Collections.synchronizedMap} over a {@code WeakHashMap}, one for the
		 * class, so the throwable reaches a landing on whatever thread the exception does
		 * (an await, a join). Null until the synthesis of a program with {@code java:}
		 * sites asks ({@link #ensureJavaExceptions}): the cause would serve as well, but
		 * the default handler prints a cause's frames under an uncaught report the class
		 * keeps to one line.
		 */
		@Nullable FieldRefEntry javaExceptionsField;

		@Nullable Utf8Entry javaExceptionsName;

		@Nullable Utf8Entry javaExceptionsDesc;

		/**
		 * {@code _jexc(Object)Object}: a value handed to a {@code java:} member as an
		 * argument whose kind the site does not know, a {@code java:java-exception} as
		 * the host exception it stands for ({@code JvmJavaInteropCompiler}). Built on the
		 * first site that needs it.
		 */
		@Nullable MethodRefEntry javaExceptionArgument;

		// What <clinit> makes _jexMap of, minted with the field.
		@Nullable ClassEntry weakMapClass;

		@Nullable MethodRefEntry weakMapCtor;

		@Nullable MethodRefEntry synchronizedMap;

		/**
		 * Mints {@code _jexMap}, which {@code _jfail} records into and the synthesis of a
		 * condition-less throw reads ({@code JvmHandlerCaseCompiler}).
		 * @param cp the constant pool
		 * @param className the class
		 * @return the field
		 */
		FieldRefEntry ensureJavaExceptions(ConstantPool cp, String className) {
			FieldRefEntry field = this.javaExceptionsField;
			if (field == null) {
				this.javaExceptionsName = cp.utf8Entry("_jexMap");
				this.javaExceptionsDesc = cp.utf8Entry("Ljava/util/Map;");
				field = cp.fieldRef(cp.classEntry(className), this.javaExceptionsName, this.javaExceptionsDesc);
				this.javaExceptionsField = field;
				this.weakMapClass = cp.classEntry(JvmThrowableRecords.WEAK_MAP);
				this.weakMapCtor = cp.methodRef(this.weakMapClass, "<init>", "()V");
				this.synchronizedMap = cp.methodRef(cp.classEntry("java/util/Collections"), "synchronizedMap",
						"(Ljava/util/Map;)Ljava/util/Map;");
			}
			return field;
		}

		/**
		 * Appends {@code _jexMap}'s initialization to {@code <clinit>}, when the class
		 * has it.
		 * @param clinit the initializer's code
		 */
		void initJavaExceptions(MethodCode clinit) {
			FieldRefEntry field = this.javaExceptionsField;
			if (field == null) {
				return;
			}
			clinit.new_(Objects.requireNonNull(this.weakMapClass));
			clinit.dup();
			clinit.invokespecial(Objects.requireNonNull(this.weakMapCtor));
			clinit.invokestatic(Objects.requireNonNull(this.synchronizedMap));
			clinit.putstatic(field);
		}

		/**
		 * Lazily creates the constant-pool entries (idempotent adds) and marks the
		 * channel used, so the class writer emits the two ThreadLocal fields and their
		 * {@code <clinit>}.
		 */
		void ensure(ConstantPool cp, String className) {
			if (this.used) {
				return;
			}
			this.used = true;
			this.fieldName = cp.utf8Entry("_condTl");
			this.fieldDesc = cp.utf8Entry("Ljava/lang/ThreadLocal;");
			ClassEntry thisClass = cp.classEntry(className);
			this.condTlField = cp.fieldRef(thisClass, this.fieldName, this.fieldDesc);
			this.depthFieldName = cp.utf8Entry("_hcDepthTl");
			this.depthTlField = cp.fieldRef(thisClass, this.depthFieldName, this.fieldDesc);
			this.condTake = JvmThrowableRecords.self(cp, thisClass, JvmThrowableRecords.COND_TAKE,
					JvmThrowableRecords.COND_TAKE_DESC);
			this.condPut = JvmThrowableRecords.self(cp, thisClass, JvmThrowableRecords.COND_PUT,
					JvmThrowableRecords.COND_PUT_DESC);
			ensureThreadLocalInfra(cp);
		}

		/**
		 * {@link #ensure}, plus the two helpers of a record that says the
		 * {@code handler-bind} handlers ran ({@link JvmThrowableRecords#COND_RAN}): what
		 * a restart-mode signal hook's terminal, a {@code %hb-guard} pad and a
		 * restart-mode {@code handler-case} landing call.
		 */
		void ensureHandlersRan(ConstantPool cp, String className) {
			ensure(cp, className);
			if (this.condRan != null) {
				return;
			}
			ClassEntry thisClass = cp.classEntry(className);
			this.condRan = JvmThrowableRecords.self(cp, thisClass, JvmThrowableRecords.COND_RAN,
					JvmThrowableRecords.COND_RAN_DESC);
			this.condOf = JvmThrowableRecords.self(cp, thisClass, JvmThrowableRecords.COND_OF,
					JvmThrowableRecords.COND_OF_DESC);
		}

		/**
		 * Lazily creates the {@code _nleTl} field ref and marks the NLE channel used, so
		 * the class writer emits the field and initializes it in {@code <clinit>}.
		 * Ensures the shared ThreadLocal constants exist even when no typed condition
		 * does.
		 */
		void ensureNle(ConstantPool cp, String className) {
			if (this.nleUsed) {
				return;
			}
			this.nleUsed = true;
			this.fieldDesc = this.fieldDesc != null ? this.fieldDesc : cp.utf8Entry("Ljava/lang/ThreadLocal;");
			this.nleFieldName = cp.utf8Entry("_nleTl");
			ClassEntry thisClass = cp.classEntry(className);
			this.nleTlField = cp.fieldRef(thisClass, this.nleFieldName, this.fieldDesc);
			ensureThreadLocalInfra(cp);
		}

		void ensureThreadLocalInfra(ConstantPool cp) {
			if (this.threadLocalClass != null) {
				return;
			}
			this.threadLocalClass = cp.classEntry("java/lang/ThreadLocal");
			this.tlCtor = cp.methodRef(this.threadLocalClass, "<init>", "()V");
			this.tlSet = cp.methodRef(this.threadLocalClass, "set", "(Ljava/lang/Object;)V");
			this.tlGet = cp.methodRef(this.threadLocalClass, "get", "()Ljava/lang/Object;");
			this.clinitName = cp.utf8Entry("<clinit>");
			this.clinitDesc = cp.utf8Entry("()V");
		}

	}

	/**
	 * The compilation-wide interner of instance layouts: one {@code private static}
	 * {@code String[]} field per instance tag the program actually references, holding
	 * <code>{tag, printName, "S"|"C", slot0, slot1, ...}</code> and initialized in
	 * {@code <clinit>}.
	 *
	 * <p>
	 * That array is also the runtime type discriminator: an instance is {@code Object[]{
	 * layout, v1, ..., vn }} and no other value the backend produces has a
	 * {@code String[]} in slot 0 (a cons is {@code Object[2]} of Lisp values, a function
	 * value has an {@code Integer} there, a ratio is {@code BigInteger[]}).
	 *
	 * <p>
	 * Everything is minted during body compilation, never from a writer lambda: the
	 * constant pool is serialized by {@code .writeConstantPool(cp)} before the field and
	 * method writers run, so an index created later would never be written.
	 */
	static final class LayoutPool {

		/**
		 * One interned layout: the field name constant, the fieldref to load it, and the
		 * layout whose strings {@code <clinit>} materializes.
		 *
		 * @param name the field name constant
		 * @param ref the fieldref used by {@code GETSTATIC}/{@code PUTSTATIC}
		 * @param layout the layout the field holds
		 */
		record LayoutField(Utf8Entry name, FieldRefEntry ref, am.ik.rontolisp.LispLayout layout) {
		}

		private final Map<String, LayoutField> byTag = new java.util.LinkedHashMap<>();

		private final Set<String> usedFieldNames = new HashSet<>();

		@Nullable Utf8Entry fieldDesc;

		@Nullable ClassEntry stringArrayCls;

		@Nullable ClassEntry stringCls;

		@Nullable Utf8Entry clinitName;

		@Nullable Utf8Entry clinitDesc;

		/**
		 * Whether no layout field has been interned, i.e. the program builds no instance.
		 * @return true when nothing has to be emitted
		 */
		boolean isEmpty() {
			return this.byTag.isEmpty();
		}

		/**
		 * The interned layout fields, in interning order.
		 * @return the fields to emit
		 */
		java.util.Collection<LayoutField> fields() {
			return this.byTag.values();
		}

		/**
		 * The {@code [Ljava/lang/String;} class constant -- the instance discriminator.
		 * Needed by the predicates even when no layout field is interned.
		 * @param cp the constant pool
		 * @return the class constant
		 */
		ClassEntry stringArrayClass(ConstantPool cp) {
			if (this.stringArrayCls == null) {
				this.stringArrayCls = cp.classEntry("[Ljava/lang/String;");
			}
			return this.stringArrayCls;
		}

		/**
		 * Interns the static field holding one instance tag's layout; idempotent per tag.
		 * @param cp the constant pool
		 * @param className the internal name of the class being emitted
		 * @param layout the layout to intern
		 * @return the fieldref of the layout constant
		 */
		FieldRefEntry intern(ConstantPool cp, String className, am.ik.rontolisp.LispLayout layout) {
			LayoutField existing = this.byTag.get(layout.tag());
			if (existing != null) {
				return existing.ref();
			}
			stringArrayClass(cp);
			if (this.stringCls == null) {
				this.stringCls = cp.classEntry("java/lang/String");
			}
			if (this.fieldDesc == null) {
				this.fieldDesc = cp.utf8Entry("[Ljava/lang/String;");
			}
			if (this.clinitName == null) {
				this.clinitName = cp.utf8Entry("<clinit>");
				this.clinitDesc = cp.utf8Entry("()V");
			}
			// The mangled tag can in principle collide with another mangled tag; the
			// used-name set makes the field name deterministic and unique anyway.
			String base = "_ly$" + mangleMethodName(layout.tag());
			String name = base;
			for (int n = 1; !this.usedFieldNames.add(name); n++) {
				name = base + "$" + n;
			}
			Utf8Entry nameUtf = cp.utf8Entry(name);
			ClassEntry thisClass = cp.classEntry(className);
			FieldRefEntry ref = cp.fieldRef(thisClass, nameUtf, this.fieldDesc);
			this.byTag.put(layout.tag(), new LayoutField(nameUtf, ref, layout));
			return ref;
		}

		/**
		 * Appends the layout initializers to the shared {@code <clinit>} body.
		 * @param code the {@code <clinit>} body being assembled
		 * @param cp the constant pool (mints the layout strings)
		 */
		void emitClinitInit(MethodCode code, ConstantPool cp) {
			for (LayoutField lf : this.byTag.values()) {
				List<String> parts = new ArrayList<>();
				parts.add(lf.layout().tag());
				parts.add(lf.layout().printName());
				parts.add(switch (lf.layout().kind()) {
					case STRUCT -> "S";
					case CLASS -> "C";
					case PATHNAME -> "P";
					case OPAQUE -> "O";
				});
				parts.addAll(lf.layout().slotNames());
				code.loadConstant(parts.size());
				code.anewarray(Objects.requireNonNull(this.stringCls));
				for (int i = 0; i < parts.size(); i++) {
					code.dup();
					code.loadConstant(i);
					code.ldc(cp.stringEntry(parts.get(i)));
					code.aastore();
				}
				code.putstatic(lf.ref());
			}
		}

	}

	/**
	 * The compilation-wide bignum-literal interner. A {@code BigInteger} is immutable, so
	 * every use of one literal is the same value: one {@code private static} field per
	 * DISTINCT value, built once in {@code <clinit>}, turns each use site from
	 * {@code new BigInteger(String)} (12 bytes and a full decimal parse plus an
	 * allocation, every time round the loop) into a 3-byte {@code GETSTATIC}. Nothing new
	 * travels: the field lives in the generated class.
	 *
	 * <p>
	 * The pool is filled during body compilation and drained into {@code <clinit>} at
	 * class assembly, exactly like {@link LayoutPool}. A program with no bignum literal
	 * interns nothing and is emitted byte for byte as before.
	 */
	static final class BigIntPool {

		/**
		 * One interned bignum literal.
		 *
		 * @param name the field name constant
		 * @param ref the fieldref used by {@code GETSTATIC}/{@code PUTSTATIC}
		 * @param value the value the field holds
		 */
		record BigIntField(Utf8Entry name, FieldRefEntry ref, java.math.BigInteger value) {
		}

		private final Map<java.math.BigInteger, BigIntField> byValue = new java.util.LinkedHashMap<>();

		@Nullable Utf8Entry fieldDesc;

		@Nullable ClassEntry bigIntegerCls;

		@Nullable MethodRefEntry ctor;

		@Nullable Utf8Entry clinitName;

		@Nullable Utf8Entry clinitDesc;

		/**
		 * Whether no bignum literal has been interned, i.e. the program has none.
		 * @return true when nothing has to be emitted
		 */
		boolean isEmpty() {
			return this.byValue.isEmpty();
		}

		/**
		 * The interned bignum fields, in interning order.
		 * @return the fields to emit
		 */
		java.util.Collection<BigIntField> fields() {
			return this.byValue.values();
		}

		/**
		 * Interns the static field holding one bignum literal; idempotent per value.
		 * @param cp the constant pool
		 * @param className the internal name of the class being emitted
		 * @param value the literal to intern
		 * @return the fieldref of the constant
		 */
		FieldRefEntry intern(ConstantPool cp, String className, java.math.BigInteger value) {
			BigIntField existing = this.byValue.get(value);
			if (existing != null) {
				return existing.ref();
			}
			if (this.bigIntegerCls == null) {
				this.bigIntegerCls = cp.classEntry("java/math/BigInteger");
				this.ctor = cp.methodRef(this.bigIntegerCls, "<init>", "(Ljava/lang/String;)V");
				this.fieldDesc = cp.utf8Entry("Ljava/math/BigInteger;");
				this.clinitName = cp.utf8Entry("<clinit>");
				this.clinitDesc = cp.utf8Entry("()V");
			}
			Utf8Entry nameUtf = cp.utf8Entry("_bi$" + this.byValue.size());
			ClassEntry thisClass = cp.classEntry(className);
			FieldRefEntry ref = cp.fieldRef(thisClass, nameUtf, Objects.requireNonNull(this.fieldDesc));
			this.byValue.put(value, new BigIntField(nameUtf, ref, value));
			return ref;
		}

		/**
		 * Appends the bignum initializers to the shared {@code <clinit>} body.
		 * @param code the {@code <clinit>} body being assembled
		 * @param cp the constant pool (mints the decimal strings)
		 */
		void emitClinitInit(MethodCode code, ConstantPool cp) {
			for (BigIntField bf : this.byValue.values()) {
				code.new_(Objects.requireNonNull(this.bigIntegerCls));
				code.dup();
				code.ldc(cp.stringEntry(bf.value().toString()));
				code.invokespecial(Objects.requireNonNull(this.ctor));
				code.putstatic(bf.ref());
			}
		}

	}

	/**
	 * An active protected region during compilation -- an {@code unwind-protect}, a
	 * {@code handler-case}'s depth bookkeeping, or a special {@code let} whose cleanups
	 * are the {@code %dyn-restore}s of its dynamic bindings ({@code JvmLetCompiler}).
	 * {@code cleanupForms} are re-compiled inline at every {@code return} escape site (a
	 * cleanup runs once per exit path); {@code blockDepth} is the {@code %block} stack
	 * depth at entry, so {@code JvmReturnCompiler} can tell whether a {@code return}
	 * escapes this scope (its target block encloses the scope) or stays inside it;
	 * {@code holes} collects the {@code [start, end)} code ranges of those inlined
	 * cleanups, which the scope's exception-table entries must exclude (a throw from an
	 * inlined cleanup must not re-enter this scope's own handler and run the cleanup
	 * twice).
	 */
	static final class UnwindScope {

		final List<LispVal> cleanupForms;

		final int blockDepth;

		final List<Hole> holes = new ArrayList<>();

		UnwindScope(List<LispVal> cleanupForms, int blockDepth) {
			this.cleanupForms = cleanupForms;
			this.blockDepth = blockDepth;
		}

		/**
		 * Adds the scope's catch-any exception-table entries: {@code [start, end)} minus
		 * the recorded holes, which lie inside the protected region but must not be
		 * covered by this scope's own handler. The holes are recorded in code order, so
		 * one left-to-right sweep suffices.
		 * @param body the method body
		 * @param start the first instruction of the protected region
		 * @param end the first instruction past it
		 * @param handler the handler's entry
		 */
		void catchAny(MethodCode body, MethodCode.Label start, MethodCode.Label end, MethodCode.Label handler) {
			MethodCode.Label cur = start;
			for (Hole hole : this.holes) {
				if (hole.start().position() > cur.position()) {
					body.exceptionCatch(cur, hole.start(), handler, null);
				}
				if (hole.end().position() > cur.position()) {
					cur = hole.end();
				}
			}
			if (cur.position() < end.position()) {
				body.exceptionCatch(cur, end, handler, null);
			}
		}

	}

	/**
	 * A cleanup sequence inlined inside a protected region ({@link UnwindScope#holes}).
	 *
	 * @param start its first instruction
	 * @param end the first instruction past it
	 */
	record Hole(MethodCode.Label start, MethodCode.Label end) {
	}

	/**
	 * An active {@code handler-case} operand-stack spill during compilation: the values
	 * the catching form saved out of the operand stack, and the {@code %block} stack
	 * depth at the spill. Everything compiled inside the form -- the protected region and
	 * the clause bodies alike -- runs on an operand stack based at empty, so a
	 * {@code return} that escapes the form cannot simply discard its way back to the
	 * block's exit shape: those values are in the spill's locals, and
	 * {@link JvmReturnCompiler} reloads them.
	 */
	record SpillScope(Ctx.Spill spill, int blockDepth) {
	}

	static final class Ctx {

		/**
		 * The highest local slot a method can have at all: {@code max_locals} is a u2, so
		 * no encoding names a higher one. Reaching it is a loud compile error, never a
		 * wrapped write.
		 */
		private static final int MAX_LOCAL_SLOT = 65535;

		final ConstantPool cp;

		final FieldRefEntry systemOut;

		final MethodRefEntry printlnStr;

		final MethodRefEntry lispToString;

		final MethodRefEntry printStr;

		final MethodRefEntry printlnVoid;

		final MethodRefEntry lispToDisplayString;

		final ClassEntry longClass;

		final MethodRefEntry longValueOf;

		final MethodRefEntry longValue;

		final ClassEntry objectClass;

		final ClassEntry objectArrayClass;

		final ClassEntry integerClass;

		final MethodRefEntry integerValueOf;

		final MethodRefEntry integerValue;

		final ClassEntry doubleClass;

		final MethodRefEntry doubleValueOf;

		final ClassEntry numberClass;

		final MethodRefEntry numberDoubleValue;

		final ClassEntry stringClass;

		final MethodRefEntry stringCharAt;

		final MethodRefEntry appendMethod;

		final MethodRefEntry mathAbsLong;

		final MethodRefEntry mathAbsDouble;

		final MethodRefEntry mathFloor;

		final MethodRefEntry mathCeil;

		final MethodRefEntry mathRint;

		final MethodRefEntry objectEquals;

		final MethodRefEntry readLineHelper;

		final @Nullable MethodRefEntry fetchHelper;

		final @Nullable MethodRefEntry awaitHelper;

		final @Nullable MethodRefEntry asyncRunHelper;

		final @Nullable MethodRefEntry futurepHelper;

		final @Nullable MethodRefEntry streampHelper;

		final @Nullable MethodRefEntry makeStreamHelper;

		final @Nullable MethodRefEntry streamNewHelper;

		final @Nullable MethodRefEntry streamReadHelper;

		final @Nullable MethodRefEntry streamWriteHelper;

		final @Nullable MethodRefEntry streamCloseHelper;

		final @Nullable MethodRefEntry drainBodyHelper;

		final @Nullable MethodRefEntry waitForHelper;

		/**
		 * {@code _flushStreams}, for a program that opens a file stream (null otherwise):
		 * what every way out of the program -- {@code main}'s return, {@code %host-exit},
		 * the uncaught-condition handler -- calls first, so an output file the program
		 * never closed keeps what it still buffers ({@link JvmFlushStreamsBuilder}).
		 */
		final @Nullable MethodRefEntry flushStreams;

		final @Nullable MethodRefEntry tcpConnectHelper;

		final @Nullable MethodRefEntry tcpListenHelper;

		final @Nullable MethodRefEntry tcpAcceptHelper;

		final @Nullable MethodRefEntry tcpLocalPortHelper;

		final @Nullable MethodRefEntry tcpLocalAddressHelper;

		final @Nullable MethodRefEntry tcpPeerAddressHelper;

		final @Nullable MethodRefEntry tcpPeerPortHelper;

		final @Nullable MethodRefEntry tcpSetTimeoutHelper;

		final @Nullable MethodRefEntry tlsConnectHelper;

		final @Nullable MethodRefEntry tlsUpgradeHelper;

		final @Nullable MethodRefEntry tlsListenHelper;

		final @Nullable MethodRefEntry tlsListenP12Helper;

		/**
		 * The {@code rontolisp:http-handler} runtime references (handler-funcref field,
		 * {@code serve} entry point, program-class constructor); null unless the program
		 * uses {@code rontolisp:http-handler}.
		 */
		final JvmHttpHandlerRuntimeBuilder.@Nullable HttpHandlerRuntime httpHandlerRuntime;

		/**
		 * The {@code java:} interop bridge references ({@code init}/{@code new}/
		 * {@code call}/{@code static}/{@code field}/{@code proxy}); null unless the
		 * program uses a {@code java:} function.
		 */
		final @Nullable Map<String, MethodRefEntry> javaOps;

		/** How each {@code java:} site resolves; null when the program has none. */
		final @Nullable JvmJavaSites javaSites;

		/**
		 * The {@code objc:} bridge references ({@code init} plus one per verb); null
		 * unless the program uses an {@code objc:} verb.
		 */
		final @Nullable Map<String, MethodRefEntry> objcOps;

		/**
		 * The {@code ffi:} bridge references ({@code init} plus one per verb); null
		 * unless the program uses an {@code ffi:} verb.
		 */
		final @Nullable Map<String, MethodRefEntry> ffiOps;

		/**
		 * The accelerated {@code vec:} bridge references ({@code init} plus one per
		 * vectorizable kernel member name -- {@code add}/{@code sub}/{@code mul}/
		 * {@code scale}/{@code dot}/{@code sum}); null unless {@code --simd} emitted the
		 * acceleration runtime for a program that uses a vectorizable {@code vec:}
		 * kernel.
		 */
		final @Nullable Map<String, MethodRefEntry> simdOps;

		/**
		 * The CBLAS bridge references ({@code init} and the one product kernel); null
		 * unless {@code --blas} emitted the bridge for a program that reaches
		 * {@code linalg:dot}.
		 */
		final @Nullable Map<String, MethodRefEntry> blasOps;

		/**
		 * The device bridge references ({@code init} and the one product kernel); null
		 * unless {@code --gpu} emitted the bridge for a program that reaches
		 * {@code linalg:dot}.
		 */
		final @Nullable Map<String, MethodRefEntry> gpuOps;

		/**
		 * The {@code geom:} kernel bridge's references, or null when the program calls
		 * none of the four accelerated members ({@link JvmGeomKernelCompiler}).
		 */
		final @Nullable Map<String, MethodRefEntry> geomOps;

		Map<String, MethodRefEntry> numOps = Map.of();

		Map<String, MethodRefEntry> mathOps = Map.of();

		Map<String, MethodRefEntry> systemOps = Map.of();

		/**
		 * The operator of the innermost form being compiled (set by
		 * {@code JvmExprCompiler.compileCons}); a numeric helper called under a named one
		 * goes through that operator's wrapper ({@link JvmOperandTypeRuntime}).
		 */
		@Nullable String operator;

		/** The compilation's operator wrappers, or null outside a full compilation. */
		JvmOperandTypeRuntime.@Nullable Wrappers operandTypeWrappers;

		/**
		 * The class's {@code subseq} bounds runtime, or null outside a full compilation:
		 * the lanes convert their bounds and refuse a range through it
		 * ({@link JvmSubseqCompiler#emitBoundsError}).
		 */
		JvmOperandTypeRuntime.@Nullable SubseqRuntime subseqRuntime;

		/**
		 * This method body's operand stack, tracked as it is emitted: it says what is
		 * live on the stack right now (which {@code handler-case} must spill, and
		 * {@code return} must discard, before a control-flow edge that arrives with an
		 * empty one) and how deep the stack ever got.
		 */
		final OperandStack stack;

		/**
		 * This method's body ({@link am.ik.jvm.MethodCode}, the words of
		 * {@code java.lang.classfile}'s {@code CodeBuilder}): its code, exception table
		 * and long branches; every instruction feeds {@link #stack}
		 * (.kb/jvm-method-size-limits.md, "Emission on java.lang.classfile").
		 */
		final am.ik.jvm.MethodCode body;

		/**
		 * The label bound at the body's first instruction: where a handler covering the
		 * whole body starts ({@link JvmUncaughtHandler}).
		 */
		final MethodCode.Label bodyStart;

		Map<String, Integer> locals = new HashMap<>();

		Map<String, FunctionInfo> functions;

		Map<String, Integer> captures = Map.of();

		Set<String> boxedVars = Set.of();

		int closureEnvSlot = -1;

		List<LambdaInfo> lambdaDecls;

		Set<Integer> indirectCallArities;

		/**
		 * The funcIds this program can reach as a first-class FUNCTION VALUE, recorded as
		 * Pass 2 emits them: every {@code (function name)} closure
		 * ({@link JvmFunctionFormCompiler}) and every {@code (lambda ...)} value
		 * ({@link JvmLambdaCompiler}). One mutable set shared by every {@code Ctx}, like
		 * {@link #indirectCallArities}; read once the bodies are done to size the
		 * {@code _invoke_N} dispatchers and the {@code _lookup} registry -- a funcId
		 * absent from it is only ever called DIRECTLY, and naming it in a dispatcher
		 * would keep it alive for the writer's shake
		 * ({@link am.ik.jvm.JvmClassSplitter}).
		 */
		Set<Integer> valueFuncIds;

		/**
		 * The bodies that make a bounce of the trampoline ({@link JvmTailBounce}), by
		 * identity: every method that emits one, and a tail group's layout that holds
		 * such a member's code. One set shared by every {@code Ctx}, like
		 * {@link #indirectCallArities}; read once the class is assembled, so the
		 * trampoline is written only when a method the shake keeps can bounce.
		 */
		Set<MethodCode> bouncingBodies;

		/**
		 * Whether a tail {@code apply} through a value was emitted anywhere
		 * ({@link JvmTailBounce#emitSpreadValueTail}): the class then carries
		 * {@code _vtcv}, and {@code _tramp} re-enters the raw apply for its bounce, the
		 * argument list unspread. One cell shared by every {@code Ctx}.
		 */
		boolean[] spreadBounces;

		/**
		 * The argument counts a tail call through a value was emitted with, one
		 * {@code _vtc<n>} each ({@link JvmTailBounce#emitValueTail}). One set shared by
		 * every {@code Ctx}, like {@link #indirectCallArities}.
		 */
		Set<Integer> valueTailArities;

		/**
		 * Every literal spelling Pass 2 emitted as a runtime VALUE the program can hold
		 * -- a quoted/self-evaluating symbol's name, a string literal's framed form, a
		 * keyword -- recorded where the value is loaded
		 * ({@link JvmEmitHelper#compileStringLiteral}). One mutable set shared by every
		 * {@code Ctx}, like {@link #valueFuncIds}, and read by {@code registryFuncIds}: a
		 * runtime symbol designator can only ever BE one of these (or a builder's product
		 * from one), so the name-registry probes read this set rather than the whole
		 * constant pool -- a string the compiler put in the pool for its own machinery (a
		 * layout table, a runtime error message) is not a name the program spells, and
		 * must not arm a dispatch case.
		 */
		Set<String> spelledLiterals;

		/**
		 * The callee SHAPES (see {@code JvmRuntimeBuilder.arityShape}) a literal
		 * {@code (apply #'f ... list)} guarded at its call site. Such a call compiles to
		 * a PHYSICAL direct call that walks the list itself, so it reaches no dispatcher
		 * and no no-match arm can report a wrong count for it -- the guard is a
		 * {@code _arityChk} call emitted beside the walk. One mutable set shared by every
		 * {@code Ctx}, like {@link #valueFuncIds}: non-empty is what tells the emitter
		 * that {@code _arityChk} is reachable and has to be built.
		 */
		Set<Integer> arityGuardShapes;

		/**
		 * The built-in operators the program's wrong-count reports name, whose indices a
		 * guarded call site bakes into its callee shape. One registry shared by every
		 * {@code Ctx}, like {@link #arityGuardShapes}, and read by the emitter to build
		 * {@code _arityMsg}.
		 */
		JvmArityOperators arityOperators;

		int[] nextFuncId;

		/**
		 * The builder every context of this compilation was built from, so a body that
		 * crosses the method-size budget can mint a continuation context with the same
		 * shared runtime ({@link JvmBodyOutliner}).
		 */
		final Builder ctxBuilder;

		/**
		 * The outlined continuation bodies of this compilation, in the order they were
		 * split off; one shared list, like {@link #lambdaDecls}.
		 */
		final List<JvmBodyOutliner.OutlinedBody> outlinedBodies;

		/** The next {@code _k$N} name, shared like {@link #nextFuncId}. */
		final int[] nextOutlinedBodyId;

		/**
		 * The shared per-class emission helpers, by name -- one method holding a sequence
		 * that is the same wherever it is emitted, so a program that writes it a hundred
		 * times pays for it once ({@link JvmEmitHelper#emitSharedCall}). One map for the
		 * whole compilation, like {@link #outlinedBodies}, whose list the built bodies
		 * join.
		 */
		final Map<String, MethodRefEntry> sharedHelpers;

		/**
		 * The tail spine this form belongs to, or null. Set by {@link JvmBodyOutliner}
		 * immediately before a value-position form is compiled and cleared by
		 * {@link JvmExprCompiler#compileExpr} on the way in, so only a construct that IS
		 * the method's tail ever sees it.
		 */
		JvmBodyOutliner.@Nullable Tail tailBody;

		/**
		 * The form whose value is this method's result, when a call there may compile to
		 * something other than a call: a bounce of the trampoline ({@link JvmTailBounce})
		 * or a jump -- a self tail call's ({@link JvmSelfTailCall}) or one within a tail
		 * group ({@link JvmTailGroup}). Laid by {@link JvmBodyOutliner} on the final
		 * spine item and re-laid, on the arm or body form whose value flows on unchanged,
		 * by the emitters of the forms that open no dynamic extent: {@code if},
		 * {@code progn}, a plain {@code let}, the blocks, a tail
		 * {@code return}/{@code return-from}'s value -- or the value of an exit on the
		 * {@link #exitMark} chain to a block in tail position -- an inline lambda's body,
		 * and the pass-through lowerings ({@link JvmExprCompiler#compileExpansion}).
		 * Matched by identity in the call emitters, so a form compiled anywhere but the
		 * true tail sees no mark and emits exactly as before.
		 */
		@Nullable LispVal tailMark;

		/**
		 * The form being compiled where nothing between it and the enclosing blocks opens
		 * a dynamic extent, so an exit from it to a block in tail position
		 * ({@link BlockTarget#tail}) hands its value form on as the method's result: the
		 * value of a {@code return} out of a loop body is then marked like the block's
		 * own last form ({@link JvmReturnCompiler}). Laid with {@link #tailMark}, by the
		 * same emitters, on EVERY sub-form they compile in place -- a {@code progn}'s, a
		 * plain {@code let}'s or a block's statements, the arms of an {@code if}, the
		 * body of a {@code while}, the statements of a {@code tagbody}, an inline
		 * lambda's body, a pass-through lowering's expansion -- and matched by identity,
		 * so a form any other emitter compiles sees no mark.
		 */
		@Nullable LispVal exitMark;

		/**
		 * Whether this method may bounce its true tail at all ({@link JvmTailBounce}): a
		 * lambda's body, a defun's whose pre-pass found a tail through a value
		 * ({@link FunctionInfo#bounceVisible}), or a continuation of either.
		 */
		boolean tailBounce;

		/**
		 * Whether every caller of this method's result checks it for a bounce, so a call
		 * on the tail mark may hand its callee's bounce on as this method's answer
		 * instead of driving it here ({@link JvmTailBounce#passesThrough}): a lambda's
		 * (only the dispatchers, {@code _apply} and the runtime's entries call it, and
		 * each checks), a {@link FunctionInfo#bounceVisible} defun's, and a
		 * continuation's (the method it was split from checks it).
		 */
		boolean passesBounces;

		/**
		 * The slot of the value-tail depth, or -1: the last parameter of every method
		 * that may bounce -- a lambda, a {@link FunctionInfo#bounceVisible} defun, a
		 * continuation split from either -- which counts the value tails between this
		 * method and the nearest ordinary call ({@link JvmTailBounce}). A value tail
		 * passes it on plus one, a call that hands its callee's bounce on passes it as it
		 * is.
		 */
		int depthSlot = -1;

		/**
		 * Whether the body read {@link #depthSlot} ({@link JvmTailBounce#depthSlot}): a
		 * lambda that never does is written without the parameter.
		 */
		boolean depthRead;

		int nextLocal = 1;

		int maxLocals = 1;

		/**
		 * The one local {@code %error}'s message rides in, allocated on first use. The
		 * value is written and read five instructions later with no control flow in
		 * between, so every {@code error} site in the method shares it rather than
		 * burning a slot each -- until {@link #allocTemp} hands the slot to a variable
		 * (its reserving scope ended), which drops the cache: a handler-case in the same
		 * method resumes after the throw, and a live variable in the slot would read the
		 * message. Past slot 255 every load and store of it would cost the three extra
		 * bytes of a {@code wide} prefix, and {@code max_locals} sizes every frame the
		 * method carries.
		 */
		private int errorMessageSlot = -1;

		boolean dynamic = false;

		/**
		 * Servlet mode ({@code -o app.war}): the http-handler directive and the
		 * {@code %http-server-*} seam register the handler and return instead of binding
		 * a port the container owns.
		 */
		boolean servletMode = false;

		/**
		 * True when the program can put a non-local exit on the {@code _nleTl} channel --
		 * it lowers a cross-lambda {@code return-from} (a {@code %nlx-*} form is emitted)
		 * or uses {@code catch}/{@code throw}. Gates the {@code handler-case} handler's
		 * non-local-exit awareness so a program with neither stays byte-identical.
		 */
		boolean blockExitChannel = false;

		/**
		 * True when the program uses the restart system
		 * ({@code LispMacroExpander.usesRestartSystem}): the error/warn/signal/cerror
		 * expansions gain the {@code %run-handlers} signal hook and the real
		 * {@code cerror}, matching the restart-runtime defuns
		 * {@code expandTopLevelDefinitions} injected. Off, every signal expansion is
		 * byte-identical to the pre-restart build.
		 */
		boolean restartMode = false;

		/**
		 * True when the program both signals and establishes a {@code handler-case}
		 * ({@code LispMacroExpander.needsSignalClauseMatch}): {@code handler-case} pushes
		 * its clause types on the dynamic {@code %handler-clusters%} stack and
		 * {@code %signal-cond} throws only when an armed clause MATCHES the condition
		 * (through the injected {@code %hc-match-p} defun), so a handler-case whose
		 * clauses do not match is declined and the signal falls through to nil (CLHS
		 * 9.1.4.1). Off, {@code %signal-cond} keeps the historical depth-counter emission
		 * and stays byte-identical.
		 */
		boolean signalClauseMatch = false;

		/**
		 * True when the program MENTIONS a printer-control variable
		 * ({@code LispMacroExpander.usesPrintControls}: {@code *print-case*},
		 * {@code *print-length*}, {@code *print-level*}, {@code *print-gensym*},
		 * {@code *print-base*}, {@code *print-radix*} -- or a {@code write-to-string}
		 * keyword binding one): every printing operator is rewritten onto the
		 * {@code %print-cased} renderer, which applies the variables to each value. Off,
		 * the printing operators compile exactly as they always did.
		 */
		boolean printControls = false;

		/**
		 * True when the program itself names a printer-control variable
		 * ({@code LispMacroExpander.mentionsPrintControlVariable}) -- as opposed to being
		 * routed through {@code %print-cased} for its {@code *package*} alone -- which is
		 * what decides whether the walk's case-fold and re-basing leaves are compiled in
		 * ({@code LispMacroExpander.expandPrintCasedLeaf}).
		 */
		boolean printControlVariables = false;

		/**
		 * True when the program can produce a packed float array (a {@code #d(...)}
		 * literal or {@code make-array :element-type 'double-float}). When set, the array
		 * op compilers route through the {@code _fv*} dispatch helpers (which handle both
		 * the packed {@code double[]} and the general {@code ArrayList} representation)
		 * instead of calling the general {@code _array*} helper directly; the default
		 * build (no packed arrays) is byte-identical. Shared across every context.
		 */
		boolean usesFloatArray = false;

		/**
		 * True when the program can build a block-quantized weight matrix
		 * ({@code rontolisp:quantize} / {@code rontolisp:make-quantized-matrix}), so the
		 * {@code _qm*} helpers exist ({@link JvmQuantizedMatrixRuntimeBuilder}). Shared
		 * across every context.
		 */
		boolean usesQuantized = false;

		/**
		 * True when a {@code dotimes} in the typed subset compiles to a guarded primitive
		 * loop ({@link JvmTypedLoopCompiler}); off under {@code --optimize=size}, which
		 * declines the speed-for-size trades. Shared across every context.
		 */
		boolean typedLoops = true;

		/**
		 * True when a nested integer arithmetic/bitwise tree compiles to an outlined
		 * fused method ({@link JvmIntFusionCompiler}); off under {@code --optimize=size}
		 * (the same speed-for-size gate as {@link #typedLoops}). Shared across every
		 * context.
		 */
		boolean intFusion = true;

		/**
		 * The fusion-inlinable defuns: uniquely defined, fixed-arity, single closed
		 * integer-tree body ({@link JvmIntFusionCompiler#isInlinableDefun}); empty under
		 * {@code --dynamic}. Shared across every context.
		 */
		Map<String, DefunDecl> inlinableDefuns = Map.of();

		/**
		 * The per-compile fused-site registry (outlined {@code _fx$N} methods, the helper
		 * flags), shared across every context; null only in a context built outside a
		 * whole-program compile.
		 */
		JvmIntFusionCompiler.@Nullable State fusedState;

		/**
		 * The unboxed dual-representation locals in scope
		 * ({@link JvmIntFusionCompiler.RawLocal}: a raw {@code long} slot plus a boxed
		 * shadow), keyed by name. Scoped like {@link #locals} -- {@link JvmLetCompiler}
		 * registers, shadows and restores; a name here is never in {@link #locals}.
		 */
		Map<String, JvmIntFusionCompiler.RawLocal> rawLocals = new HashMap<>();

		/**
		 * The lexical variable names declared to be floats in scope
		 * ({@code (declare (type double-float ...))}, read by
		 * {@link am.ik.rontolisp.compiler.DeclaredScalarTypes}): the routing predicate
		 * ({@link JvmLispCompiler#containsDouble(LispVal, Ctx)}) counts a reference to
		 * one as a double literal, so arithmetic over declared floats takes the unboxed
		 * IEEE path with no literal in sight ({@code .kb/jvm-double-arithmetic.md}).
		 * Registered from body-head declarations by the defun/lambda setup and by
		 * {@link JvmLetCompiler} (bound AND free declarations); shadowed names removed,
		 * restored on scope exit. Specials are never registered.
		 */
		Set<String> declaredDoubles = Set.of();

		/**
		 * The declared-float locals kept in a raw {@code double} slot pair, keyed by name
		 * to the base slot ({@code .kb/jvm-double-arithmetic.md}): a plain lexical
		 * {@code let}/{@code let*} binding covered by a bound float declaration -- never
		 * special, never captured -- whose reads push the raw slot and whose assignments
		 * store into it ({@code checkcast Double} on an operand the emitter cannot prove,
		 * so a FALSE declaration is a deterministic cast error at the site, never a
		 * silently coerced value). Scoped like {@link #locals}; a name here is never in
		 * {@link #locals} or {@link #rawLocals}.
		 */
		Map<String, Integer> rawDoubleLocals = new HashMap<>();

		/**
		 * The let-bound local functions eligible for fused-call substitution
		 * ({@code flet}'s {@code __FLETn_f} lambdas), scoped like {@link #locals}.
		 */
		Map<String, JvmIntFusionCompiler.LocalIntLambda> localIntLambdas = new HashMap<>();

		/**
		 * True when the program can produce a packed integer vector (a {@code #N@(...)}
		 * literal or {@code make-array :element-type '(unsigned-byte 8|16|32)}). When
		 * set, the rank-1 array op compilers route through the {@code _iv*} dispatch
		 * helpers (which handle the packed {@code long[]} and delegate any other shape
		 * down the fv/general chain); the default build is byte-identical. Shared across
		 * every context.
		 */
		boolean usesIntArray = false;

		/**
		 * True when the program can build a general array that REMEMBERS its declared
		 * element type -- any {@code make-array} whose {@code :element-type} upgrades to
		 * something other than {@code t}. Only {@code array-element-type} reads the
		 * remembered slot, so this gate is what keeps its lowering byte-identical for a
		 * program that never asks for a specialized element type. Shared across every
		 * context.
		 */
		boolean usesTypedArray = false;

		/**
		 * True when the {@code _readSeqPacked} / {@code _writeSeqPacked} helpers are
		 * emitted ({@code .kb/binary-sequence-io.md}); when they are not, the
		 * {@code %read-sequence-packed} / {@code %write-sequence-packed} primitives
		 * compile to a declining nil. Shared across every context.
		 */
		boolean usesPackedSequenceIo = false;

		/**
		 * True when the {@code _readSeqChars} helper is emitted
		 * ({@code .kb/character-sequence-io.md}); when it is not, the
		 * {@code %read-sequence-chars} primitive compiles to a declining nil. Shared
		 * across every context.
		 */
		boolean usesCharSequenceIo = false;

		/**
		 * True when the array runtime helper group ({@link JvmArrayRuntimeBuilder}) is
		 * emitted for this program. Gates the mutable-character-vector consumers (the
		 * {@code stringp} extension and the per-site {@code _strv} normalization), so an
		 * array-free program compiles byte-identically. Shared across every context.
		 */
		boolean usesArrays = false;

		/**
		 * True when the hash-table runtime helper group ({@link JvmHashRuntimeBuilder})
		 * is emitted for this program. Gates the {@code hash-table-p} clause of the
		 * {@code %class-designator} lowering: without the runtime no hash table can
		 * exist, so the clause would only be a call to a {@code _hashP} that was never
		 * generated. Shared across every context.
		 */
		boolean usesHashTables = false;

		/**
		 * True when the {@code equalp} key-fold helpers are emitted for this program,
		 * i.e. when its source writes {@code (make-hash-table :test 'equalp)} somewhere.
		 * Gates the fold at the {@code make-hash-table} site and the real
		 * {@code hash-table-test} answer: with no folding table in the program both are
		 * calls to helpers that were never generated, and the constant answer is the true
		 * one.
		 */
		boolean usesEqualpHashTables = false;

		/**
		 * True when the identity-table helpers are emitted for this program, i.e. when
		 * its source writes {@code (make-hash-table :test 'eq)} or
		 * {@code (make-hash-table :test 'eql)} somewhere. Gates the makers, the test
		 * reader and the test-dispatched comparison/placement: with no identity table in
		 * the program those are calls to helpers that were never generated, and the
		 * {@code equal} behavior is the true one.
		 */
		boolean usesIdentityHashTables = false;

		/**
		 * True when the {@code %seq-string} helper is injected for this program, i.e. the
		 * program itself writes a {@code (concatenate 'string ...)} with an argument that
		 * is not a literal string. Only then does the string-family lowering normalize
		 * its arguments through it; the {@code concatenate 'string} forms this compiler's
		 * own macro expansions produce during codegen already hold strings, so they keep
		 * the bare {@code %string-concat} chain and every other program stays
		 * byte-identical. Shared across every context.
		 */
		boolean usesSeqString = false;

		/**
		 * True when the program contains a flipped string PRODUCER
		 * ({@code MutableStringProducers.programUsesAny}): only then do the
		 * {@code concatenate 'string} / case-family / {@code format nil} /
		 * string-stream-capture / {@code read-line} sites wrap their fresh result through
		 * {@code _toMutStr}, giving it a writable identity. A subset of
		 * {@link #usesArrays} (the scan joins the array gate), so the wrap always has its
		 * helper. The WASM backend wraps under the SAME scan, which is what keeps the
		 * backends agreeing on which results carry identity. Shared across every context.
		 */
		boolean mutableStringProducers = false;

		/**
		 * True when an instance value can exist in this class (see
		 * {@code LispMacroExpander.mayCreateInstances}). Gates the instance exclusion in
		 * the cons-shaped predicates, so a program that cannot build one compiles
		 * byte-identically. Shared across every context.
		 */
		boolean mayUseInstances = false;

		/**
		 * True when the program may observe a complex value (see
		 * {@code LispMacroExpander.mayCreateComplex}). Gates the holder-aware arms of the
		 * predicates and accessors, so a program that cannot build one compiles
		 * byte-identically -- and, crucially, never names the travelling holder class it
		 * does not carry. Shared across every context.
		 */
		boolean usesComplex = false;

		/**
		 * True when the program establishes a handler landing pad
		 * ({@code LispMacroExpander.establishesLandingPad}): a {@code %program-error}
		 * signal then carries a fresh {@code program-error} instance, so a
		 * {@code program-error} clause matches it. Without a pad nothing can observe the
		 * class and the signal takes the plain {@code %error} channel, byte-identically.
		 */
		boolean hasLandingPad = false;

		/**
		 * True when the program can build a SYNONYM STREAM ({@code make-synonym-stream}
		 * is the only way to, and it has no read syntax), so every stream-designator
		 * resolution has to run through {@code %STREAM-TARGET}. A program that never
		 * spells it keeps its exact bytes.
		 */
		boolean usesSynonymStreams = false;

		/**
		 * True when the program names {@code input-stream-p} or {@code output-stream-p}:
		 * only then do they answer the REAL direction
		 * ({@code LispMacroExpander.expandStreamDirectionP}).
		 */
		boolean asksStreamDirection = false;

		/**
		 * True when an OPEN stream VALUE ({@code LispLayout.STREAM}) can exist in this
		 * class -- the program spells a stream constructor, or names
		 * {@code *error-output*} whose seeded default is one
		 * ({@code LispMacroExpander.mayCreateStreamValues}). It gates BOTH halves of the
		 * representation: the {@code %obj-new} wrap a producer emits and the
		 * {@code %STREAM-TARGET} unwrap a consumer emits, so the two can never disagree
		 * and a program the scan says no about keeps raw handles end to end.
		 */
		boolean usesStreamValues = false;

		/**
		 * True when an async runtime value -- a stream or a stream-read token, both
		 * {@code Object[3]} headed by an interned marker -- can exist in this class.
		 * Gates the async-value exclusion in the cons-shaped predicates, so a program
		 * without the async runtime compiles byte-identically. Shared across every
		 * context.
		 */
		boolean mayUseAsyncValues = false;

		/**
		 * True for the single context that compiles top-level forms (the {@code main}
		 * body), false for defun/lambda bodies. When the embedded {@code eval} runtime is
		 * present, a top-level global variable binding is mirrored into the runtime's
		 * global environment so {@code eval} can resolve it (see {@link #evalStoreRef}).
		 */
		boolean topLevel = false;

		/**
		 * The {@code _store(place, value, env)} methodref, set only when the program uses
		 * {@code eval}. Used to mirror top-level global variable bindings into the eval
		 * runtime's global environment; null otherwise.
		 */
		@Nullable MethodRefEntry evalStoreRef;

		/**
		 * The one top-level form whose returned NAME the emitter is dropping, or
		 * {@code null}. Set immediately before a {@code defvar}/{@code defparameter}/
		 * {@code defconstant} in statement position is compiled;
		 * {@link JvmDefvarCompiler} clears it when it takes the offer and emits no name
		 * (see {@code compiler/ToplevelStatements},
		 * {@code .kb/toplevel-statement-values.md}). Keyed by the cons IDENTITY, and
		 * cleared on acceptance, so the emitter can tell whether the offer was taken --
		 * an offer the dispatch did not route to the defvar compiler leaves a value on
		 * the stack and still gets its pop -- and so a nested definer compiled while this
		 * one's init expression is being emitted cannot take it.
		 */
		@Nullable LispVal definerNameDropped;

		String className = "";

		Set<String> userDefunNames = Set.of();

		/**
		 * The defuns whose lambda list takes the counts of the built-in they are named
		 * after (compiler/BuiltinCallArity#builtinShapedDefuns): a direct call of one is
		 * judged, and reported, as the built-in rather than by the defun's own check.
		 */
		Set<String> builtinShapedDefuns = Set.of();

		/**
		 * The {@code cl} function names this compile ATTEMPT has already warned about, so
		 * an override that happens at fifty call sites reports once -- and a retried
		 * attempt (a mispredicted helper gate) warns again, because
		 * {@code CompileWarnings} threw the first attempt's messages away. See
		 * {@link am.ik.rontolisp.compiler.ClRedefinitionWarnings}.
		 */
		Set<String> warnedClRedefinitions = new HashSet<>();

		/**
		 * Whether the program calls {@code fmakunbound} anywhere. When it does, a LITERAL
		 * {@code (fboundp 'x)} may no longer be folded to a bare constant: the retired
		 * name must answer nil, so the fold is emitted behind a runtime tombstone probe
		 * of {@code _fenv} ({@link JvmSymbolApiCompiler#compileFboundp}).
		 */
		boolean usesFmakunbound = false;

		/**
		 * Whether the program can create, delete or rename packages at run time (a
		 * {@code make-package} / {@code delete-package} / {@code rename-package}
		 * reference outside quoted data). When it does, the package lowerings consult the
		 * {@code %runtime-packages%} table before their baked answers (see
		 * {@code .kb/packages.md}); read off the resolver after {@code resolveProgram},
		 * so the flag and the resolver's literal folds agree by construction. Every other
		 * program keeps the baked-only lowerings and stays byte-identical.
		 */
		boolean usesRuntimePackages = false;

		/**
		 * The package designators the program's {@code defpackage}s and the built-in
		 * registry make resolvable, mapped to the canonical package name -- the table a
		 * COMPUTED {@code (find-package x)} is answered from, since the compiled runtime
		 * has no registry ({@link LispMacroExpander#expandRuntimeFindPackage}).
		 */
		Map<String, String> packageTable = Map.of();

		/**
		 * Every registered package mapped to the packages it uses -- the table
		 * {@code list-all-packages} / {@code package-use-list} /
		 * {@code package-used-by-list} are answered from, for the same reason
		 * {@link #packageTable} exists ({@link LispMacroExpander#expandPackageQuery}).
		 */
		Map<String, java.util.List<String>> packageUseTable = Map.of();

		/**
		 * The registry's answer to which symbol a read/compile-time package reaches under
		 * a name -- what the {@code find-symbol} / {@code intern} lowerings fold a
		 * literal lookup against and route a computed one through {@code %baked-access}
		 * by.
		 */
		am.ik.rontolisp.macro.BakedSymbolAccess bakedSymbolAccess = am.ik.rontolisp.macro.BakedSymbolAccess.NONE;

		/**
		 * The table the printer drops an accessible symbol's package qualifier from
		 * ({@link LispMacroExpander#expandSymbolPrintBareP}), baked from the resolver's
		 * final registry when the program can print under a package other than
		 * {@code cl-user} ({@link LispMacroExpander#printsUnderAPackage}); null
		 * otherwise, which lowers the check to a constant.
		 */
		am.ik.rontolisp.@Nullable SymbolPrintTable symbolPrintTable;

		/**
		 * {@code defstruct} accessor names to their 1-based slot position, collected by
		 * the pre-pass in {@link JvmLispCompiler#compile}; {@code setf} expansion treats
		 * these as places. Shared across every context.
		 */
		Map<String, Integer> structAccessors = Map.of();

		/**
		 * The CLOS registry (classes, generics, slot positions), collected by the
		 * pre-pass in {@link JvmLispCompiler#compile}; {@code make-instance}/
		 * {@code slot-value} expansion resolves through it. Shared across every context.
		 * Assigned in the constructor only: a registry is pre-seeded with the condition
		 * hierarchy, and a context is built per compiled body, so a field initializer
		 * here seeded one per body only for the constructor to discard it.
		 */
		ClosRegistry closRegistry;

		/**
		 * The capture walk's answers so far ({@link FreeVarAnalyzer.CaptureMemo}): every
		 * scope asks, and an enclosing scope's walk has covered a nested one's body.
		 * Shared across every context of one compilation.
		 */
		final FreeVarAnalyzer.CaptureMemo captureMemo;

		/**
		 * Names of top-level global variables (defvar/defparameter/defconstant and
		 * top-level setq/setf places). Each has a dedicated static field in
		 * {@link #globalFields}; a reference compiles to a {@code getstatic} from any
		 * method body, so a defun/lambda can read a global. Shared across every context.
		 */
		Set<String> globals = Set.of();

		/**
		 * Names of the program's NON-top-level {@code defun}s (a subset of
		 * {@link #globals}): each lowers to {@code (setq name (lambda ...))}, so the
		 * function value lives in the global variable and nowhere else. A call site and a
		 * {@code #'name} must therefore dispatch through the variable BEFORE the
		 * late-binding fallback, which under {@code --dynamic} resolves the runtime
		 * FUNCTION namespace -- where a nested defun never appears (it answered nil).
		 * Shared across every context.
		 */
		Set<String> nestedDefunNames = Set.of();

		/**
		 * Names of special (dynamically bound) variables (a subset of {@link #globals}).
		 * A {@code let}/{@code let*} of one of these names saves its global static field,
		 * assigns the init value, and restores the field on normal exit -- a dynamic
		 * binding -- instead of allocating a fresh lexical slot. Shared across every
		 * context.
		 */
		Set<String> specialVars = Set.of();

		/**
		 * Maps a global variable name to its backing {@code private static Object} field.
		 */
		Map<String, FieldRefEntry> globalFields = Map.of();

		/**
		 * The {@code %mv-spill} channel's store ({@link JvmMvChannel}), or null when the
		 * program declares no spill global. Every read and write of the channel goes
		 * through it, never through {@link #globalFields} directly.
		 */
		@Nullable JvmMvChannel mvChannel;

		/**
		 * The promoted top-level globals that carry the unboxed dual representation
		 * ({@code .kb/jvm-int-fusion.md}): a raw {@code long} field and an {@code int}
		 * flag beside the ordinary {@code _g$} field, which stays the boxed shadow. A
		 * name here is a plain global everywhere the flag is clear, so a store that
		 * cannot be raw is byte-for-byte the store the unfused compiler emits.
		 * Eligibility is program-wide ({@link JvmRawGlobals}); shared across every
		 * context.
		 */
		Map<String, JvmIntFusionCompiler.RawLocal> rawGlobals = Map.of();

		/**
		 * The thread-scoped dynamic-binding runtime for the specials that are dynamically
		 * bound somewhere in the program (a {@code _d$} ThreadLocal per name next to the
		 * {@code _g$} global default, plus the {@code _dget}/{@code _dbind}/{@code _dset}
		 * helpers), or {@code null} when no special is ever {@code let}-bound or no
		 * binding needs one ({@link #threadScopedSpecials}). Shared across every context.
		 */
		JvmDynVarRuntimeBuilder.@Nullable DynVarRuntime dynVars;

		/**
		 * Whether a special's dynamic binding is thread-scoped -- Lisp code of the
		 * program can run on another thread, so a binding lives in the special's
		 * {@link #dynVars} ThreadLocal -- rather than a shallow binding that saves, sets
		 * and restores its {@code _g$} field. Shared across every context.
		 */
		boolean threadScopedSpecials;

		/**
		 * The UNBOUND marker and the globals whose {@code _g$} starts as it -- their
		 * variable carries their bound-ness, so a read answers nil for the marker and
		 * {@code boundp} reads the field -- or {@code null} without such a global. Shared
		 * across every context.
		 */
		JvmDynVarRuntimeBuilder.@Nullable UnboundMarker unboundMarker;

		/**
		 * Top-level globals already initialized by a {@code defvar}/{@code defparameter}
		 * in this compilation, used to implement {@code defvar}'s "bind only if not
		 * already bound" idempotence at compile time. Per-context (only the top-level
		 * context mutates it).
		 */
		Set<String> definedGlobals = new HashSet<>();

		/**
		 * Stack of active {@code %block} return boundaries. The innermost block is on
		 * top; a {@code return} stores its value into the block's slot and jumps to its
		 * exit.
		 */
		final Deque<BlockTarget> blockTargets = new ArrayDeque<>();

		/**
		 * Stack of active {@code unwind-protect} protected regions. The innermost scope
		 * is on top; a {@code return} that escapes a scope compiles its cleanup forms
		 * inline before jumping (see {@link JvmReturnCompiler}).
		 */
		final Deque<UnwindScope> unwindScopes = new ArrayDeque<>();

		/**
		 * Stack of active {@code tagbody} label scopes, innermost on top. A {@code go}
		 * resolves its tag against these lexically -- the compilers do not support the
		 * interpreter's dynamic {@code go} across function boundaries.
		 */
		final Deque<TagbodyScope> tagbodyScopes = new ArrayDeque<>();

		/**
		 * Stack of active {@code handler-case} operand-stack spills, innermost on top.
		 * Only a catching form compiled with operands live pushes one.
		 */
		final Deque<SpillScope> spillScopes = new ArrayDeque<>();

		/**
		 * The compilation's source-site table, shared by every context like
		 * {@link #lambdaDecls}; {@code null} when the compile records no source positions
		 * (an embedder calling {@link JvmLispCompiler#compile} on forms it built), which
		 * leaves every method without line numbers.
		 */
		final @Nullable JvmSourceSites sites;

		/**
		 * The name of the function this method compiles, or {@code null} for the top
		 * level and an anonymous function: what an async body's report head names
		 * ({@link JvmUncaughtHandler#appendAsyncCrossing}).
		 */
		@Nullable String functionName;

		/**
		 * The name the report calls the program function this method's code is written in
		 * -- its own for a function, the one around it for a lambda -- or {@code null}
		 * for none (the top level, an async body): the owner of its sites, and what a
		 * lambda it builds is written in ({@link LambdaInfo#writtenIn}).
		 */
		@Nullable String writtenIn;

		/**
		 * The report names of lambda forms a non-top-level defun lowered to, by identity;
		 * shared per compilation ({@link LambdaInfo#reportName}).
		 */
		final Map<LispCons, String> lambdaReportNames;

		/**
		 * The report heads of {@code %async-run} thunk forms, by identity; shared per
		 * compilation ({@link LambdaInfo#asyncHead}).
		 */
		final Map<LispCons, String> asyncBodyHeads;

		/**
		 * The {@code labels} variable each lambda form is assigned to, by identity;
		 * shared per compilation ({@link LambdaInfo#selfVar}).
		 */
		final Map<LispCons, String> lambdaSelfVars;

		/**
		 * The tail-group member each {@code labels} lambda form is, by identity; shared
		 * per compilation ({@link LambdaInfo#tailMember}).
		 */
		final Map<LispCons, JvmTailGroup.Member> tailGroupMembers;

		/**
		 * The function this method is, as the target of its own tail calls: a defun's
		 * body or a {@code labels} function's, never a continuation or any other method
		 * ({@link JvmSelfTailCall}).
		 */
		JvmSelfTailCall.@Nullable Loop selfLoop;

		/**
		 * The tail group this method is a member of, whose siblings its tail calls jump
		 * to ({@link JvmTailGroup}); null for a continuation and every other method.
		 */
		JvmTailGroup.@Nullable Member tailMember;

		/**
		 * The method its tail group laid out for this member -- the group rooted at it
		 * ({@link JvmTailGroup#finish}) -- which the class takes in place of
		 * {@link #body}; null for every other method.
		 */
		JvmTailGroup.@Nullable LaidOut laidOut;

		/** The owner code ({@link JvmSourceSites#owner}) of {@link #writtenIn}. */
		int siteOwner;

		/**
		 * The site of the innermost located form being emitted, else 0.
		 */
		int siteCurrent;

		/**
		 * The {@code {position, site}} marks emission left, in position order: where the
		 * innermost located form changes ({@link am.ik.jvm.MethodCode#position()}).
		 * {@link #lines} turns them into the method's {@code LineNumberTable}.
		 */
		private final List<int[]> siteMarks = new ArrayList<>();

		/**
		 * The compilation-wide condition-channel state (the {@code _condTl} ThreadLocal
		 * field constants); one instance shared across every context of a compilation
		 * through the single builder, like {@link #nextFuncId}.
		 */
		final ConditionChannel conditionChannel;

		/**
		 * The compilation-wide instance-layout interner (one static {@code String[]}
		 * field per instance tag actually referenced); one instance shared across every
		 * context of a compilation through the single builder, like
		 * {@link #conditionChannel}.
		 */
		final LayoutPool layoutPool;

		/**
		 * The compilation-wide bignum-literal interner (one static
		 * {@code java.math.BigInteger} field per distinct literal); one instance shared
		 * across every context of a compilation through the single builder, like
		 * {@link #layoutPool}.
		 */
		final BigIntPool bigIntPool;

		/**
		 * The compilation-wide quoted-datum table (one slot per distinct quoted
		 * aggregate, .kb/quoted-data.md); one instance shared across every context of a
		 * compilation through the single builder, like {@link #bigIntPool}.
		 */
		final JvmQuotePool quotePool;

		/**
		 * The compilation-wide UNSUPPLIED marker (the static field a caller passes for a
		 * physical optional it has no argument for, {@link JvmPhysicalArgs}); one
		 * instance shared across every context of a compilation through the single
		 * builder, like {@link #quotePool}.
		 */
		final JvmUnsupplied unsupplied;

		private Ctx(Builder builder) {
			this.conditionChannel = builder.conditionChannel;
			this.layoutPool = builder.layoutPool;
			this.bigIntPool = builder.bigIntPool;
			this.quotePool = builder.quotePool;
			this.unsupplied = builder.unsupplied;
			this.dynamic = builder.dynamic;
			this.servletMode = builder.servletMode;
			this.blockExitChannel = builder.blockExitChannel;
			this.restartMode = builder.restartMode;
			this.signalClauseMatch = builder.signalClauseMatch;
			this.printControls = builder.printControls;
			this.printControlVariables = builder.printControlVariables;
			this.usesFloatArray = builder.usesFloatArray;
			this.usesQuantized = builder.usesQuantized;
			this.typedLoops = builder.typedLoops;
			this.intFusion = builder.intFusion;
			this.inlinableDefuns = builder.inlinableDefuns;
			this.fusedState = builder.fusedState;
			this.usesIntArray = builder.usesIntArray;
			this.usesTypedArray = builder.usesTypedArray;
			this.usesPackedSequenceIo = builder.usesPackedSequenceIo;
			this.usesCharSequenceIo = builder.usesCharSequenceIo;
			this.usesArrays = builder.usesArrays;
			this.usesHashTables = builder.usesHashTables;
			this.usesEqualpHashTables = builder.usesEqualpHashTables;
			this.usesIdentityHashTables = builder.usesIdentityHashTables;
			this.usesSeqString = builder.usesSeqString;
			this.mutableStringProducers = builder.mutableStringProducers;
			this.mayUseInstances = builder.mayUseInstances;
			this.usesComplex = builder.usesComplex;
			this.hasLandingPad = builder.hasLandingPad;
			this.usesSynonymStreams = builder.usesSynonymStreams;
			this.asksStreamDirection = builder.asksStreamDirection;
			this.usesStreamValues = builder.usesStreamValues;
			this.mayUseAsyncValues = builder.mayUseAsyncValues;
			this.className = builder.className;
			this.userDefunNames = builder.userDefunNames;
			this.builtinShapedDefuns = builder.builtinShapedDefuns;
			this.warnedClRedefinitions = builder.warnedClRedefinitions;
			this.usesFmakunbound = builder.usesFmakunbound;
			this.usesRuntimePackages = builder.usesRuntimePackages;
			this.packageTable = builder.packageTable;
			this.packageUseTable = builder.packageUseTable;
			this.bakedSymbolAccess = builder.bakedSymbolAccess;
			this.symbolPrintTable = builder.symbolPrintTable;
			this.structAccessors = builder.structAccessors;
			this.closRegistry = builder.closRegistry != null ? builder.closRegistry : new ClosRegistry();
			this.captureMemo = builder.captureMemo;
			this.globals = builder.globals;
			this.nestedDefunNames = builder.nestedDefunNames;
			this.specialVars = builder.specialVars;
			this.globalFields = builder.globalFields;
			this.mvChannel = builder.mvChannel;
			this.rawGlobals = builder.rawGlobals;
			this.dynVars = builder.dynVars;
			this.threadScopedSpecials = builder.threadScopedSpecials;
			this.unboundMarker = builder.unboundMarker;
			this.cp = Objects.requireNonNull(builder.cp);
			this.stack = new OperandStack();
			this.body = new am.ik.jvm.MethodCode(this.stack);
			this.bodyStart = this.body.newBoundLabel();
			this.systemOut = Objects.requireNonNull(builder.systemOut);
			this.printlnStr = Objects.requireNonNull(builder.printlnStr);
			this.lispToString = Objects.requireNonNull(builder.lispToString);
			this.printStr = Objects.requireNonNull(builder.printStr);
			this.printlnVoid = Objects.requireNonNull(builder.printlnVoid);
			this.lispToDisplayString = Objects.requireNonNull(builder.lispToDisplayString);
			this.longClass = Objects.requireNonNull(builder.longClass);
			this.longValueOf = Objects.requireNonNull(builder.longValueOf);
			this.longValue = Objects.requireNonNull(builder.longValue);
			this.objectClass = Objects.requireNonNull(builder.objectClass);
			this.objectArrayClass = Objects.requireNonNull(builder.objectArrayClass);
			this.integerClass = Objects.requireNonNull(builder.integerClass);
			this.integerValueOf = Objects.requireNonNull(builder.integerValueOf);
			this.integerValue = Objects.requireNonNull(builder.integerValue);
			this.doubleClass = Objects.requireNonNull(builder.doubleClass);
			this.doubleValueOf = Objects.requireNonNull(builder.doubleValueOf);
			this.numberClass = Objects.requireNonNull(builder.numberClass);
			this.numberDoubleValue = Objects.requireNonNull(builder.numberDoubleValue);
			this.stringClass = Objects.requireNonNull(builder.stringClass);
			this.stringCharAt = Objects.requireNonNull(builder.stringCharAt);
			this.appendMethod = Objects.requireNonNull(builder.appendMethod);
			this.mathAbsLong = Objects.requireNonNull(builder.mathAbsLong);
			this.mathAbsDouble = Objects.requireNonNull(builder.mathAbsDouble);
			this.mathFloor = Objects.requireNonNull(builder.mathFloor);
			this.mathCeil = Objects.requireNonNull(builder.mathCeil);
			this.mathRint = Objects.requireNonNull(builder.mathRint);
			this.objectEquals = Objects.requireNonNull(builder.objectEquals);
			this.readLineHelper = Objects.requireNonNull(builder.readLineHelper);
			this.fetchHelper = builder.fetchHelper;
			this.awaitHelper = builder.awaitHelper;
			this.asyncRunHelper = builder.asyncRunHelper;
			this.futurepHelper = builder.futurepHelper;
			this.streampHelper = builder.streampHelper;
			this.makeStreamHelper = builder.makeStreamHelper;
			this.streamNewHelper = builder.streamNewHelper;
			this.streamReadHelper = builder.streamReadHelper;
			this.streamWriteHelper = builder.streamWriteHelper;
			this.streamCloseHelper = builder.streamCloseHelper;
			this.drainBodyHelper = builder.drainBodyHelper;
			this.waitForHelper = builder.waitForHelper;
			this.flushStreams = builder.flushStreams;
			this.tcpConnectHelper = builder.tcpConnectHelper;
			this.tcpListenHelper = builder.tcpListenHelper;
			this.tcpAcceptHelper = builder.tcpAcceptHelper;
			this.tcpLocalPortHelper = builder.tcpLocalPortHelper;
			this.tcpLocalAddressHelper = builder.tcpLocalAddressHelper;
			this.tcpPeerAddressHelper = builder.tcpPeerAddressHelper;
			this.tcpPeerPortHelper = builder.tcpPeerPortHelper;
			this.tcpSetTimeoutHelper = builder.tcpSetTimeoutHelper;
			this.tlsConnectHelper = builder.tlsConnectHelper;
			this.tlsUpgradeHelper = builder.tlsUpgradeHelper;
			this.tlsListenHelper = builder.tlsListenHelper;
			this.tlsListenP12Helper = builder.tlsListenP12Helper;
			this.httpHandlerRuntime = builder.httpHandlerRuntime;
			this.javaOps = builder.javaOps;
			this.javaSites = builder.javaSites;
			this.objcOps = builder.objcOps;
			this.ffiOps = builder.ffiOps;
			this.simdOps = builder.simdOps;
			this.blasOps = builder.blasOps;
			this.gpuOps = builder.gpuOps;
			this.geomOps = builder.geomOps;
			this.functions = builder.functions;
			this.lambdaDecls = builder.lambdaDecls;
			this.indirectCallArities = builder.indirectCallArities;
			this.valueFuncIds = builder.valueFuncIds;
			this.bouncingBodies = builder.bouncingBodies;
			this.spreadBounces = builder.spreadBounces;
			this.valueTailArities = builder.valueTailArities;
			this.arityGuardShapes = builder.arityGuardShapes;
			this.arityOperators = builder.arityOperators;
			this.spelledLiterals = builder.spelledLiterals;
			this.nextFuncId = builder.nextFuncId;
			this.ctxBuilder = builder;
			this.outlinedBodies = builder.outlinedBodies;
			this.nextOutlinedBodyId = builder.nextOutlinedBodyId;
			this.sharedHelpers = builder.sharedHelpers;
			this.numOps = builder.numOps;
			this.mathOps = builder.mathOps;
			this.systemOps = builder.systemOps;
			this.operandTypeWrappers = builder.operandTypeWrappers;
			this.subseqRuntime = builder.subseqRuntime;
			this.sites = builder.sites;
			this.lambdaReportNames = builder.lambdaReportNames;
			this.asyncBodyHeads = builder.asyncBodyHeads;
			this.lambdaSelfVars = builder.lambdaSelfVars;
			this.tailGroupMembers = builder.tailGroupMembers;
		}

		/**
		 * Opens the method of a function or a lambda: its name for an async body's report
		 * head, and the program function its code is written in, which owns its sites.
		 * @param name the name the report calls the function by, or {@code null} for an
		 * anonymous function
		 * @param around for an anonymous function, the name of the function it is written
		 * in ({@link #writtenIn}), else ignored
		 * @param body its body forms
		 */
		void openFunction(@Nullable String name, @Nullable String around, List<LispVal> body) {
			this.functionName = name;
			this.writtenIn = name != null ? name : around;
			JvmSourceSites table = this.sites;
			String owner = this.writtenIn;
			// Only a body that holds a located form has a site to own.
			if (table != null && owner != null && JvmSourceSites.sourced(body)) {
				this.siteOwner = table.owner(owner);
			}
		}

		/**
		 * Opens a continuation's sites ({@link JvmBodyOutliner}): the method it was split
		 * from continues here, so the function and the innermost located form open at the
		 * split carry over.
		 * @param from the method the continuation was split from
		 */
		void continueFunction(Ctx from) {
			this.functionName = from.functionName;
			this.writtenIn = from.writtenIn;
			this.siteOwner = from.siteOwner;
			this.siteCurrent = from.siteCurrent;
			if (this.siteCurrent != 0) {
				this.siteMarks.add(new int[] { this.body.position(), this.siteCurrent });
			}
		}

		/**
		 * Enters a form: from here until the matching {@link #leaveSite}, emitted code
		 * belongs to the form when it has a position in a named file.
		 * @param form the form about to be compiled
		 * @return what {@link #leaveSite} restores, or -1 when the form has no site (the
		 * enclosing one keeps the code)
		 */
		int enterSite(LispCons form) {
			JvmSourceSites table = this.sites;
			if (table == null) {
				return -1;
			}
			int site = table.site(form, this.siteOwner);
			if (site == 0) {
				return -1;
			}
			int saved = this.siteCurrent;
			this.siteCurrent = site;
			this.siteMarks.add(new int[] { this.body.position(), site });
			return saved;
		}

		/**
		 * Leaves a form entered by {@link #enterSite}: the code emitted next belongs to
		 * the site that was current before it.
		 * @param saved what {@link #enterSite} answered
		 */
		void leaveSite(int saved) {
			if (saved < 0) {
				return;
			}
			this.siteCurrent = saved;
			this.siteMarks.add(new int[] { this.body.position(), saved });
		}

		/**
		 * Makes the code emitted next belong to {@code site}: a tail-spine item's
		 * ({@link JvmBodyOutliner}), queued by a construct whose own compilation -- and
		 * whose {@link #leaveSite} -- is over by the time the item is emitted.
		 * @param site the site current where the item was queued
		 */
		void restoreSite(int site) {
			if (site != this.siteCurrent) {
				this.siteCurrent = site;
				this.siteMarks.add(new int[] { this.body.position(), site });
			}
		}

		/**
		 * Adds this body to the class as a method.
		 * @param definition the class
		 * @param access the method's access flags
		 * @param name its name
		 * @param descriptor its descriptor
		 */
		void addTo(ClassDefinition.Builder definition, int access, Utf8Entry name, Utf8Entry descriptor) {
			JvmTailGroup.LaidOut group = this.laidOut;
			if (group != null) {
				definition.addMethod(access, name, descriptor, group.body(), group.lines());
				return;
			}
			definition.addMethod(access, name, descriptor, this.body, this.lines());
		}

		/**
		 * {@return the bytecodes of the method the class takes for this body} -- its tail
		 * group's layout when it has one ({@link #laidOut})
		 */
		int emittedSize() {
			JvmTailGroup.LaidOut group = this.laidOut;
			return group != null ? group.body().size() : this.body.size();
		}

		/**
		 * This method's {@code LineNumberTable}: one entry wherever the site changes, the
		 * last mark winning where several fall on one instruction, nothing before the
		 * first site (an instruction before every entry reports no line at all). The
		 * number is a site id ({@link JvmSourceSites}); {@link JvmSourceSites#NO_SITE}
		 * marks code outside any site. The writer moves an entry along with the
		 * instruction it starts at when a relaxed branch grows the code before it.
		 * @return the entries, in position order; empty when the method has no site
		 */
		List<ClassDefinition.Line> lines() {
			List<ClassDefinition.Line> entries = new ArrayList<>();
			int end = this.body.position();
			for (int[] mark : this.siteMarks) {
				int position = mark[0];
				if (position >= end) {
					break;
				}
				if (!entries.isEmpty() && entries.getLast().position() == position) {
					entries.removeLast();
				}
				int line = mark[1] == 0 ? JvmSourceSites.NO_SITE : mark[1];
				int previous = entries.isEmpty() ? JvmSourceSites.NO_SITE : entries.getLast().lineNumber();
				if (line != previous) {
					entries.add(new ClassDefinition.Line(position, line));
				}
			}
			return entries;
		}

		static Builder builder() {
			return new Builder();
		}

		static final class Builder {

			/**
			 * One condition channel per builder (= per compilation): every context built
			 * from the same builder shares it.
			 */
			private final ConditionChannel conditionChannel = new ConditionChannel();

			/**
			 * One layout pool per builder (= per compilation): every context built from
			 * the same builder shares it.
			 */
			private final LayoutPool layoutPool = new LayoutPool();

			/**
			 * One bignum-literal pool per builder (= per compilation): every context
			 * built from the same builder shares it.
			 */
			private final BigIntPool bigIntPool = new BigIntPool();

			/**
			 * One quoted-datum pool per builder (= per compilation): every context built
			 * from the same builder shares it.
			 */
			private final JvmQuotePool quotePool = new JvmQuotePool();

			/**
			 * One UNSUPPLIED marker per builder (= per compilation): every context built
			 * from the same builder shares it.
			 */
			private final JvmUnsupplied unsupplied = new JvmUnsupplied();

			/**
			 * One source-site table per builder (= per compilation), or none when the
			 * compile records no source positions: every context built from the same
			 * builder shares it.
			 */
			private final @Nullable JvmSourceSites sites = SourceProvenance.isRecording() ? new JvmSourceSites() : null;

			/**
			 * The report names of the lambda FORMS a non-top-level defun lowered to, by
			 * identity, shared per compilation: set where the defun is compiled, read by
			 * {@link JvmLambdaCompiler} when it registers the lambda.
			 */
			private final Map<LispCons, String> lambdaReportNames = new java.util.IdentityHashMap<>();

			/**
			 * The report heads of the {@code %async-run} thunk FORMS, by identity, shared
			 * per compilation like {@link #lambdaReportNames}.
			 */
			private final Map<LispCons, String> asyncBodyHeads = new java.util.IdentityHashMap<>();

			/**
			 * The {@code labels} variable each lambda FORM is assigned to, by identity,
			 * shared per compilation like {@link #lambdaReportNames}: set where the
			 * expansion's {@code setq} is compiled, read by {@link JvmLambdaCompiler}.
			 */
			private final Map<LispCons, String> lambdaSelfVars = new java.util.IdentityHashMap<>();

			/**
			 * The tail-group member each {@code labels} lambda FORM is, by identity,
			 * shared per compilation like {@link #lambdaSelfVars}: set where the
			 * expansion is compiled ({@link JvmTailGroup#ofLabels}), read by
			 * {@link JvmLambdaCompiler}.
			 */
			private final Map<LispCons, JvmTailGroup.Member> tailGroupMembers = new java.util.IdentityHashMap<>();

			private @Nullable ConstantPool cp;

			private @Nullable FieldRefEntry systemOut;

			private @Nullable MethodRefEntry printlnStr;

			private @Nullable MethodRefEntry lispToString;

			private @Nullable MethodRefEntry printStr;

			private @Nullable MethodRefEntry printlnVoid;

			private @Nullable MethodRefEntry lispToDisplayString;

			private @Nullable ClassEntry longClass;

			private @Nullable MethodRefEntry longValueOf;

			private @Nullable MethodRefEntry longValue;

			private @Nullable ClassEntry objectClass;

			private @Nullable ClassEntry objectArrayClass;

			private @Nullable ClassEntry integerClass;

			private @Nullable MethodRefEntry integerValueOf;

			private @Nullable MethodRefEntry integerValue;

			private @Nullable ClassEntry doubleClass;

			private @Nullable MethodRefEntry doubleValueOf;

			private @Nullable ClassEntry numberClass;

			private @Nullable MethodRefEntry numberDoubleValue;

			private @Nullable ClassEntry stringClass;

			private @Nullable MethodRefEntry stringCharAt;

			private @Nullable MethodRefEntry appendMethod;

			private @Nullable MethodRefEntry mathAbsLong;

			private @Nullable MethodRefEntry mathAbsDouble;

			private @Nullable MethodRefEntry mathFloor;

			private @Nullable MethodRefEntry mathCeil;

			private @Nullable MethodRefEntry mathRint;

			private @Nullable MethodRefEntry objectEquals;

			private @Nullable MethodRefEntry readLineHelper;

			private @Nullable MethodRefEntry fetchHelper;

			private @Nullable MethodRefEntry awaitHelper;

			private @Nullable MethodRefEntry asyncRunHelper;

			private @Nullable MethodRefEntry futurepHelper;

			private @Nullable MethodRefEntry streampHelper;

			private @Nullable MethodRefEntry makeStreamHelper;

			private @Nullable MethodRefEntry streamNewHelper;

			private @Nullable MethodRefEntry streamReadHelper;

			private @Nullable MethodRefEntry streamWriteHelper;

			private @Nullable MethodRefEntry streamCloseHelper;

			private @Nullable MethodRefEntry drainBodyHelper;

			private @Nullable MethodRefEntry waitForHelper;

			private @Nullable MethodRefEntry flushStreams;

			private @Nullable MethodRefEntry tcpConnectHelper;

			private @Nullable MethodRefEntry tcpListenHelper;

			private @Nullable MethodRefEntry tcpAcceptHelper;

			private @Nullable MethodRefEntry tcpLocalPortHelper;

			private @Nullable MethodRefEntry tcpLocalAddressHelper;

			private @Nullable MethodRefEntry tcpPeerAddressHelper;

			private @Nullable MethodRefEntry tcpPeerPortHelper;

			private @Nullable MethodRefEntry tcpSetTimeoutHelper;

			private @Nullable MethodRefEntry tlsConnectHelper;

			private @Nullable MethodRefEntry tlsUpgradeHelper;

			private @Nullable MethodRefEntry tlsListenHelper;

			private @Nullable MethodRefEntry tlsListenP12Helper;

			private JvmHttpHandlerRuntimeBuilder.@Nullable HttpHandlerRuntime httpHandlerRuntime;

			private @Nullable Map<String, MethodRefEntry> javaOps;

			private @Nullable JvmJavaSites javaSites;

			private @Nullable Map<String, MethodRefEntry> objcOps;

			private @Nullable Map<String, MethodRefEntry> ffiOps;

			private @Nullable Map<String, MethodRefEntry> simdOps;

			private @Nullable Map<String, MethodRefEntry> blasOps;

			private @Nullable Map<String, MethodRefEntry> gpuOps;

			private @Nullable Map<String, MethodRefEntry> geomOps;

			private Map<String, FunctionInfo> functions = Map.of();

			private List<LambdaInfo> lambdaDecls = new ArrayList<>();

			private Set<Integer> indirectCallArities = new HashSet<>();

			private Set<Integer> valueFuncIds = new HashSet<>();

			/**
			 * One set for every {@code Ctx} this builder makes: see
			 * {@link Ctx#bouncingBodies}.
			 */
			private final Set<MethodCode> bouncingBodies = java.util.Collections
				.newSetFromMap(new java.util.IdentityHashMap<>());

			/**
			 * One cell for every {@code Ctx} this builder makes: see
			 * {@link Ctx#spreadBounces}.
			 */
			private final boolean[] spreadBounces = new boolean[1];

			/**
			 * One set for every {@code Ctx} this builder makes: see
			 * {@link Ctx#valueTailArities}.
			 */
			private final Set<Integer> valueTailArities = new java.util.TreeSet<>();

			private Set<Integer> arityGuardShapes = new HashSet<>();

			private JvmArityOperators arityOperators = new JvmArityOperators();

			private Set<String> spelledLiterals = new HashSet<>();

			private int[] nextFuncId = new int[1];

			private final List<JvmBodyOutliner.OutlinedBody> outlinedBodies = new ArrayList<>();

			private final int[] nextOutlinedBodyId = new int[1];

			private final Map<String, MethodRefEntry> sharedHelpers = new LinkedHashMap<>();

			private boolean dynamic = false;

			private boolean servletMode = false;

			private boolean blockExitChannel = false;

			private boolean restartMode = false;

			private boolean signalClauseMatch = false;

			private boolean printControls = false;

			private boolean printControlVariables = false;

			private boolean usesFloatArray = false;

			private boolean usesQuantized = false;

			private boolean typedLoops = true;

			private boolean intFusion = true;

			private Map<String, DefunDecl> inlinableDefuns = Map.of();

			private JvmIntFusionCompiler.@Nullable State fusedState;

			private boolean usesIntArray = false;

			private boolean usesTypedArray = false;

			private boolean usesPackedSequenceIo = false;

			private boolean usesCharSequenceIo = false;

			private boolean usesArrays = false;

			private boolean usesHashTables = false;

			private boolean usesEqualpHashTables = false;

			private boolean usesIdentityHashTables = false;

			private boolean usesSeqString = false;

			private boolean mutableStringProducers = false;

			private boolean mayUseInstances = false;

			private boolean usesComplex = false;

			private boolean hasLandingPad = false;

			private boolean usesSynonymStreams = false;

			private boolean asksStreamDirection = false;

			private boolean usesStreamValues = false;

			private boolean mayUseAsyncValues = false;

			private String className = "";

			private Set<String> userDefunNames = Set.of();

			private Set<String> builtinShapedDefuns = Set.of();

			private Set<String> warnedClRedefinitions = new HashSet<>();

			private boolean usesFmakunbound = false;

			private boolean usesRuntimePackages = false;

			private Map<String, String> packageTable = Map.of();

			private Map<String, java.util.List<String>> packageUseTable = Map.of();

			private am.ik.rontolisp.macro.BakedSymbolAccess bakedSymbolAccess = am.ik.rontolisp.macro.BakedSymbolAccess.NONE;

			private am.ik.rontolisp.@Nullable SymbolPrintTable symbolPrintTable;

			private Map<String, Integer> structAccessors = Map.of();

			private @Nullable ClosRegistry closRegistry;

			private final FreeVarAnalyzer.CaptureMemo captureMemo = new FreeVarAnalyzer.CaptureMemo();

			private Set<String> globals = Set.of();

			private Set<String> nestedDefunNames = Set.of();

			private Set<String> specialVars = Set.of();

			private Map<String, FieldRefEntry> globalFields = Map.of();

			private @Nullable JvmMvChannel mvChannel;

			Builder mvChannel(@Nullable JvmMvChannel mvChannel) {
				this.mvChannel = mvChannel;
				return this;
			}

			private Map<String, JvmIntFusionCompiler.RawLocal> rawGlobals = Map.of();

			private JvmDynVarRuntimeBuilder.@Nullable DynVarRuntime dynVars;

			private boolean threadScopedSpecials;

			private JvmDynVarRuntimeBuilder.@Nullable UnboundMarker unboundMarker;

			private Map<String, MethodRefEntry> numOps = Map.of();

			private JvmOperandTypeRuntime.@Nullable Wrappers operandTypeWrappers;

			private JvmOperandTypeRuntime.@Nullable SubseqRuntime subseqRuntime;

			private Map<String, MethodRefEntry> mathOps = Map.of();

			private Map<String, MethodRefEntry> systemOps = Map.of();

			Builder cp(ConstantPool cp) {
				this.cp = cp;
				return this;
			}

			Builder systemOut(FieldRefEntry systemOut) {
				this.systemOut = systemOut;
				return this;
			}

			Builder printlnStr(MethodRefEntry printlnStr) {
				this.printlnStr = printlnStr;
				return this;
			}

			Builder lispToString(MethodRefEntry lispToString) {
				this.lispToString = lispToString;
				return this;
			}

			Builder printStr(MethodRefEntry printStr) {
				this.printStr = printStr;
				return this;
			}

			Builder printlnVoid(MethodRefEntry printlnVoid) {
				this.printlnVoid = printlnVoid;
				return this;
			}

			Builder lispToDisplayString(MethodRefEntry lispToDisplayString) {
				this.lispToDisplayString = lispToDisplayString;
				return this;
			}

			Builder longClass(ClassEntry longClass) {
				this.longClass = longClass;
				return this;
			}

			Builder longValueOf(MethodRefEntry longValueOf) {
				this.longValueOf = longValueOf;
				return this;
			}

			Builder longValue(MethodRefEntry longValue) {
				this.longValue = longValue;
				return this;
			}

			Builder objectClass(ClassEntry objectClass) {
				this.objectClass = objectClass;
				return this;
			}

			Builder objectArrayClass(ClassEntry objectArrayClass) {
				this.objectArrayClass = objectArrayClass;
				return this;
			}

			Builder integerClass(ClassEntry integerClass) {
				this.integerClass = integerClass;
				return this;
			}

			Builder integerValueOf(MethodRefEntry integerValueOf) {
				this.integerValueOf = integerValueOf;
				return this;
			}

			Builder integerValue(MethodRefEntry integerValue) {
				this.integerValue = integerValue;
				return this;
			}

			Builder doubleClass(ClassEntry doubleClass) {
				this.doubleClass = doubleClass;
				return this;
			}

			Builder doubleValueOf(MethodRefEntry doubleValueOf) {
				this.doubleValueOf = doubleValueOf;
				return this;
			}

			Builder numberClass(ClassEntry numberClass) {
				this.numberClass = numberClass;
				return this;
			}

			Builder numberDoubleValue(MethodRefEntry numberDoubleValue) {
				this.numberDoubleValue = numberDoubleValue;
				return this;
			}

			Builder stringClass(ClassEntry stringClass) {
				this.stringClass = stringClass;
				return this;
			}

			Builder stringCharAt(MethodRefEntry stringCharAt) {
				this.stringCharAt = stringCharAt;
				return this;
			}

			Builder appendMethod(MethodRefEntry appendMethod) {
				this.appendMethod = appendMethod;
				return this;
			}

			Builder mathAbsLong(MethodRefEntry mathAbsLong) {
				this.mathAbsLong = mathAbsLong;
				return this;
			}

			Builder mathAbsDouble(MethodRefEntry mathAbsDouble) {
				this.mathAbsDouble = mathAbsDouble;
				return this;
			}

			Builder mathFloor(MethodRefEntry mathFloor) {
				this.mathFloor = mathFloor;
				return this;
			}

			Builder mathCeil(MethodRefEntry mathCeil) {
				this.mathCeil = mathCeil;
				return this;
			}

			Builder mathRint(MethodRefEntry mathRint) {
				this.mathRint = mathRint;
				return this;
			}

			Builder objectEquals(MethodRefEntry objectEquals) {
				this.objectEquals = objectEquals;
				return this;
			}

			Builder readLineHelper(MethodRefEntry readLineHelper) {
				this.readLineHelper = readLineHelper;
				return this;
			}

			Builder fetchHelper(@Nullable MethodRefEntry fetchHelper) {
				this.fetchHelper = fetchHelper;
				return this;
			}

			Builder awaitHelper(@Nullable MethodRefEntry awaitHelper) {
				this.awaitHelper = awaitHelper;
				return this;
			}

			Builder asyncRunHelper(@Nullable MethodRefEntry asyncRunHelper) {
				this.asyncRunHelper = asyncRunHelper;
				return this;
			}

			Builder futurepHelper(@Nullable MethodRefEntry futurepHelper) {
				this.futurepHelper = futurepHelper;
				return this;
			}

			Builder streampHelper(@Nullable MethodRefEntry streampHelper) {
				this.streampHelper = streampHelper;
				return this;
			}

			Builder makeStreamHelper(@Nullable MethodRefEntry makeStreamHelper) {
				this.makeStreamHelper = makeStreamHelper;
				return this;
			}

			Builder streamNewHelper(@Nullable MethodRefEntry streamNewHelper) {
				this.streamNewHelper = streamNewHelper;
				return this;
			}

			Builder streamReadHelper(@Nullable MethodRefEntry streamReadHelper) {
				this.streamReadHelper = streamReadHelper;
				return this;
			}

			Builder streamWriteHelper(@Nullable MethodRefEntry streamWriteHelper) {
				this.streamWriteHelper = streamWriteHelper;
				return this;
			}

			Builder streamCloseHelper(@Nullable MethodRefEntry streamCloseHelper) {
				this.streamCloseHelper = streamCloseHelper;
				return this;
			}

			Builder drainBodyHelper(@Nullable MethodRefEntry drainBodyHelper) {
				this.drainBodyHelper = drainBodyHelper;
				return this;
			}

			Builder waitForHelper(@Nullable MethodRefEntry waitForHelper) {
				this.waitForHelper = waitForHelper;
				return this;
			}

			Builder flushStreams(@Nullable MethodRefEntry flushStreams) {
				this.flushStreams = flushStreams;
				return this;
			}

			Builder tcpConnectHelper(@Nullable MethodRefEntry tcpConnectHelper) {
				this.tcpConnectHelper = tcpConnectHelper;
				return this;
			}

			Builder tcpListenHelper(@Nullable MethodRefEntry tcpListenHelper) {
				this.tcpListenHelper = tcpListenHelper;
				return this;
			}

			Builder tcpAcceptHelper(@Nullable MethodRefEntry tcpAcceptHelper) {
				this.tcpAcceptHelper = tcpAcceptHelper;
				return this;
			}

			Builder tcpLocalAddressHelper(@Nullable MethodRefEntry tcpLocalAddressHelper) {
				this.tcpLocalAddressHelper = tcpLocalAddressHelper;
				return this;
			}

			Builder tcpPeerAddressHelper(@Nullable MethodRefEntry tcpPeerAddressHelper) {
				this.tcpPeerAddressHelper = tcpPeerAddressHelper;
				return this;
			}

			Builder tcpPeerPortHelper(@Nullable MethodRefEntry tcpPeerPortHelper) {
				this.tcpPeerPortHelper = tcpPeerPortHelper;
				return this;
			}

			Builder tcpSetTimeoutHelper(@Nullable MethodRefEntry tcpSetTimeoutHelper) {
				this.tcpSetTimeoutHelper = tcpSetTimeoutHelper;
				return this;
			}

			Builder tcpLocalPortHelper(@Nullable MethodRefEntry tcpLocalPortHelper) {
				this.tcpLocalPortHelper = tcpLocalPortHelper;
				return this;
			}

			Builder tlsConnectHelper(@Nullable MethodRefEntry tlsConnectHelper) {
				this.tlsConnectHelper = tlsConnectHelper;
				return this;
			}

			Builder tlsUpgradeHelper(@Nullable MethodRefEntry tlsUpgradeHelper) {
				this.tlsUpgradeHelper = tlsUpgradeHelper;
				return this;
			}

			Builder tlsListenHelper(@Nullable MethodRefEntry tlsListenHelper) {
				this.tlsListenHelper = tlsListenHelper;
				return this;
			}

			Builder tlsListenP12Helper(@Nullable MethodRefEntry tlsListenP12Helper) {
				this.tlsListenP12Helper = tlsListenP12Helper;
				return this;
			}

			Builder httpHandlerRuntime(JvmHttpHandlerRuntimeBuilder.@Nullable HttpHandlerRuntime httpHandlerRuntime) {
				this.httpHandlerRuntime = httpHandlerRuntime;
				return this;
			}

			Builder javaOps(@Nullable Map<String, MethodRefEntry> javaOps) {
				this.javaOps = javaOps;
				return this;
			}

			Builder javaSites(@Nullable JvmJavaSites javaSites) {
				this.javaSites = javaSites;
				return this;
			}

			Builder objcOps(@Nullable Map<String, MethodRefEntry> objcOps) {
				this.objcOps = objcOps;
				return this;
			}

			Builder ffiOps(@Nullable Map<String, MethodRefEntry> ffiOps) {
				this.ffiOps = ffiOps;
				return this;
			}

			Builder simdOps(@Nullable Map<String, MethodRefEntry> simdOps) {
				this.simdOps = simdOps;
				return this;
			}

			Builder blasOps(@Nullable Map<String, MethodRefEntry> blasOps) {
				this.blasOps = blasOps;
				return this;
			}

			Builder gpuOps(@Nullable Map<String, MethodRefEntry> gpuOps) {
				this.gpuOps = gpuOps;
				return this;
			}

			Builder geomOps(@Nullable Map<String, MethodRefEntry> geomOps) {
				this.geomOps = geomOps;
				return this;
			}

			Builder functions(Map<String, FunctionInfo> functions) {
				this.functions = functions;
				return this;
			}

			Builder lambdaDecls(List<LambdaInfo> lambdaDecls) {
				this.lambdaDecls = lambdaDecls;
				return this;
			}

			Builder indirectCallArities(Set<Integer> indirectCallArities) {
				this.indirectCallArities = indirectCallArities;
				return this;
			}

			Builder spelledLiterals(Set<String> spelledLiterals) {
				this.spelledLiterals = spelledLiterals;
				return this;
			}

			Builder valueFuncIds(Set<Integer> valueFuncIds) {
				this.valueFuncIds = valueFuncIds;
				return this;
			}

			Builder arityGuardShapes(Set<Integer> arityGuardShapes) {
				this.arityGuardShapes = arityGuardShapes;
				return this;
			}

			Builder arityOperators(JvmArityOperators arityOperators) {
				this.arityOperators = arityOperators;
				return this;
			}

			Builder nextFuncId(int[] nextFuncId) {
				this.nextFuncId = nextFuncId;
				return this;
			}

			Builder dynamic(boolean dynamic) {
				this.dynamic = dynamic;
				return this;
			}

			Builder servletMode(boolean servletMode) {
				this.servletMode = servletMode;
				return this;
			}

			Builder restartMode(boolean restartMode) {
				this.restartMode = restartMode;
				return this;
			}

			Builder signalClauseMatch(boolean signalClauseMatch) {
				this.signalClauseMatch = signalClauseMatch;
				return this;
			}

			Builder printControls(boolean printControls) {
				this.printControls = printControls;
				return this;
			}

			Builder printControlVariables(boolean printControlVariables) {
				this.printControlVariables = printControlVariables;
				return this;
			}

			Builder blockExitChannel(boolean blockExitChannel) {
				this.blockExitChannel = blockExitChannel;
				return this;
			}

			Builder usesFloatArray(boolean usesFloatArray) {
				this.usesFloatArray = usesFloatArray;
				return this;
			}

			Builder usesQuantized(boolean usesQuantized) {
				this.usesQuantized = usesQuantized;
				return this;
			}

			Builder typedLoops(boolean typedLoops) {
				this.typedLoops = typedLoops;
				return this;
			}

			Builder intFusion(boolean intFusion) {
				this.intFusion = intFusion;
				return this;
			}

			Builder inlinableDefuns(Map<String, DefunDecl> inlinableDefuns) {
				this.inlinableDefuns = inlinableDefuns;
				return this;
			}

			Builder fusedState(JvmIntFusionCompiler.@Nullable State fusedState) {
				this.fusedState = fusedState;
				return this;
			}

			Builder usesIntArray(boolean usesIntArray) {
				this.usesIntArray = usesIntArray;
				return this;
			}

			Builder usesTypedArray(boolean usesTypedArray) {
				this.usesTypedArray = usesTypedArray;
				return this;
			}

			Builder usesPackedSequenceIo(boolean usesPackedSequenceIo) {
				this.usesPackedSequenceIo = usesPackedSequenceIo;
				return this;
			}

			Builder usesCharSequenceIo(boolean usesCharSequenceIo) {
				this.usesCharSequenceIo = usesCharSequenceIo;
				return this;
			}

			Builder usesArrays(boolean usesArrays) {
				this.usesArrays = usesArrays;
				return this;
			}

			Builder usesHashTables(boolean usesHashTables) {
				this.usesHashTables = usesHashTables;
				return this;
			}

			Builder usesEqualpHashTables(boolean usesEqualpHashTables) {
				this.usesEqualpHashTables = usesEqualpHashTables;
				return this;
			}

			Builder usesIdentityHashTables(boolean usesIdentityHashTables) {
				this.usesIdentityHashTables = usesIdentityHashTables;
				return this;
			}

			Builder usesSeqString(boolean usesSeqString) {
				this.usesSeqString = usesSeqString;
				return this;
			}

			Builder mutableStringProducers(boolean mutableStringProducers) {
				this.mutableStringProducers = mutableStringProducers;
				return this;
			}

			Builder mayUseInstances(boolean mayUseInstances) {
				this.mayUseInstances = mayUseInstances;
				return this;
			}

			Builder usesComplex(boolean usesComplex) {
				this.usesComplex = usesComplex;
				return this;
			}

			Builder hasLandingPad(boolean hasLandingPad) {
				this.hasLandingPad = hasLandingPad;
				return this;
			}

			Builder usesSynonymStreams(boolean usesSynonymStreams) {
				this.usesSynonymStreams = usesSynonymStreams;
				return this;
			}

			Builder asksStreamDirection(boolean asksStreamDirection) {
				this.asksStreamDirection = asksStreamDirection;
				return this;
			}

			Builder usesStreamValues(boolean usesStreamValues) {
				this.usesStreamValues = usesStreamValues;
				return this;
			}

			Builder mayUseAsyncValues(boolean mayUseAsyncValues) {
				this.mayUseAsyncValues = mayUseAsyncValues;
				return this;
			}

			Builder className(String className) {
				this.className = className;
				return this;
			}

			Builder userDefunNames(Set<String> userDefunNames) {
				this.userDefunNames = userDefunNames;
				return this;
			}

			Builder builtinShapedDefuns(Set<String> builtinShapedDefuns) {
				this.builtinShapedDefuns = builtinShapedDefuns;
				return this;
			}

			Builder warnedClRedefinitions(Set<String> warnedClRedefinitions) {
				this.warnedClRedefinitions = warnedClRedefinitions;
				return this;
			}

			Builder usesFmakunbound(boolean usesFmakunbound) {
				this.usesFmakunbound = usesFmakunbound;
				return this;
			}

			Builder usesRuntimePackages(boolean usesRuntimePackages) {
				this.usesRuntimePackages = usesRuntimePackages;
				return this;
			}

			Builder packageTable(Map<String, String> packageTable) {
				this.packageTable = packageTable;
				return this;
			}

			Builder packageUseTable(Map<String, java.util.List<String>> packageUseTable) {
				this.packageUseTable = packageUseTable;
				return this;
			}

			Builder bakedSymbolAccess(am.ik.rontolisp.macro.BakedSymbolAccess bakedSymbolAccess) {
				this.bakedSymbolAccess = bakedSymbolAccess;
				return this;
			}

			Builder symbolPrintTable(am.ik.rontolisp.@Nullable SymbolPrintTable symbolPrintTable) {
				this.symbolPrintTable = symbolPrintTable;
				return this;
			}

			Builder structAccessors(Map<String, Integer> structAccessors) {
				this.structAccessors = structAccessors;
				return this;
			}

			Builder closRegistry(ClosRegistry closRegistry) {
				this.closRegistry = closRegistry;
				return this;
			}

			Builder globals(Set<String> globals) {
				this.globals = globals;
				return this;
			}

			Builder nestedDefunNames(Set<String> nestedDefunNames) {
				this.nestedDefunNames = nestedDefunNames;
				return this;
			}

			Builder specialVars(Set<String> specialVars) {
				this.specialVars = specialVars;
				return this;
			}

			Builder dynVars(JvmDynVarRuntimeBuilder.@Nullable DynVarRuntime dynVars) {
				this.dynVars = dynVars;
				return this;
			}

			Builder threadScopedSpecials(boolean threadScopedSpecials) {
				this.threadScopedSpecials = threadScopedSpecials;
				return this;
			}

			Builder unboundMarker(JvmDynVarRuntimeBuilder.@Nullable UnboundMarker unboundMarker) {
				this.unboundMarker = unboundMarker;
				return this;
			}

			Builder globalFields(Map<String, FieldRefEntry> globalFields) {
				this.globalFields = globalFields;
				return this;
			}

			Builder rawGlobals(Map<String, JvmIntFusionCompiler.RawLocal> rawGlobals) {
				this.rawGlobals = rawGlobals;
				return this;
			}

			Builder numOps(Map<String, MethodRefEntry> numOps) {
				this.numOps = numOps;
				return this;
			}

			Builder mathOps(Map<String, MethodRefEntry> mathOps) {
				this.mathOps = mathOps;
				return this;
			}

			Builder operandTypeWrappers(JvmOperandTypeRuntime.Wrappers operandTypeWrappers) {
				this.operandTypeWrappers = operandTypeWrappers;
				return this;
			}

			Builder subseqRuntime(JvmOperandTypeRuntime.@Nullable SubseqRuntime subseqRuntime) {
				this.subseqRuntime = subseqRuntime;
				return this;
			}

			Builder systemOps(Map<String, MethodRefEntry> systemOps) {
				this.systemOps = systemOps;
				return this;
			}

			Ctx build() {
				return new Ctx(this);
			}

		}

		/**
		 * {@code java.lang.Boolean}, the class of the bounce marker in slot 0 of a
		 * trampoline array: no compiled function value carries a there (its slot 0 is the
		 * {@code Integer} funcId), so the marker test is one {@code instanceof}.
		 * @return the class entry
		 */
		ClassEntry booleanClass() {
			return this.cp.classEntry("java/lang/Boolean");
		}

		/**
		 * The reference to the marker the bounce arrays carry in slot 0:
		 * {@code Boolean.TRUE}, a host singleton no Lisp value is.
		 * @return the field reference
		 */
		FieldRefEntry booleanMarker() {
			return this.cp.fieldRef(this.booleanClass(), "TRUE", JvmTailBounce.MARKER_DESC);
		}

		/**
		 * The trampoline loop {@code _tramp(Object)Object}: takes a possibly-bounced
		 * result, and while it is a bounce array re-enters the dispatcher of the array's
		 * argument count with its designator and arguments, until a real value comes
		 * back.
		 * @return the method reference
		 */
		MethodRefEntry trampRef(String className) {
			return this.cp.methodRef(this.cp.classEntry(className), this.trampName(), this.trampDesc());
		}

		Utf8Entry trampName() {
			return this.cp.utf8Entry("_tramp");
		}

		Utf8Entry trampDesc() {
			return this.cp.utf8Entry("(Ljava/lang/Object;)Ljava/lang/Object;");
		}

		MethodRefEntry numOp(String key) {
			MethodRefEntry ref = Objects.requireNonNull(this.numOps.get(key), () -> "Unknown numeric helper: " + key);
			String desc = JvmNumericRuntimeBuilder.wrappedDesc(key);
			return desc == null ? ref : wrapForOperator(key, desc, ref);
		}

		/**
		 * The reference a call to a numeric helper compiled at this point invokes: the
		 * innermost named operator's wrapper, or the helper itself.
		 * @param helper the helper's method name
		 * @param desc its descriptor
		 * @param ref its reference
		 * @return the reference to invoke
		 */
		MethodRefEntry wrapForOperator(String helper, String desc, MethodRefEntry ref) {
			JvmOperandTypeRuntime.Wrappers wrappers = this.operandTypeWrappers;
			return wrappers == null ? ref : wrappers.wrap(this.operator, helper, desc, ref);
		}

		MethodRefEntry mathOp(String key) {
			return Objects.requireNonNull(this.mathOps.get(key), () -> "Unknown math helper: " + key);
		}

		MethodRefEntry systemOp(String key) {
			return Objects.requireNonNull(this.systemOps.get(key), () -> "Unknown system helper: " + key);
		}

		/**
		 * Replaces this context's compile-time "already bound" tracking set with a shared
		 * one, so {@code defvar} idempotence holds across the several methods the
		 * top-level body is split into.
		 */
		void shareDefinedGlobals(Set<String> shared) {
			this.definedGlobals = shared;
		}

		/**
		 * The deepest this method body's operand stack ever gets. The floor keeps the
		 * emitted {@code Code} attribute byte-identical to the fixed value this used to
		 * be, which every existing method stayed well under.
		 */
		int maxStack() {
			return Math.max(64, this.stack.maxDepth());
		}

		/**
		 * Spills the values live on the operand stack into fresh locals, leaving it
		 * empty, and returns the spill (empty when the stack already was). Entering a JVM
		 * exception handler discards the operand stack, so a form that catches -- whose
		 * handler merges back into the normal path -- must not be entered with operands
		 * live: they are saved here and reloaded by {@link Spill#restore} past the merge.
		 */
		Spill spillOperandStack() {
			return this.spillOperandStack("a catching form");
		}

		/**
		 * Spills the values live on the operand stack so the loop that follows has its
		 * backedge target at depth 0 -- the only shape HotSpot will OSR-compile (see
		 * {@link JvmTagbodyCompiler}). Reloading is the caller's job, under whatever
		 * value the loop leaves behind.
		 */
		Spill spillLoopEntryStack() {
			return this.spillOperandStack("a loop");
		}

		/**
		 * {@return true when the live operand stack still fits in this method's local
		 * slots} A catching form has no choice and fails the compile without the room; a
		 * loop spill is an optimization, so it asks first and simply declines.
		 */
		boolean hasRoomToSpillOperandStack() {
			int needed = 0;
			for (OperandStack.Slot slot : this.stack.snapshot()) {
				needed += slot.width();
			}
			return this.nextLocal + needed - 1 <= MAX_LOCAL_SLOT;
		}

		private Spill spillOperandStack(String what) {
			List<OperandStack.Slot> live = this.stack.snapshot();
			if (live.isEmpty()) {
				return Spill.EMPTY;
			}
			if (live.contains(OperandStack.Slot.UNINIT)) {
				// A half-constructed object cannot be saved into a local across an
				// exception-protected region: the verifier invalidates it in the handler.
				// No emitter puts a catching form there today; one that did would have to
				// evaluate the value into a local before the `new`.
				throw new UnsupportedOperationException(
						"Cannot compile " + what + " while an object is under construction");
			}
			int[] slots = new int[live.size()];
			for (int i = live.size() - 1; i >= 0; i--) {
				OperandStack.Slot slot = live.get(i);
				slots[i] = this.allocTemp();
				if (slot.wide()) {
					this.allocTemp();
				}
				if (this.nextLocal - 1 > MAX_LOCAL_SLOT) {
					// A spilled value must survive the protected region, so it needs a
					// slot of its own; past the u2 `max_locals` ceiling there is none.
					throw new UnsupportedOperationException(
							"Cannot compile " + what + " here: the function is out of local variable slots");
				}
				this.body.storeLocal(kind(slot), slots[i]);
			}
			return new Spill(live, slots);
		}

		/**
		 * Discards the operands the current form pushed on top of {@code keep} entries,
		 * so a jump out of it reaches its target with the operand stack the target is
		 * reached with on every other path.
		 * @param keep the number of entries, counted from the bottom, to leave in place
		 */
		void discardOperandsDownTo(int keep) {
			for (int i = this.stack.snapshot().size(); i > keep; i--) {
				if (this.stack.snapshot().getLast().wide()) {
					this.body.pop2();
				}
				else {
					this.body.pop();
				}
			}
		}

		/**
		 * The values a catching form saved out of the operand stack, and the locals
		 * holding them.
		 */
		record Spill(List<OperandStack.Slot> live, int[] slots) {

			static final Spill EMPTY = new Spill(List.of(), new int[0]);

			/**
			 * {@return true when the operand stack was already empty, so nothing was
			 * saved}
			 */
			boolean isEmpty() {
				return this.live.isEmpty();
			}

			/**
			 * Reloads the spilled values, restoring the operand stack it was taken from.
			 */
			void restore(Ctx ctx) {
				this.restore(ctx, this.live.size());
			}

			/** Reloads the bottom {@code count} of the spilled values. */
			void restore(Ctx ctx, int count) {
				for (int i = 0; i < count; i++) {
					ctx.body.loadLocal(kind(this.live.get(i)), this.slots[i]);
				}
			}

		}

		private static TypeKind kind(OperandStack.Slot slot) {
			return switch (slot) {
				case REF -> TypeKind.REFERENCE;
				case INT -> TypeKind.INT;
				case FLOAT -> TypeKind.FLOAT;
				case LONG -> TypeKind.LONG;
				case DOUBLE -> TypeKind.DOUBLE;
				case UNINIT -> throw new IllegalStateException("an object under construction cannot be spilled");
			};
		}

		int allocLocal(String name) {
			int slot = this.allocTemp();
			this.locals.put(name, slot);
			return slot;
		}

		/**
		 * {@return the shared local {@code %error} spills its message into}
		 */
		int errorMessageSlot() {
			if (this.errorMessageSlot < 0) {
				this.errorMessageSlot = this.allocTemp();
			}
			return this.errorMessageSlot;
		}

		int allocTemp() {
			int slot = this.nextLocal++;
			if (slot == this.errorMessageSlot) {
				// The scope that reserved the %error message slot has ended and the slot
				// is being handed to a live variable: an error caught in THIS method
				// would overwrite it, so the next error site takes a fresh slot.
				this.errorMessageSlot = -1;
			}
			if (slot > MAX_LOCAL_SLOT) {
				// max_locals is a u2: no load or store, `wide` included, can name a
				// higher slot. Say so instead of writing an index that wraps.
				throw new IllegalStateException(
						"this function needs more than " + MAX_LOCAL_SLOT + " local variable slots");
			}
			if (this.nextLocal > this.maxLocals) {
				this.maxLocals = this.nextLocal;
			}
			return slot;
		}

	}

}
