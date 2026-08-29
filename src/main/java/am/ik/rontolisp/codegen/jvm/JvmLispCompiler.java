package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
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
import am.ik.rontolisp.macro.SpecialVarCollector;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.compiler.BuiltinFunctionWrappers;
import am.ik.rontolisp.compiler.CompileTimeBoundp;
import am.ik.rontolisp.compiler.ConcatenateForms;
import am.ik.rontolisp.compiler.AstOutliner;
import am.ik.rontolisp.compiler.CrossLambdaExitLowering;
import am.ik.rontolisp.compiler.DesignatorSpellings;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.rontolisp.compiler.GlobalVarCollector;
import am.ik.rontolisp.compiler.LispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.compiler.ShadowedBuiltins;
import am.ik.rontolisp.compiler.StreamDesignators;
import am.ik.rontolisp.compiler.JvmExportDirective;
import am.ik.rontolisp.compiler.WasmImportDirective;

import am.ik.jvm.AccessFlag;
import am.ik.jvm.ByteCodeWriter;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.JvmClassShaker;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.jvm.OperandStack;
import am.ik.jvm.StackMapAugmenter;
import org.jspecify.annotations.Nullable;

/**
 * Compiles Lisp expressions to JVM .class bytecode, stamped class file version 61 (Java
 * 17) after {@link StackMapAugmenter} computes the mandatory StackMapTable offline.
 * Supports first-class functions, closures, and capture-by-reference semantics.
 */
public final class JvmLispCompiler implements LispCompiler {

	/**
	 * The class-file major version the finished class is stamped with (61 = Java 17).
	 * Emission itself stays version-agnostic; {@link StackMapAugmenter} computes the
	 * StackMapTable that every version above 50 requires and stamps this version as the
	 * final step of {@link #compile}.
	 */
	private static final int CLASS_MAJOR_VERSION = 61;

	private final String className;

	private final boolean dynamic;

	private final OptimizeLevel optimize;

	private final boolean simdAccel;

	private final boolean blasAccel;

	private final boolean gpuAccel;

	private final boolean parallelAccel;

	/**
	 * The names the compiled program's {@code *features*} starts out holding. The JVM
	 * backend's own set unless the frontend {@link #runtimeFeatures(List) says otherwise}
	 * -- reading and running must agree on it, and only the frontend knows what it read
	 * with.
	 */
	private List<String> runtimeFeatures = LispMacroExpander.backendFeatures(false);

	/**
	 * Library mode ({@code --no-main}): no {@code main} method; the class is entered
	 * through its {@code rontolisp:jvm-export} wrappers only. See {@link #noMain}.
	 */
	private boolean noMain;

	/**
	 * Servlet mode ({@code -o app.war}): the program serves through a servlet container
	 * that owns the port, so the {@code rontolisp:http-handler} directive registers its
	 * handler and RETURNS (no bind, no block), the top level moves into {@code <clinit>}
	 * (the container's initializer runs it via {@code Class.forName}), and the two
	 * servlet adapter classes join {@link #runtimeClassFiles()}. See {@link #servlet}.
	 */
	private boolean servletMode;

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
	 * Whether the last {@link #compile} builds an {@code equalp} hash table, i.e. whether
	 * the emitted class needs {@code RontoHashTable} -- the class the key fold is written
	 * in -- beside it.
	 */
	private boolean needsHashFoldRuntime;

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

	/** The embedded eval/apply runtime group ({@link JvmEvalRuntimeBuilder}). */
	private static final String GROUP_EVAL = "eval";

	/**
	 * The eval runtime's own methods; {@code _lookup$N} segments hang off
	 * {@code _lookup}.
	 */
	private static final Set<String> EVAL_METHOD_NAMES = Set.of("_eval", "_apply", "_store", "_envLookup", "_lookup");

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
		if (JvmHashRuntimeBuilder.METHOD_NAMES.contains(helperName)) {
			return GROUP_HASH;
		}
		if (EVAL_METHOD_NAMES.contains(helperName)) {
			return GROUP_EVAL;
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

		private final Map<String, Integer> oversized;

		private MethodTooLarge(Map<String, Integer> oversized) {
			super(null, null, false, false);
			this.oversized = oversized;
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
	 * Create a new JVM compiler targeting the given class name.
	 * @param className the fully qualified class name for the generated class
	 * <p>
	 * Compiles at {@link OptimizeLevel#DEFAULT} -- the level an absent {@code --optimize}
	 * selects, so an embedder that names no level gets what this project's own frontend
	 * gives. Declining the optimizer is asked for by name: {@link OptimizeLevel#NONE}.
	 */
	public JvmLispCompiler(String className) {
		this(className, false);
	}

	/**
	 * Create a new JVM compiler targeting the given class name.
	 * @param className the fully qualified class name for the generated class
	 * @param dynamic when {@code true}, unresolved function calls and variable references
	 * are not rejected at compile time but resolved at runtime against the embedded
	 * {@code eval} global environment (late binding), so a program that defines functions
	 * via {@code load} can compile without changes. This forces the {@code eval} runtime
	 * to be emitted.
	 * <p>
	 * Compiles at {@link OptimizeLevel#DEFAULT} -- the level an absent {@code --optimize}
	 * selects, so an embedder that names no level gets what this project's own frontend
	 * gives. Declining the optimizer is asked for by name: {@link OptimizeLevel#NONE}.
	 */
	public JvmLispCompiler(String className, boolean dynamic) {
		this(className, dynamic, OptimizeLevel.DEFAULT);
	}

	/**
	 * Create a new JVM compiler targeting the given class name.
	 * @param className the fully qualified class name for the generated class
	 * @param dynamic when {@code true}, unresolved function calls and variable references
	 * are resolved at runtime against the embedded {@code eval} global environment (late
	 * binding); see {@link #JvmLispCompiler(String, boolean)}
	 * @param optimize what to optimize the class FOR (the CLI's {@code --optimize}).
	 * Every level but {@link OptimizeLevel#NONE} dead-code-eliminates the finished class
	 * with {@link JvmClassShaker}: methods unreachable from {@code main} (and any static
	 * field only they reference) are dropped and the constant pool is compacted.
	 * {@link OptimizeLevel#SIZE} is accepted and equals {@link OptimizeLevel#DEFAULT}
	 * here: this backend has nothing that spends bytes on speed -- the emissions the
	 * level declines are wasm-GC ones, and the same program's JVM bytecode is a third the
	 * size of its WASM to begin with.
	 */
	public JvmLispCompiler(String className, boolean dynamic, OptimizeLevel optimize) {
		this(className, dynamic, optimize, false);
	}

	/**
	 * Create a new JVM compiler targeting the given class name.
	 * @param className the fully qualified class name for the generated class
	 * @param dynamic when {@code true}, unresolved function calls and variable references
	 * are resolved at runtime against the embedded {@code eval} global environment (late
	 * binding); see {@link #JvmLispCompiler(String, boolean)}
	 * @param optimize dead-code elimination and what the class is optimized FOR; see
	 * {@link #JvmLispCompiler(String, boolean, OptimizeLevel)}
	 * @param simdAccel when {@code true} ({@code --simd}), the six vectorizable
	 * {@code vec:} kernels
	 * ({@code add}/{@code sub}/{@code mul}/{@code scale}/{@code dot}/ {@code sum}) are
	 * lowered at their call sites to an embedded {@code jdk.incubator.vector} bridge
	 * ({@link JvmSimdVectorTemplate}) instead of the scalar {@code vec.lisp} reference.
	 * Running such a class requires {@code java --add-modules jdk.incubator.vector}.
	 */
	public JvmLispCompiler(String className, boolean dynamic, OptimizeLevel optimize, boolean simdAccel) {
		this(className, dynamic, optimize, simdAccel, false);
	}

	/**
	 * Create a new JVM compiler targeting the given class name.
	 * @param className the fully qualified class name for the generated class
	 * @param dynamic when {@code true}, unresolved function calls and variable references
	 * are resolved at runtime against the embedded {@code eval} global environment (late
	 * binding); see {@link #JvmLispCompiler(String, boolean)}
	 * @param optimize dead-code elimination and what the class is optimized FOR; see
	 * {@link #JvmLispCompiler(String, boolean, OptimizeLevel)}
	 * @param simdAccel the {@code --simd} lowering; see
	 * {@link #JvmLispCompiler(String, boolean, OptimizeLevel, boolean)}
	 * @param blasAccel when {@code true} ({@code --blas}), the {@code linalg:} matrix
	 * product is lowered at its call sites to an embedded CBLAS bridge
	 * ({@link JvmBlasTemplate}), which binds a tuned library out of the OS at run time
	 * and declines to whatever is below it -- the {@code --simd} kernel or the scalar
	 * defun -- when there is none. Orthogonal to {@code simdAccel}: either, both or
	 * neither.
	 */
	public JvmLispCompiler(String className, boolean dynamic, OptimizeLevel optimize, boolean simdAccel,
			boolean blasAccel) {
		this(className, dynamic, optimize, simdAccel, blasAccel, false);
	}

	/**
	 * Create a new JVM compiler targeting the given class name.
	 * @param className the fully qualified class name for the generated class
	 * @param dynamic when {@code true}, unresolved function calls and variable references
	 * are resolved at runtime against the embedded {@code eval} global environment (late
	 * binding); see {@link #JvmLispCompiler(String, boolean)}
	 * @param optimize dead-code elimination and what the class is optimized FOR; see
	 * {@link #JvmLispCompiler(String, boolean, OptimizeLevel)}
	 * @param simdAccel the {@code --simd} lowering; see
	 * {@link #JvmLispCompiler(String, boolean, OptimizeLevel, boolean)}
	 * @param blasAccel the {@code --blas} lowering; see
	 * {@link #JvmLispCompiler(String, boolean, OptimizeLevel, boolean, boolean)}
	 * @param gpuAccel when {@code true} ({@code --gpu}), the matrix-by-matrix case of the
	 * {@code linalg:} product is lowered at its call sites to an embedded device bridge
	 * ({@link JvmGpuTemplate} over the injected {@code am.ik.gpu}), which offers the
	 * product to an NVIDIA GPU and declines to whatever is below it -- the CBLAS bridge,
	 * the {@code --simd} kernel or the scalar defun -- when there is no device or the
	 * product is one it does not take. Orthogonal to both flags above: any combination.
	 */
	public JvmLispCompiler(String className, boolean dynamic, OptimizeLevel optimize, boolean simdAccel,
			boolean blasAccel, boolean gpuAccel) {
		this(className, dynamic, optimize, simdAccel, blasAccel, gpuAccel, false);
	}

	/**
	 * Create a new JVM compiler targeting the given class name.
	 * @param className the fully qualified class name for the generated class
	 * @param dynamic when {@code true}, unresolved function calls and variable references
	 * are resolved at runtime against the embedded {@code eval} global environment (late
	 * binding); see {@link #JvmLispCompiler(String, boolean)}
	 * @param optimize dead-code elimination and what the class is optimized FOR; see
	 * {@link #JvmLispCompiler(String, boolean, OptimizeLevel)}
	 * @param simdAccel the {@code --simd} lowering; see
	 * {@link #JvmLispCompiler(String, boolean, OptimizeLevel, boolean)}
	 * @param blasAccel the {@code --blas} lowering; see
	 * {@link #JvmLispCompiler(String, boolean, OptimizeLevel, boolean, boolean)}
	 * @param gpuAccel the {@code --gpu} lowering; see
	 * {@link #JvmLispCompiler(String, boolean, OptimizeLevel, boolean, boolean, boolean)}
	 * @param parallelAccel when {@code true} ({@code --parallel}), the {@code --simd}
	 * bridge's GEMV / GEMM call sites ({@code vec:matvec}, {@code vec:matvec-into},
	 * {@code linalg:dot}, the stacked {@code linalg:matmul}) bind to the entries that
	 * split their rows across {@code RONTOLISP_THREADS} threads -- the same row chains,
	 * so the same bits ({@code .kb/simd-parallel.md}). A modifier of {@code simdAccel},
	 * which it therefore requires; the emitted bytes differ by those method names alone
	 */
	public JvmLispCompiler(String className, boolean dynamic, OptimizeLevel optimize, boolean simdAccel,
			boolean blasAccel, boolean gpuAccel, boolean parallelAccel) {
		if (parallelAccel && !simdAccel) {
			throw new IllegalArgumentException(
					"--parallel splits the --simd kernels across threads, so it needs --simd");
		}
		this.className = className;
		this.dynamic = dynamic;
		this.optimize = optimize;
		this.simdAccel = simdAccel;
		this.blasAccel = blasAccel;
		this.gpuAccel = gpuAccel;
		this.parallelAccel = parallelAccel;
	}

	/**
	 * Sets the feature names the compiled program's {@code *features*} starts out
	 * holding. The frontend passes the set it READ the program with, so a
	 * {@code (member :rontolisp-component *features*)} at run time answers what the
	 * {@code #+rontolisp-component} beside it answered at read time. Left alone, the
	 * backend's base set stands ({@link LispMacroExpander#backendFeatures}).
	 * @param features the feature names, without the leading colon
	 * @return this compiler
	 */
	public JvmLispCompiler runtimeFeatures(List<String> features) {
		this.runtimeFeatures = List.copyOf(features);
		return this;
	}

	/**
	 * Compile a library class instead of a command: no {@code main} method is emitted
	 * (the CLI's {@code --no-main}, the twin of the WASM side's {@code --no-wasi} reactor
	 * turn). The program must declare at least one {@code rontolisp:jvm-export} —
	 * {@code main} is the only tree-shaker root an unexported program has, so a main-less
	 * class without exports would shake to nothing — and its top level runs in
	 * {@code <clinit>}, i.e. once, when the class is initialized by the first call into
	 * it (a class with exports runs its top level there whether or not {@code main} is
	 * kept; see {@code .kb/jvm-export.md}).
	 * @param noMain whether to omit the {@code main} entry point
	 * @return this compiler
	 */
	public JvmLispCompiler noMain(boolean noMain) {
		this.noMain = noMain;
		return this;
	}

	/**
	 * Selects servlet mode ({@code -o app.war}). The program must serve (a
	 * {@code rontolisp:http-handler} directive or the {@code %http-server-start} seam): a
	 * war with nothing for the container to call is refused at compile time. The top
	 * level moves into {@code <clinit>} exactly as an export does -- the container's
	 * initializer triggers it through {@code Class.forName} -- and the directive stores
	 * the handler funcref and returns instead of calling the blocking {@code serve}: the
	 * container owns the port.
	 * @param servlet whether to compile for a servlet container
	 * @return this compiler
	 */
	public JvmLispCompiler servlet(boolean servlet) {
		this.servletMode = servlet;
		return this;
	}

	/**
	 * The runtime class files the compiled class needs BESIDE it — the packed float-array
	 * handle a {@code :float-vector} / {@code :float-matrix} export hands out with its
	 * marshalling seam, the embedded HTTP server a {@code rontolisp:http-handler} program
	 * serves through, and the {@code equalp} key fold a program that writes
	 * {@code :test 'equalp} places its keys by. Empty unless the program does one of
	 * those, so an ordinary compilation still produces exactly one file.
	 *
	 * <p>
	 * They are written at their canonical names rather than renamed into the program's
	 * package ({@link JvmRuntimeClassFiles}), and the {@code runtime} package they come
	 * from imports nothing, which is what makes the output run with no rontolisp jar on
	 * the classpath ({@code .kb/jvm-export.md}). Valid after {@link #compile}.
	 * @return each class file's path within an output tree (or jar), mapped to its bytes
	 */
	public Map<String, byte[]> runtimeClassFiles() {
		if (!this.needsHandleRuntime && !this.needsHttpRuntime && !this.needsHashFoldRuntime) {
			return Map.of();
		}
		Map<String, byte[]> files = new LinkedHashMap<>();
		if (this.needsHandleRuntime) {
			files.putAll(JvmExportRuntimeBuilder.runtimeClassFiles());
		}
		if (this.needsHashFoldRuntime) {
			files.putAll(JvmRuntimeClassFiles.read(JvmHashRuntimeBuilder.RUNTIME_CLASS_FILES));
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
				signal.oversized.forEach((name, size) -> {
					AstOutliner.Budget known = outline.get(name);
					outline.put(name, known == null ? new AstOutliner.Budget(size, OUTLINE_TARGET_BYTES)
							: new AstOutliner.Budget(known.measuredBytes(), known.targetBytes() * 2 / 3));
				});
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
		// Whether *print-case* is in play. Decided on the SURFACE program, like the scan
		// that gives the variable its defvar, and threaded into the expression compiler
		// so every printing operator routes through the case-applying renderer.
		boolean printCase = LispMacroExpander.usesPrintCase(program);
		// The dispatch narrower drops generic-function branches no call site can select
		// (compiler/GenericDispatchNarrowing); only an optimizing, early-bound compile
		// may narrow -- under --dynamic any name resolves at run time.
		program = LispMacroExpander.expandTopLevelDefinitions(program, structAccessors, closRegistry,
				packageResolver::spellsAsExternal, this.dynamic, false,
				this.optimize.eliminatesDeadCode() && !this.dynamic
						? new am.ik.rontolisp.compiler.GenericDispatchNarrowing() : null);
		if (System.getProperty("rontolisp.debug.dump-program") != null) {
			for (LispVal form : program) {
				System.err.println(form.print());
			}
		}
		// A generic function whose name is a compiler-lowered built-in (fast-io's close
		// methods): rename its dispatcher, keep the built-in as the default method, and
		// route the program's call sites through it. No-op without such a generic.
		program = ShadowedBuiltins.process(program, closRegistry);
		// Whether an instance value can exist in this class at all. The predicates and
		// _equal need the answer BEFORE any body is compiled (their shape changes), and
		// with the gate off nothing they would guard against can be constructed -- so an
		// instance-free program stays byte-identical to a build that never knew about
		// instances. Restart mode forces it on: the signal hook synthesizes simple-*
		// instances for plain string signals.
		boolean mayUseInstances = LispMacroExpander.mayCreateInstances(program, closRegistry) || restartMode;
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
		program = LambdaLists.desugarProgram(program);
		// Create the %mv-spill global (a top-level setq) when the program uses a
		// multiple-value operator: the expansions read/write it across functions.
		program = LispMacroExpander.injectMvSpillGlobal(program, this.runtimeFeatures);
		ConstantPool cp = new ConstantPool();
		ClassConstant thisClass = cp.addClass(cp.addUtf8(this.className));
		// The internal-name package prefix of the generated class ("" for the default
		// package, otherwise e.g. "com/example/"): every embedded acceleration/interop
		// bridge is renamed into it, because Lookup.defineClass(byte[]) requires the
		// defined class to share the lookup class's package.
		int classNameSlash = this.className.lastIndexOf('/');
		String bridgePackagePrefix = classNameSlash < 0 ? "" : this.className.substring(0, classNameSlash + 1);
		ClassConstant objectClass = cp.addClass(cp.addUtf8("java/lang/Object"));

		ClassConstant systemClass = cp.addClass(cp.addUtf8("java/lang/System"));
		FieldrefConstant systemOut = cp.addFieldref(systemClass,
				cp.addNameAndType(cp.addUtf8("out"), cp.addUtf8("Ljava/io/PrintStream;")));
		ClassConstant printStreamClass = cp.addClass(cp.addUtf8("java/io/PrintStream"));

		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		MethodrefConstant longValueOf = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)Ljava/lang/Long;")));
		MethodrefConstant longValue = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J")));

		MethodrefConstant printlnStr = cp.addMethodref(printStreamClass,
				cp.addNameAndType(cp.addUtf8("println"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant printStr = cp.addMethodref(printStreamClass,
				cp.addNameAndType(cp.addUtf8("print"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant printlnVoid = cp.addMethodref(printStreamClass,
				cp.addNameAndType(cp.addUtf8("println"), cp.addUtf8("()V")));

		ClassConstant integerClass = cp.addClass(cp.addUtf8("java/lang/Integer"));
		MethodrefConstant integerValueOf = cp.addMethodref(integerClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(I)Ljava/lang/Integer;")));
		MethodrefConstant integerValue = cp.addMethodref(integerClass,
				cp.addNameAndType(cp.addUtf8("intValue"), cp.addUtf8("()I")));

		ClassConstant doubleClass = cp.addClass(cp.addUtf8("java/lang/Double"));
		MethodrefConstant doubleValueOf = cp.addMethodref(doubleClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(D)Ljava/lang/Double;")));
		MethodrefConstant doubleToString = cp.addMethodref(doubleClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));

		ClassConstant numberClass = cp.addClass(cp.addUtf8("java/lang/Number"));
		MethodrefConstant numberDoubleValue = cp.addMethodref(numberClass,
				cp.addNameAndType(cp.addUtf8("doubleValue"), cp.addUtf8("()D")));

		Utf8Constant lispToStringName = cp.addUtf8("_lispToString");
		Utf8Constant lispToStringDescUtf = cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/String;");
		MethodrefConstant lispToStringMethod = cp.addMethodref(thisClass,
				cp.addNameAndType(lispToStringName, lispToStringDescUtf));
		Utf8Constant consToStringName = cp.addUtf8("_consToString");
		Utf8Constant consToStringDescUtf = cp.addUtf8("([Ljava/lang/Object;)Ljava/lang/String;");
		MethodrefConstant consToStringMethod = cp.addMethodref(thisClass,
				cp.addNameAndType(consToStringName, consToStringDescUtf));
		Utf8Constant lispToDisplayStringName = cp.addUtf8("_lispToDisplayString");
		MethodrefConstant lispToDisplayStringMethod = cp.addMethodref(thisClass,
				cp.addNameAndType(lispToDisplayStringName, lispToStringDescUtf));
		Utf8Constant consToDisplayStringName = cp.addUtf8("_consToDisplayString");
		MethodrefConstant consToDisplayStringMethod = cp.addMethodref(thisClass,
				cp.addNameAndType(consToDisplayStringName, consToStringDescUtf));
		Utf8Constant appendName = cp.addUtf8("_append");
		Utf8Constant appendDescUtf = cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		MethodrefConstant appendMethod = cp.addMethodref(thisClass, cp.addNameAndType(appendName, appendDescUtf));
		ClassConstant stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
		MethodrefConstant stringCharAt = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("charAt"), cp.addUtf8("(I)C")));
		MethodrefConstant stringLength = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("length"), cp.addUtf8("()I")));
		MethodrefConstant stringSubstring = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("substring"), cp.addUtf8("(II)Ljava/lang/String;")));
		// Used by _lispToDisplayString to cut a symbol's package qualifier / marker: the
		// princ spelling is everything after the last colon.
		MethodrefConstant stringLastIndexOf = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("lastIndexOf"), cp.addUtf8("(I)I")));
		MethodrefConstant objectEquals = cp.addMethodref(objectClass,
				cp.addNameAndType(cp.addUtf8("equals"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		// CHARACTER runtime representation references (used by _lispToString /
		// _lispToDisplayString to print the #\name form and the bare glyph,
		// respectively). A CHARACTER is a length-1 int[]{codePoint} (see
		// JvmEmitHelper.boxCodePoint) -- the discriminator is INSTANCEOF [I. The classic
		// java/lang/Character is still cached below because the char builtins delegate
		// to Character.toUpperCase(int) / Character.isLetter(int) / Character.digit(int,
		// int) / Character.toString(int) for JDK-provided semantics.
		ClassConstant charBoxClass = cp.addClass(cp.addUtf8("[I"));
		ClassConstant characterClass = cp.addClass(cp.addUtf8("java/lang/Character"));
		MethodrefConstant characterToString = cp.addMethodref(characterClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("(I)Ljava/lang/String;")));
		Utf8Constant charPrin1Name = cp.addUtf8("_charPrin1");
		Utf8Constant charPrin1Desc = cp.addUtf8("(I)Ljava/lang/String;");
		MethodrefConstant charPrin1Method = cp.addMethodref(thisClass, cp.addNameAndType(charPrin1Name, charPrin1Desc));
		ClassConstant mathClass = cp.addClass(cp.addUtf8("java/lang/Math"));
		MethodrefConstant mathAbsLong = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("abs"), cp.addUtf8("(J)J")));
		MethodrefConstant mathAbsDouble = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("abs"), cp.addUtf8("(D)D")));
		MethodrefConstant mathMinLong = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("min"), cp.addUtf8("(JJ)J")));
		MethodrefConstant mathMinDouble = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("min"), cp.addUtf8("(DD)D")));
		MethodrefConstant mathMaxLong = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("max"), cp.addUtf8("(JJ)J")));
		MethodrefConstant mathMaxDouble = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("max"), cp.addUtf8("(DD)D")));
		MethodrefConstant mathFloor = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("floor"), cp.addUtf8("(D)D")));
		MethodrefConstant mathCeil = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("ceil"), cp.addUtf8("(D)D")));
		MethodrefConstant mathRint = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("rint"), cp.addUtf8("(D)D")));

		// Math helper references for sqrt/exp/log/trig/expt/signum compilers.
		Map<String, MethodrefConstant> mathOps = JvmMathFnCompiler.buildOps(cp, mathClass);

		// System helper references for the time / getenv compilers.
		Map<String, MethodrefConstant> systemOps = new java.util.LinkedHashMap<>();
		systemOps.put("currentTimeMillis",
				cp.addMethodref(systemClass, cp.addNameAndType(cp.addUtf8("currentTimeMillis"), cp.addUtf8("()J"))));
		systemOps.put("nanoTime",
				cp.addMethodref(systemClass, cp.addNameAndType(cp.addUtf8("nanoTime"), cp.addUtf8("()J"))));
		systemOps.put("getenv", cp.addMethodref(systemClass,
				cp.addNameAndType(cp.addUtf8("getenv"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;"))));

		// read-line helper
		ClassConstant bufferedReaderClass = cp.addClass(cp.addUtf8("java/io/BufferedReader"));
		ClassConstant inputStreamReaderClass = cp.addClass(cp.addUtf8("java/io/InputStreamReader"));
		MethodrefConstant brInit = cp.addMethodref(bufferedReaderClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/io/Reader;)V")));
		MethodrefConstant brReadLine = cp.addMethodref(bufferedReaderClass,
				cp.addNameAndType(cp.addUtf8("readLine"), cp.addUtf8("()Ljava/lang/String;")));
		MethodrefConstant isrInit = cp.addMethodref(inputStreamReaderClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/io/InputStream;)V")));
		FieldrefConstant systemIn = cp.addFieldref(systemClass,
				cp.addNameAndType(cp.addUtf8("in"), cp.addUtf8("Ljava/io/InputStream;")));
		MethodrefConstant stringConcat = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("concat"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;")));
		Utf8Constant stdinReaderFieldName = cp.addUtf8("_stdinReader");
		Utf8Constant stdinReaderFieldDesc = cp.addUtf8("Ljava/io/BufferedReader;");
		FieldrefConstant stdinReaderField = cp.addFieldref(thisClass,
				cp.addNameAndType(stdinReaderFieldName, stdinReaderFieldDesc));
		Utf8Constant readLineHelperName = cp.addUtf8("_readLine");
		Utf8Constant readLineHelperDesc = cp.addUtf8("()Ljava/lang/Object;");
		MethodrefConstant readLineHelperMethod = cp.addMethodref(thisClass,
				cp.addNameAndType(readLineHelperName, readLineHelperDesc));

		// The async/await runtime (JvmAsyncRuntimeBuilder): %async-run (the lowered
		// async-defun/async-lambda), the generic _await resolver, the first-class stream
		// operations and the futurep/streamp predicates all live in one builder, emitted
		// when the program touches any of them (http-handler included: its handle()
		// awaits the handler's future and drains a stream response body). _fetch is
		// separate (JvmFetchRuntimeBuilder) and additionally gates _await's HttpResponse
		// branch so fetch-free programs never load java.net.http classes.
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
		MethodrefConstant fetchHelperMethod = usesFetch
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmFetchRuntimeBuilder.METHOD_NAME),
						cp.addUtf8(JvmFetchRuntimeBuilder.METHOD_DESC)))
				: null;
		MethodrefConstant awaitHelperMethod = usesAsyncRuntime
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmAsyncRuntimeBuilder.AWAIT_METHOD),
						cp.addUtf8(JvmAsyncRuntimeBuilder.AWAIT_DESC)))
				: null;
		MethodrefConstant asyncRunHelperMethod = usesAsyncRuntime
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmAsyncRuntimeBuilder.ASYNC_RUN_METHOD),
						cp.addUtf8(JvmAsyncRuntimeBuilder.ASYNC_RUN_DESC)))
				: null;
		MethodrefConstant futurepHelperMethod = usesAsyncRuntime
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmAsyncRuntimeBuilder.FUTUREP_METHOD),
						cp.addUtf8(JvmAsyncRuntimeBuilder.UNARY_DESC)))
				: null;
		MethodrefConstant streampHelperMethod = usesAsyncRuntime
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmAsyncRuntimeBuilder.STREAMP_METHOD),
						cp.addUtf8(JvmAsyncRuntimeBuilder.UNARY_DESC)))
				: null;
		MethodrefConstant makeStreamHelperMethod = usesAsyncRuntime
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmAsyncRuntimeBuilder.MAKE_STREAM_METHOD),
						cp.addUtf8(JvmAsyncRuntimeBuilder.MAKE_STREAM_DESC)))
				: null;
		MethodrefConstant streamNewHelperMethod = usesAsyncRuntime
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmAsyncRuntimeBuilder.STREAM_NEW_METHOD),
						cp.addUtf8(JvmAsyncRuntimeBuilder.STREAM_NEW_DESC)))
				: null;
		MethodrefConstant streamReadHelperMethod = usesAsyncRuntime
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmAsyncRuntimeBuilder.STREAM_READ_METHOD),
						cp.addUtf8(JvmAsyncRuntimeBuilder.UNARY_DESC)))
				: null;
		MethodrefConstant streamWriteHelperMethod = usesAsyncRuntime
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmAsyncRuntimeBuilder.STREAM_WRITE_METHOD),
						cp.addUtf8(JvmAsyncRuntimeBuilder.STREAM_WRITE_DESC)))
				: null;
		MethodrefConstant streamCloseHelperMethod = usesAsyncRuntime
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmAsyncRuntimeBuilder.STREAM_CLOSE_METHOD),
						cp.addUtf8(JvmAsyncRuntimeBuilder.UNARY_DESC)))
				: null;
		MethodrefConstant drainBodyHelperMethod = usesAsyncRuntime
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmAsyncRuntimeBuilder.DRAIN_BODY_METHOD),
						cp.addUtf8(JvmAsyncRuntimeBuilder.UNARY_DESC)))
				: null;
		MethodrefConstant waitForHelperMethod = usesAsyncRuntime
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmAsyncRuntimeBuilder.WAIT_FOR_METHOD),
						cp.addUtf8(JvmAsyncRuntimeBuilder.UNARY_DESC)))
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
		// Strict UTF-8 decode helper: emitted only when the program references the
		// internal %octets-to-string-strict primitive (the prelude's lenient octet
		// decoder is its one caller), so a program that never turns bytes into text
		// keeps byte-identical output. Gated on ITS OWN name rather than riding
		// usesAsyncRuntime: %octets-to-string is an ordinary function, reachable from a
		// program that spawns nothing.
		boolean usesOctetsStrict = programUsesSymbol(program, LispNames.OCTETS_TO_STRING_STRICT_INTERNAL_QUALIFIED);
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
		MethodrefConstant tcpConnectHelperMethod = usesSockets
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmSocketRuntimeBuilder.TCP_CONNECT_METHOD),
						cp.addUtf8(JvmSocketRuntimeBuilder.TCP_CONNECT_DESC)))
				: null;
		MethodrefConstant tcpListenHelperMethod = usesSockets
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmSocketRuntimeBuilder.TCP_LISTEN_METHOD),
						cp.addUtf8(JvmSocketRuntimeBuilder.TCP_LISTEN_DESC)))
				: null;
		MethodrefConstant tcpAcceptHelperMethod = usesSockets
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmSocketRuntimeBuilder.TCP_ACCEPT_METHOD),
						cp.addUtf8(JvmSocketRuntimeBuilder.TCP_ACCEPT_DESC)))
				: null;
		MethodrefConstant tcpLocalPortHelperMethod = usesSockets ? cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(JvmSocketRuntimeBuilder.TCP_LOCAL_PORT_METHOD),
						cp.addUtf8(JvmSocketRuntimeBuilder.TCP_LOCAL_PORT_DESC)))
				: null;
		MethodrefConstant tcpLocalAddressHelperMethod = usesSockets ? cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(JvmSocketRuntimeBuilder.TCP_LOCAL_ADDRESS_METHOD),
						cp.addUtf8(JvmSocketRuntimeBuilder.TCP_LOCAL_ADDRESS_DESC)))
				: null;
		MethodrefConstant tcpPeerAddressHelperMethod = usesSockets ? cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(JvmSocketRuntimeBuilder.TCP_PEER_ADDRESS_METHOD),
						cp.addUtf8(JvmSocketRuntimeBuilder.TCP_PEER_ADDRESS_DESC)))
				: null;
		MethodrefConstant tcpPeerPortHelperMethod = usesSockets
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmSocketRuntimeBuilder.TCP_PEER_PORT_METHOD),
						cp.addUtf8(JvmSocketRuntimeBuilder.TCP_PEER_PORT_DESC)))
				: null;
		MethodrefConstant tcpSetTimeoutHelperMethod = usesSockets ? cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(JvmSocketRuntimeBuilder.TCP_SET_TIMEOUT_METHOD),
						cp.addUtf8(JvmSocketRuntimeBuilder.TCP_SET_TIMEOUT_DESC)))
				: null;
		MethodrefConstant tlsConnectHelperMethod = usesSockets
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmSocketRuntimeBuilder.TLS_CONNECT_METHOD),
						cp.addUtf8(JvmSocketRuntimeBuilder.TLS_CONNECT_DESC)))
				: null;
		MethodrefConstant tlsUpgradeHelperMethod = usesSockets
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmSocketRuntimeBuilder.TLS_UPGRADE_METHOD),
						cp.addUtf8(JvmSocketRuntimeBuilder.TLS_UPGRADE_DESC)))
				: null;
		MethodrefConstant tlsListenHelperMethod = usesSockets
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmSocketRuntimeBuilder.TLS_LISTEN_METHOD),
						cp.addUtf8(JvmSocketRuntimeBuilder.TLS_LISTEN_DESC)))
				: null;
		MethodrefConstant tlsListenP12HelperMethod = usesSockets ? cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(JvmSocketRuntimeBuilder.TLS_LISTEN_P12_METHOD),
						cp.addUtf8(JvmSocketRuntimeBuilder.TLS_LISTEN_P12_DESC)))
				: null;

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
		ClassConstant x509TrustManagerClass = usesTlsConnect ? cp.addClass(cp.addUtf8("javax/net/ssl/X509TrustManager"))
				: null;
		ClassConstant x509CertificateClass = usesTlsConnect
				? cp.addClass(cp.addUtf8("java/security/cert/X509Certificate")) : null;
		MethodrefConstant objectInitRef = needsInstanceCtor
				? cp.addMethodref(objectClass, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V"))) : null;
		Utf8Constant instanceInitName = needsInstanceCtor ? cp.addUtf8("<init>") : null;
		Utf8Constant instanceInitDesc = needsInstanceCtor ? cp.addUtf8("()V") : null;
		Utf8Constant checkClientName = usesTlsConnect ? cp.addUtf8("checkClientTrusted") : null;
		Utf8Constant checkServerName = usesTlsConnect ? cp.addUtf8("checkServerTrusted") : null;
		Utf8Constant checkTrustedDesc = usesTlsConnect
				? cp.addUtf8("([Ljava/security/cert/X509Certificate;Ljava/lang/String;)V") : null;
		Utf8Constant acceptedIssuersName = usesTlsConnect ? cp.addUtf8("getAcceptedIssuers") : null;
		Utf8Constant acceptedIssuersDesc = usesTlsConnect ? cp.addUtf8("()[Ljava/security/cert/X509Certificate;")
				: null;

		// java: interop runtime: emitted only when the program uses one of the five
		// java: functions. It embeds the (renamed) JavaBridgeTemplate bytecode and
		// forces the eval runtime (the bridge applies Lisp callables through _apply).
		boolean usesJava = programUsesAnyJavaOp(program);
		final JvmJavaRuntimeBuilder.@Nullable JavaRuntime javaRuntime = usesJava
				? JvmJavaRuntimeBuilder.build(cp, thisClass, stringConcat, bridgePackagePrefix) : null;

		// objc: runtime: emitted only when the program uses one of the seven objc: verbs
		// (an appkit: program does, through the spliced appkit.lisp). It embeds the whole
		// am.ik.objc library plus the bridge and the handle, renamed into this class's
		// package (JvmObjcRuntimeBuilder), and forces the eval runtime: a method of
		// objc:define-class and the body of objc:on-main are applied through _apply from
		// an upcall on thread 0.
		boolean usesObjc = programUsesAnyObjcOp(program);
		final JvmObjcRuntimeBuilder.@Nullable ObjcRuntime objcRuntime = usesObjc
				? JvmObjcRuntimeBuilder.build(cp, thisClass, stringConcat, bridgePackagePrefix) : null;

		// ffi: runtime: emitted only when the program uses one of the ffi: verbs (a
		// cffi: program does, through the spliced cffi-sys backend). It embeds the whole
		// am.ik.ffi library plus the bridge and the pointer class, renamed into this
		// class's package (JvmFfiRuntimeBuilder), and forces the eval runtime: an
		// ffi:callback's Lisp function is applied through _apply from an upcall.
		boolean usesFfi = programUsesAnyFfiOp(program);
		final JvmFfiRuntimeBuilder.@Nullable FfiRuntime ffiRuntime = usesFfi
				? JvmFfiRuntimeBuilder.build(cp, thisClass, stringConcat, bridgePackagePrefix) : null;

		ClassConstant objectArrayClass = cp.addClass(cp.addUtf8("[Ljava/lang/Object;"));
		final JvmHttpHandlerRuntimeBuilder.@Nullable HttpHandlerRuntime httpHandlerRuntime = usesHttpHandler
				? JvmHttpHandlerRuntimeBuilder.build(cp, thisClass, objectArrayClass, stringLength, stringConcat,
						am.ik.rontolisp.compiler.ClackEnv.usesBufferedBody(program))
				: null;
		ClassConstant stringBuilderClass = cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
		MethodrefConstant longToString = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		MethodrefConstant objectToString = cp.addMethodref(objectClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		MethodrefConstant sbInitStr = cp.addMethodref(stringBuilderClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant sbAppendStr = cp.addMethodref(stringBuilderClass,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/StringBuilder;")));
		MethodrefConstant sbToString = cp.addMethodref(stringBuilderClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		ClassConstant ratioArrayClass = cp.addClass(cp.addUtf8("[Ljava/math/BigInteger;"));
		ConstantPool.StringConstant nilStr = cp.addString("NIL");
		ConstantPool.StringConstant funcStr = cp.addString("#<function>");
		ConstantPool.StringConstant slashStr = cp.addString("/");
		ConstantPool.StringConstant openParenStr = cp.addString("(");
		ConstantPool.StringConstant closeParenStr = cp.addString(")");
		ConstantPool.StringConstant spaceStr = cp.addString(" ");
		ConstantPool.StringConstant dotStr = cp.addString(" . ");

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
				defuns.add(extractSetqLambda(LispMacroExpander.expandDefun(cons)));
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
		// Whether the packed (unsigned-byte 8|16|32) vector builder is reachable: a
		// concatenate whose result type spells a packed element type lowers to a call to
		// it, and so does the #'concatenate wrapper's own vector arm (its designator is a
		// runtime value, so it re-does the width dispatch there). Forces usesIntArray
		// below -- the helper's make-array calls are in the WRAPPER, which the source
		// scans below never see.
		boolean usesSeqIntVector = ConcatenateForms.needsSeqIntVector(program, closRegistry) || program.stream()
			.anyMatch(expr -> BuiltinFunctionWrappers.referencesFunctionValue(expr, LispNames.CONCATENATE));
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
		// :test 'equalp is emitted exactly as it was before the fold existed -- and the
		// travelling RontoHashTable stays out of its output.
		boolean usesEqualpHashTables = LispMacroExpander.programMakesEqualpHashTable(program)
				|| forcedGroups.contains(GROUP_HASH_EQUALP);
		boolean usesHashTables = programUsesAnyHashOp(program) || forcedGroups.contains(GROUP_HASH) || usesHttpHandler
				|| usesEqualpHashTables;
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
		// name registry a designator it cannot read: a computed funcall/apply target, or
		// a name the program reads or builds at run time.
		boolean usesApplyingWrapperValue = LispMacroExpander.usesRuntimeFunctionDesignator(program) || nameResolvable
				|| symbolBuilders || BuiltinFunctionWrappers.APPLY_USING_FUNCTIONS.stream()
					.anyMatch(op -> referencesFunctionDesignator(resolvedProgram, closRegistry, op));
		// When the program uses eval, the runtime _apply dispatches by argument count, so
		// every arity up to the maximum callable must have a dispatch method. The apply
		// built-in reuses _apply, so it forces the eval runtime to be emitted as well.
		// boundp/symbol-value/fboundp resolve symbols at runtime against the eval
		// runtime's global env mirror (_genv) and function registry (_lookup/_fenv), so
		// they force the eval runtime like apply does. fmakunbound writes the tombstone
		// into that same _fenv.
		// multiple-value-call forces apply too: its expansion spreads a spill
		// producer's dynamic value count with (apply fn (append ...)).
		boolean usesEval = programUsesEval(program) || usesLoad || this.dynamic || usesJava || usesObjc || usesFfi
				|| programUsesSymbol(program, LispNames.APPLY) || programUsesSymbol(program, LispNames.BOUNDP)
				|| programUsesSymbol(program, LispNames.SYMBOL_VALUE) || programUsesSymbol(program, LispNames.FBOUNDP)
				|| programUsesSymbol(program, LispNames.FMAKUNBOUND)
				// (setf (symbol-function ...)) writes _fenv (the raw place shape is
				// scanned: the lowering to %set-symbol-function happens per expression,
				// after this gate).
				|| LispMacroExpander.usesSymbolFunctionWrite(program)
				|| programUsesSymbol(program, LispNames.MULTIPLE_VALUE_CALL)
				// The injected wrapper bodies that are (apply f r): the wrappers and the
				// runtime they call are gated on the same reference (see
				// wrapperExcludes).
				|| usesApplyingWrapperValue || forcedGroups.contains(GROUP_EVAL);
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
		if (!usesFuncallValue) {
			wrapperExcludes.add(LispNames.FUNCALL);
		}
		// The map*/every/some family, gated on the eval runtime as a whole rather than on
		// each name: with the runtime OFF nothing can reach a wrapper the program does
		// not spell (and its body would call an _apply that is not there), while with it
		// ON an eval'd (mapcar ...) resolves the name through _lookup and needs every
		// wrapper registered.
		if (!usesEval) {
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
		// %seq-int-vector is the concatenate packed-vector builder, gated the same way.
		if (!usesSeqIntVector) {
			wrapperExcludes.add(LispNames.SEQ_INT_VECTOR);
		}
		// #'error/#'cerror/#'signal/#'warn wrappers forward the datum only (lite), and
		// #'format renders via the runtime control renderer; inject each only when the
		// program takes the operator as a first-class value. Condition :report lambdas
		// live only in the class registry (define-condition is rewritten out of the
		// program) but are re-injected by the error/signal expansions, so they count as
		// references too.
		for (String op : BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS) {
			if (!referencesFunctionValue(program, closRegistry, op)) {
				wrapperExcludes.add(op);
			}
		}
		List<LispVal> wrappers = BuiltinFunctionWrappers.generate(userDefinedNames, wrapperExcludes);
		for (LispVal wrapper : wrappers) {
			defuns.add(extractSetqLambda(wrapper));
		}
		// The shared merge sort, once per program that sorts -- from its own source or
		// from the #'sort wrapper just added, which is why this sits here beside the
		// other shared sequence helpers (.kb/sort.md). No array gate, unlike the two
		// below: its body is car/cdr/rplacd and a funcall of its predicate, so it pulls
		// nothing in. When it is absent JvmExprCompiler keeps the inline sort.
		if (!userDefinedNames.contains(LispNames.SORT_RUNTIME)
				&& (LispMacroExpander.programUsesSort(program) || LispMacroExpander.programUsesSort(wrappers))) {
			defuns.add(extractSetqLambda(LispMacroExpander.sortRuntimeWrapper()));
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
			defuns.add(extractSetqLambda(helper));
		}
		if (!userDefinedNames.contains(LispNames.SUBSEQ_RUNTIME) && arrayGate
				&& (LispMacroExpander.programUsesSubseq(program) || LispMacroExpander.programUsesSubseq(wrappers)
						|| LispMacroExpander.programUsesSubseq(seqOpHelpers))) {
			defuns.add(extractSetqLambda(LispMacroExpander.subseqRuntimeWrapper()));
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
				defuns.add(extractSetqLambda(helper));
			}
		}

		// Collect top-level global variables and give each a dedicated static field.
		// A reference compiles to getstatic from any method body, so a global is
		// readable/assignable from a defun/lambda (not just from main). Field names are
		// prefixed to avoid colliding with runtime helper fields (e.g. _genv).
		SequencedSet<String> globals = new java.util.LinkedHashSet<>(GlobalVarCollector.collect(topLevelExprs));
		// Promote any top-level *free* variable that is also assigned somewhere (a setq /
		// setf bare-symbol place) to a global field. Per Common Lisp such an assignment
		// targets the global namespace; giving it a persistent static field (rather than
		// a
		// main() local) lets the top-level body be split across several methods (below)
		// without a value set in one chunk becoming unreachable from a later one. The
		// free
		// test (scope-aware, via FreeVarAnalyzer) keeps a lexical that a lambda closes
		// over
		// out of the global set, and the assigned test keeps a genuinely-unbound read
		// (e.g. a function name in value position) erroring instead of silently reading
		// nil.
		Set<String> functionNames = new HashSet<>();
		for (DefunDecl defun : defuns) {
			functionNames.add(defun.name);
		}
		Set<String> assignedSymbols = new HashSet<>();
		for (LispVal expr : topLevelExprs) {
			collectAssignedSymbols(expr, assignedSymbols);
		}
		for (String free : FreeVarAnalyzer.findFreeVars(topLevelExprs, Set.of(), functionNames, globals)) {
			if (assignedSymbols.contains(free)) {
				globals.add(free);
			}
		}
		// Special (dynamically bound) variables. Each needs the same global backing store
		// (a let of a special save/restores over it), so union them into the globals set
		// before fields are minted; a let/let* of one of these names becomes a dynamic
		// binding rather than a lexical slot (JvmLetCompiler). Collected over the WHOLE
		// program: a local (declare (special x)) inside a defun body (cl-ppcre's
		// remove-registers-p) must make x a global cell for its free readers too.
		// A SequencedSet, not a plain Set: this order mints the _g$ static fields, and
		// collectDynamicallyBound copies it wholesale when the program has a progv, so an
		// unordered set here makes the emitted class differ per JVM run
		// (.kb/emitted-output-determinism.md).
		SequencedSet<String> specialVars = SpecialVarCollector.collect(program);
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
		Map<String, FieldrefConstant> globalFields = new HashMap<>();
		List<Utf8Constant> globalFieldNameUtfs = new ArrayList<>();
		Utf8Constant globalFieldDescUtf = cp.addUtf8("Ljava/lang/Object;");
		for (String g : globals) {
			Utf8Constant fieldNameUtf = cp.addUtf8("_g$" + mangleMethodName(g));
			globalFieldNameUtfs.add(fieldNameUtf);
			globalFields.put(g, cp.addFieldref(thisClass, cp.addNameAndType(fieldNameUtf, globalFieldDescUtf)));
		}
		// A special that is DYNAMICALLY BOUND somewhere additionally gets a per-thread
		// store (a _d$ ThreadLocal next to its _g$ global default), so concurrent
		// http-handler requests binding the same special do not clobber each other --
		// interpreter parity (its DynamicBindings is a ThreadLocal for the same reason).
		// A special never let-bound keeps the bare static field, so its reads stay a
		// single getstatic and a binding-free program compiles byte-identically.
		SequencedSet<String> boundSpecialVars = SpecialVarCollector.collectDynamicallyBound(program, specialVars);
		if (usesThreads) {
			// Every special becomes runtime-bindable by name through make-thread's
			// bindings alist, so each needs its _d$ ThreadLocal (the _dtl dispatch in
			// JvmThreadRuntimeBuilder). Over-collection is only a small read cost.
			boundSpecialVars.addAll(specialVars);
		}
		final JvmDynVarRuntimeBuilder.@Nullable DynVarRuntime dynVarRuntime = boundSpecialVars.isEmpty() ? null
				: JvmDynVarRuntimeBuilder.build(cp, thisClass, objectArrayClass, boundSpecialVars);

		// Assign funcIds and register in CP
		int[] nextFuncId = { 0 };
		Map<String, FunctionInfo> functions = new HashMap<>();
		for (DefunDecl defun : defuns) {
			int funcId = nextFuncId[0]++;
			String descriptor = "(" + "Ljava/lang/Object;".repeat(defun.paramNames.size()) + ")Ljava/lang/Object;";
			Utf8Constant nameUtf8 = cp.addUtf8(mangleMethodName(defun.name));
			Utf8Constant descUtf8 = cp.addUtf8(descriptor);
			MethodrefConstant methodref = cp.addMethodref(thisClass, cp.addNameAndType(nameUtf8, descUtf8));
			functions.put(defun.name, new FunctionInfo(funcId, defun.paramNames.size(), defun.variadic, false,
					methodref, nameUtf8, descUtf8));
		}

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
		// An equalp table folds its keys through RontoHashTable.equalpKey, so that class
		// travels with the output too -- and with nothing else, since no other program
		// emits a call to it.
		this.needsHashFoldRuntime = usesEqualpHashTables;
		boolean usesFloatArray = programUsesFloatArray(program, closRegistry) || usesRead || this.needsHandleRuntime;

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
				|| usesHttpHandler;
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

		// Whether the array runtime helper group is emitted (the same test that gates
		// its emission below). The mutable-character-vector consumers -- the _eqv
		// normalization, the stringp extension, the per-site _strv calls and the print
		// branch -- all key off this one gate, so an array-free program compiles
		// byte-identically to a build that never knew character vectors.
		// forcedGroups carries the verdict of a previous run whose source scan
		// under-predicted this gate (see compile(List)); it never turns the gate OFF.
		boolean usesArrays = programUsesAnyArrayOp(program) || usesFloatArray || usesIntArray
				|| forcedGroups.contains(GROUP_ARRAYS);
		MethodrefConstant strvMethod = usesArrays ? cp.addMethodref(thisClass, cp
			.addNameAndType(cp.addUtf8(JvmArrayRuntimeBuilder.STRV), cp.addUtf8(JvmArrayRuntimeBuilder.STRV_DESC)))
				: null;

		// Numeric runtime helpers (long arithmetic with automatic BigInteger promotion)
		// The interned layout array of an instance -- the discriminator the structural
		// _equal and _hash arms share, minted once so both see the same constant.
		ClassConstant instanceLayoutClass = mayUseInstances ? cp.addClass(cp.addUtf8("[Ljava/lang/String;")) : null;
		JvmNumericRuntimeBuilder.NumericRuntime numericRuntime = JvmNumericRuntimeBuilder.build(cp, thisClass,
				strvMethod, instanceLayoutClass);

		// --vec: emit the Vector API acceleration bridge only when the program actually
		// references one of the six accelerated vec: kernels (directly or via a spliced
		// mean/norm body). Off by default, so the ordinary scalar vec.lisp is used. The
		// bridge is a self-contained embedded class (like the java: interop bridge); the
		// packed float-array _fv* helpers still render/index its double[] results.
		boolean usesSimd = this.simdAccel && programUsesAnyAcceleratedSimdOp(program);
		final JvmSimdRuntimeBuilder.@Nullable SimdRuntime simdRuntime = usesSimd
				? JvmSimdRuntimeBuilder.build(cp, thisClass, stringConcat, this.parallelAccel, bridgePackagePrefix)
				: null;

		// --blas: emit the CBLAS bridge only when the program actually reaches the
		// linalg: matrix product -- directly, or through the spliced linalg:matmul /
		// linalg:solve bodies, which call linalg:dot themselves and are part of the
		// program by the time this scan runs. Orthogonal to --simd: neither implies the
		// other, and a build with both emits both bridges.
		boolean usesBlas = this.blasAccel && programUsesSymbol(program, JvmLinalgBlas.QUALIFIED_DOT);
		final JvmBlasRuntimeBuilder.@Nullable BlasRuntime blasRuntime = usesBlas
				? JvmBlasRuntimeBuilder.build(cp, thisClass, stringConcat, bridgePackagePrefix) : null;

		// --gpu: the same gate over its own members -- the matrix by matrix case of
		// linalg:dot, the STACKED rank->=3 product behind linalg:matmul, and the twelve
		// element-wise ufuncs whose scalar cost is a libm call -- and the same
		// orthogonality. What it embeds is not one template but am.ik.gpu itself, renamed
		// into this class's package (JvmGpuRuntimeBuilder). The gate has to name every
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
				? JvmGpuRuntimeBuilder.build(cp, thisClass, stringConcat, bridgePackagePrefix) : null;

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
		Set<String> rawGlobalNames = JvmRawGlobals.collect(program, globals, boundSpecialVars, intFusion
				&& !this.dynamic && !usesEval && !usesThreads && !usesHttpHandler && !usesAsyncRuntime && !usesSockets);
		Map<String, JvmIntFusionCompiler.RawLocal> rawGlobals = new HashMap<>();
		List<Utf8Constant> rawGlobalLongFieldNameUtfs = new ArrayList<>();
		List<Utf8Constant> rawGlobalFlagFieldNameUtfs = new ArrayList<>();
		Utf8Constant rawGlobalLongDescUtf = rawGlobalNames.isEmpty() ? null : cp.addUtf8("J");
		Utf8Constant rawGlobalFlagDescUtf = rawGlobalNames.isEmpty() ? null : cp.addUtf8("I");
		for (String g : rawGlobalNames) {
			Utf8Constant longNameUtf = cp.addUtf8("_gr$" + mangleMethodName(g));
			Utf8Constant flagNameUtf = cp.addUtf8("_gk$" + mangleMethodName(g));
			rawGlobalLongFieldNameUtfs.add(longNameUtf);
			rawGlobalFlagFieldNameUtfs.add(flagNameUtf);
			rawGlobals.put(g,
					JvmIntFusionCompiler.RawLocal.fields(
							cp.addFieldref(thisClass,
									cp.addNameAndType(longNameUtf, Objects.requireNonNull(rawGlobalLongDescUtf))),
							Objects.requireNonNull(globalFields.get(g)), cp.addFieldref(thisClass,
									cp.addNameAndType(flagNameUtf, Objects.requireNonNull(rawGlobalFlagDescUtf)))));
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
			.spelledLiterals(spelledLiterals)
			.nextFuncId(nextFuncId)
			.appendMethod(appendMethod)
			.mathAbsLong(mathAbsLong)
			.mathAbsDouble(mathAbsDouble)
			.mathMinLong(mathMinLong)
			.mathMinDouble(mathMinDouble)
			.mathMaxLong(mathMaxLong)
			.mathMaxDouble(mathMaxDouble)
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
			.objcOps(objcRuntime != null ? objcRuntime.ops() : null)
			.ffiOps(ffiRuntime != null ? ffiRuntime.ops() : null)
			.dynamic(this.dynamic)
			.servletMode(this.servletMode)
			.blockExitChannel(blockExitChannel)
			.restartMode(restartMode)
			.signalClauseMatch(signalClauseMatch)
			.printCase(printCase)
			.usesFloatArray(usesFloatArray)
			.typedLoops(!this.optimize.prefersSizeOverSpeed())
			.usesIntArray(usesIntArray)
			.usesPackedSequenceIo(usesPackedSequenceIo)
			.usesArrays(usesArrays)
			.usesHashTables(usesHashTables)
			.usesEqualpHashTables(usesEqualpHashTables)
			.usesSeqString(usesSeqString)
			.mayUseInstances(mayUseInstances)
			.usesSynonymStreams(programUsesSymbol(program, LispNames.MAKE_SYNONYM_STREAM))
			.usesStreamValues(usesStreamValues)
			.mayUseAsyncValues(usesAsyncRuntime)
			.simdOps(simdRuntime != null ? simdRuntime.ops() : null)
			.blasOps(blasRuntime != null ? blasRuntime.ops() : null)
			.gpuOps(gpuRuntime != null ? gpuRuntime.ops() : null)
			.className(this.className)
			.userDefunNames(Set.copyOf(userDefinedNames))
			.warnedClRedefinitions(new HashSet<>())
			.usesFmakunbound(programUsesSymbol(program, LispNames.FMAKUNBOUND))
			.usesProgv(programUsesSymbol(program, LispNames.PROGV))
			.packageTable(packageResolver.runtimePackageTable())
			.packageUseTable(packageResolver.runtimePackageUseTable())
			.globals(globals)
			.specialVars(specialVars)
			.globalFields(globalFields)
			.dynVars(dynVarRuntime)
			.structAccessors(structAccessors)
			.closRegistry(closRegistry);

		// When eval is present, a top-level global variable binding (setq/defvar/...) is
		// mirrored into the eval runtime's global environment via _store, so an eval'd
		// expression can resolve it. Created before Pass 2a because EVERY context gets
		// the ref: the progv lowering maintains that same mirror from any position
		// (its own consumers stay top-level-gated).
		MethodrefConstant evalStoreRef = usesEval
				? cp.addMethodref(thisClass,
						cp.addNameAndType(cp.addUtf8("_store"),
								cp.addUtf8(
										"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")))
				: null;

		// Pass 2a: Compile each defun body
		List<Ctx> funcCtxs = new ArrayList<>();
		for (DefunDecl defun : defuns) {
			Ctx funcCtx = ctxBuilder.build();
			funcCtx.evalStoreRef = evalStoreRef;
			funcCtx.nextLocal = defun.paramNames.size();
			funcCtx.maxLocals = defun.paramNames.size();
			for (int i = 0; i < defun.paramNames.size(); i++) {
				funcCtx.locals.put(defun.paramNames.get(i), i);
			}
			// Determine which params are captured by nested lambdas
			Set<String> capturedVars = FreeVarAnalyzer.findCapturedVars(defun.bodyExprs,
					new HashSet<>(defun.paramNames), functions.keySet());
			funcCtx.boxedVars = capturedVars;
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
				funcCtx.emit(Opcode.ARETURN);
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
		Utf8Constant topChunkDesc = cp.addUtf8("()V");
		List<Ctx> topChunks = new ArrayList<>();
		List<Utf8Constant> topChunkNames = new ArrayList<>();
		List<MethodrefConstant> topChunkRefs = new ArrayList<>();
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
		Ctx chunkCtx = null;
		for (LispVal expr : topLevelExprs) {
			if (chunkCtx == null || chunkCtx.code.size() >= chunkCodeBudget) {
				if (chunkCtx != null) {
					chunkCtx.emit(Opcode.RETURN);
				}
				chunkCtx = ctxBuilder.build();
				chunkCtx.topLevel = true;
				chunkCtx.evalStoreRef = evalStoreRef;
				chunkCtx.shareDefinedGlobals(sharedDefinedGlobals);
				Utf8Constant nameUtf8 = cp.addUtf8("_top$" + topChunks.size());
				topChunks.add(chunkCtx);
				topChunkNames.add(nameUtf8);
				topChunkRefs.add(cp.addMethodref(thisClass, cp.addNameAndType(nameUtf8, topChunkDesc)));
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
				chunkCtx.emit(Opcode.POP);
			}
		}
		if (chunkCtx != null) {
			chunkCtx.emit(Opcode.RETURN);
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
		// The command line's static home, built HERE rather than beside the other
		// runtime helpers because main's own prologue is what fills it: a defun that
		// reads the arguments is an ordinary static method and cannot see main's locals.
		final JvmArgvRuntimeBuilder.@Nullable ArgvRuntime argvRuntime = usesArgv
				? JvmArgvRuntimeBuilder.build(cp, thisClass, objectClass, stringConcat, this.className) : null;
		if (argvRuntime != null) {
			// _argv = args. In main and only in main -- with a jvm-export the top level
			// has already run in <clinit>, before any main could store one, which is the
			// null the helper answers nil for.
			mainCtx.emit(Opcode.ALOAD_0);
			mainCtx.emit(Opcode.PUTSTATIC);
			mainCtx.emitU2(argvRuntime.field().index());
		}
		Ctx topRunnerCtx = null;
		Ctx entryCtx = mainCtx;
		if (topLevelInClinit) {
			topRunnerCtx = ctxBuilder.build();
			topRunnerCtx.evalStoreRef = evalStoreRef;
			entryCtx = topRunnerCtx;
		}
		for (MethodrefConstant ref : topChunkRefs) {
			entryCtx.emit(Opcode.INVOKESTATIC);
			entryCtx.emitU2(ref.index());
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
			entryCtx.emit(Opcode.GETSTATIC);
			entryCtx.emitU2(systemOut.index());
			entryCtx.emit(Opcode.INVOKEVIRTUAL);
			entryCtx.emitU2(cp.addMethodref(cp.addClass(cp.addUtf8("java/io/PrintStream")),
					cp.addNameAndType(cp.addUtf8("flush"), cp.addUtf8("()V")))
				.index());
		}
		entryCtx.emit(Opcode.RETURN);
		// A condition nobody caught reports itself on standard error instead of
		// unwinding out of main as a stack trace through mangled Lisp names. Last, so
		// every handler main already carries dispatches first. In _top$run the same
		// report-and-rethrow surfaces to a Java caller as ExceptionInInitializerError
		// (which also poisons the class permanently) — the reactor's failure shape,
		// stated in the docs rather than designed around.
		JvmUncaughtHandler.append(entryCtx);
		if (topLevelInClinit) {
			// main (when kept) has nothing left to do: invoking it already triggered
			// <clinit>, which ran the top level.
			mainCtx.emit(Opcode.RETURN);
		}

		// Pass 2c: Compile lambda bodies (iteratively, new lambdas may be discovered
		// during defun compilation, top-level compilation, or even lambda compilation)
		List<Ctx> lambdaCtxs = new ArrayList<>();
		List<FunctionInfo> lambdaFuncInfos = new ArrayList<>();
		int lambdaIdx = 0;
		while (lambdaIdx < lambdaDecls.size()) {
			LambdaInfo lambda = lambdaDecls.get(lambdaIdx);
			// Register lambda in CP: first param is Object[] env, rest are lambda params
			String descriptor = "([Ljava/lang/Object;" + "Ljava/lang/Object;".repeat(lambda.paramNames.size())
					+ ")Ljava/lang/Object;";
			Utf8Constant nameUtf8 = cp.addUtf8(lambda.methodName);
			Utf8Constant descUtf8 = cp.addUtf8(descriptor);
			MethodrefConstant methodref = cp.addMethodref(thisClass, cp.addNameAndType(nameUtf8, descUtf8));
			FunctionInfo fi = new FunctionInfo(lambda.funcId, lambda.paramNames.size(), lambda.variadic, true,
					methodref, nameUtf8, descUtf8);
			lambdaFuncInfos.add(fi);

			Ctx lambdaCtx = ctxBuilder.build();
			lambdaCtx.evalStoreRef = evalStoreRef;
			lambdaCtx.closureEnvSlot = 0; // slot 0 = env Object[]
			// Lambda params start at slot 1
			for (int i = 0; i < lambda.paramNames.size(); i++) {
				lambdaCtx.locals.put(lambda.paramNames.get(i), i + 1);
			}
			lambdaCtx.nextLocal = lambda.paramNames.size() + 1; // +1 for env
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
					functions.keySet());
			lambdaCtx.boxedVars = capturedVars;
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
				lambdaCtx.emit(Opcode.ACONST_NULL);
			}
			lambdaCtx.emit(Opcode.ARETURN);
			lambdaCtxs.add(lambdaCtx);
			lambdaIdx++;
		}

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

		// The invariant the whole class is measured against: no method that runs per
		// evaluated form may cross HotSpot's HugeMethodLimit, or it is never
		// JIT-compiled and nothing says so (.kb/hot-path-method-size.md). Every body
		// has been emitted by now, so this is where a REAL size exists to check --
		// which is why the cut is made by re-running the compile rather than by
		// predicting the size from the AST (bytecodes per node ranges over an order of
		// magnitude, because the surface macros expand during Pass 2). Only a defun is
		// reported: a lambda's generated name cannot be pointed back at a form for the
		// next attempt to cut.
		Map<String, Integer> tooLarge = new LinkedHashMap<>();
		for (int i = 0; i < defuns.size(); i++) {
			int size = funcCtxs.get(i).code.size();
			if (size <= HUGE_METHOD_LIMIT) {
				continue;
			}
			String name = defuns.get(i).name;
			AstOutliner.Budget budget = outlineBudgets.get(name);
			// Ask again only when there is something new to ask: an untried function,
			// or one this attempt really did cut and whose target can still shrink. A
			// function the pass cannot cut is left over the limit rather than costing
			// a compile per attempt to learn that again.
			if (budget == null
					|| (astOutlined.outlined().contains(name) && budget.targetBytes() > OUTLINE_TARGET_FLOOR_BYTES)) {
				tooLarge.put(name, size);
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
				sized.add(new Sized(defuns.get(i).name, funcCtxs.get(i).code.size()));
			}
			for (int i = 0; i < lambdaCtxs.size(); i++) {
				sized.add(new Sized(lambdaDecls.get(i).methodName, lambdaCtxs.get(i).code.size()));
			}
			// The top-level chunks are subject to the same 64 KB cap, and unlike a defun
			// they cannot be split by the author -- chunking happens BETWEEN top-level
			// forms, so one oversized form has no split point (see chunkCodeBudget).
			for (int i = 0; i < topChunks.size(); i++) {
				sized.add(new Sized("_top$" + i, topChunks.get(i).code.size()));
			}
			for (JvmBodyOutliner.OutlinedBody outlined : mainCtx.outlinedBodies) {
				sized.add(new Sized(outlined.name(), outlined.ctx().code.size()));
			}
			sized.stream()
				.sorted(java.util.Comparator.comparingInt(Sized::size).reversed())
				.limit(40)
				.forEach(s -> System.err.println("[method-size] " + s.size() + "\t" + s.name()));
		}

		// Names of the eval runtime methods and the global-environment field (the
		// constants are cheap; the method bodies are built only when used)
		Utf8Constant evalName = cp.addUtf8("_eval");
		Utf8Constant evalDesc = cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		Utf8Constant applyName = cp.addUtf8("_apply");
		Utf8Constant storeName = cp.addUtf8("_store");
		Utf8Constant storeDesc = cp
			.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		Utf8Constant envLookupName = cp.addUtf8("_envLookup");
		Utf8Constant envLookupDesc = cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		Utf8Constant lookupName = cp.addUtf8("_lookup");
		Utf8Constant lookupDesc = cp.addUtf8("(Ljava/lang/Object;)[Ljava/lang/Object;");
		Utf8Constant genvName = cp.addUtf8("_genv");
		Utf8Constant genvDesc = cp.addUtf8("Ljava/lang/Object;");
		FieldrefConstant genvField = cp.addFieldref(thisClass, cp.addNameAndType(genvName, genvDesc));
		Utf8Constant fenvName = cp.addUtf8("_fenv");
		FieldrefConstant fenvField = cp.addFieldref(thisClass, cp.addNameAndType(fenvName, genvDesc));
		List<Integer> evalCode = List.of();
		List<Integer> applyCode = List.of();
		List<Integer> storeCode = List.of();
		List<Integer> envLookupCode = List.of();
		List<List<Integer>> lookupSegments = List.of();
		List<Utf8Constant> lookupSegmentNames = new ArrayList<>();
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
		boolean needsLookup = usesEval || LispMacroExpander.usesRuntimeFunctionDesignator(program)
				|| !indirectCallArities.isEmpty();
		// Which funcIds the _invoke_N dispatchers (and the _lookup registry) must be
		// able to reach. Every method body has been emitted by now, so valueFuncIds is
		// exactly the set of funcIds this program turns into function VALUES -- macro
		// expansions that ran during Pass 2 included. Everything else is only ever
		// called directly, and dropping its dispatcher case is what lets
		// JvmClassShaker reach the library code an ASDF system splices.
		Set<Integer> dispatchableFuncIds = dispatchableFuncIds(functions, valueFuncIds, spelledLiterals, needsLookup,
				nameResolvable, symbolBuilders);
		if (needsLookup) {
			MethodrefConstant evalRef = cp.addMethodref(thisClass, cp.addNameAndType(evalName, evalDesc));
			MethodrefConstant applyRef = cp.addMethodref(thisClass, cp.addNameAndType(applyName, evalDesc));
			MethodrefConstant storeRef = cp.addMethodref(thisClass, cp.addNameAndType(storeName, storeDesc));
			MethodrefConstant envLookupRef = cp.addMethodref(thisClass,
					cp.addNameAndType(envLookupName, envLookupDesc));
			MethodrefConstant lookupRef = cp.addMethodref(thisClass, cp.addNameAndType(lookupName, lookupDesc));
			MethodrefConstant[] invoke = new MethodrefConstant[JvmEvalRuntimeBuilder.MAX_CALLABLE_ARITY + 1];
			for (int n = 0; n <= JvmEvalRuntimeBuilder.MAX_CALLABLE_ARITY; n++) {
				Utf8Constant invName = cp.addUtf8("_invoke_" + n);
				Utf8Constant invDesc = cp.addUtf8("(" + "Ljava/lang/Object;".repeat(n + 1) + ")Ljava/lang/Object;");
				invoke[n] = cp.addMethodref(thisClass, cp.addNameAndType(invName, invDesc));
			}
			// _invoke_v(funcval, argList): the spread dispatcher _apply hands the whole
			// argument list to (see JvmRuntimeBuilder.buildDispatchMethods).
			MethodrefConstant invokeSpread = cp.addMethodref(thisClass,
					cp.addNameAndType(cp.addUtf8(JvmRuntimeBuilder.dispatcherName(0, true)),
							cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")));
			MethodrefConstant stringLengthRef = cp.addMethodref(stringClass,
					cp.addNameAndType(cp.addUtf8("length"), cp.addUtf8("()I")));
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
				.storeRef(storeRef)
				.envLookupRef(envLookupRef)
				.lookupRef(lookupRef)
				.genvField(genvField)
				.fenvField(fenvField)
				.invoke(invoke)
				.invokeSpread(invokeSpread)
				.functions(functions)
				.build();
			if (usesEval) {
				evalCode = JvmEvalRuntimeBuilder.buildEval(ec);
				applyCode = JvmEvalRuntimeBuilder.buildApply(ec);
				storeCode = JvmEvalRuntimeBuilder.buildStore(ec);
				envLookupCode = JvmEvalRuntimeBuilder.buildEnvLookup(ec);
			}
			lookupSegments = JvmEvalRuntimeBuilder.buildLookupSegments(ec, thisClass, dispatchableFuncIds,
					this.dynamic || nameResolvable || symbolBuilders, spelledLiterals);
			for (int g = 1; g < lookupSegments.size(); g++) {
				lookupSegmentNames.add(cp.addUtf8("_lookup$" + g));
			}
		}

		// Build dispatch functions for each needed arity. When the eval runtime is
		// present, the dispatcher falls back to _apply for interpreted closures
		// (funcId == -1) created by the runtime's lambda; a String funcval (a symbol
		// used as a function designator) resolves through _lookup.
		MethodrefConstant applyRefForDispatch = usesEval
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8("_apply"),
						cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")))
				: null;
		MethodrefConstant lookupRefForDispatch = needsLookup
				? cp.addMethodref(thisClass, cp.addNameAndType(lookupName, lookupDesc)) : null;
		List<DispatchMethod> dispatchMethods = new ArrayList<>();
		for (int arity : indirectCallArities) {
			dispatchMethods.addAll(JvmRuntimeBuilder.buildDispatchMethods(arity, functions, lambdaDecls,
					lambdaFuncInfos, cp, thisClass, objectArrayClass, integerClass, integerValue, objectClass,
					stringClass, applyRefForDispatch, lookupRefForDispatch, dispatchableFuncIds));
		}
		// The spread dispatcher _apply calls: it takes the argument list whole, so an
		// apply through a COMPUTED designator has no arity ceiling. Emitted with the eval
		// runtime, which is what apply forces.
		if (usesEval) {
			dispatchMethods.addAll(JvmRuntimeBuilder.buildDispatchMethods(0, functions, lambdaDecls, lambdaFuncInfos,
					cp, thisClass, objectArrayClass, integerClass, integerValue, objectClass, stringClass,
					applyRefForDispatch, lookupRefForDispatch, true, dispatchableFuncIds));
		}

		// Build the runtime reader methods (read/load), only when used
		Utf8Constant readSrcName = cp.addUtf8("_readSrc");
		Utf8Constant readSrcDesc = cp.addUtf8("Ljava/lang/String;");
		Utf8Constant readPosName = cp.addUtf8("_readPos");
		Utf8Constant readPosDesc = cp.addUtf8("I");
		Utf8Constant rdStructsName = cp.addUtf8(JvmReadRuntimeBuilder.STRUCT_TABLE_FIELD);
		Utf8Constant rdStructsDesc = cp.addUtf8(JvmReadRuntimeBuilder.STRUCT_TABLE_DESC);
		List<JvmReadRuntimeBuilder.ReadMethod> readMethods = List.of();
		List<Integer> structTableClinit = List.of();
		if (usesRead) {
			// The reader reads #S(...) and #P"..." only when an instance can exist at
			// all (the same gate the instance machinery uses); with it on, every struct
			// layout is interned so the runtime directory can resolve any registered
			// tag, the fixed PATHNAME layout is interned for the #P arm, and the
			// directory itself is baked into <clinit>.
			boolean readerInstances = mayUseInstances;
			am.ik.jvm.ConstantPool.FieldrefConstant pathnameLayoutField = null;
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
						stringCharAt, stringLength, stringSubstring, objectEquals, readLineHelperMethod, usesLoad,
						readerInstances, pathnameLayoutField)
				.methods();
		}
		final List<JvmReadRuntimeBuilder.ReadMethod> readMethodsFinal = readMethods;
		final List<Integer> structTableClinitFinal = structTableClinit;

		// Build the hash-table runtime helpers, only when the program uses hash tables.
		final List<JvmHashRuntimeBuilder.HashMethod> hashMethods = usesHashTables
				? JvmHashRuntimeBuilder.build(cp, thisClass, objectClass, objectArrayClass, longValueOf,
						Objects.requireNonNull(numericRuntime.ops().get(JvmNumericRuntimeBuilder.EQUAL)), strvMethod,
						instanceLayoutClass, usesEqualpHashTables)
				: List.of();

		// Build the array runtime helpers, only when the program uses arrays. Includes
		// the
		// two array-printing helpers (_arrayToString / _arrayToDisplayString) so a
		// literal
		// or make-array result prints as #(...) / #2A(...).
		final List<JvmArrayRuntimeBuilder.ArrayMethod> arrayMethods;
		if (usesArrays) {
			List<JvmArrayRuntimeBuilder.ArrayMethod> built = new ArrayList<>(
					JvmArrayRuntimeBuilder.build(cp, objectClass, objectArrayClass, thisClass));
			built.addAll(JvmArrayRuntimeBuilder.buildToStringMethods(cp, lispToStringMethod, lispToDisplayStringMethod,
					thisClass));
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
						gpuRuntime != null ? gpuRuntime.ops().get(JvmGpuRuntimeBuilder.WRITTEN) : null,
						gpuRuntime != null ? gpuRuntime.ops().get(JvmGpuRuntimeBuilder.MATERIALIZE) : null));
			}
			// The packed integer-vector helpers (_iv*) dispatch on instanceof long[]
			// and delegate any other array shape down the chain (to the _fv* tier when
			// it is emitted, else straight to the general helpers).
			if (usesIntArray) {
				built.addAll(
						JvmIntArrayRuntimeBuilder.build(cp, objectClass, objectArrayClass, thisClass, usesFloatArray));
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
			packedPrint = new JvmRuntimeBuilder.PackedPrint(cp.addClass(cp.addUtf8("[D")),
					cp.addClass(cp.addUtf8("[F")),
					cp.addMethodref(thisClass,
							cp.addNameAndType(cp.addUtf8(JvmFloatArrayRuntimeBuilder.TO_GENERAL_PRINT),
									cp.addUtf8(JvmFloatArrayRuntimeBuilder.TO_GENERAL_DESC))),
					cp.addMethodref(stringClass,
							cp.addNameAndType(cp.addUtf8("replaceFirst"),
									cp.addUtf8("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"))),
					cp.addString("^#\\d*A?\\("), cp.addString("#d("), cp.addString("#f("));
		}
		// The packed integer-vector print branch: a long[] renders as a plain #(...)
		// vector (CL prints specialized vectors this way) by converting to a general
		// array (_ivToGeneral) and reusing the general renderer -- no prefix rewrite,
		// unlike the #d/#f float syntax.
		JvmRuntimeBuilder.@Nullable PackedIntPrint packedIntPrint = null;
		if (usesIntArray) {
			packedIntPrint = new JvmRuntimeBuilder.PackedIntPrint(cp.addClass(cp.addUtf8("[J")),
					cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmIntArrayRuntimeBuilder.TO_GENERAL),
							cp.addUtf8(JvmIntArrayRuntimeBuilder.TO_GENERAL_DESC))));
		}
		ClassConstant arrayListClassForPrint = usesArrays ? cp.addClass(cp.addUtf8("java/util/ArrayList")) : null;
		MethodrefConstant arrayToStringMethod = usesArrays
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmArrayRuntimeBuilder.TO_STRING),
						cp.addUtf8(JvmArrayRuntimeBuilder.TO_STRING_DESC)))
				: null;
		MethodrefConstant arrayToDisplayStringMethod = usesArrays
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmArrayRuntimeBuilder.TO_DISPLAY_STRING),
						cp.addUtf8(JvmArrayRuntimeBuilder.TO_STRING_DESC)))
				: null;

		// Wrapped java: host objects print as #<java class.Name> (interpreter parity);
		// the branch is emitted only when the program uses java: interop.
		final JvmRuntimeBuilder.@Nullable JavaPrint javaPrint;
		if (usesJava) {
			ClassConstant bigIntegerClassForPrint = cp.addClass(cp.addUtf8("java/math/BigInteger"));
			MethodrefConstant objectGetClass = cp.addMethodref(objectClass,
					cp.addNameAndType(cp.addUtf8("getClass"), cp.addUtf8("()Ljava/lang/Class;")));
			ClassConstant classClass = cp.addClass(cp.addUtf8("java/lang/Class"));
			MethodrefConstant classGetName = cp.addMethodref(classClass,
					cp.addNameAndType(cp.addUtf8("getName"), cp.addUtf8("()Ljava/lang/String;")));
			javaPrint = new JvmRuntimeBuilder.JavaPrint(bigIntegerClassForPrint, objectGetClass, classGetName,
					stringConcat, cp.addString("#<java "), cp.addString(">"));
		}
		else {
			javaPrint = null;
		}
		// A wrapped objc: object prints as #<objc Class> (interpreter parity), through
		// the bridge's print hook -- emitted AHEAD of the java: branch, which would
		// otherwise print the wrapper as a host object; guarded by the init field, so
		// the printer never names the bridge class before _objcInit defined it.
		final JvmRuntimeBuilder.@Nullable ObjcPrint objcPrint = objcRuntime != null
				? new JvmRuntimeBuilder.ObjcPrint(objcRuntime.initedField(),
						Objects.requireNonNull(objcRuntime.ops().get(JvmObjcRuntimeBuilder.PRINT)))
				: null;

		// A foreign pointer prints as #<pointer #x...> (interpreter parity) through the
		// ffi bridge's print hook, the same arrangement (and the same record type) as
		// objcPrint above; guarded by _ffiInited for the same reason.
		final JvmRuntimeBuilder.@Nullable ObjcPrint ffiPrint = ffiRuntime != null
				? new JvmRuntimeBuilder.ObjcPrint(ffiRuntime.initedField(),
						Objects.requireNonNull(ffiRuntime.ops().get(JvmFfiRuntimeBuilder.PRINT)))
				: null;

		// A hash table prints as the unreadable #<HASH-TABLE :TEST EQUAL :COUNT n> tag,
		// the same text the interpreter and both WASM backends emit; the branch is
		// emitted only when the program uses hash tables.
		final JvmRuntimeBuilder.@Nullable HashPrint hashPrint;
		if (usesHashTables) {
			ClassConstant mapClassForPrint = cp.addClass(cp.addUtf8(JvmHashRuntimeBuilder.MAP_CLASS));
			// The live entry count comes from the same helper hash-table-count reads, so
			// the printed :COUNT and the accessor cannot disagree (the map's own size()
			// counts buckets, not entries).
			MethodrefConstant mapSize = cp.addMethodref(thisClass, cp
				.addNameAndType(cp.addUtf8(JvmHashRuntimeBuilder.SIZE), cp.addUtf8(JvmHashRuntimeBuilder.SIZE_DESC)));
			MethodrefConstant intToString = cp.addMethodref(cp.addClass(cp.addUtf8("java/lang/Integer")),
					cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("(I)Ljava/lang/String;")));
			// The :TEST field is the test lookup implements. Only a program that can
			// build an equalp table interns the second tag and asks the table which one
			// it is; in every other program no table folds, so the EQUAL tag is a
			// constant exactly as it was.
			hashPrint = new JvmRuntimeBuilder.HashPrint(mapClassForPrint, cp.addString(LispHashTable.HASH_TABLE_PREFIX),
					mapSize, intToString, stringConcat, cp.addString(">"),
					usesEqualpHashTables ? cp.addString(LispHashTable.HASH_TABLE_PREFIX_EQUALP) : null,
					usesEqualpHashTables
							? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmHashRuntimeBuilder.EQUALP_P),
									cp.addUtf8(JvmHashRuntimeBuilder.EQUALP_P_DESC)))
							: null);
		}
		else {
			hashPrint = null;
		}

		// Futures (CompletableFutures / stream-read tokens at runtime) print as
		// #<FUTURE> and streams as #<STREAM> (interpreter parity); the branches are
		// emitted only when the program can create them.
		final JvmRuntimeBuilder.@Nullable FuturePrint futurePrint = usesAsyncRuntime
				? new JvmRuntimeBuilder.FuturePrint(cp.addClass(cp.addUtf8("java/util/concurrent/CompletableFuture")),
						cp.addString("#<FUTURE>"), objectArrayClass, cp.addString(JvmAsyncRuntimeBuilder.SMARKER),
						cp.addString(JvmAsyncRuntimeBuilder.RMARKER), cp.addString("#<STREAM>"))
				: null;

		// Instances print as #S(NAME :SLOT v ...) / #<NAME :SLOT v ...>. Every constant
		// is minted here, AFTER the body passes have interned whatever layouts the
		// program references and BEFORE .writeConstantPool(cp) freezes the pool, so an
		// instance-free program's pool -- and therefore its whole class -- is unchanged.
		final boolean usesInstances = !mainCtx.layoutPool.isEmpty();
		Utf8Constant instToStringName = usesInstances ? cp.addUtf8("_instToString") : null;
		Utf8Constant instToDisplayStringName = usesInstances ? cp.addUtf8("_instToDisplayString") : null;
		final JvmRuntimeBuilder.@Nullable InstPrint instPrint = usesInstances ? new JvmRuntimeBuilder.InstPrint(
				mainCtx.layoutPool.stringArrayClass(cp),
				cp.addMethodref(thisClass,
						cp.addNameAndType(Objects.requireNonNull(instToStringName), consToStringDescUtf)),
				cp.addMethodref(thisClass,
						cp.addNameAndType(Objects.requireNonNull(instToDisplayStringName), consToStringDescUtf)))
				: null;

		// _strEsc: the *print-escape* escaping the readable renderer applies to a string
		// value's content (todo 216). Always emitted -- _lispToString is unconditional.
		Utf8Constant strEscName = cp.addUtf8("_strEsc");
		Utf8Constant strEscDescUtf = cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;");
		MethodrefConstant strEscMethod = cp.addMethodref(thisClass, cp.addNameAndType(strEscName, strEscDescUtf));
		MethodrefConstant stringIndexOf = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("indexOf"), cp.addUtf8("(I)I")));
		MethodrefConstant stringIndexOfFrom = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("indexOf"), cp.addUtf8("(II)I")));
		MethodrefConstant stringReplace = cp.addMethodref(stringClass, cp.addNameAndType(cp.addUtf8("replace"),
				cp.addUtf8("(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;")));
		List<Integer> strEscCode = JvmRuntimeBuilder.buildStrEscBody(cp, stringLength, stringCharAt, stringIndexOf,
				stringIndexOfFrom, stringSubstring, stringReplace, stringConcat);

		// Float text: every double spelling gets the lowercase exponent marker (the
		// FloatText contract), and a packed single-float array element prints through a
		// transient Float box at its f32 width.
		ClassConstant floatBoxClass = cp.addClass(cp.addUtf8("java/lang/Float"));
		MethodrefConstant floatToString = cp.addMethodref(floatBoxClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		JvmRuntimeBuilder.FloatPrint floatPrint = new JvmRuntimeBuilder.FloatPrint(floatBoxClass, floatToString,
				stringReplace, cp.addString("E"), cp.addString("e"));

		// Build _lispToString and _consToString helper method bodies
		List<Integer> ltsCode = JvmRuntimeBuilder.buildLispToStringBody(longClass, doubleClass, stringClass,
				objectArrayClass, integerClass, longToString, doubleToString, floatPrint, objectToString,
				consToStringMethod, nilStr, funcStr, ratioArrayClass, stringConcat, slashStr, charBoxClass,
				charPrin1Method, arrayListClassForPrint, arrayToStringMethod, strvMethod, javaPrint, objcPrint,
				ffiPrint, futurePrint, packedPrint, packedIntPrint, instPrint, strEscMethod, hashPrint);
		List<Integer> ctsCode = JvmRuntimeBuilder.buildConsToStringBody(objectArrayClass, stringBuilderClass, sbInitStr,
				sbAppendStr, sbToString, lispToStringMethod, openParenStr, closeParenStr, spaceStr, dotStr,
				ratioArrayClass);
		List<Integer> ltdsCode = JvmRuntimeBuilder.buildLispToDisplayStringBody(longClass, doubleClass, stringClass,
				objectArrayClass, integerClass, longToString, doubleToString, floatPrint, objectToString,
				consToDisplayStringMethod, nilStr, funcStr, stringCharAt, stringLength, stringSubstring,
				stringLastIndexOf, ratioArrayClass, stringConcat, slashStr, charBoxClass, characterToString,
				arrayListClassForPrint, arrayToDisplayStringMethod, strvMethod, javaPrint, objcPrint, ffiPrint,
				futurePrint, packedPrint, packedIntPrint, instPrint, hashPrint);
		List<Integer> instCode = usesInstances ? JvmRuntimeBuilder.buildInstToStringBody(objectArrayClass,
				mainCtx.layoutPool.stringArrayClass(cp), stringBuilderClass, sbInitStr, sbAppendStr, sbToString,
				objectEquals, lispToStringMethod, cp.addString("S"), cp.addString("#S("), cp.addString("#<"),
				closeParenStr, cp.addString(">"), cp.addString(" :"), spaceStr, cp.addString("P"), cp.addString("#P"))
				: List.of();
		List<Integer> instDisplayCode = usesInstances ? JvmRuntimeBuilder.buildInstToStringBody(objectArrayClass,
				mainCtx.layoutPool.stringArrayClass(cp), stringBuilderClass, sbInitStr, sbAppendStr, sbToString,
				objectEquals, lispToDisplayStringMethod, cp.addString("S"), cp.addString("#S("), cp.addString("#<"),
				closeParenStr, cp.addString(">"), cp.addString(" :"), spaceStr, cp.addString("P"), null) : List.of();
		List<Integer> charPrin1Code = JvmRuntimeBuilder.buildCharPrin1Body(cp, stringConcat, characterToString);
		List<Integer> ctdsCode = JvmRuntimeBuilder.buildConsToDisplayStringBody(objectArrayClass, stringBuilderClass,
				sbInitStr, sbAppendStr, sbToString, lispToDisplayStringMethod, openParenStr, closeParenStr, spaceStr,
				dotStr, ratioArrayClass);
		List<Integer> appendCode = JvmRuntimeBuilder.buildAppendBody(objectArrayClass, objectClass, appendMethod);
		ConstantPool.StringConstant quoteStr = cp.addString("\"");
		List<Integer> readLineCode = JvmRuntimeBuilder.buildReadLineBody(bufferedReaderClass, inputStreamReaderClass,
				brInit, brReadLine, isrInit, systemIn, stdinReaderField, quoteStr, stringConcat);

		// File-stream runtime (open/close/write-line/read-line with a stream)
		MethodrefConstant stringLengthForIo = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("length"), cp.addUtf8("()I")));
		// TCP/TLS socket runtime (only when the program uses a rontolisp:tcp-* or
		// rontolisp:tls-connect built-in); built before the IO runtime so the stream
		// built-ins can grow socket branches.
		final JvmSecureRandomRuntimeBuilder.@Nullable SecureRandomRuntime secureRandomRuntime = usesSecureRandom
				? JvmSecureRandomRuntimeBuilder.build(cp, thisClass, longValueOf) : null;
		final JvmAsyncRuntimeBuilder.@Nullable AsyncMethod octetsStrictRuntime = usesOctetsStrict
				? JvmAsyncRuntimeBuilder.buildOctetsStrict(cp, stringConcat) : null;
		final List<JvmMutexRuntimeBuilder.MutexMethod> mutexMethods = usesMutexes ? JvmMutexRuntimeBuilder.build(cp)
				: List.of();
		final JvmSocketRuntimeBuilder.@Nullable SocketRuntime socketRuntime = usesSockets
				? JvmSocketRuntimeBuilder.build(cp, thisClass, stringClass, longClass, longValueOf, longValue,
						stringLengthForIo, stringSubstring, stringConcat)
				: null;
		// *error-output* is the reserved stream handle 2 (the process standard error), so
		// a program that can name it -- explicitly, or through the warn redirect -- gets
		// the stream built-ins' stderr branch and the reserved table handles; one that
		// never mentions it keeps its original bytes.
		final boolean usesErrorOutput = programUsesSymbol(program, LispNames.ERROR_OUTPUT_VAR);
		// The directory-LISTING helper joins the stream runtime only for a program that
		// calls the primitive, so every artifact compiled without one keeps its bytes.
		final boolean usesListDirectory = programUsesSymbol(program, LispNames.LIST_DIRECTORY);
		// The file-metadata helpers ride the same rule, one gate each: file-length also
		// grows _open and adds the _streamPaths side table, so a program that never asks
		// for it must not pay for either.
		final JvmIoRuntimeBuilder.FileMeta fileMeta = new JvmIoRuntimeBuilder.FileMeta(
				programUsesSymbol(program, LispNames.FILE_WRITE_DATE),
				programUsesSymbol(program, LispNames.MAKE_DIRECTORIES),
				programUsesSymbol(program, LispNames.FILE_LENGTH),
				programUsesSymbol(program, LispNames.DELETE_FILE_INTERNAL),
				programUsesSymbol(program, LispNames.RENAME_FILE_INTERNAL));
		List<JvmIoRuntimeBuilder.IoMethod> ioMethods = JvmIoRuntimeBuilder
			.create(cp, thisClass, objectClass, stringClass, longClass, longValueOf, longValue, stringLengthForIo,
					stringSubstring, stringConcat, systemOut, printlnStr, readLineHelperMethod, socketRuntime,
					usesErrorOutput, usesListDirectory, fileMeta, usesPackedSequenceIo)
			.methods();
		Utf8Constant streamsFieldName = cp.addUtf8(JvmIoRuntimeBuilder.STREAMS_FIELD);
		Utf8Constant streamsFieldDesc = cp.addUtf8(JvmIoRuntimeBuilder.STREAMS_DESC);
		final @Nullable Utf8Constant streamPathsFieldName = fileMeta.fileLength()
				? cp.addUtf8(JvmIoRuntimeBuilder.STREAM_PATHS_FIELD) : null;
		final @Nullable Utf8Constant streamPathsFieldDesc = fileMeta.fileLength()
				? cp.addUtf8(JvmIoRuntimeBuilder.STREAM_PATHS_DESC) : null;
		Utf8Constant streamCountFieldName = cp.addUtf8(JvmIoRuntimeBuilder.STREAM_COUNT_FIELD);
		Utf8Constant streamCountFieldDesc = cp.addUtf8(JvmIoRuntimeBuilder.STREAM_COUNT_DESC);
		// Tracks whether stdout is at the start of a line (0 = at line start), so
		// fresh-line
		// can decide whether to emit a newline. A static int defaults to 0 (at line
		// start).
		Utf8Constant colFieldName = cp.addUtf8(JvmFreshLineCompiler.COL_FIELD);
		Utf8Constant colFieldDesc = cp.addUtf8(JvmFreshLineCompiler.COL_DESC);
		Utf8Constant gensymCtrFieldName = cp.addUtf8(JvmGensymCompiler.CTR_FIELD);
		Utf8Constant gensymCtrFieldDesc = cp.addUtf8(JvmGensymCompiler.CTR_DESC);

		// fetch runtime helper body (only when the program uses rontolisp:fetch; the
		// generic _await lives in the async runtime).
		final JvmFetchRuntimeBuilder.@Nullable FetchRuntime fetchRuntimeBodies = usesFetch
				? JvmFetchRuntimeBuilder.build(cp, objectArrayClass, stringClass, stringLength, stringSubstring) : null;

		// The async/await runtime: %async-run + run() (the class implements Runnable),
		// the generic _await, streams and predicates. It rides the condition channel
		// (the error payload re-signals typed conditions across the await), so the
		// channel is forced on.
		final JvmAsyncRuntimeBuilder.@Nullable AsyncRuntime asyncRuntimeBodies;
		final @Nullable ClassConstant runnableClass;
		if (usesAsyncRuntime) {
			mainCtx.conditionChannel.ensure(cp, this.className);
			MethodrefConstant progInitForAsync = cp.addMethodref(thisClass,
					cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
			asyncRuntimeBodies = JvmAsyncRuntimeBuilder.build(cp, thisClass, objectClass, objectArrayClass, stringClass,
					mainCtx.conditionChannel, progInitForAsync, usesFetch, longValueOf, stringLength, stringSubstring,
					stringConcat);
			runnableClass = cp.addClass(cp.addUtf8("java/lang/Runnable"));
		}
		else {
			asyncRuntimeBodies = null;
			runnableClass = null;
		}
		// The thread runtime: _thread_spawn + call() (the class implements Callable),
		// join/alive/destroy/threadp and the _dtl name-to-ThreadLocal dispatch. It rides
		// the condition channel (call()'s error payload re-signals typed conditions
		// across the join, the _await pattern).
		final JvmThreadRuntimeBuilder.@Nullable ThreadRuntime threadRuntimeBodies;
		final @Nullable ClassConstant callableClass;
		final ConstantPool.@Nullable FieldrefConstant curThreadTlFieldRef;
		final @Nullable Utf8Constant curThreadTlFieldName;
		final @Nullable Utf8Constant curThreadTlFieldDesc;
		if (usesThreads) {
			mainCtx.conditionChannel.ensure(cp, this.className);
			MethodrefConstant progInitForThread = cp.addMethodref(thisClass,
					cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
			// The per-thread handle cache behind _thread_current (declared below,
			// initialized in <clinit> next to the condition channel's ThreadLocals).
			curThreadTlFieldName = cp.addUtf8(JvmThreadRuntimeBuilder.CURRENT_TL_FIELD);
			curThreadTlFieldDesc = cp.addUtf8("Ljava/lang/ThreadLocal;");
			curThreadTlFieldRef = cp.addFieldref(thisClass,
					cp.addNameAndType(curThreadTlFieldName, curThreadTlFieldDesc));
			threadRuntimeBodies = JvmThreadRuntimeBuilder.build(cp, thisClass, objectClass, objectArrayClass,
					stringClass, mainCtx.conditionChannel, progInitForThread, stringConcat,
					java.util.Objects.requireNonNull(dynVarRuntime), curThreadTlFieldRef);
			callableClass = cp.addClass(cp.addUtf8("java/util/concurrent/Callable"));
		}
		else {
			threadRuntimeBodies = null;
			callableClass = null;
			curThreadTlFieldRef = null;
			curThreadTlFieldName = null;
			curThreadTlFieldDesc = null;
		}
		final @Nullable Utf8Constant threadFnFieldName = usesThreads ? cp.addUtf8(JvmThreadRuntimeBuilder.FN_FIELD)
				: null;
		final @Nullable Utf8Constant threadBindingsFieldName = usesThreads
				? cp.addUtf8(JvmThreadRuntimeBuilder.BINDINGS_FIELD) : null;
		final @Nullable Utf8Constant threadInstanceFieldDesc = usesThreads ? cp.addUtf8("Ljava/lang/Object;") : null;
		final @Nullable Utf8Constant handoffFieldName = usesAsyncRuntime
				? cp.addUtf8(JvmAsyncRuntimeBuilder.HANDOFF_FIELD) : null;
		final @Nullable Utf8Constant handoffFieldDesc = usesAsyncRuntime ? cp.addUtf8("Ljava/lang/ThreadLocal;") : null;
		final @Nullable Utf8Constant asyncFnFieldName = usesAsyncRuntime ? cp.addUtf8(JvmAsyncRuntimeBuilder.FN_FIELD)
				: null;
		final @Nullable Utf8Constant asyncFutureFieldName = usesAsyncRuntime
				? cp.addUtf8(JvmAsyncRuntimeBuilder.FUTURE_FIELD) : null;
		final @Nullable Utf8Constant asyncLatchFieldName = usesAsyncRuntime
				? cp.addUtf8(JvmAsyncRuntimeBuilder.LATCH_FIELD) : null;
		final @Nullable Utf8Constant asyncInstanceFieldDesc = usesAsyncRuntime ? cp.addUtf8("Ljava/lang/Object;")
				: null;
		final ConstantPool.@Nullable FieldrefConstant handoffFieldRef = usesAsyncRuntime
				? cp.addFieldref(thisClass, cp.addNameAndType(java.util.Objects.requireNonNull(handoffFieldName),
						java.util.Objects.requireNonNull(handoffFieldDesc)))
				: null;

		// length runtime helper. Emitted unconditionally (it is small and lives in its
		// own
		// method): length is also generated internally by other compilers (e.g. format
		// padding), so a source-symbol gate would miss those call sites. The whole
		// computation lives in one method so each call site is a single invokestatic,
		// keeping main within the JVM's 64 KB per-method limit.
		final JvmLengthRuntimeBuilder.LengthMethod lengthMethodBody = JvmLengthRuntimeBuilder.build(cp,
				objectArrayClass, stringClass, longValueOf, thisClass);

		// nthcdr runtime helper. Emitted unconditionally for the same reason _length is:
		// nthcdr is generated internally by a long tail of expanders (nth, elt, loop's
		// list stepping, destructuring-bind, format's ~* family), so a source-symbol gate
		// would miss those call sites -- and the body is ~20 bytes. It exists as a method
		// at all so its loop's backedge sits at operand stack depth 0, the only shape
		// HotSpot will OSR-compile (JvmNthcdrRuntimeBuilder).
		final JvmNthcdrRuntimeBuilder.NthcdrMethod nthcdrMethodBody = JvmNthcdrRuntimeBuilder.build(cp,
				objectArrayClass);

		// The character-index helpers (_cpoff / _scount) every string index and every
		// string length reads through. Emitted unconditionally for the same reason
		// _length is: the sites are generated internally too, and the pair is ~60 bytes.
		final List<JvmStringIndexRuntimeBuilder.StringIndexMethod> stringIndexMethods = JvmStringIndexRuntimeBuilder
			.build(cp, thisClass, stringClass);
		final List<Utf8Constant> stringIndexFieldNames = java.util.Arrays.stream(JvmStringIndexRuntimeBuilder.FIELDS)
			.map(cp::addUtf8)
			.toList();
		final Utf8Constant stringIndexFieldDesc = cp.addUtf8(JvmStringIndexRuntimeBuilder.FIELD_DESC);

		Utf8Constant mainUtf8 = cp.addUtf8("main");
		Utf8Constant mainDesc = cp.addUtf8("([Ljava/lang/String;)V");
		Utf8Constant codeUtf8 = cp.addUtf8("Code");
		// The typed jvm-export wrapper methods (and their marshalling helpers), plus
		// the _top$run method <clinit> calls to run the top level (see mainCtx above).
		// Built here so every constant they mint precedes the pool serialization below.
		final Utf8Constant topRunnerName = topLevelInClinit ? cp.addUtf8("_top$run") : null;
		final MethodrefConstant topRunnerRef = topRunnerName == null ? null
				: cp.addMethodref(thisClass, cp.addNameAndType(topRunnerName, topChunkDesc));
		final List<JvmExportRuntimeBuilder.BuiltMethod> exportMethods = exportDecls.isEmpty() ? List.of()
				: JvmExportRuntimeBuilder.build(cp, thisClass, exportDecls, functions);

		// Effectively-final aliases for capture in the writer lambda
		final Ctx topRunnerCtxFinal = topRunnerCtx;
		final List<Integer> evalBody = evalCode;
		final List<Integer> applyBody = applyCode;
		final List<Integer> storeBody = storeCode;
		final List<Integer> envLookupBody = envLookupCode;
		final List<List<Integer>> lookupBodies = lookupSegments;

		// A program that redirects *standard-output* (the variable is in globals only
		// then) seeds its global default from StreamDesignators' table -- the designator
		// t = stdout for the two stdio variables, a stream VALUE over handle 2 for
		// *error-output*, which t cannot name; the constants are minted here for the
		// same serialization-order reason as the layout half below.
		final Map<String, FieldrefConstant> streamGlobalSeeds = new LinkedHashMap<>();
		// The eval runtime's global-environment mirror is the SECOND home of the same
		// value, and symbol-value/boundp/eval read only that one -- so it seeds from the
		// same table, or a variable the field seeding just bound reads back as unbound
		// there (.kb/symbol-runtime-api.md). Gated on the name appearing in the source,
		// which keeps a program that never mentions one byte-identical AND is the very
		// scan the --component stderr narrowing uses, so the two cannot disagree about
		// whether the reserved handle 2 is reachable.
		final Map<String, ConstantPool.StringConstant> streamGenvSeeds = new LinkedHashMap<>();
		boolean seedsTDesignator = false;
		for (Map.Entry<String, LispVal> streamVar : StreamDesignators.standardStreamDefaults().entrySet()) {
			FieldrefConstant globalField = globalFields.get(streamVar.getKey());
			if (globalField != null) {
				streamGlobalSeeds.put(streamVar.getKey(), globalField);
			}
			if (usesEval && programUsesSymbol(program, streamVar.getKey())) {
				streamGenvSeeds.put(streamVar.getKey(), cp.addString(streamVar.getKey()));
			}
			seedsTDesignator |= streamVar.getValue() instanceof LispTrue
					&& (globalField != null || streamGenvSeeds.containsKey(streamVar.getKey()));
		}
		final boolean seedsStandardStream = !streamGlobalSeeds.isEmpty() || !streamGenvSeeds.isEmpty();
		final ConstantPool.StringConstant standardOutputTStr = seedsTDesignator ? cp.addString("T") : null;
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
		final @Nullable FieldrefConstant streamLayoutField = seedsStreamValue
				? mainCtx.layoutPool.intern(cp, className, am.ik.rontolisp.LispLayout.STREAM) : null;
		final ConstantPool.@Nullable StringConstant streamKindStandardStr = seedsStreamValue
				? cp.addString(am.ik.rontolisp.LispLayout.Kinds.STANDARD) : null;
		// The layout half of <clinit>, assembled HERE because it mints CONSTANT_String
		// entries and the constant pool is serialized by .writeConstantPool(cp) below,
		// before the writeFields/writeMethods lambdas run.
		final List<Integer> layoutClinitCode = new ArrayList<>();
		mainCtx.layoutPool.emitClinitInit(layoutClinitCode, cp);
		// The bignum-literal half of <clinit>, assembled HERE for the same reason: it
		// mints the CONSTANT_String decimal forms, and the pool is complete because
		// every body (defun, top-level chunk, lambda, outlined fused site) has been
		// compiled by now. Empty for a program with no bignum literal, which is then
		// emitted byte for byte as before.
		final List<Integer> bigIntClinitCode = new ArrayList<>();
		mainCtx.bigIntPool.emitClinitInit(bigIntClinitCode, cp);
		// When the standard-stream handles are reserved, the stream table must EXIST
		// from the start with those slots empty. _addStream reserves the COUNT, but it
		// runs only when something is opened -- while the reserved handle 2 is a live
		// stream designator in a program that opens nothing. A helper that indexes the
		// table from a raw handle ahead of its stderr branch (the socket probes in
		// _writeString) then reads an empty slot instead of a null table.
		final @Nullable FieldrefConstant streamsFieldRef = usesErrorOutput
				? cp.addFieldref(thisClass, cp.addNameAndType(streamsFieldName, streamsFieldDesc)) : null;
		final @Nullable FieldrefConstant streamCountFieldRef = usesErrorOutput
				? cp.addFieldref(thisClass, cp.addNameAndType(streamCountFieldName, streamCountFieldDesc)) : null;
		final boolean initsClinit = seedsStandardStream || usesErrorOutput || topLevelInClinit;
		final Utf8Constant standardOutputClinitName = initsClinit ? cp.addUtf8("<clinit>") : null;
		final Utf8Constant standardOutputClinitDesc = initsClinit ? cp.addUtf8("()V") : null;

		// Branch relaxation: any Ctx-compiled body whose patchBranch overflowed the
		// signed 16-bit encoding is rewritten over goto_w here, before assembly
		// (fast-http's generated parse-header-field-and-value state machine is the
		// real-world trigger). A method with no deferred branch is untouched, byte for
		// byte. The runtime-builder methods never defer: their raw-list patchBranch
		// still throws, and they stay under budget by construction.
		am.ik.jvm.BranchRelaxer.relax(mainCtx.code, mainCtx.deferredBranches, mainCtx.exceptionTable);
		if (topRunnerCtx != null) {
			am.ik.jvm.BranchRelaxer.relax(topRunnerCtx.code, topRunnerCtx.deferredBranches,
					topRunnerCtx.exceptionTable);
		}
		for (Ctx chunk : topChunks) {
			am.ik.jvm.BranchRelaxer.relax(chunk.code, chunk.deferredBranches, chunk.exceptionTable);
		}
		for (Ctx funcCtx : funcCtxs) {
			am.ik.jvm.BranchRelaxer.relax(funcCtx.code, funcCtx.deferredBranches, funcCtx.exceptionTable);
		}
		for (Ctx lambdaCtx : lambdaCtxs) {
			am.ik.jvm.BranchRelaxer.relax(lambdaCtx.code, lambdaCtx.deferredBranches, lambdaCtx.exceptionTable);
		}
		for (Ctx fusedCtx : fusedCtxs) {
			am.ik.jvm.BranchRelaxer.relax(fusedCtx.code, fusedCtx.deferredBranches, fusedCtx.exceptionTable);
		}
		for (JvmBodyOutliner.OutlinedBody outlined : mainCtx.outlinedBodies) {
			am.ik.jvm.BranchRelaxer.relax(outlined.ctx().code, outlined.ctx().deferredBranches,
					outlined.ctx().exceptionTable);
		}
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

		ByteArrayOutputStream classOut = new ByteArrayOutputStream();
		new ByteCodeWriter(classOut) //
			.write(0xCA, 0xFE, 0xBA, 0xBE) //
			.writeVersion(0, 50) //
			.writeConstantPool(cp) //
			.writeClass(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_SUPER, thisClass, objectClass) //
			.writeInterfaces(i -> {
				if (x509TrustManagerClass != null) {
					i.add(w -> w.writeU2(x509TrustManagerClass.index()));
				}
				if (httpHandlerRuntime != null) {
					i.add(w -> w.writeU2(httpHandlerRuntime.handlerInterface().index()));
				}
				if (runnableClass != null) {
					i.add(w -> w.writeU2(runnableClass.index()));
				}
				if (callableClass != null) {
					i.add(w -> w.writeU2(callableClass.index()));
				}
			})
			.writeFields(f -> {
				f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
					.writeU2(stdinReaderFieldName)
					.writeU2(stdinReaderFieldDesc)
					.writeU2(0));
				if (secureRandomRuntime != null) {
					f.add(w -> w.writeU2(JvmSecureRandomRuntimeBuilder.fieldAccessFlags())
						.writeU2(secureRandomRuntime.fieldName())
						.writeU2(secureRandomRuntime.fieldDesc())
						.writeU2(0));
				}
				if (argvRuntime != null) {
					f.add(w -> w.writeU2(JvmArgvRuntimeBuilder.fieldAccessFlags())
						.writeU2(argvRuntime.fieldName())
						.writeU2(argvRuntime.fieldDesc())
						.writeU2(0));
				}
				// VOLATILE: the synchronized _addStream writes the table back on every
				// call, and that store is what publishes a new entry to the reader
				// threads (one virtual thread per served request).
				f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_VOLATILE)
					.writeU2(streamsFieldName)
					.writeU2(streamsFieldDesc)
					.writeU2(0));
				f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
					.writeU2(streamCountFieldName)
					.writeU2(streamCountFieldDesc)
					.writeU2(0));
				if (streamPathsFieldName != null) {
					// VOLATILE for the same reason _streams is: _setStreamPath is
					// synchronized and its write-back publishes the table.
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_VOLATILE)
						.writeU2(streamPathsFieldName)
						.writeU2(java.util.Objects.requireNonNull(streamPathsFieldDesc))
						.writeU2(0));
				}
				f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
					.writeU2(colFieldName)
					.writeU2(colFieldDesc)
					.writeU2(0));
				f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
					.writeU2(gensymCtrFieldName)
					.writeU2(gensymCtrFieldDesc)
					.writeU2(0));
				// The two strings last PROVEN to hold no surrogate pair, so a character
				// index into one is 1 + i. Deliberately NOT volatile: a String is
				// immutable and a reference field is written atomically, so a racing
				// reader sees an older string (a re-probe) but never a torn pair.
				for (Utf8Constant siName : stringIndexFieldNames) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(siName)
						.writeU2(stringIndexFieldDesc)
						.writeU2(0));
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
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_VOLATILE)
						.writeU2(httpHandlerRuntime.handlerFieldName())
						.writeU2(httpHandlerRuntime.handlerFieldDesc())
						.writeU2(0));
				}
				if (asyncRuntimeBodies != null) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(java.util.Objects.requireNonNull(handoffFieldName))
						.writeU2(java.util.Objects.requireNonNull(handoffFieldDesc))
						.writeU2(0));
					for (Utf8Constant instField : List.of(java.util.Objects.requireNonNull(asyncFnFieldName),
							java.util.Objects.requireNonNull(asyncFutureFieldName),
							java.util.Objects.requireNonNull(asyncLatchFieldName))) {
						f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE)
							.writeU2(instField)
							.writeU2(java.util.Objects.requireNonNull(asyncInstanceFieldDesc))
							.writeU2(0));
					}
				}
				if (threadRuntimeBodies != null) {
					for (Utf8Constant instField : List.of(java.util.Objects.requireNonNull(threadFnFieldName),
							java.util.Objects.requireNonNull(threadBindingsFieldName))) {
						f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE)
							.writeU2(instField)
							.writeU2(java.util.Objects.requireNonNull(threadInstanceFieldDesc))
							.writeU2(0));
					}
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(java.util.Objects.requireNonNull(curThreadTlFieldName))
						.writeU2(java.util.Objects.requireNonNull(curThreadTlFieldDesc))
						.writeU2(0));
				}
				// One static Object field per top-level global variable (default null =
				// nil); written by setq/defvar, read by getstatic from any method body.
				for (Utf8Constant gfName : globalFieldNameUtfs) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(gfName)
						.writeU2(globalFieldDescUtf)
						.writeU2(0));
				}
				// The raw long half and the int flag of an unboxed global's triple; the
				// _g$ field above is its boxed shadow. Both default to 0, so the flag
				// starts clear and the shadow's null (nil) is authoritative -- exactly
				// the state a plain global starts in.
				for (Utf8Constant rgName : rawGlobalLongFieldNameUtfs) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(rgName)
						.writeU2(Objects.requireNonNull(rawGlobalLongDescUtf))
						.writeU2(0));
				}
				for (Utf8Constant rkName : rawGlobalFlagFieldNameUtfs) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(rkName)
						.writeU2(Objects.requireNonNull(rawGlobalFlagDescUtf))
						.writeU2(0));
				}
				if (dynVarRuntime != null) {
					// One static ThreadLocal per dynamically-bound special: the thread's
					// innermost dynamic binding as a one-element Object[] cell (see
					// JvmDynVarRuntimeBuilder); created in <clinit>.
					for (Utf8Constant dfName : dynVarRuntime.fieldNameUtfs()) {
						f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
							.writeU2(dfName)
							.writeU2(dynVarRuntime.fieldDescUtf())
							.writeU2(0));
					}
				}
				if (javaRuntime != null) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(javaRuntime.initedFieldName())
						.writeU2(javaRuntime.initedFieldDesc())
						.writeU2(0));
				}
				if (objcRuntime != null) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(objcRuntime.initedFieldName())
						.writeU2(objcRuntime.initedFieldDesc())
						.writeU2(0));
				}
				if (ffiRuntime != null) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(ffiRuntime.initedFieldName())
						.writeU2(ffiRuntime.initedFieldDesc())
						.writeU2(0));
				}
				if (simdRuntime != null) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(simdRuntime.initedFieldName())
						.writeU2(simdRuntime.initedFieldDesc())
						.writeU2(0));
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(simdRuntime.availableFieldName())
						.writeU2(simdRuntime.availableFieldDesc())
						.writeU2(0));
				}
				if (blasRuntime != null) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(blasRuntime.initedFieldName())
						.writeU2(blasRuntime.initedFieldDesc())
						.writeU2(0));
				}
				if (gpuRuntime != null) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(gpuRuntime.initedFieldName())
						.writeU2(gpuRuntime.initedFieldDesc())
						.writeU2(0));
				}
				if (usesEval) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(genvName)
						.writeU2(genvDesc)
						.writeU2(0));
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(fenvName)
						.writeU2(genvDesc)
						.writeU2(0));
				}
				if (usesRead) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(readSrcName)
						.writeU2(readSrcDesc)
						.writeU2(0));
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(readPosName)
						.writeU2(readPosDesc)
						.writeU2(0));
				}
				if (!structTableClinitFinal.isEmpty()) {
					// The runtime struct-layout directory for #S(...) read at run time.
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(rdStructsName)
						.writeU2(rdStructsDesc)
						.writeU2(0));
				}
				if (mainCtx.conditionChannel.used) {
					// The per-thread condition carrier from a %error-cond throw site to a
					// handler-case catch handler; initialized in <clinit>.
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(java.util.Objects.requireNonNull(mainCtx.conditionChannel.fieldName))
						.writeU2(java.util.Objects.requireNonNull(mainCtx.conditionChannel.fieldDesc))
						.writeU2(0));
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(java.util.Objects.requireNonNull(mainCtx.conditionChannel.depthFieldName))
						.writeU2(java.util.Objects.requireNonNull(mainCtx.conditionChannel.fieldDesc))
						.writeU2(0));
				}
				if (mainCtx.conditionChannel.nleUsed) {
					// The per-thread non-local-exit carrier from a %nlx-throw site to the
					// matching %nlx-catch (a {throwable, id, value} Object[]);
					// initialized
					// in <clinit>.
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(java.util.Objects.requireNonNull(mainCtx.conditionChannel.nleFieldName))
						.writeU2(java.util.Objects.requireNonNull(mainCtx.conditionChannel.fieldDesc))
						.writeU2(0));
				}
				// One private static String[] per instance layout the program references:
				// {tag, printName, "S"|"C", slot0, ...}. Initialized in <clinit>; the
				// array in slot 0 of an instance is also its type discriminator. The
				// attribute count MUST stay 0 -- JvmClassShaker rejects field attributes.
				for (LayoutPool.LayoutField lf : mainCtx.layoutPool.fields()) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(lf.name())
						.writeU2(java.util.Objects.requireNonNull(mainCtx.layoutPool.fieldDesc))
						.writeU2(0));
				}
				// One private static BigInteger per DISTINCT bignum literal, built once
				// in <clinit> so a use site is a GETSTATIC. The attribute count MUST stay
				// 0 -- JvmClassShaker rejects field attributes -- which is also why the
				// field is not marked ACC_FINAL-with-ConstantValue: a BigInteger has no
				// constant-pool form.
				for (BigIntPool.BigIntField bf : mainCtx.bigIntPool.fields()) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(bf.name())
						.writeU2(java.util.Objects.requireNonNull(mainCtx.bigIntPool.fieldDesc))
						.writeU2(0));
				}
			})
			.writeMethods(methods -> {
				if (!this.noMain) {
					methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, mainUtf8, mainDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(mainCtx.maxStack())
									.writeU2(mainCtx.maxLocals)
									.writeCode((Object[]) mainCtx.code.toArray(new Integer[0]))
									.writeExceptionTable(mainCtx.exceptionTable)
									.writeU2(0);
							})));
				}
				if (topRunnerCtxFinal != null) {
					// _top$run: the top-level body <clinit> runs (see mainCtx above).
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC,
							java.util.Objects.requireNonNull(topRunnerName), topChunkDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(topRunnerCtxFinal.maxStack())
									.writeU2(topRunnerCtxFinal.maxLocals)
									.writeCode((Object[]) topRunnerCtxFinal.code.toArray(new Integer[0]))
									.writeExceptionTable(topRunnerCtxFinal.exceptionTable)
									.writeU2(0);
							})));
				}
				for (JvmExportRuntimeBuilder.BuiltMethod em : exportMethods) {
					methods.add(
							(em.isPublic() ? AccessFlag.ACC_PUBLIC : AccessFlag.ACC_PRIVATE) | AccessFlag.ACC_STATIC,
							em.name(), em.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(em.maxStack())
									.writeU2(em.maxLocals())
									.writeCode((Object[]) em.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				// The top-level body, split into one or more void chunk methods main()
				// calls.
				for (int i = 0; i < topChunks.size(); i++) {
					final Ctx chunk = topChunks.get(i);
					methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, topChunkNames.get(i), topChunkDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(chunk.maxStack())
									.writeU2(chunk.maxLocals)
									.writeCode((Object[]) chunk.code.toArray(new Integer[0]))
									.writeExceptionTable(chunk.exceptionTable)
									.writeU2(0);
							})));
				}
				for (int i = 0; i < defuns.size(); i++) {
					FunctionInfo fi = java.util.Objects.requireNonNull(functions.get(defuns.get(i).name));
					final Ctx funcCtx = funcCtxs.get(i);
					methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, fi.nameUtf8, fi.descUtf8,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(funcCtx.maxStack())
									.writeU2(funcCtx.maxLocals)
									.writeCode((Object[]) funcCtx.code.toArray(new Integer[0]))
									.writeExceptionTable(funcCtx.exceptionTable)
									.writeU2(0);
							})));
				}
				for (int i = 0; i < lambdaCtxs.size(); i++) {
					FunctionInfo fi = lambdaFuncInfos.get(i);
					final Ctx lambdaCtx = lambdaCtxs.get(i);
					methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, fi.nameUtf8, fi.descUtf8,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(lambdaCtx.maxStack())
									.writeU2(lambdaCtx.maxLocals)
									.writeCode((Object[]) lambdaCtx.code.toArray(new Integer[0]))
									.writeExceptionTable(lambdaCtx.exceptionTable)
									.writeU2(0);
							})));
				}
				for (DispatchMethod dm : dispatchMethods) {
					methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, dm.nameUtf8, dm.descUtf8,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(64)
									.writeU2(dm.maxLocals)
									.writeCode((Object[]) dm.code.toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				if (mainCtx.conditionChannel.used || mainCtx.conditionChannel.nleUsed || !mainCtx.layoutPool.isEmpty()
						|| !mainCtx.bigIntPool.isEmpty() || !structTableClinitFinal.isEmpty() || dynVarRuntime != null
						|| initsClinit) {
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
					List<FieldrefConstant> tlFields = new java.util.ArrayList<>();
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
					if (curThreadTlFieldRef != null) {
						// The _thread_current handle cache joins the same initializer.
						tlFields.add(curThreadTlFieldRef);
					}
					List<Integer> clinitCode = new java.util.ArrayList<>();
					for (FieldrefConstant tlField : tlFields) {
						clinitCode.add(Opcode.NEW);
						JvmRuntimeBuilder.emitU2(clinitCode,
								java.util.Objects.requireNonNull(channel.threadLocalClass).index());
						clinitCode.add(Opcode.DUP);
						clinitCode.add(Opcode.INVOKESPECIAL);
						JvmRuntimeBuilder.emitU2(clinitCode, java.util.Objects.requireNonNull(channel.tlCtor).index());
						clinitCode.add(Opcode.PUTSTATIC);
						JvmRuntimeBuilder.emitU2(clinitCode, tlField.index());
					}
					if (dynVarRuntime != null) {
						// The dynamic-binding ThreadLocals (one per bound special) join
						// the
						// same initializer -- never lazily: a racy first binding from two
						// request threads would mint two ThreadLocals and lose one
						// binding.
						clinitCode.addAll(dynVarRuntime.clinitCode());
					}
					if (streamsFieldRef != null) {
						// _streams = new Object[16]; _streamCount = 3 -- the reserved
						// standard-stream handles as empty table slots (see above).
						clinitCode.add(Opcode.BIPUSH);
						clinitCode.add(16);
						clinitCode.add(Opcode.ANEWARRAY);
						JvmRuntimeBuilder.emitU2(clinitCode, objectClass.index());
						clinitCode.add(Opcode.PUTSTATIC);
						JvmRuntimeBuilder.emitU2(clinitCode, streamsFieldRef.index());
						clinitCode.add(Opcode.ICONST_3);
						clinitCode.add(Opcode.PUTSTATIC);
						JvmRuntimeBuilder.emitU2(clinitCode,
								java.util.Objects.requireNonNull(streamCountFieldRef).index());
					}
					// The bignum literals go in before the layouts: they are plain
					// values with no dependency of their own, and every later fragment
					// (and the top-level runner, invoked last) may read them.
					clinitCode.addAll(bigIntClinitCode);
					clinitCode.addAll(layoutClinitCode);
					// The standard stream variables' defaults, one table
					// (StreamDesignators) feeding BOTH homes: the per-name global field
					// a direct read uses, and the eval runtime's _genv mirror that
					// symbol-value / boundp / eval probe.
					for (Map.Entry<String, LispVal> streamVar : StreamDesignators.standardStreamDefaults().entrySet()) {
						FieldrefConstant globalField = streamGlobalSeeds.get(streamVar.getKey());
						if (globalField != null) {
							emitStreamDefault(clinitCode, streamVar.getValue(), standardOutputTStr, longValueOf,
									objectClass, streamLayoutField, streamKindStandardStr);
							clinitCode.add(Opcode.PUTSTATIC);
							JvmRuntimeBuilder.emitU2(clinitCode, globalField.index());
						}
						ConstantPool.StringConstant seedName = streamGenvSeeds.get(streamVar.getKey());
						if (seedName != null) {
							// _genv = {{name, default}, _genv} -- the binding shape
							// _store prepends, so a later top-level assignment MUTATES
							// this cell rather than shadowing it.
							clinitCode.add(Opcode.ICONST_2);
							clinitCode.add(Opcode.ANEWARRAY);
							JvmRuntimeBuilder.emitU2(clinitCode, objectClass.index());
							clinitCode.add(Opcode.DUP);
							clinitCode.add(Opcode.ICONST_0);
							clinitCode.add(Opcode.ICONST_2);
							clinitCode.add(Opcode.ANEWARRAY);
							JvmRuntimeBuilder.emitU2(clinitCode, objectClass.index());
							clinitCode.add(Opcode.DUP);
							clinitCode.add(Opcode.ICONST_0);
							clinitCode.add(Opcode.LDC_W);
							JvmRuntimeBuilder.emitU2(clinitCode, seedName.index());
							clinitCode.add(Opcode.AASTORE);
							clinitCode.add(Opcode.DUP);
							clinitCode.add(Opcode.ICONST_1);
							emitStreamDefault(clinitCode, streamVar.getValue(), standardOutputTStr, longValueOf,
									objectClass, streamLayoutField, streamKindStandardStr);
							clinitCode.add(Opcode.AASTORE);
							clinitCode.add(Opcode.AASTORE);
							clinitCode.add(Opcode.DUP);
							clinitCode.add(Opcode.ICONST_1);
							clinitCode.add(Opcode.GETSTATIC);
							JvmRuntimeBuilder.emitU2(clinitCode, genvField.index());
							clinitCode.add(Opcode.AASTORE);
							clinitCode.add(Opcode.PUTSTATIC);
							JvmRuntimeBuilder.emitU2(clinitCode, genvField.index());
						}
					}
					clinitCode.addAll(structTableClinitFinal);
					if (topRunnerRef != null) {
						// Run the top level last, after every piece of runtime infra
						// above is seeded — this is the export-carrying class's
						// "top level at instantiation" (see mainCtx above).
						clinitCode.add(Opcode.INVOKESTATIC);
						JvmRuntimeBuilder.emitU2(clinitCode, topRunnerRef.index());
					}
					clinitCode.add(Opcode.RETURN);
					// max_stack: the ThreadLocal group peaks at 2 (NEW; DUP), the layout
					// group at 4 (array; DUP; index; LDC), the reader's struct directory
					// at 10 (outer array, entry, initTexts nested builds each keep a DUP
					// and an index live), a _genv seed at 8 (outer array plus index under
					// the inner array build, whose boxed handle is briefly a long).
					// StackMapAugmenter copies the declared maximum verbatim, so an
					// under-declaration is a VerifyError at class load, not a compile
					// error.
					// A stream-VALUE seed adds its own Object[3] build (array, dup,
					// index, then a briefly-two-slot long) on top of whichever nest it
					// sits in, hence the +6 -- an over-declared maximum is free, an
					// under-declared one is a VerifyError at class load.
					// A bignum initializer peaks at 3 (the uninitialized BigInteger, its
					// dup, the decimal string).
					final int clinitMaxStack = Math.max(
							Math.max(streamGenvSeeds.isEmpty() ? 0 : 8, mainCtx.bigIntPool.isEmpty() ? 0 : 3),
							!structTableClinitFinal.isEmpty() ? 10 : (mainCtx.layoutPool.isEmpty() ? 2 : 4))
							+ (streamLayoutField != null ? 6 : 0);
					// A layout-only program never runs ensureThreadLocalInfra, so the
					// channel's <clinit> name constants are null there; a
					// bound-special-only
					// program has neither, so the dyn-var runtime carries its own.
					Utf8Constant clinitNameUtf = channel.clinitName != null ? channel.clinitName
							: mainCtx.layoutPool.clinitName != null ? mainCtx.layoutPool.clinitName
									: dynVarRuntime != null ? dynVarRuntime.clinitName()
											: standardOutputClinitName != null ? standardOutputClinitName
													: java.util.Objects.requireNonNull(mainCtx.bigIntPool.clinitName);
					Utf8Constant clinitDescUtf = channel.clinitDesc != null ? channel.clinitDesc
							: mainCtx.layoutPool.clinitDesc != null ? mainCtx.layoutPool.clinitDesc
									: dynVarRuntime != null ? dynVarRuntime.clinitDesc()
											: standardOutputClinitDesc != null ? standardOutputClinitDesc
													: java.util.Objects.requireNonNull(mainCtx.bigIntPool.clinitDesc);
					methods.add(AccessFlag.ACC_STATIC, clinitNameUtf, clinitDescUtf,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(clinitMaxStack)
									.writeU2(0)
									.writeCode((Object[]) clinitCode.toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				if (dynVarRuntime != null) {
					// _dget/_dbind/_dset: the shared thread-scoped dynamic-binding
					// helpers.
					for (JvmDynVarRuntimeBuilder.HelperMethod hm : dynVarRuntime.methods()) {
						methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, hm.nameUtf8(), hm.descUtf8(),
								method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
									attr.writeU2(hm.maxStack())
										.writeU2(hm.maxLocals())
										.writeCode((Object[]) hm.code().toArray(new Integer[0]))
										.writeU2(0)
										.writeU2(0);
								})));
					}
				}
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, strEscName, strEscDescUtf,
						method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(6)
								.writeU2(2)
								.writeCode((Object[]) strEscCode.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, lispToStringName, lispToStringDescUtf,
						method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(3)
								.writeU2(2)
								.writeCode((Object[]) ltsCode.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
				if (usesInstances) {
					// _instToString / _instToDisplayString: one body builder, two element
					// formatters, so the readable and display renderings cannot drift.
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC,
							Objects.requireNonNull(instToStringName), consToStringDescUtf,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(5)
									.writeU2(5)
									.writeCode((Object[]) instCode.toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC,
							Objects.requireNonNull(instToDisplayStringName), consToStringDescUtf,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(5)
									.writeU2(5)
									.writeCode((Object[]) instDisplayCode.toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, consToStringName, consToStringDescUtf,
						method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(3)
								.writeU2(5)
								.writeCode((Object[]) ctsCode.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, appendName, appendDescUtf,
						method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(5)
								.writeU2(3)
								.writeCode((Object[]) appendCode.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, readLineHelperName, readLineHelperDesc,
						method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(5)
								.writeU2(1)
								.writeCode((Object[]) readLineCode.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
				for (JvmIoRuntimeBuilder.IoMethod im : ioMethods) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | im.extraFlags(), im.name(), im.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(im.maxStack())
									.writeU2(im.maxLocals())
									.writeCode((Object[]) im.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				// The five lazy _*Init methods below define an embedded class (or bind a
				// native library) behind a plain int guard, and a served program runs
				// one virtual thread per request -- two first calls arriving together
				// both passed the guard and the second defineClass died with a
				// LinkageError (found by WarE2eTest's concurrent burst; the exact bug
				// family .kb/concurrent-served-requests.md records for the
				// interpreter's lazy loads, whose rule is: take the lock, check the
				// flag, set it, evaluate). ACC_SYNCHRONIZED is that rule in bytecode;
				// steady state pays one uncontended class monitor per call, which every
				// one of these paths (reflection, FFM, a kernel) dwarfs.
				if (javaRuntime != null) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED,
							javaRuntime.initName(), javaRuntime.initDesc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(javaRuntime.maxStack())
									.writeU2(javaRuntime.maxLocals())
									.writeCode((Object[]) javaRuntime.initCode().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				if (objcRuntime != null) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED,
							objcRuntime.initName(), objcRuntime.initDesc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(objcRuntime.maxStack())
									.writeU2(objcRuntime.maxLocals())
									.writeCode((Object[]) objcRuntime.initCode().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				if (ffiRuntime != null) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED,
							ffiRuntime.initName(), ffiRuntime.initDesc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(ffiRuntime.maxStack())
									.writeU2(ffiRuntime.maxLocals())
									.writeCode((Object[]) ffiRuntime.initCode().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				if (simdRuntime != null) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED,
							simdRuntime.initName(), simdRuntime.initDesc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(simdRuntime.maxStack())
									.writeU2(simdRuntime.maxLocals())
									.writeCode((Object[]) simdRuntime.initCode().toArray(new Integer[0]))
									.writeExceptionTable(simdRuntime.initExceptionTable())
									.writeU2(0);
							})));
					// _simdReady(): returns whether the bridge define succeeded --
					// _simdInit must have run first, same as every ops.get(member)
					// call site. False on a runtime without jdk.incubator.vector, so
					// the accelerated call sites (JvmSimdCompiler, the --simd rung of
					// JvmLinalgKernelCompiler's chain) can decline to the scalar defun
					// instead of resolving a method reference into a bridge class that
					// was never defined.
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, simdRuntime.readyName(),
							simdRuntime.readyDesc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(1)
									.writeU2(0)
									.writeCode((Object[]) simdRuntime.readyCode().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				if (blasRuntime != null) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED,
							blasRuntime.initName(), blasRuntime.initDesc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(blasRuntime.maxStack())
									.writeU2(blasRuntime.maxLocals())
									.writeCode((Object[]) blasRuntime.initCode().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				if (gpuRuntime != null) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED,
							gpuRuntime.initName(), gpuRuntime.initDesc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(gpuRuntime.maxStack())
									.writeU2(gpuRuntime.maxLocals())
									.writeCode((Object[]) gpuRuntime.initCode().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
					// The residency invalidation guard, called from every in-place write
					// to a packed float array, answering the array to write into
					// (JvmGpuRuntimeBuilder.WRITTEN_METHOD).
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, gpuRuntime.writtenName(),
							gpuRuntime.writtenDesc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(1)
									.writeU2(1)
									.writeCode((Object[]) gpuRuntime.writtenCode().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
					// Its read-side twin, called before every host read of one and
					// answering the array to read
					// (JvmGpuRuntimeBuilder.MATERIALIZE_METHOD).
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, gpuRuntime.materializeName(),
							gpuRuntime.materializeDesc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(1)
									.writeU2(1)
									.writeCode((Object[]) gpuRuntime.materializeCode().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
					// And the one a call site runs over a host rung's answer, per
					// argument it handed over (JvmGpuRuntimeBuilder.UNSWAP_METHOD).
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, gpuRuntime.unswapName(),
							gpuRuntime.unswapDesc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(3)
									.writeU2(3)
									.writeCode((Object[]) gpuRuntime.unswapCode().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				if (fetchRuntimeBodies != null) {
					JvmFetchRuntimeBuilder.FetchMethod fm = fetchRuntimeBodies.fetch();
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, fm.name(), fm.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(fm.maxStack())
									.writeU2(fm.maxLocals())
									.writeCode((Object[]) fm.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				if (asyncRuntimeBodies != null) {
					for (JvmAsyncRuntimeBuilder.AsyncMethod am : asyncRuntimeBodies.staticMethods()) {
						methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, am.name(), am.desc(),
								method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
									attr.writeU2(am.maxStack())
										.writeU2(am.maxLocals())
										.writeCode((Object[]) am.code().toArray(new Integer[0]));
									writeAsyncExceptionTable(attr, am);
									attr.writeU2(0);
								})));
					}
					JvmAsyncRuntimeBuilder.AsyncMethod runBody = asyncRuntimeBodies.runMethod();
					methods.add(AccessFlag.ACC_PUBLIC, runBody.name(), runBody.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(runBody.maxStack())
									.writeU2(runBody.maxLocals())
									.writeCode((Object[]) runBody.code().toArray(new Integer[0]));
								writeAsyncExceptionTable(attr, runBody);
								attr.writeU2(0);
							})));
				}
				if (octetsStrictRuntime != null) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, octetsStrictRuntime.name(),
							octetsStrictRuntime.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(octetsStrictRuntime.maxStack())
									.writeU2(octetsStrictRuntime.maxLocals())
									.writeCode((Object[]) octetsStrictRuntime.code().toArray(new Integer[0]));
								writeAsyncExceptionTable(attr, octetsStrictRuntime);
								attr.writeU2(0);
							})));
				}
				if (secureRandomRuntime != null) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, secureRandomRuntime.name(),
							secureRandomRuntime.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(secureRandomRuntime.maxStack())
									.writeU2(secureRandomRuntime.maxLocals())
									.writeCode((Object[]) secureRandomRuntime.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				if (argvRuntime != null) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, argvRuntime.name(), argvRuntime.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(argvRuntime.maxStack())
									.writeU2(argvRuntime.maxLocals())
									.writeCode((Object[]) argvRuntime.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				for (JvmMutexRuntimeBuilder.MutexMethod mm : mutexMethods) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, mm.name(), mm.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(mm.maxStack())
									.writeU2(mm.maxLocals())
									.writeCode((Object[]) mm.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				if (threadRuntimeBodies != null) {
					for (JvmThreadRuntimeBuilder.ThreadMethod tm : threadRuntimeBodies.staticMethods()) {
						methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, tm.name(), tm.desc(),
								method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
									attr.writeU2(tm.maxStack())
										.writeU2(tm.maxLocals())
										.writeCode((Object[]) tm.code().toArray(new Integer[0]));
									writeThreadExceptionTable(attr, tm);
									attr.writeU2(0);
								})));
					}
					JvmThreadRuntimeBuilder.ThreadMethod callBody = threadRuntimeBodies.callMethod();
					methods.add(AccessFlag.ACC_PUBLIC, callBody.name(), callBody.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(callBody.maxStack())
									.writeU2(callBody.maxLocals())
									.writeCode((Object[]) callBody.code().toArray(new Integer[0]));
								writeThreadExceptionTable(attr, callBody);
								attr.writeU2(0);
							})));
				}
				if (socketRuntime != null) {
					for (JvmSocketRuntimeBuilder.SocketMethod sm : socketRuntime.methods()) {
						methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, sm.name(), sm.desc(),
								method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
									attr.writeU2(sm.maxStack())
										.writeU2(sm.maxLocals())
										.writeCode((Object[]) sm.code().toArray(new Integer[0]))
										.writeU2(0)
										.writeU2(0);
								})));
					}
				}
				if (needsInstanceCtor) {
					// No-arg constructor: super(). _tlsConnect does `new Prog()` for the
					// :insecure trust-all manager; the http-handler directive does the
					// same for the RontoHttpServer.Handler instance.
					Utf8Constant initName = java.util.Objects.requireNonNull(instanceInitName);
					Utf8Constant initDesc = java.util.Objects.requireNonNull(instanceInitDesc);
					int objectInitIdx = java.util.Objects.requireNonNull(objectInitRef).index();
					List<Integer> instanceInitCode = new java.util.ArrayList<>(
							List.of(Opcode.ALOAD_0, Opcode.INVOKESPECIAL));
					JvmRuntimeBuilder.emitU2(instanceInitCode, objectInitIdx);
					instanceInitCode.add(Opcode.RETURN);
					methods.add(AccessFlag.ACC_PUBLIC, initName, initDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8,
									attr -> attr.writeU2(1)
										.writeU2(1)
										.writeCode((Object[]) instanceInitCode.toArray(new Integer[0]))
										.writeU2(0)
										.writeU2(0))));
				}
				if (usesTlsConnect) {
					Utf8Constant clientName = java.util.Objects.requireNonNull(checkClientName);
					Utf8Constant serverName = java.util.Objects.requireNonNull(checkServerName);
					Utf8Constant trustedDesc = java.util.Objects.requireNonNull(checkTrustedDesc);
					Utf8Constant issuersName = java.util.Objects.requireNonNull(acceptedIssuersName);
					Utf8Constant issuersDesc = java.util.Objects.requireNonNull(acceptedIssuersDesc);
					int x509CertIdx = java.util.Objects.requireNonNull(x509CertificateClass).index();
					// X509TrustManager: trust-all client/server checks (empty bodies) and
					// an empty accepted-issuers array.
					methods.add(AccessFlag.ACC_PUBLIC, clientName, trustedDesc, method -> method
						.writeAttributes(attrs -> attrs.add(codeUtf8,
								attr -> attr.writeU2(0).writeU2(3).writeCode(Opcode.RETURN).writeU2(0).writeU2(0))));
					methods.add(AccessFlag.ACC_PUBLIC, serverName, trustedDesc, method -> method
						.writeAttributes(attrs -> attrs.add(codeUtf8,
								attr -> attr.writeU2(0).writeU2(3).writeCode(Opcode.RETURN).writeU2(0).writeU2(0))));
					List<Integer> acceptedIssuersCode = new java.util.ArrayList<>(
							List.of(Opcode.ICONST_0, Opcode.ANEWARRAY));
					JvmRuntimeBuilder.emitU2(acceptedIssuersCode, x509CertIdx);
					acceptedIssuersCode.add(Opcode.ARETURN);
					methods.add(AccessFlag.ACC_PUBLIC, issuersName, issuersDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8,
									attr -> attr.writeU2(1)
										.writeU2(1)
										.writeCode((Object[]) acceptedIssuersCode.toArray(new Integer[0]))
										.writeU2(0)
										.writeU2(0))));
				}
				if (httpHandlerRuntime != null) {
					// handle(Request): the RontoHttpServer.Handler implementation
					// adapting each incoming request to the compiled Lisp handler.
					JvmHttpHandlerRuntimeBuilder.HandleMethod hm = httpHandlerRuntime.handle();
					methods.add(AccessFlag.ACC_PUBLIC, hm.name(), hm.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(hm.maxStack())
									.writeU2(hm.maxLocals())
									.writeCode((Object[]) hm.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				{
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, lengthMethodBody.name(),
							lengthMethodBody.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(lengthMethodBody.maxStack())
									.writeU2(lengthMethodBody.maxLocals())
									.writeCode((Object[]) lengthMethodBody.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				{
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, nthcdrMethodBody.name(),
							nthcdrMethodBody.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(nthcdrMethodBody.maxStack())
									.writeU2(nthcdrMethodBody.maxLocals())
									.writeCode((Object[]) nthcdrMethodBody.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				for (JvmStringIndexRuntimeBuilder.StringIndexMethod sm : stringIndexMethods) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, sm.name(), sm.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(sm.maxStack())
									.writeU2(sm.maxLocals())
									.writeCode((Object[]) sm.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				for (JvmReadRuntimeBuilder.ReadMethod rm : readMethodsFinal) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, rm.name(), rm.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(rm.maxStack())
									.writeU2(rm.maxLocals())
									.writeCode((Object[]) rm.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				for (JvmHashRuntimeBuilder.HashMethod hm : hashMethods) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, hm.name(), hm.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(hm.maxStack())
									.writeU2(hm.maxLocals())
									.writeCode((Object[]) hm.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				for (JvmArrayRuntimeBuilder.ArrayMethod am : arrayMethods) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, am.name(), am.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(am.maxStack())
									.writeU2(am.maxLocals())
									.writeCode((Object[]) am.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				for (JvmNumericRuntimeBuilder.NumericMethod nm : numericRuntime.methods()) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, nm.nameUtf8(), nm.descUtf8(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(nm.maxStack())
									.writeU2(nm.maxLocals())
									.writeCode((Object[]) nm.code().toArray(new Integer[0]))
									.writeU2(nm.exceptionTable().size());
								for (int[] entry : nm.exceptionTable()) {
									attr.writeU2(entry[0]).writeU2(entry[1]).writeU2(entry[2]).writeU2(entry[3]);
								}
								attr.writeU2(0);
							})));
				}
				// The outlined fused-site methods (.kb/jvm-int-fusion.md) and their
				// two shared helpers, present only when Pass 2 registered a site / a
				// raw local -- a program without one is byte-identical to before.
				for (int i = 0; i < fusedCtxs.size(); i++) {
					JvmIntFusionCompiler.Pending pendingFused = fusedState.pending.get(i);
					final Ctx fusedCtx = fusedCtxs.get(i);
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, pendingFused.nameUtf8(),
							pendingFused.descUtf8(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(fusedCtx.maxStack())
									.writeU2(fusedCtx.maxLocals)
									.writeCode((Object[]) fusedCtx.code.toArray(new Integer[0]))
									.writeExceptionTable(fusedCtx.exceptionTable)
									.writeU2(0);
							})));
				}
				// The outlined tail continuations of a body that would have compiled
				// past HotSpot's HugeMethodLimit (JvmBodyOutliner); empty for every
				// program whose bodies stay under the budget.
				for (JvmBodyOutliner.OutlinedBody outlined : mainCtx.outlinedBodies) {
					final Ctx outlinedCtx = outlined.ctx();
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, outlined.nameUtf8(),
							outlined.descUtf8(), method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(outlinedCtx.maxStack())
									.writeU2(outlinedCtx.maxLocals)
									.writeCode((Object[]) outlinedCtx.code.toArray(new Integer[0]))
									.writeExceptionTable(outlinedCtx.exceptionTable)
									.writeU2(0);
							})));
				}
				for (JvmNumericRuntimeBuilder.NumericMethod nm : fusedHelperMethods) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, nm.nameUtf8(), nm.descUtf8(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(nm.maxStack())
									.writeU2(nm.maxLocals())
									.writeCode((Object[]) nm.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, lispToDisplayStringName,
						lispToStringDescUtf, method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(4)
								.writeU2(2)
								.writeCode((Object[]) ltdsCode.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, consToDisplayStringName,
						consToStringDescUtf, method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(3)
								.writeU2(5)
								.writeCode((Object[]) ctdsCode.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, charPrin1Name, charPrin1Desc,
						method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(3)
								.writeU2(1)
								.writeCode((Object[]) charPrin1Code.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
				for (int g = 0; g < lookupBodies.size(); g++) {
					final List<Integer> segBody = lookupBodies.get(g);
					Utf8Constant segName = g == 0 ? lookupName : lookupSegmentNames.get(g - 1);
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, segName, lookupDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8,
									attr -> attr.writeU2(8)
										.writeU2(2)
										.writeCode((Object[]) segBody.toArray(new Integer[0]))
										.writeU2(0)
										.writeU2(0))));
				}
				if (usesEval) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, envLookupName, envLookupDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8,
									attr -> attr.writeU2(8)
										.writeU2(5)
										.writeCode((Object[]) envLookupBody.toArray(new Integer[0]))
										.writeU2(0)
										.writeU2(0))));
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, evalName, evalDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8,
									attr -> attr.writeU2(32)
										.writeU2(22)
										.writeCode((Object[]) evalBody.toArray(new Integer[0]))
										.writeU2(0)
										.writeU2(0))));
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, applyName, evalDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8,
									attr -> attr.writeU2(32)
										.writeU2(20)
										.writeCode((Object[]) applyBody.toArray(new Integer[0]))
										.writeU2(0)
										.writeU2(0))));
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, storeName, storeDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8,
									attr -> attr.writeU2(32)
										.writeU2(14)
										.writeCode((Object[]) storeBody.toArray(new Integer[0]))
										.writeU2(0)
										.writeU2(0))));
				}
			}) //
			.writeAttributes(a -> {
			});
		byte[] classBytes = classOut.toByteArray();
		// Check the runtime-helper gates against what the bodies turned out to reference,
		// rather than trusting the source scans that predicted them (see compile(List)).
		// An unresolved own-class call is either a mispredicted gate -- re-run with that
		// group forced on -- or an internal inconsistency no re-run can fix, and then it
		// is far better to say so here than to hand the user a class that throws
		// NoSuchMethodError the first time the branch is taken. Every caller counts,
		// including the injected built-in wrappers: their bodies no longer carry an arm
		// for a value the absent runtime cannot construct, because each lowering behind
		// them is gated on Ctx.usesArrays (.kb/adjustable-arrays.md).
		List<JvmClassShaker.UnresolvedSelfMethod> unresolved = JvmClassShaker.unresolvedSelfMethods(classBytes);
		Set<String> underpredicted = new LinkedHashSet<>();
		List<JvmClassShaker.UnresolvedSelfMethod> unrecoverable = new ArrayList<>();
		for (JvmClassShaker.UnresolvedSelfMethod missing : unresolved) {
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
			java.util.Set<String> roots = new java.util.HashSet<>();
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
			if (usesJava || usesObjc || usesFfi) {
				roots.add("_apply");
			}
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
			if (usesAsyncRuntime) {
				roots.add("run");
			}
			// The thread runtime's FutureTask invokes call() through the Callable
			// interface -- the same invisible edge as run() above.
			if (usesThreads) {
				roots.add("call");
			}
			classBytes = JvmClassShaker.shake(classBytes, roots);
		}
		// Insert the StackMapTable every class version above 50 requires (and the shaker
		// could not have preserved), stamping the target version. Must stay after the
		// shake: the shaker rejects Code sub-attributes and would not rewrite the
		// constant-pool entries the frames reference.
		return StackMapAugmenter.augment(classBytes, CLASS_MAJOR_VERSION);
	}

	/**
	 * The funcIds the {@code _invoke_N}/{@code _invoke_v} dispatchers and the
	 * {@code _lookup} name registry must be able to reach -- everything else in
	 * {@code functions} is called only through a direct {@code invokestatic}, so naming
	 * it in a dispatcher would do nothing except keep it alive for
	 * {@link am.ik.jvm.JvmClassShaker} ({@code .kb/optimize-dead-code-elimination.md}).
	 * The WASM twin is {@code WasmLispCompiler.dispatchableFuncIds}, and the two must
	 * agree: a name that stops resolving here has to stop resolving there too, or the
	 * backends disagree about which forged designator still works.
	 *
	 * <p>
	 * Two sources, both EXACT rather than heuristic:
	 * <ul>
	 * <li>{@code valueFuncIds} -- what Pass 2 actually materialized as a closure, so a
	 * {@code #'name} a macro synthesized during Pass 2 counts (a pre-scan of the source
	 * program would have missed exactly those);</li>
	 * <li>the names a runtime SYMBOL designator can resolve. {@code _lookup} compares the
	 * designator against string CONSTANTS, so a registry row is reachable only when the
	 * program already loads that name as a string VALUE -- a quoted symbol, a string
	 * literal, an {@code intern} of a literal. The probe reads
	 * {@code Ctx.spelledLiterals} -- the spellings Pass 2 emitted as values -- not the
	 * whole constant pool: the pool also holds strings the compiler added for its own
	 * machinery (layout tables, runtime error messages), and no run-time path turns those
	 * into a designator the program did not spell itself. This is the constant-pool
	 * counterpart of the WASM side's spelled-literal test, and the two must classify
	 * alike -- which is why the spellings themselves come from the shared
	 * {@link DesignatorSpellings} rather than from a list repeated on each side.</li>
	 * </ul>
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
	 * @param symbolBuilders whether the program contains a symbol BUILDER
	 * ({@code RuntimeNameProducers.anySymbolBuilder}) -- only then can a framed string
	 * literal or keyword spelling become a designator, so only then are those probes
	 * applied
	 * @return the funcIds that need a dispatcher case (and a registry row)
	 */
	private Set<Integer> dispatchableFuncIds(Map<String, FunctionInfo> functions, Set<Integer> valueFuncIds,
			Set<String> spelledLiterals, boolean registryLive, boolean anyNameResolvable, boolean symbolBuilders) {
		if (this.dynamic || anyNameResolvable) {
			// Late binding, or an operator that can produce a name this compile never
			// sees spelled: any name can be resolved at run time.
			Set<Integer> all = new HashSet<>(valueFuncIds);
			for (FunctionInfo fi : functions.values()) {
				all.add(fi.funcId());
			}
			return all;
		}
		Set<Integer> dispatchable = new HashSet<>(valueFuncIds);
		if (registryLive) {
			for (Map.Entry<String, FunctionInfo> entry : functions.entrySet()) {
				// Every spelling a runtime designator can carry for the name --
				// canonical, the alias row's, the bare member, and (only with a symbol
				// BUILDER present) the framed string literal and the two package-less
				// symbol spellings. The list is shared with the WASM twin
				// (compiler.DesignatorSpellings) so the two cannot drift.
				if (DesignatorSpellings.anySpelled(entry.getKey(), spelledLiterals, symbolBuilders)) {
					dispatchable.add(entry.getValue().funcId());
				}
			}
		}
		if (Boolean.getBoolean("rontolisp.debug.dispatchgate")) {
			System.err.println("[dispatch-gate] " + dispatchable.size() + " of " + functions.size()
					+ " functions dispatchable (" + valueFuncIds.size() + " funcIds materialized as values)");
			for (Map.Entry<String, FunctionInfo> entry : functions.entrySet()) {
				if (dispatchable.contains(entry.getValue().funcId())
						&& !valueFuncIds.contains(entry.getValue().funcId())) {
					System.err.println("[dispatch-gate] name-armed\t" + entry.getKey() + "\tby\t"
							+ DesignatorSpellings.matched(entry.getKey(), spelledLiterals, symbolBuilders));
				}
			}
		}
		return dispatchable;
	}

	/**
	 * Whether the program can produce a function NAME this compile never sees spelled out
	 * -- in which case {@link #dispatchableFuncIds} must keep every function
	 * dispatchable. Only the data evaluators ({@code eval}/{@code read}/
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

	private static boolean programUsesEval(List<LispVal> program) {
		for (LispVal expr : program) {
			if (usesEval(expr)) {
				return true;
			}
		}
		return false;
	}

	// Writes an async runtime method's exception table (or the empty-table u2 when the
	// method has none) into its Code attribute.
	private static void writeAsyncExceptionTable(ByteCodeWriter attr, JvmAsyncRuntimeBuilder.AsyncMethod am) {
		List<ByteCodeWriter.ExceptionTableEntry> entries = new java.util.ArrayList<>();
		for (int[] e : am.exceptionTable()) {
			entries.add(new ByteCodeWriter.ExceptionTableEntry(e[0], e[1], e[2], e[3]));
		}
		attr.writeExceptionTable(entries);
	}

	// The thread runtime twin of writeAsyncExceptionTable.
	private static void writeThreadExceptionTable(ByteCodeWriter attr, JvmThreadRuntimeBuilder.ThreadMethod tm) {
		List<ByteCodeWriter.ExceptionTableEntry> entries = new java.util.ArrayList<>();
		for (int[] e : tm.exceptionTable()) {
			entries.add(new ByteCodeWriter.ExceptionTableEntry(e[0], e[1], e[2], e[3]));
		}
		attr.writeExceptionTable(entries);
	}

	/**
	 * Pushes a standard stream variable's seeded default onto a {@code <clinit>} body:
	 * the designator {@code t} (a bare {@code "T"} symbol) for the two stdio variables, a
	 * boxed stream handle for {@code *error-output*}. The two homes that need the value
	 * -- the variable's global field and the eval runtime's {@code _genv} mirror -- both
	 * push it through here, so neither can drift from {@code StreamDesignators}' table.
	 */
	private static void emitStreamDefault(List<Integer> code, LispVal value,
			ConstantPool.@Nullable StringConstant tDesignator, MethodrefConstant longValueOf,
			ConstantPool.ClassConstant objectClass, @Nullable FieldrefConstant streamLayoutField,
			ConstantPool.@Nullable StringConstant streamKindStr) {
		if (value instanceof LispCons) {
			// *error-output*'s default is the stream VALUE over the reserved handle 2:
			// Object[]{layout, Long(2), ":STANDARD"} -- the same shape
			// JvmObjCompiler.emitWrapStream builds at a producer, written out here
			// because <clinit> has no expression compiler.
			JvmRuntimeBuilder.emitIntConstStatic(code, 1 + am.ik.rontolisp.LispLayout.STREAM.capacity());
			code.add(Opcode.ANEWARRAY);
			JvmRuntimeBuilder.emitU2(code, objectClass.index());
			code.add(Opcode.DUP);
			code.add(Opcode.ICONST_0);
			code.add(Opcode.GETSTATIC);
			JvmRuntimeBuilder.emitU2(code, java.util.Objects.requireNonNull(streamLayoutField).index());
			code.add(Opcode.AASTORE);
			code.add(Opcode.DUP);
			code.add(Opcode.ICONST_1);
			JvmRuntimeBuilder.emitIntConstStatic(code, (int) StreamDesignators.STANDARD_ERROR_HANDLE);
			code.add(Opcode.I2L);
			code.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(code, longValueOf.index());
			code.add(Opcode.AASTORE);
			code.add(Opcode.DUP);
			code.add(Opcode.ICONST_2);
			code.add(Opcode.LDC_W);
			JvmRuntimeBuilder.emitU2(code, java.util.Objects.requireNonNull(streamKindStr).index());
			code.add(Opcode.AASTORE);
			return;
		}
		if (value instanceof LispInteger handle) {
			JvmRuntimeBuilder.emitIntConstStatic(code, (int) handle.value());
			code.add(Opcode.I2L);
			code.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(code, longValueOf.index());
			return;
		}
		if (value instanceof LispCons) {
			// The instance gate is off (see seedsStreamValue): the raw reserved handle.
			JvmRuntimeBuilder.emitIntConstStatic(code, (int) StreamDesignators.STANDARD_ERROR_HANDLE);
			code.add(Opcode.I2L);
			code.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(code, longValueOf.index());
			return;
		}
		// LDC_W, not the narrow LDC: this is the emission a redirecting program had
		// before the two seed sites were merged, and its bytes are pinned by the
		// byte-identity rule in .kb/standard-output-redirect.md.
		code.add(Opcode.LDC_W);
		JvmRuntimeBuilder.emitU2(code, java.util.Objects.requireNonNull(tDesignator).index());
	}

	private static boolean programUsesSymbol(List<LispVal> program, String name) {
		for (LispVal expr : program) {
			if (usesSymbol(expr, name)) {
				return true;
			}
		}
		return false;
	}

	// True when the program references any of the five java: interop functions, so the
	// bridge runtime (and the eval runtime its callbacks need) is emitted.
	private static boolean programUsesAnyJavaOp(List<LispVal> program) {
		for (String member : List.of(LispNames.JAVA_NEW, LispNames.JAVA_CALL, LispNames.JAVA_STATIC,
				LispNames.JAVA_FIELD, LispNames.JAVA_PROXY)) {
			if (programUsesSymbol(program, PackageRegistry.qualify(LispNames.JAVA_PKG, member))) {
				return true;
			}
		}
		return false;
	}

	// True when the program references any of the seven objc: verbs, so the embedded
	// am.ik.objc blob (and the eval runtime its callbacks need) is emitted. A program
	// that uses appkit: qualifies through the spliced appkit.lisp, whose widgets are
	// objc:send.
	private static boolean programUsesAnyObjcOp(List<LispVal> program) {
		for (String member : JvmObjcInteropCompiler.members()) {
			if (programUsesSymbol(program, PackageRegistry.qualify(LispNames.OBJC_PKG, member))) {
				return true;
			}
		}
		return false;
	}

	// True when the program references any of the ffi: verbs, so the embedded
	// am.ik.ffi blob (and the eval runtime an ffi:callback needs) is emitted. A
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
		return LispMacroExpander.programUsesGeneralArrayOp(program) || programBuildsConcatenateSequence(program)
				|| programTakesSequenceBuilderValue(program);
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
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol op && LispNames.CONCATENATE.equals(op.name())) {
			LispVal typeForm = (cons.cdr() instanceof LispCons rest) ? rest.car() : LispNil.INSTANCE;
			if (ConcatenateForms.literalResultFamily(typeForm) != ConcatenateForms.ResultFamily.STRING) {
				return true;
			}
		}
		return buildsConcatenateSequence(cons.car()) || buildsConcatenateSequence(cons.cdr());
	}

	// True when the program can produce a packed float array: a #d(...) literal
	// (LispFloatArray) or a (make-array ... :element-type 'double-float ...) form. Gates
	// the _fv* dispatch helpers and their routing; when false the array op compilers call
	// the general _array* helpers directly, keeping the default build byte-identical.
	private static boolean programUsesFloatArray(List<LispVal> program, ClosRegistry closRegistry) {
		for (LispVal expr : program) {
			if (usesFloatArray(expr, closRegistry)) {
				return true;
			}
		}
		return false;
	}

	private static boolean usesFloatArray(LispVal val, ClosRegistry closRegistry) {
		if (val instanceof am.ik.rontolisp.LispFloatArray) {
			return true;
		}
		if (val instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head && LispNames.MAKE_ARRAY.equals(head.name())
					&& makeArrayIsPackedFloat(cons, closRegistry)) {
				return true;
			}
			return usesFloatArray(cons.car(), closRegistry) || usesFloatArray(cons.cdr(), closRegistry);
		}
		return false;
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
		if (val instanceof am.ik.rontolisp.LispIntVector) {
			return true;
		}
		if (val instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head && LispNames.MAKE_ARRAY.equals(head.name())
					&& makeArrayIsPackedInt(cons, closRegistry)) {
				return true;
			}
			return usesIntArray(cons.car(), closRegistry) || usesIntArray(cons.cdr(), closRegistry);
		}
		return false;
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

	// Whether a (make-array ...) call carries :element-type 'double-float or
	// 'single-float (a literal quoted symbol at the call site, package qualifier ignored)
	// --
	// either produces a packed float array.
	private static boolean makeArrayIsPackedFloat(LispCons makeArray, ClosRegistry closRegistry) {
		List<LispVal> args = makeArray.toList();
		for (int i = 2; i + 1 < args.size(); i++) {
			if (args.get(i) instanceof LispSymbol kw && LispNames.ELEMENT_TYPE_KEYWORD.equals(kw.name())) {
				LispVal type = LispMacroExpander.resolveElementTypeAlias(args.get(i + 1), closRegistry);
				if (type instanceof LispSymbol resolved) {
					// An alias resolves to the BARE symbol, without the quote wrapper the
					// literal spelling still carries.
					type = new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(resolved, LispNil.INSTANCE));
				}
				if (type instanceof LispCons q && q.car() instanceof LispSymbol qs && LispNames.QUOTE.equals(qs.name())
						&& q.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol ts) {
					String name = ts.name();
					int colon = name.lastIndexOf(':');
					String local = colon >= 0 ? name.substring(colon + 1) : name;
					return local.equals(LispNames.DOUBLE_FLOAT) || local.equals(LispNames.SINGLE_FLOAT);
				}
			}
		}
		return false;
	}

	private static boolean usesSymbol(LispVal val, String name) {
		if (!(val instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym && name.equals(sym.name())) {
			return true;
		}
		return usesSymbol(cons.car(), name) || usesSymbol(cons.cdr(), name);
	}

	/**
	 * Collects every symbol that appears as the target of a {@code setq} place or a
	 * {@code setf} bare-symbol place anywhere in the given form (quoted data excluded).
	 * This is an over-approximation (it does not track lexical scope); it is intersected
	 * with the scope-aware free-variable set to decide which top-level variables become
	 * global fields.
	 */
	private static void collectAssignedSymbols(LispVal val, Set<String> out) {
		if (!(val instanceof LispCons cons)) {
			return;
		}
		List<LispVal> parts = cons.toList();
		if (cons.car() instanceof LispSymbol head) {
			switch (head.name()) {
				case LispNames.QUOTE -> {
					return;
				}
				case LispNames.SETQ, LispNames.SETF -> {
					// place/value pairs; a bare-symbol place is a variable assignment (a
					// non-symbol setf place like (car x) names a location, not a
					// variable).
					for (int i = 1; i + 1 < parts.size(); i += 2) {
						if (parts.get(i) instanceof LispSymbol place && !place.isKeyword()) {
							out.add(place.name());
						}
					}
				}
				default -> {
				}
			}
		}
		for (LispVal part : parts) {
			collectAssignedSymbols(part, out);
		}
	}

	/**
	 * Whether the program takes the named built-in as a first-class function value, i.e.
	 * whether its injected wrapper can be reached at all. A condition's {@code :report}
	 * lambda counts: {@code define-condition} is rewritten out of the program, so the
	 * lambda lives only in the registry, but the error/signal expansions inject it back.
	 * @param program the resolved top-level forms
	 * @param closRegistry the registry holding the condition reports
	 * @param op the built-in's name
	 * @return {@code true} when a {@code (function op)} reference occurs
	 */
	private static boolean referencesFunctionValue(List<LispVal> program, ClosRegistry closRegistry, String op) {
		return program.stream().anyMatch(expr -> BuiltinFunctionWrappers.referencesFunctionValue(expr, op))
				|| closRegistry.conditionReports()
					.values()
					.stream()
					.anyMatch(report -> BuiltinFunctionWrappers.referencesFunctionValue(report, op));
	}

	/**
	 * As {@link #referencesFunctionValue}, but counting the {@code 'op} spelling too --
	 * see {@link BuiltinFunctionWrappers#referencesFunctionDesignator}.
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

	private static boolean usesEval(LispVal val) {
		if (!(val instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym && LispNames.EVAL.equals(sym.name())) {
			return true;
		}
		return usesEval(cons.car()) || usesEval(cons.cdr());
	}

	static boolean hasDoubleLiteral(List<LispVal> args) {
		for (int i = 1; i < args.size(); i++) {
			if (containsDouble(args.get(i))) {
				return true;
			}
		}
		return false;
	}

	/** The forms whose value is an integer whatever their argument types. */
	private static final java.util.Set<String> INTEGER_VALUED_FORMS = java.util.Set.of(LispNames.ROUND,
			LispNames.TRUNCATE, LispNames.FLOOR, LispNames.CEILING);

	static boolean containsDouble(LispVal val) {
		if (val instanceof LispDouble) {
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
				if (containsDouble(element)) {
					return true;
				}
			}
		}
		return false;
	}

	static DefunDecl extractSetqLambda(LispVal expr) {
		List<LispVal> parts = ((LispCons) expr).toList();
		String funcName = ((LispSymbol) parts.get(1)).name();
		List<LispVal> lambdaParts = ((LispCons) parts.get(2)).toList();
		LambdaLists.NativeForm nf = LambdaLists.toNative(lambdaParts.get(1),
				lambdaParts.subList(2, lambdaParts.size()));
		return new DefunDecl(funcName, nf.paramNames(), nf.variadic(), nf.body());
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
	 * remaining arguments as a cons list).
	 */
	record DefunDecl(String name, List<String> paramNames, boolean variadic, List<LispVal> bodyExprs) {
	}

	/**
	 * Registry entry for a compiled function. {@code paramCount} is the physical JVM
	 * parameter count; when {@code variadic}, the last parameter is the rest list and the
	 * callable minimum is {@code paramCount - 1} arguments.
	 */
	record FunctionInfo(int funcId, int paramCount, boolean variadic, boolean isClosure, MethodrefConstant methodref,
			Utf8Constant nameUtf8, Utf8Constant descUtf8) {
	}

	record LambdaInfo(int funcId, String methodName, List<String> paramNames, boolean variadic, List<LispVal> bodyExprs,
			List<String> freeVarNames) {
	}

	record DispatchMethod(Utf8Constant nameUtf8, Utf8Constant descUtf8, List<Integer> code, int maxLocals) {
	}

	/**
	 * An active block return boundary during compilation ({@code %block}, a named
	 * {@code block} or the {@code %fn-block} function boundary). {@code rvSlot} is the
	 * local that holds the block's value; {@code exitPatches} collects the positions of
	 * the {@code goto} instructions emitted by {@code return}/{@code return-from} forms,
	 * all back-patched to the block's exit once its body has been compiled;
	 * {@code entryStack} is the operand stack the block was entered with, which is the
	 * shape its exit is reached with on every path -- an exit discards whatever the body
	 * had pushed on top of it (see {@link JvmReturnCompiler}). {@code name} is the block
	 * name a {@code return-from} matches against ({@code null} for {@code %block} and the
	 * {@code nil} block); {@code catchesPlain} marks the targets a plain {@code return}
	 * exits ({@code %block} and {@code (block nil ...)}); {@code functionBoundary} marks
	 * the {@code %fn-block} wrap -- the fallback target for a {@code return-from} whose
	 * name matches no enclosing block.
	 */
	record BlockTarget(int rvSlot, List<Integer> exitPatches, List<OperandStack.Slot> entryStack, @Nullable String name,
			boolean catchesPlain, boolean functionBoundary) {
	}

	/**
	 * An active {@code tagbody} during compilation. {@code labelPositions} maps each
	 * label already emitted to its code position (a {@code go} to it is a backward jump
	 * patched immediately); {@code pendingGos} holds, per label, the {@code goto}
	 * positions of forward {@code go}s awaiting the label (its key set is the tagbody's
	 * full label set, registered up front so {@code JvmGoCompiler} can resolve the
	 * innermost tagbody declaring a tag). {@code entryStack} is the operand stack at
	 * tagbody entry -- every label is reached with exactly that shape ({@code go}
	 * discards anything above it); {@code unwindDepth}/{@code spillDepth} are the
	 * scope-stack sizes at entry, so a {@code go} can tell which
	 * {@code unwind-protect}/{@code handler-case} scopes it escapes.
	 */
	record TagbodyScope(List<OperandStack.Slot> entryStack, int unwindDepth, int spillDepth,
			java.util.Map<String, Integer> labelPositions, java.util.Map<String, List<Integer>> pendingGos) {
	}

	/**
	 * The shared condition-channel state of one compilation: the constants of the
	 * {@code private static ThreadLocal _condTl} field that carries a condition object (a
	 * tagged-list instance) from a {@code %error-cond} throw site to a
	 * {@code handler-case} catch handler on the same thread of control (thread-scoped so
	 * concurrent {@code rontolisp:http-handler} requests do not clobber each other). One
	 * instance is shared by every {@link Ctx} of a compilation (the {@code nextFuncId}
	 * pattern); the field and its {@code <clinit>} initializer are emitted only when a
	 * compiler marked it {@link #used}.
	 */
	static final class ConditionChannel {

		boolean used = false;

		@Nullable FieldrefConstant condTlField;

		@Nullable Utf8Constant fieldName;

		@Nullable Utf8Constant fieldDesc;

		@Nullable ClassConstant threadLocalClass;

		@Nullable MethodrefConstant tlCtor;

		@Nullable MethodrefConstant tlSet;

		@Nullable MethodrefConstant tlGet;

		@Nullable Utf8Constant clinitName;

		@Nullable Utf8Constant clinitDesc;

		/**
		 * The per-thread {@code handler-case} handler-depth counter (a
		 * {@code ThreadLocal} of {@code Integer}, null = 0), consulted by
		 * {@code %signal-cond} -- {@code signal} raises only when a handler is
		 * established.
		 */
		@Nullable FieldrefConstant depthTlField;

		@Nullable Utf8Constant depthFieldName;

		/**
		 * True when the program lowers a cross-lambda {@code return-from}: the
		 * {@code _nleTl} ThreadLocal channel carries the pending non-local exit's
		 * {@code {throwable, id, value}} triple. Tracked independently of {@link #used}
		 * so a program that only lowers a cross-lambda exit (no typed conditions) still
		 * emits the field and its {@code <clinit>}, and a program that only uses
		 * conditions stays byte-identical.
		 */
		boolean nleUsed = false;

		@Nullable FieldrefConstant nleTlField;

		@Nullable Utf8Constant nleFieldName;

		/**
		 * The shared {@code %hb-guard} landing pad ({@code _hbGuard}), built on the first
		 * {@code handler-bind} the program compiles and called by every one after it.
		 * Held here rather than per method because the pad reads only the caught
		 * throwable and the class-wide channel, so one copy serves the whole class.
		 */
		@Nullable MethodrefConstant hbGuardPad;

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
			this.fieldName = cp.addUtf8("_condTl");
			this.fieldDesc = cp.addUtf8("Ljava/lang/ThreadLocal;");
			ClassConstant thisClass = cp.addClass(cp.addUtf8(className));
			this.condTlField = cp.addFieldref(thisClass, cp.addNameAndType(this.fieldName, this.fieldDesc));
			this.depthFieldName = cp.addUtf8("_hcDepthTl");
			this.depthTlField = cp.addFieldref(thisClass, cp.addNameAndType(this.depthFieldName, this.fieldDesc));
			ensureThreadLocalInfra(cp);
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
			this.fieldDesc = this.fieldDesc != null ? this.fieldDesc : cp.addUtf8("Ljava/lang/ThreadLocal;");
			this.nleFieldName = cp.addUtf8("_nleTl");
			ClassConstant thisClass = cp.addClass(cp.addUtf8(className));
			this.nleTlField = cp.addFieldref(thisClass, cp.addNameAndType(this.nleFieldName, this.fieldDesc));
			ensureThreadLocalInfra(cp);
		}

		private void ensureThreadLocalInfra(ConstantPool cp) {
			if (this.threadLocalClass != null) {
				return;
			}
			this.threadLocalClass = cp.addClass(cp.addUtf8("java/lang/ThreadLocal"));
			this.tlCtor = cp.addMethodref(this.threadLocalClass,
					cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
			this.tlSet = cp.addMethodref(this.threadLocalClass,
					cp.addNameAndType(cp.addUtf8("set"), cp.addUtf8("(Ljava/lang/Object;)V")));
			this.tlGet = cp.addMethodref(this.threadLocalClass,
					cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("()Ljava/lang/Object;")));
			this.clinitName = cp.addUtf8("<clinit>");
			this.clinitDesc = cp.addUtf8("()V");
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
		record LayoutField(Utf8Constant name, FieldrefConstant ref, am.ik.rontolisp.LispLayout layout) {
		}

		private final Map<String, LayoutField> byTag = new java.util.LinkedHashMap<>();

		private final Set<String> usedFieldNames = new HashSet<>();

		@Nullable Utf8Constant fieldDesc;

		@Nullable ClassConstant stringArrayCls;

		@Nullable ClassConstant stringCls;

		@Nullable Utf8Constant clinitName;

		@Nullable Utf8Constant clinitDesc;

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
		ClassConstant stringArrayClass(ConstantPool cp) {
			if (this.stringArrayCls == null) {
				this.stringArrayCls = cp.addClass(cp.addUtf8("[Ljava/lang/String;"));
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
		FieldrefConstant intern(ConstantPool cp, String className, am.ik.rontolisp.LispLayout layout) {
			LayoutField existing = this.byTag.get(layout.tag());
			if (existing != null) {
				return existing.ref();
			}
			stringArrayClass(cp);
			if (this.stringCls == null) {
				this.stringCls = cp.addClass(cp.addUtf8("java/lang/String"));
			}
			if (this.fieldDesc == null) {
				this.fieldDesc = cp.addUtf8("[Ljava/lang/String;");
			}
			if (this.clinitName == null) {
				this.clinitName = cp.addUtf8("<clinit>");
				this.clinitDesc = cp.addUtf8("()V");
			}
			// The mangled tag can in principle collide with another mangled tag; the
			// used-name set makes the field name deterministic and unique anyway.
			String base = "_ly$" + mangleMethodName(layout.tag());
			String name = base;
			for (int n = 1; !this.usedFieldNames.add(name); n++) {
				name = base + "$" + n;
			}
			Utf8Constant nameUtf = cp.addUtf8(name);
			ClassConstant thisClass = cp.addClass(cp.addUtf8(className));
			FieldrefConstant ref = cp.addFieldref(thisClass, cp.addNameAndType(nameUtf, this.fieldDesc));
			this.byTag.put(layout.tag(), new LayoutField(nameUtf, ref, layout));
			return ref;
		}

		/**
		 * Appends the layout initializers to the shared {@code <clinit>} body. Peak
		 * operand depth is 4 (array, dup, index, string).
		 * @param code the {@code <clinit>} body being assembled
		 * @param cp the constant pool (mints the layout strings)
		 */
		void emitClinitInit(List<Integer> code, ConstantPool cp) {
			for (LayoutField lf : this.byTag.values()) {
				List<String> parts = new ArrayList<>();
				parts.add(lf.layout().tag());
				parts.add(lf.layout().printName());
				parts.add(switch (lf.layout().kind()) {
					case STRUCT -> "S";
					case CLASS -> "C";
					case PATHNAME -> "P";
				});
				parts.addAll(lf.layout().slotNames());
				JvmRuntimeBuilder.emitIntConstStatic(code, parts.size());
				code.add(Opcode.ANEWARRAY);
				JvmRuntimeBuilder.emitU2(code, Objects.requireNonNull(this.stringCls).index());
				for (int i = 0; i < parts.size(); i++) {
					code.add(Opcode.DUP);
					JvmRuntimeBuilder.emitIntConstStatic(code, i);
					JvmRuntimeBuilder.emitLdc(code, cp.addString(parts.get(i)).index());
					code.add(Opcode.AASTORE);
				}
				code.add(Opcode.PUTSTATIC);
				JvmRuntimeBuilder.emitU2(code, lf.ref().index());
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
		record BigIntField(Utf8Constant name, FieldrefConstant ref, java.math.BigInteger value) {
		}

		private final Map<java.math.BigInteger, BigIntField> byValue = new java.util.LinkedHashMap<>();

		@Nullable Utf8Constant fieldDesc;

		@Nullable ClassConstant bigIntegerCls;

		@Nullable MethodrefConstant ctor;

		@Nullable Utf8Constant clinitName;

		@Nullable Utf8Constant clinitDesc;

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
		FieldrefConstant intern(ConstantPool cp, String className, java.math.BigInteger value) {
			BigIntField existing = this.byValue.get(value);
			if (existing != null) {
				return existing.ref();
			}
			if (this.bigIntegerCls == null) {
				this.bigIntegerCls = cp.addClass(cp.addUtf8("java/math/BigInteger"));
				this.ctor = cp.addMethodref(this.bigIntegerCls,
						cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
				this.fieldDesc = cp.addUtf8("Ljava/math/BigInteger;");
				this.clinitName = cp.addUtf8("<clinit>");
				this.clinitDesc = cp.addUtf8("()V");
			}
			Utf8Constant nameUtf = cp.addUtf8("_bi$" + this.byValue.size());
			ClassConstant thisClass = cp.addClass(cp.addUtf8(className));
			FieldrefConstant ref = cp.addFieldref(thisClass,
					cp.addNameAndType(nameUtf, Objects.requireNonNull(this.fieldDesc)));
			this.byValue.put(value, new BigIntField(nameUtf, ref, value));
			return ref;
		}

		/**
		 * Appends the bignum initializers to the shared {@code <clinit>} body. Peak
		 * operand depth is 3 (the uninitialized instance, its dup, the string).
		 * @param code the {@code <clinit>} body being assembled
		 * @param cp the constant pool (mints the decimal strings)
		 */
		void emitClinitInit(List<Integer> code, ConstantPool cp) {
			for (BigIntField bf : this.byValue.values()) {
				code.add(Opcode.NEW);
				JvmRuntimeBuilder.emitU2(code, Objects.requireNonNull(this.bigIntegerCls).index());
				code.add(Opcode.DUP);
				JvmRuntimeBuilder.emitLdc(code, cp.addString(bf.value().toString()).index());
				code.add(Opcode.INVOKESPECIAL);
				JvmRuntimeBuilder.emitU2(code, Objects.requireNonNull(this.ctor).index());
				code.add(Opcode.PUTSTATIC);
				JvmRuntimeBuilder.emitU2(code, bf.ref().index());
			}
		}

	}

	/**
	 * An active {@code unwind-protect} protected region during compilation.
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

		final List<int[]> holes = new ArrayList<>();

		UnwindScope(List<LispVal> cleanupForms, int blockDepth) {
			this.cleanupForms = cleanupForms;
			this.blockDepth = blockDepth;
		}

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
		 * The highest local slot a one-byte load/store operand can name. Past it
		 * {@link #emit(int)} rewrites the instruction into its {@code wide} form, whose
		 * two-byte index reaches {@link #MAX_LOCAL_SLOT}.
		 */
		private static final int MAX_ONE_BYTE_LOCAL_SLOT = 255;

		/**
		 * The highest local slot a method can have at all: {@code max_locals} is a u2, so
		 * no encoding names a higher one. Reaching it is a loud compile error, never a
		 * wrapped write.
		 */
		private static final int MAX_LOCAL_SLOT = 65535;

		final ConstantPool cp;

		final FieldrefConstant systemOut;

		final MethodrefConstant printlnStr;

		final MethodrefConstant lispToString;

		final MethodrefConstant printStr;

		final MethodrefConstant printlnVoid;

		final MethodrefConstant lispToDisplayString;

		final ClassConstant longClass;

		final MethodrefConstant longValueOf;

		final MethodrefConstant longValue;

		final ClassConstant objectClass;

		final ClassConstant objectArrayClass;

		final ClassConstant integerClass;

		final MethodrefConstant integerValueOf;

		final MethodrefConstant integerValue;

		final ClassConstant doubleClass;

		final MethodrefConstant doubleValueOf;

		final ClassConstant numberClass;

		final MethodrefConstant numberDoubleValue;

		final ClassConstant stringClass;

		final MethodrefConstant stringCharAt;

		final MethodrefConstant appendMethod;

		final MethodrefConstant mathAbsLong;

		final MethodrefConstant mathAbsDouble;

		final MethodrefConstant mathMinLong;

		final MethodrefConstant mathMinDouble;

		final MethodrefConstant mathMaxLong;

		final MethodrefConstant mathMaxDouble;

		final MethodrefConstant mathFloor;

		final MethodrefConstant mathCeil;

		final MethodrefConstant mathRint;

		final MethodrefConstant objectEquals;

		final MethodrefConstant readLineHelper;

		final @Nullable MethodrefConstant fetchHelper;

		final @Nullable MethodrefConstant awaitHelper;

		final @Nullable MethodrefConstant asyncRunHelper;

		final @Nullable MethodrefConstant futurepHelper;

		final @Nullable MethodrefConstant streampHelper;

		final @Nullable MethodrefConstant makeStreamHelper;

		final @Nullable MethodrefConstant streamNewHelper;

		final @Nullable MethodrefConstant streamReadHelper;

		final @Nullable MethodrefConstant streamWriteHelper;

		final @Nullable MethodrefConstant streamCloseHelper;

		final @Nullable MethodrefConstant drainBodyHelper;

		final @Nullable MethodrefConstant waitForHelper;

		final @Nullable MethodrefConstant tcpConnectHelper;

		final @Nullable MethodrefConstant tcpListenHelper;

		final @Nullable MethodrefConstant tcpAcceptHelper;

		final @Nullable MethodrefConstant tcpLocalPortHelper;

		final @Nullable MethodrefConstant tcpLocalAddressHelper;

		final @Nullable MethodrefConstant tcpPeerAddressHelper;

		final @Nullable MethodrefConstant tcpPeerPortHelper;

		final @Nullable MethodrefConstant tcpSetTimeoutHelper;

		final @Nullable MethodrefConstant tlsConnectHelper;

		final @Nullable MethodrefConstant tlsUpgradeHelper;

		final @Nullable MethodrefConstant tlsListenHelper;

		final @Nullable MethodrefConstant tlsListenP12Helper;

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
		final @Nullable Map<String, MethodrefConstant> javaOps;

		/**
		 * The {@code objc:} bridge references ({@code init} plus one per verb); null
		 * unless the program uses an {@code objc:} verb.
		 */
		final @Nullable Map<String, MethodrefConstant> objcOps;

		/**
		 * The {@code ffi:} bridge references ({@code init} plus one per verb); null
		 * unless the program uses an {@code ffi:} verb.
		 */
		final @Nullable Map<String, MethodrefConstant> ffiOps;

		/**
		 * The accelerated {@code vec:} bridge references ({@code init} plus one per
		 * vectorizable kernel member name -- {@code add}/{@code sub}/{@code mul}/
		 * {@code scale}/{@code dot}/{@code sum}); null unless {@code --simd} emitted the
		 * acceleration runtime for a program that uses a vectorizable {@code vec:}
		 * kernel.
		 */
		final @Nullable Map<String, MethodrefConstant> simdOps;

		/**
		 * The CBLAS bridge references ({@code init} and the one product kernel); null
		 * unless {@code --blas} emitted the bridge for a program that reaches
		 * {@code linalg:dot}.
		 */
		final @Nullable Map<String, MethodrefConstant> blasOps;

		/**
		 * The device bridge references ({@code init} and the one product kernel); null
		 * unless {@code --gpu} emitted the bridge for a program that reaches
		 * {@code linalg:dot}.
		 */
		final @Nullable Map<String, MethodrefConstant> gpuOps;

		Map<String, MethodrefConstant> numOps = Map.of();

		Map<String, MethodrefConstant> mathOps = Map.of();

		Map<String, MethodrefConstant> systemOps = Map.of();

		final List<Integer> code = new ArrayList<>();

		/**
		 * This method body's operand stack, tracked as it is emitted: it says what is
		 * live on the stack right now (which {@code handler-case} must spill, and
		 * {@code return} must discard, before a control-flow edge that arrives with an
		 * empty one) and how deep the stack ever got.
		 */
		final OperandStack stack;

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
		 * would keep it alive for {@link am.ik.jvm.JvmClassShaker}.
		 */
		Set<Integer> valueFuncIds;

		/**
		 * Every literal spelling Pass 2 emitted as a runtime VALUE the program can hold
		 * -- a quoted/self-evaluating symbol's name, a string literal's framed form, a
		 * keyword -- recorded where the value is loaded
		 * ({@link JvmEmitHelper#compileStringLiteral}). One mutable set shared by every
		 * {@code Ctx}, like {@link #valueFuncIds}, and read by
		 * {@code dispatchableFuncIds}: a runtime symbol designator can only ever BE one
		 * of these (or a builder's product from one), so the name-registry probes read
		 * this set rather than the whole constant pool -- a string the compiler put in
		 * the pool for its own machinery (a layout table, a runtime error message) is not
		 * a name the program spells, and must not arm a dispatch case.
		 */
		Set<String> spelledLiterals;

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
		final Map<String, MethodrefConstant> sharedHelpers;

		/**
		 * The tail spine this form belongs to, or null. Set by {@link JvmBodyOutliner}
		 * immediately before a value-position form is compiled and cleared by
		 * {@link JvmExprCompiler#compileExpr} on the way in, so only a construct that IS
		 * the method's tail ever sees it.
		 */
		JvmBodyOutliner.@Nullable Tail tailBody;

		int nextLocal = 1;

		int maxLocals = 1;

		/**
		 * The one local {@code %error}'s message rides in, allocated on first use. The
		 * value is written and read five instructions later with no control flow in
		 * between and the throw never returns, so every {@code error} site in the method
		 * shares it rather than burning a slot each. Past slot 255 every load and store
		 * of it would cost the three extra bytes of a {@code wide} prefix, and
		 * {@code max_locals} sizes every frame the method carries.
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
		 * True when the program MENTIONS {@code *print-case*}
		 * ({@code LispMacroExpander.usesPrintCase}): every printing operator is rewritten
		 * onto the {@code %print-cased} renderer, which applies the variable to each
		 * symbol spelling. Off, the printing operators compile exactly as they always
		 * did.
		 */
		boolean printCase = false;

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
		 * True when the {@code _readSeqPacked} / {@code _writeSeqPacked} helpers are
		 * emitted ({@code .kb/binary-sequence-io.md}); when they are not, the
		 * {@code %read-sequence-packed} / {@code %write-sequence-packed} primitives
		 * compile to a declining nil. Shared across every context.
		 */
		boolean usesPackedSequenceIo = false;

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
		 * True when an instance value can exist in this class (see
		 * {@code LispMacroExpander.mayCreateInstances}). Gates the instance exclusion in
		 * the cons-shaped predicates, so a program that cannot build one compiles
		 * byte-identically. Shared across every context.
		 */
		boolean mayUseInstances = false;

		/**
		 * True when the program can build a SYNONYM STREAM ({@code make-synonym-stream}
		 * is the only way to, and it has no read syntax), so every stream-designator
		 * resolution has to run through {@code %STREAM-TARGET}. A program that never
		 * spells it keeps its exact bytes.
		 */
		boolean usesSynonymStreams = false;

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
		@Nullable MethodrefConstant evalStoreRef;

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
		 * Whether the program uses {@code progv}. Switches {@code symbol-value} to the
		 * dynamic-first dispatch over the special set
		 * ({@link JvmSymbolApiCompiler#compileSymbolValue}).
		 */
		boolean usesProgv = false;

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
		 * {@code defstruct} accessor names to their 1-based slot position, collected by
		 * the pre-pass in {@link JvmLispCompiler#compile}; {@code setf} expansion treats
		 * these as places. Shared across every context.
		 */
		Map<String, Integer> structAccessors = Map.of();

		/**
		 * The CLOS registry (classes, generics, slot positions), collected by the
		 * pre-pass in {@link JvmLispCompiler#compile}; {@code make-instance}/
		 * {@code slot-value} expansion resolves through it. Shared across every context.
		 */
		ClosRegistry closRegistry = new ClosRegistry();

		/**
		 * Names of top-level global variables (defvar/defparameter/defconstant and
		 * top-level setq/setf places). Each has a dedicated static field in
		 * {@link #globalFields}; a reference compiles to a {@code getstatic} from any
		 * method body, so a defun/lambda can read a global. Shared across every context.
		 */
		Set<String> globals = Set.of();

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
		Map<String, FieldrefConstant> globalFields = Map.of();

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
		 * helpers), or {@code null} when no special is ever {@code let}-bound. Shared
		 * across every context.
		 */
		JvmDynVarRuntimeBuilder.@Nullable DynVarRuntime dynVars;

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
		 * Active special-variable dynamic bindings, innermost on top:
		 * {@code {tlFieldIndex, saveSlot, blockDepth}} per binding (see JvmLetCompiler;
		 * the save slot holds the thread's previous binding CELL, possibly null). A
		 * {@code return}/{@code return-from} that exits a block entered before the
		 * binding ({@code blockDepth >=} the target's depth) restores the saved cell on
		 * its way out, so a named exit from a scan closure does not leak the bound value
		 * into this thread's dynamic store (cl-ppcre's *reg-starts*).
		 */
		final Deque<int[]> specialBindScopes = new ArrayDeque<>();

		/**
		 * This method's {@code Code} attribute exception table, in dispatch order.
		 * {@code unwind-protect} appends catch-any entries covering its protected region
		 * (class version 50 verifies handlers without a StackMapTable).
		 */
		final List<ByteCodeWriter.ExceptionTableEntry> exceptionTable = new ArrayList<>();

		/**
		 * Branches whose patch overflowed the signed 16-bit encoding, as
		 * {@code {branchPos, targetPos}} pairs: {@code JvmEmitHelper.patchBranch} defers
		 * them here instead of throwing, and {@link am.ik.jvm.BranchRelaxer} rewrites
		 * each over a {@code goto_w} once the body is complete. Empty for every method
		 * whose branches fit, which keeps those bodies byte-identical.
		 */
		final List<int[]> deferredBranches = new ArrayList<>();

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

		private Ctx(Builder builder) {
			this.conditionChannel = builder.conditionChannel;
			this.layoutPool = builder.layoutPool;
			this.bigIntPool = builder.bigIntPool;
			this.dynamic = builder.dynamic;
			this.servletMode = builder.servletMode;
			this.blockExitChannel = builder.blockExitChannel;
			this.restartMode = builder.restartMode;
			this.signalClauseMatch = builder.signalClauseMatch;
			this.printCase = builder.printCase;
			this.usesFloatArray = builder.usesFloatArray;
			this.typedLoops = builder.typedLoops;
			this.intFusion = builder.intFusion;
			this.inlinableDefuns = builder.inlinableDefuns;
			this.fusedState = builder.fusedState;
			this.usesIntArray = builder.usesIntArray;
			this.usesPackedSequenceIo = builder.usesPackedSequenceIo;
			this.usesArrays = builder.usesArrays;
			this.usesHashTables = builder.usesHashTables;
			this.usesEqualpHashTables = builder.usesEqualpHashTables;
			this.usesSeqString = builder.usesSeqString;
			this.mayUseInstances = builder.mayUseInstances;
			this.usesSynonymStreams = builder.usesSynonymStreams;
			this.usesStreamValues = builder.usesStreamValues;
			this.mayUseAsyncValues = builder.mayUseAsyncValues;
			this.className = builder.className;
			this.userDefunNames = builder.userDefunNames;
			this.warnedClRedefinitions = builder.warnedClRedefinitions;
			this.usesFmakunbound = builder.usesFmakunbound;
			this.usesProgv = builder.usesProgv;
			this.packageTable = builder.packageTable;
			this.packageUseTable = builder.packageUseTable;
			this.structAccessors = builder.structAccessors;
			this.closRegistry = builder.closRegistry;
			this.globals = builder.globals;
			this.specialVars = builder.specialVars;
			this.globalFields = builder.globalFields;
			this.rawGlobals = builder.rawGlobals;
			this.dynVars = builder.dynVars;
			this.cp = Objects.requireNonNull(builder.cp);
			this.stack = new OperandStack(this.cp);
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
			this.mathMinLong = Objects.requireNonNull(builder.mathMinLong);
			this.mathMinDouble = Objects.requireNonNull(builder.mathMinDouble);
			this.mathMaxLong = Objects.requireNonNull(builder.mathMaxLong);
			this.mathMaxDouble = Objects.requireNonNull(builder.mathMaxDouble);
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
			this.objcOps = builder.objcOps;
			this.ffiOps = builder.ffiOps;
			this.simdOps = builder.simdOps;
			this.blasOps = builder.blasOps;
			this.gpuOps = builder.gpuOps;
			this.functions = builder.functions;
			this.lambdaDecls = builder.lambdaDecls;
			this.indirectCallArities = builder.indirectCallArities;
			this.valueFuncIds = builder.valueFuncIds;
			this.spelledLiterals = builder.spelledLiterals;
			this.nextFuncId = builder.nextFuncId;
			this.ctxBuilder = builder;
			this.outlinedBodies = builder.outlinedBodies;
			this.nextOutlinedBodyId = builder.nextOutlinedBodyId;
			this.sharedHelpers = builder.sharedHelpers;
			this.numOps = builder.numOps;
			this.mathOps = builder.mathOps;
			this.systemOps = builder.systemOps;
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

			private @Nullable ConstantPool cp;

			private @Nullable FieldrefConstant systemOut;

			private @Nullable MethodrefConstant printlnStr;

			private @Nullable MethodrefConstant lispToString;

			private @Nullable MethodrefConstant printStr;

			private @Nullable MethodrefConstant printlnVoid;

			private @Nullable MethodrefConstant lispToDisplayString;

			private @Nullable ClassConstant longClass;

			private @Nullable MethodrefConstant longValueOf;

			private @Nullable MethodrefConstant longValue;

			private @Nullable ClassConstant objectClass;

			private @Nullable ClassConstant objectArrayClass;

			private @Nullable ClassConstant integerClass;

			private @Nullable MethodrefConstant integerValueOf;

			private @Nullable MethodrefConstant integerValue;

			private @Nullable ClassConstant doubleClass;

			private @Nullable MethodrefConstant doubleValueOf;

			private @Nullable ClassConstant numberClass;

			private @Nullable MethodrefConstant numberDoubleValue;

			private @Nullable ClassConstant stringClass;

			private @Nullable MethodrefConstant stringCharAt;

			private @Nullable MethodrefConstant appendMethod;

			private @Nullable MethodrefConstant mathAbsLong;

			private @Nullable MethodrefConstant mathAbsDouble;

			private @Nullable MethodrefConstant mathMinLong;

			private @Nullable MethodrefConstant mathMinDouble;

			private @Nullable MethodrefConstant mathMaxLong;

			private @Nullable MethodrefConstant mathMaxDouble;

			private @Nullable MethodrefConstant mathFloor;

			private @Nullable MethodrefConstant mathCeil;

			private @Nullable MethodrefConstant mathRint;

			private @Nullable MethodrefConstant objectEquals;

			private @Nullable MethodrefConstant readLineHelper;

			private @Nullable MethodrefConstant fetchHelper;

			private @Nullable MethodrefConstant awaitHelper;

			private @Nullable MethodrefConstant asyncRunHelper;

			private @Nullable MethodrefConstant futurepHelper;

			private @Nullable MethodrefConstant streampHelper;

			private @Nullable MethodrefConstant makeStreamHelper;

			private @Nullable MethodrefConstant streamNewHelper;

			private @Nullable MethodrefConstant streamReadHelper;

			private @Nullable MethodrefConstant streamWriteHelper;

			private @Nullable MethodrefConstant streamCloseHelper;

			private @Nullable MethodrefConstant drainBodyHelper;

			private @Nullable MethodrefConstant waitForHelper;

			private @Nullable MethodrefConstant tcpConnectHelper;

			private @Nullable MethodrefConstant tcpListenHelper;

			private @Nullable MethodrefConstant tcpAcceptHelper;

			private @Nullable MethodrefConstant tcpLocalPortHelper;

			private @Nullable MethodrefConstant tcpLocalAddressHelper;

			private @Nullable MethodrefConstant tcpPeerAddressHelper;

			private @Nullable MethodrefConstant tcpPeerPortHelper;

			private @Nullable MethodrefConstant tcpSetTimeoutHelper;

			private @Nullable MethodrefConstant tlsConnectHelper;

			private @Nullable MethodrefConstant tlsUpgradeHelper;

			private @Nullable MethodrefConstant tlsListenHelper;

			private @Nullable MethodrefConstant tlsListenP12Helper;

			private JvmHttpHandlerRuntimeBuilder.@Nullable HttpHandlerRuntime httpHandlerRuntime;

			private @Nullable Map<String, MethodrefConstant> javaOps;

			private @Nullable Map<String, MethodrefConstant> objcOps;

			private @Nullable Map<String, MethodrefConstant> ffiOps;

			private @Nullable Map<String, MethodrefConstant> simdOps;

			private @Nullable Map<String, MethodrefConstant> blasOps;

			private @Nullable Map<String, MethodrefConstant> gpuOps;

			private Map<String, FunctionInfo> functions = Map.of();

			private List<LambdaInfo> lambdaDecls = new ArrayList<>();

			private Set<Integer> indirectCallArities = new HashSet<>();

			private Set<Integer> valueFuncIds = new HashSet<>();

			private Set<String> spelledLiterals = new HashSet<>();

			private int[] nextFuncId = new int[1];

			private final List<JvmBodyOutliner.OutlinedBody> outlinedBodies = new ArrayList<>();

			private final int[] nextOutlinedBodyId = new int[1];

			private final Map<String, MethodrefConstant> sharedHelpers = new LinkedHashMap<>();

			private boolean dynamic = false;

			private boolean servletMode = false;

			private boolean blockExitChannel = false;

			private boolean restartMode = false;

			private boolean signalClauseMatch = false;

			private boolean printCase = false;

			private boolean usesFloatArray = false;

			private boolean typedLoops = true;

			private boolean intFusion = true;

			private Map<String, DefunDecl> inlinableDefuns = Map.of();

			private JvmIntFusionCompiler.@Nullable State fusedState;

			private boolean usesIntArray = false;

			private boolean usesPackedSequenceIo = false;

			private boolean usesArrays = false;

			private boolean usesHashTables = false;

			private boolean usesEqualpHashTables = false;

			private boolean usesSeqString = false;

			private boolean mayUseInstances = false;

			private boolean usesSynonymStreams = false;

			private boolean usesStreamValues = false;

			private boolean mayUseAsyncValues = false;

			private String className = "";

			private Set<String> userDefunNames = Set.of();

			private Set<String> warnedClRedefinitions = new HashSet<>();

			private boolean usesFmakunbound = false;

			private boolean usesProgv = false;

			private Map<String, String> packageTable = Map.of();

			private Map<String, java.util.List<String>> packageUseTable = Map.of();

			private Map<String, Integer> structAccessors = Map.of();

			private ClosRegistry closRegistry = new ClosRegistry();

			private Set<String> globals = Set.of();

			private Set<String> specialVars = Set.of();

			private Map<String, FieldrefConstant> globalFields = Map.of();

			private Map<String, JvmIntFusionCompiler.RawLocal> rawGlobals = Map.of();

			private JvmDynVarRuntimeBuilder.@Nullable DynVarRuntime dynVars;

			private Map<String, MethodrefConstant> numOps = Map.of();

			private Map<String, MethodrefConstant> mathOps = Map.of();

			private Map<String, MethodrefConstant> systemOps = Map.of();

			Builder cp(ConstantPool cp) {
				this.cp = cp;
				return this;
			}

			Builder systemOut(FieldrefConstant systemOut) {
				this.systemOut = systemOut;
				return this;
			}

			Builder printlnStr(MethodrefConstant printlnStr) {
				this.printlnStr = printlnStr;
				return this;
			}

			Builder lispToString(MethodrefConstant lispToString) {
				this.lispToString = lispToString;
				return this;
			}

			Builder printStr(MethodrefConstant printStr) {
				this.printStr = printStr;
				return this;
			}

			Builder printlnVoid(MethodrefConstant printlnVoid) {
				this.printlnVoid = printlnVoid;
				return this;
			}

			Builder lispToDisplayString(MethodrefConstant lispToDisplayString) {
				this.lispToDisplayString = lispToDisplayString;
				return this;
			}

			Builder longClass(ClassConstant longClass) {
				this.longClass = longClass;
				return this;
			}

			Builder longValueOf(MethodrefConstant longValueOf) {
				this.longValueOf = longValueOf;
				return this;
			}

			Builder longValue(MethodrefConstant longValue) {
				this.longValue = longValue;
				return this;
			}

			Builder objectClass(ClassConstant objectClass) {
				this.objectClass = objectClass;
				return this;
			}

			Builder objectArrayClass(ClassConstant objectArrayClass) {
				this.objectArrayClass = objectArrayClass;
				return this;
			}

			Builder integerClass(ClassConstant integerClass) {
				this.integerClass = integerClass;
				return this;
			}

			Builder integerValueOf(MethodrefConstant integerValueOf) {
				this.integerValueOf = integerValueOf;
				return this;
			}

			Builder integerValue(MethodrefConstant integerValue) {
				this.integerValue = integerValue;
				return this;
			}

			Builder doubleClass(ClassConstant doubleClass) {
				this.doubleClass = doubleClass;
				return this;
			}

			Builder doubleValueOf(MethodrefConstant doubleValueOf) {
				this.doubleValueOf = doubleValueOf;
				return this;
			}

			Builder numberClass(ClassConstant numberClass) {
				this.numberClass = numberClass;
				return this;
			}

			Builder numberDoubleValue(MethodrefConstant numberDoubleValue) {
				this.numberDoubleValue = numberDoubleValue;
				return this;
			}

			Builder stringClass(ClassConstant stringClass) {
				this.stringClass = stringClass;
				return this;
			}

			Builder stringCharAt(MethodrefConstant stringCharAt) {
				this.stringCharAt = stringCharAt;
				return this;
			}

			Builder appendMethod(MethodrefConstant appendMethod) {
				this.appendMethod = appendMethod;
				return this;
			}

			Builder mathAbsLong(MethodrefConstant mathAbsLong) {
				this.mathAbsLong = mathAbsLong;
				return this;
			}

			Builder mathAbsDouble(MethodrefConstant mathAbsDouble) {
				this.mathAbsDouble = mathAbsDouble;
				return this;
			}

			Builder mathMinLong(MethodrefConstant mathMinLong) {
				this.mathMinLong = mathMinLong;
				return this;
			}

			Builder mathMinDouble(MethodrefConstant mathMinDouble) {
				this.mathMinDouble = mathMinDouble;
				return this;
			}

			Builder mathMaxLong(MethodrefConstant mathMaxLong) {
				this.mathMaxLong = mathMaxLong;
				return this;
			}

			Builder mathMaxDouble(MethodrefConstant mathMaxDouble) {
				this.mathMaxDouble = mathMaxDouble;
				return this;
			}

			Builder mathFloor(MethodrefConstant mathFloor) {
				this.mathFloor = mathFloor;
				return this;
			}

			Builder mathCeil(MethodrefConstant mathCeil) {
				this.mathCeil = mathCeil;
				return this;
			}

			Builder mathRint(MethodrefConstant mathRint) {
				this.mathRint = mathRint;
				return this;
			}

			Builder objectEquals(MethodrefConstant objectEquals) {
				this.objectEquals = objectEquals;
				return this;
			}

			Builder readLineHelper(MethodrefConstant readLineHelper) {
				this.readLineHelper = readLineHelper;
				return this;
			}

			Builder fetchHelper(@Nullable MethodrefConstant fetchHelper) {
				this.fetchHelper = fetchHelper;
				return this;
			}

			Builder awaitHelper(@Nullable MethodrefConstant awaitHelper) {
				this.awaitHelper = awaitHelper;
				return this;
			}

			Builder asyncRunHelper(@Nullable MethodrefConstant asyncRunHelper) {
				this.asyncRunHelper = asyncRunHelper;
				return this;
			}

			Builder futurepHelper(@Nullable MethodrefConstant futurepHelper) {
				this.futurepHelper = futurepHelper;
				return this;
			}

			Builder streampHelper(@Nullable MethodrefConstant streampHelper) {
				this.streampHelper = streampHelper;
				return this;
			}

			Builder makeStreamHelper(@Nullable MethodrefConstant makeStreamHelper) {
				this.makeStreamHelper = makeStreamHelper;
				return this;
			}

			Builder streamNewHelper(@Nullable MethodrefConstant streamNewHelper) {
				this.streamNewHelper = streamNewHelper;
				return this;
			}

			Builder streamReadHelper(@Nullable MethodrefConstant streamReadHelper) {
				this.streamReadHelper = streamReadHelper;
				return this;
			}

			Builder streamWriteHelper(@Nullable MethodrefConstant streamWriteHelper) {
				this.streamWriteHelper = streamWriteHelper;
				return this;
			}

			Builder streamCloseHelper(@Nullable MethodrefConstant streamCloseHelper) {
				this.streamCloseHelper = streamCloseHelper;
				return this;
			}

			Builder drainBodyHelper(@Nullable MethodrefConstant drainBodyHelper) {
				this.drainBodyHelper = drainBodyHelper;
				return this;
			}

			Builder waitForHelper(@Nullable MethodrefConstant waitForHelper) {
				this.waitForHelper = waitForHelper;
				return this;
			}

			Builder tcpConnectHelper(@Nullable MethodrefConstant tcpConnectHelper) {
				this.tcpConnectHelper = tcpConnectHelper;
				return this;
			}

			Builder tcpListenHelper(@Nullable MethodrefConstant tcpListenHelper) {
				this.tcpListenHelper = tcpListenHelper;
				return this;
			}

			Builder tcpAcceptHelper(@Nullable MethodrefConstant tcpAcceptHelper) {
				this.tcpAcceptHelper = tcpAcceptHelper;
				return this;
			}

			Builder tcpLocalAddressHelper(@Nullable MethodrefConstant tcpLocalAddressHelper) {
				this.tcpLocalAddressHelper = tcpLocalAddressHelper;
				return this;
			}

			Builder tcpPeerAddressHelper(@Nullable MethodrefConstant tcpPeerAddressHelper) {
				this.tcpPeerAddressHelper = tcpPeerAddressHelper;
				return this;
			}

			Builder tcpPeerPortHelper(@Nullable MethodrefConstant tcpPeerPortHelper) {
				this.tcpPeerPortHelper = tcpPeerPortHelper;
				return this;
			}

			Builder tcpSetTimeoutHelper(@Nullable MethodrefConstant tcpSetTimeoutHelper) {
				this.tcpSetTimeoutHelper = tcpSetTimeoutHelper;
				return this;
			}

			Builder tcpLocalPortHelper(@Nullable MethodrefConstant tcpLocalPortHelper) {
				this.tcpLocalPortHelper = tcpLocalPortHelper;
				return this;
			}

			Builder tlsConnectHelper(@Nullable MethodrefConstant tlsConnectHelper) {
				this.tlsConnectHelper = tlsConnectHelper;
				return this;
			}

			Builder tlsUpgradeHelper(@Nullable MethodrefConstant tlsUpgradeHelper) {
				this.tlsUpgradeHelper = tlsUpgradeHelper;
				return this;
			}

			Builder tlsListenHelper(@Nullable MethodrefConstant tlsListenHelper) {
				this.tlsListenHelper = tlsListenHelper;
				return this;
			}

			Builder tlsListenP12Helper(@Nullable MethodrefConstant tlsListenP12Helper) {
				this.tlsListenP12Helper = tlsListenP12Helper;
				return this;
			}

			Builder httpHandlerRuntime(JvmHttpHandlerRuntimeBuilder.@Nullable HttpHandlerRuntime httpHandlerRuntime) {
				this.httpHandlerRuntime = httpHandlerRuntime;
				return this;
			}

			Builder javaOps(@Nullable Map<String, MethodrefConstant> javaOps) {
				this.javaOps = javaOps;
				return this;
			}

			Builder objcOps(@Nullable Map<String, MethodrefConstant> objcOps) {
				this.objcOps = objcOps;
				return this;
			}

			Builder ffiOps(@Nullable Map<String, MethodrefConstant> ffiOps) {
				this.ffiOps = ffiOps;
				return this;
			}

			Builder simdOps(@Nullable Map<String, MethodrefConstant> simdOps) {
				this.simdOps = simdOps;
				return this;
			}

			Builder blasOps(@Nullable Map<String, MethodrefConstant> blasOps) {
				this.blasOps = blasOps;
				return this;
			}

			Builder gpuOps(@Nullable Map<String, MethodrefConstant> gpuOps) {
				this.gpuOps = gpuOps;
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

			Builder printCase(boolean printCase) {
				this.printCase = printCase;
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

			Builder usesPackedSequenceIo(boolean usesPackedSequenceIo) {
				this.usesPackedSequenceIo = usesPackedSequenceIo;
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

			Builder usesSeqString(boolean usesSeqString) {
				this.usesSeqString = usesSeqString;
				return this;
			}

			Builder mayUseInstances(boolean mayUseInstances) {
				this.mayUseInstances = mayUseInstances;
				return this;
			}

			Builder usesSynonymStreams(boolean usesSynonymStreams) {
				this.usesSynonymStreams = usesSynonymStreams;
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

			Builder warnedClRedefinitions(Set<String> warnedClRedefinitions) {
				this.warnedClRedefinitions = warnedClRedefinitions;
				return this;
			}

			Builder usesProgv(boolean usesProgv) {
				this.usesProgv = usesProgv;
				return this;
			}

			Builder usesFmakunbound(boolean usesFmakunbound) {
				this.usesFmakunbound = usesFmakunbound;
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

			Builder specialVars(Set<String> specialVars) {
				this.specialVars = specialVars;
				return this;
			}

			Builder dynVars(JvmDynVarRuntimeBuilder.@Nullable DynVarRuntime dynVars) {
				this.dynVars = dynVars;
				return this;
			}

			Builder globalFields(Map<String, FieldrefConstant> globalFields) {
				this.globalFields = globalFields;
				return this;
			}

			Builder rawGlobals(Map<String, JvmIntFusionCompiler.RawLocal> rawGlobals) {
				this.rawGlobals = rawGlobals;
				return this;
			}

			Builder numOps(Map<String, MethodrefConstant> numOps) {
				this.numOps = numOps;
				return this;
			}

			Builder mathOps(Map<String, MethodrefConstant> mathOps) {
				this.mathOps = mathOps;
				return this;
			}

			Builder systemOps(Map<String, MethodrefConstant> systemOps) {
				this.systemOps = systemOps;
				return this;
			}

			Ctx build() {
				return new Ctx(this);
			}

		}

		MethodrefConstant numOp(String key) {
			return Objects.requireNonNull(this.numOps.get(key), () -> "Unknown numeric helper: " + key);
		}

		MethodrefConstant mathOp(String key) {
			return Objects.requireNonNull(this.mathOps.get(key), () -> "Unknown math helper: " + key);
		}

		MethodrefConstant systemOp(String key) {
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

		void emit(int opcode) {
			if (opcode > MAX_ONE_BYTE_LOCAL_SLOT && this.stack.awaitingLocalIndex()) {
				// A local index past 255 does not fit the one-byte operand of the plain
				// load/store opcodes; rewrite the instruction just emitted into its
				// `wide` form rather than writing a truncated index that names a
				// DIFFERENT slot (which the frame walk only sometimes notices -- a
				// wrapped index landing on a same-typed slot is a silent wrong answer).
				int op = this.code.removeLast();
				this.code.add(Opcode.WIDE);
				this.code.add(op);
				this.code.add((opcode >> 8) & 0xFF);
				this.code.add(opcode & 0xFF);
				this.stack.widenPendingLocalIndex();
				return;
			}
			this.code.add(opcode);
			this.stack.feed(opcode);
		}

		void emitU2(int value) {
			byte[] bytes = ByteBuffer.allocate(2).putShort((short) value).array();
			this.code.add((int) bytes[0]);
			this.stack.feed(bytes[0]);
			this.code.add((int) bytes[1]);
			this.stack.feed(bytes[1]);
		}

		/**
		 * Appends an assembled block ({@link JvmAsm}) whole: a self-contained sequence
		 * with its own internal labels that computes over locals and leaves
		 * {@code produced} on the operand stack.
		 */
		void emitBlock(List<Integer> block, OperandStack.Slot... produced) {
			this.code.addAll(block);
			this.stack.appendOpaque(block.size(), produced);
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
				this.emit(storeOpcode(slot));
				this.emit(slots[i]);
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
				this.emit(this.stack.snapshot().getLast().wide() ? Opcode.POP2 : Opcode.POP);
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
					ctx.emit(loadOpcode(this.live.get(i)));
					ctx.emit(this.slots[i]);
				}
			}

		}

		private static int storeOpcode(OperandStack.Slot slot) {
			return switch (slot) {
				case REF -> Opcode.ASTORE;
				case INT -> Opcode.ISTORE;
				case FLOAT -> Opcode.FSTORE;
				case LONG -> Opcode.LSTORE;
				case DOUBLE -> Opcode.DSTORE;
				case UNINIT -> throw new IllegalStateException("an object under construction cannot be spilled");
			};
		}

		private static int loadOpcode(OperandStack.Slot slot) {
			return switch (slot) {
				case REF -> Opcode.ALOAD;
				case INT -> Opcode.ILOAD;
				case FLOAT -> Opcode.FLOAD;
				case LONG -> Opcode.LLOAD;
				case DOUBLE -> Opcode.DLOAD;
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
