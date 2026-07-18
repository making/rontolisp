package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispAsync;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SpecialVarCollector;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.compiler.BuiltinFunctionWrappers;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.rontolisp.compiler.GlobalVarCollector;
import am.ik.rontolisp.compiler.LispCompiler;
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

	private final boolean optimize;

	private final boolean simdAccel;

	/**
	 * Create a new JVM compiler targeting the given class name.
	 * @param className the fully qualified class name for the generated class
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
	 */
	public JvmLispCompiler(String className, boolean dynamic) {
		this(className, dynamic, false);
	}

	/**
	 * Create a new JVM compiler targeting the given class name.
	 * @param className the fully qualified class name for the generated class
	 * @param dynamic when {@code true}, unresolved function calls and variable references
	 * are resolved at runtime against the embedded {@code eval} global environment (late
	 * binding); see {@link #JvmLispCompiler(String, boolean)}
	 * @param optimize when {@code true}, dead-code-eliminate the finished class with
	 * {@link JvmClassShaker}: methods unreachable from {@code main} (and any static field
	 * only they reference) are dropped and the constant pool is compacted
	 */
	public JvmLispCompiler(String className, boolean dynamic, boolean optimize) {
		this(className, dynamic, optimize, false);
	}

	/**
	 * Create a new JVM compiler targeting the given class name.
	 * @param className the fully qualified class name for the generated class
	 * @param dynamic when {@code true}, unresolved function calls and variable references
	 * are resolved at runtime against the embedded {@code eval} global environment (late
	 * binding); see {@link #JvmLispCompiler(String, boolean)}
	 * @param optimize when {@code true}, dead-code-eliminate the finished class with
	 * {@link JvmClassShaker}; see {@link #JvmLispCompiler(String, boolean, boolean)}
	 * @param simdAccel when {@code true} ({@code --simd}), the six vectorizable
	 * {@code vec:} kernels
	 * ({@code add}/{@code sub}/{@code mul}/{@code scale}/{@code dot}/ {@code sum}) are
	 * lowered at their call sites to an embedded {@code jdk.incubator.vector} bridge
	 * ({@link JvmSimdVectorTemplate}) instead of the scalar {@code vec.lisp} reference.
	 * Running such a class requires {@code java --add-modules jdk.incubator.vector}.
	 */
	public JvmLispCompiler(String className, boolean dynamic, boolean optimize, boolean simdAccel) {
		this.className = className;
		this.dynamic = dynamic;
		this.optimize = optimize;
		this.simdAccel = simdAccel;
	}

	@Override
	public byte[] compile(List<LispVal> program) {
		// Resolve packages (in-package directives, qualified symbols, *package*) up front
		// so
		// the rest of compilation sees canonical names.
		program = new PackageResolver().resolveProgram(program);
		// Splice top-level (progn ...)/(eval-when ...) so Pass 1 collects the defuns
		// nested in them (the CLI already flattens via UserMacroExpander; this keeps
		// direct compiler invocations equivalent).
		program = LispMacroExpander.flattenTopLevel(program);
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
		program = LispMacroExpander.expandTopLevelDefinitions(program, structAccessors, closRegistry);
		// Desugar extended lambda lists (&optional/&key/&aux) into the native
		// "required + &rest" shape so the passes below only see that shape.
		program = LambdaLists.desugarProgram(program);
		// Create the %mv-spill global (a top-level setq) when the program uses a
		// multiple-value operator: the expansions read/write it across functions.
		program = LispMacroExpander.injectMvSpillGlobal(program);
		ConstantPool cp = new ConstantPool();
		ClassConstant thisClass = cp.addClass(cp.addUtf8(this.className));
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
		MethodrefConstant objectEquals = cp.addMethodref(objectClass,
				cp.addNameAndType(cp.addUtf8("equals"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		// Character runtime representation references (used by _lispToString /
		// _lispToDisplayString to print the #\name form and the bare glyph,
		// respectively).
		ClassConstant characterClass = cp.addClass(cp.addUtf8("java/lang/Character"));
		MethodrefConstant charValue = cp.addMethodref(characterClass,
				cp.addNameAndType(cp.addUtf8("charValue"), cp.addUtf8("()C")));
		MethodrefConstant stringValueOfChar = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(C)Ljava/lang/String;")));
		Utf8Constant charPrin1Name = cp.addUtf8("_charPrin1");
		Utf8Constant charPrin1Desc = cp.addUtf8("(C)Ljava/lang/String;");
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
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.HTTP_HANDLER));
		boolean usesFetch = programUsesSymbol(program, fetchQualified);
		boolean usesAsyncSpawn = programUsesSymbol(program, LispNames.ASYNC_RUN_QUALIFIED) || usesHttpHandler;
		boolean usesStreamOps = programUsesSymbol(program,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MAKE_STREAM))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.STREAM_READ))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.STREAM_WRITE))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.STREAM_CLOSE))
				|| programUsesSymbol(program,
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.ASYNC_STREAMP));
		boolean usesAsyncRuntime = usesFetch || usesAsyncSpawn || usesStreamOps
				|| programUsesSymbol(program, awaitQualified)
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
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_CONNECT))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_LISTEN))
				|| programUsesSymbol(program,
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_LISTEN_P12));
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
		MethodrefConstant tlsConnectHelperMethod = usesSockets
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmSocketRuntimeBuilder.TLS_CONNECT_METHOD),
						cp.addUtf8(JvmSocketRuntimeBuilder.TLS_CONNECT_DESC)))
				: null;
		MethodrefConstant tlsListenHelperMethod = usesSockets
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmSocketRuntimeBuilder.TLS_LISTEN_METHOD),
						cp.addUtf8(JvmSocketRuntimeBuilder.TLS_LISTEN_DESC)))
				: null;
		MethodrefConstant tlsListenP12HelperMethod = usesSockets ? cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(JvmSocketRuntimeBuilder.TLS_LISTEN_P12_METHOD),
						cp.addUtf8(JvmSocketRuntimeBuilder.TLS_LISTEN_P12_DESC)))
				: null;

		// tls-connect's :insecure opt-out installs the generated class itself as a
		// trust-all X509TrustManager (the JVM backend cannot emit an anonymous class),
		// so when the program uses tls-connect the class implements the interface, gets
		// a no-arg constructor (for _tlsConnect's `new Prog()`) and the three trust
		// methods. JSSE calls the trust methods through the interface, an edge the
		// tree-shaker cannot see, so they are extra --optimize roots.
		boolean usesTlsConnect = programUsesSymbol(program,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_CONNECT));
		// rontolisp:http-handler reuses the same "the generated class implements the
		// interface" mechanism: the class implements HttpHandlerSupport.Handler, the
		// directive stores the handler funcref in a static field and calls
		// HttpHandlerSupport.serve(port, new Prog()), and the injected handle() method
		// marshals the request/response plists through the _invoke_1 dispatcher.
		// The async runtime is a third user: the class implements Runnable and
		// _async_run does `new Prog()` per spawned body.
		boolean needsInstanceCtor = usesTlsConnect || usesHttpHandler || usesAsyncRuntime;
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
				? JvmJavaRuntimeBuilder.build(cp, thisClass, stringConcat) : null;

		ClassConstant objectArrayClass = cp.addClass(cp.addUtf8("[Ljava/lang/Object;"));
		final JvmHttpHandlerRuntimeBuilder.@Nullable HttpHandlerRuntime httpHandlerRuntime = usesHttpHandler
				? JvmHttpHandlerRuntimeBuilder.build(cp, thisClass, objectClass, objectArrayClass, stringClass,
						longClass, longValue, stringLength, stringSubstring, stringConcat)
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
		ConstantPool.StringConstant nilStr = cp.addString("nil");
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
		for (LispVal expr : program) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym
					&& LispNames.DEFUN.equals(sym.name())) {
				defuns.add(extractSetqLambda(LispMacroExpander.expandDefun(cons)));
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

		// Inject built-in function wrappers (user defuns take priority)
		Set<String> userDefinedNames = new HashSet<>();
		for (DefunDecl defun : defuns) {
			userDefinedNames.add(defun.name);
		}
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
		// Hash-table wrappers reference helpers (JvmHashRuntimeBuilder) emitted only when
		// the program uses a hash table; gate the whole group together.
		if (!programUsesAnyHashOp(program)) {
			wrapperExcludes.addAll(BuiltinFunctionWrappers.HASH_FUNCTIONS);
		}
		// Fill-pointer array wrappers reference the array runtime helpers
		// (JvmArrayRuntimeBuilder), emitted only when the program uses an array
		// operator; gate the group the same way.
		if (!programUsesAnyArrayOp(program)) {
			wrapperExcludes.addAll(BuiltinFunctionWrappers.ARRAY_FILL_POINTER_FUNCTIONS);
		}
		// #'error/#'cerror/#'signal/#'warn wrappers forward the datum only (lite), and
		// #'format renders via the runtime control renderer; inject each only when the
		// program takes the operator as a first-class value.
		for (String op : BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS) {
			if (program.stream().noneMatch(expr -> BuiltinFunctionWrappers.referencesFunctionValue(expr, op))) {
				wrapperExcludes.add(op);
			}
		}
		for (LispVal wrapper : BuiltinFunctionWrappers.generate(userDefinedNames, wrapperExcludes)) {
			defuns.add(extractSetqLambda(wrapper));
		}

		// Collect top-level global variables and give each a dedicated static field.
		// A reference compiles to getstatic from any method body, so a global is
		// readable/assignable from a defun/lambda (not just from main). Field names are
		// prefixed to avoid colliding with runtime helper fields (e.g. _genv).
		Set<String> globals = new java.util.LinkedHashSet<>(GlobalVarCollector.collect(topLevelExprs));
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
		// binding rather than a lexical slot (JvmLetCompiler).
		Set<String> specialVars = SpecialVarCollector.collect(topLevelExprs);
		globals.addAll(specialVars);
		Map<String, FieldrefConstant> globalFields = new HashMap<>();
		List<Utf8Constant> globalFieldNameUtfs = new ArrayList<>();
		Utf8Constant globalFieldDescUtf = cp.addUtf8("Ljava/lang/Object;");
		for (String g : globals) {
			Utf8Constant fieldNameUtf = cp.addUtf8("_g$" + mangleMethodName(g));
			globalFieldNameUtfs.add(fieldNameUtf);
			globalFields.put(g, cp.addFieldref(thisClass, cp.addNameAndType(fieldNameUtf, globalFieldDescUtf)));
		}

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

		// Shared state for lambda discovery
		List<LambdaInfo> lambdaDecls = new ArrayList<>();
		Set<Integer> indirectCallArities = new HashSet<>();

		// The reader runtime is emitted for read/load; load also evaluates each form, so
		// it pulls in the eval runtime as well.
		boolean usesLoad = programUsesSymbol(program, LispNames.LOAD);
		boolean usesRead = programUsesSymbol(program, LispNames.READ)
				|| programUsesSymbol(program, LispNames.READ_FROM_STRING) || usesLoad;

		// When the program uses eval, the runtime _apply dispatches by argument count, so
		// every arity up to the maximum callable must have a dispatch method. The apply
		// built-in reuses _apply, so it forces the eval runtime to be emitted as well.
		// boundp/symbol-value/fboundp resolve symbols at runtime against the eval
		// runtime's global env mirror (_genv) and function registry (_lookup/_fenv), so
		// they force the eval runtime like apply does.
		// multiple-value-call forces apply too: its expansion spreads a spill
		// producer's dynamic value count with (apply fn (append ...)).
		boolean usesEval = programUsesEval(program) || usesLoad || this.dynamic || usesJava
				|| programUsesSymbol(program, LispNames.APPLY) || programUsesSymbol(program, LispNames.BOUNDP)
				|| programUsesSymbol(program, LispNames.SYMBOL_VALUE) || programUsesSymbol(program, LispNames.FBOUNDP)
				|| programUsesSymbol(program, LispNames.MULTIPLE_VALUE_CALL);
		if (usesEval) {
			for (int arity = 0; arity <= JvmEvalRuntimeBuilder.MAX_CALLABLE_ARITY; arity++) {
				indirectCallArities.add(arity);
			}
		}
		// _await applies rontolisp:then callbacks through the arity-1 dispatcher and
		// _async_run applies the body thunk through the arity-0 one, so their emission
		// must be forced whenever the async runtime is present; the http-handler
		// handle() method applies the handler through arity 1 the same way.
		if (usesAsyncRuntime) {
			indirectCallArities.add(0);
			indirectCallArities.add(1);
		}

		// Whether the program can produce a packed float array (a #d(...) literal or
		// make-array :element-type 'double-float). When true, the array op compilers
		// route through the _fv* dispatch helpers so a packed double[] and a general
		// ArrayList are both handled; when false the default build is byte-identical.
		boolean usesFloatArray = programUsesFloatArray(program);

		// Whether the array runtime helper group is emitted (the same test that gates
		// its emission below). The mutable-character-vector consumers -- the _eqv
		// normalization, the stringp extension, the per-site _strv calls and the print
		// branch -- all key off this one gate, so an array-free program compiles
		// byte-identically to a build that never knew character vectors.
		boolean usesArrays = programUsesAnyArrayOp(program) || usesFloatArray;
		MethodrefConstant strvMethod = usesArrays ? cp.addMethodref(thisClass, cp
			.addNameAndType(cp.addUtf8(JvmArrayRuntimeBuilder.STRV), cp.addUtf8(JvmArrayRuntimeBuilder.STRV_DESC)))
				: null;

		// Numeric runtime helpers (long arithmetic with automatic BigInteger promotion)
		JvmNumericRuntimeBuilder.NumericRuntime numericRuntime = JvmNumericRuntimeBuilder.build(cp, thisClass,
				strvMethod);

		// --vec: emit the Vector API acceleration bridge only when the program actually
		// references one of the six accelerated vec: kernels (directly or via a spliced
		// mean/norm body). Off by default, so the ordinary scalar vec.lisp is used. The
		// bridge is a self-contained embedded class (like the java: interop bridge); the
		// packed float-array _fv* helpers still render/index its double[] results.
		boolean usesSimd = this.simdAccel && programUsesAnyAcceleratedSimdOp(program);
		final JvmSimdRuntimeBuilder.@Nullable SimdRuntime simdRuntime = usesSimd
				? JvmSimdRuntimeBuilder.build(cp, thisClass, stringConcat) : null;

		// Reusable builder template with shared constants and state
		Ctx.Builder ctxBuilder = Ctx.builder()
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
			.tlsConnectHelper(tlsConnectHelperMethod)
			.tlsListenHelper(tlsListenHelperMethod)
			.tlsListenP12Helper(tlsListenP12HelperMethod)
			.httpHandlerRuntime(httpHandlerRuntime)
			.javaOps(javaRuntime != null ? javaRuntime.ops() : null)
			.dynamic(this.dynamic)
			.usesFloatArray(usesFloatArray)
			.usesArrays(usesArrays)
			.simdOps(simdRuntime != null ? simdRuntime.ops() : null)
			.className(this.className)
			.userDefunNames(Set.copyOf(userDefinedNames))
			.globals(globals)
			.specialVars(specialVars)
			.globalFields(globalFields)
			.structAccessors(structAccessors)
			.closRegistry(closRegistry);

		// Pass 2a: Compile each defun body
		List<Ctx> funcCtxs = new ArrayList<>();
		for (DefunDecl defun : defuns) {
			Ctx funcCtx = ctxBuilder.build();
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
			for (int i = 0; i < defun.bodyExprs.size(); i++) {
				if (i > 0) {
					funcCtx.emit(Opcode.POP);
				}
				JvmExprCompiler.compileExpr(defun.bodyExprs.get(i), funcCtx, this.className);
			}
			funcCtx.emit(Opcode.ARETURN);
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
		// When eval is present, a top-level global variable binding (setq/defvar/...) is
		// mirrored into the eval runtime's global environment via _store, so an eval'd
		// expression can resolve it.
		MethodrefConstant evalStoreRef = usesEval
				? cp.addMethodref(thisClass,
						cp.addNameAndType(cp.addUtf8("_store"),
								cp.addUtf8(
										"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")))
				: null;
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
		// plus the trailing RETURN; a single form larger than this still cannot be split
		// (a pre-existing per-form limit: chunking happens BETWEEN top-level forms, so
		// one
		// form whose bytecode passes the 64 KB per-method cap has no split point).
		final int chunkCodeBudget = 48000;
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
			JvmExprCompiler.compileExpr(expr, chunkCtx, this.className);
			chunkCtx.emit(Opcode.POP);
		}
		if (chunkCtx != null) {
			chunkCtx.emit(Opcode.RETURN);
		}

		// main() simply calls each top-level chunk in order, then returns.
		Ctx mainCtx = ctxBuilder.build();
		for (MethodrefConstant ref : topChunkRefs) {
			mainCtx.emit(Opcode.INVOKESTATIC);
			mainCtx.emitU2(ref.index());
		}
		mainCtx.emit(Opcode.RETURN);

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
			for (int i = 0; i < lambda.bodyExprs.size(); i++) {
				if (i > 0) {
					lambdaCtx.emit(Opcode.POP);
				}
				JvmExprCompiler.compileExpr(lambda.bodyExprs.get(i), lambdaCtx, this.className);
			}
			lambdaCtx.emit(Opcode.ARETURN);
			lambdaCtxs.add(lambdaCtx);
			lambdaIdx++;
		}

		// Build dispatch functions for each needed arity. When the eval runtime is
		// present, the dispatcher falls back to _apply for interpreted closures
		// (funcId == -1) created by the runtime's lambda.
		MethodrefConstant applyRefForDispatch = usesEval
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8("_apply"),
						cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")))
				: null;
		List<DispatchMethod> dispatchMethods = new ArrayList<>();
		for (int arity : indirectCallArities) {
			DispatchMethod dm = JvmRuntimeBuilder.buildDispatchMethod(arity, functions, lambdaDecls, lambdaFuncInfos,
					cp, thisClass, objectArrayClass, integerClass, integerValue, objectClass, applyRefForDispatch);
			dispatchMethods.add(dm);
		}

		// Build the eval runtime methods and the global-environment field (only when
		// used)
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
		List<Integer> lookupCode = List.of();
		if (usesEval) {
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
				.functions(functions)
				.build();
			evalCode = JvmEvalRuntimeBuilder.buildEval(ec);
			applyCode = JvmEvalRuntimeBuilder.buildApply(ec);
			storeCode = JvmEvalRuntimeBuilder.buildStore(ec);
			envLookupCode = JvmEvalRuntimeBuilder.buildEnvLookup(ec);
			lookupCode = JvmEvalRuntimeBuilder.buildLookup(ec);
		}

		// Build the runtime reader methods (read/load), only when used
		Utf8Constant readSrcName = cp.addUtf8("_readSrc");
		Utf8Constant readSrcDesc = cp.addUtf8("Ljava/lang/String;");
		Utf8Constant readPosName = cp.addUtf8("_readPos");
		Utf8Constant readPosDesc = cp.addUtf8("I");
		List<JvmReadRuntimeBuilder.ReadMethod> readMethods = List.of();
		if (usesRead) {
			readMethods = JvmReadRuntimeBuilder
				.create(cp, thisClass, objectClass, objectArrayClass, stringClass, longValueOf, doubleValueOf,
						stringCharAt, stringLength, stringSubstring, objectEquals, readLineHelperMethod, usesLoad)
				.methods();
		}
		final List<JvmReadRuntimeBuilder.ReadMethod> readMethodsFinal = readMethods;

		// Build the hash-table runtime helpers, only when the program uses hash tables.
		boolean usesHashTables = programUsesAnyHashOp(program);
		final List<JvmHashRuntimeBuilder.HashMethod> hashMethods = usesHashTables ? JvmHashRuntimeBuilder.build(cp,
				thisClass, objectClass, objectArrayClass, longValueOf, lispToStringMethod) : List.of();

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
				built.addAll(JvmFloatArrayRuntimeBuilder.build(cp, objectClass, objectArrayClass, thisClass));
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
							cp.addNameAndType(cp.addUtf8(JvmFloatArrayRuntimeBuilder.TO_GENERAL),
									cp.addUtf8(JvmFloatArrayRuntimeBuilder.TO_GENERAL_DESC))),
					cp.addMethodref(stringClass,
							cp.addNameAndType(cp.addUtf8("replaceFirst"),
									cp.addUtf8("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"))),
					cp.addString("^#\\d*A?\\("), cp.addString("#d("), cp.addString("#f("));
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
			ClassConstant hashMapClassForPrint = usesHashTables ? cp.addClass(cp.addUtf8("java/util/HashMap")) : null;
			MethodrefConstant objectGetClass = cp.addMethodref(objectClass,
					cp.addNameAndType(cp.addUtf8("getClass"), cp.addUtf8("()Ljava/lang/Class;")));
			ClassConstant classClass = cp.addClass(cp.addUtf8("java/lang/Class"));
			MethodrefConstant classGetName = cp.addMethodref(classClass,
					cp.addNameAndType(cp.addUtf8("getName"), cp.addUtf8("()Ljava/lang/String;")));
			javaPrint = new JvmRuntimeBuilder.JavaPrint(bigIntegerClassForPrint, hashMapClassForPrint, objectGetClass,
					classGetName, stringConcat, cp.addString("#<java "), cp.addString(">"));
		}
		else {
			javaPrint = null;
		}

		// Futures (CompletableFutures / stream-read tokens at runtime) print as
		// #<FUTURE> and streams as #<STREAM> (interpreter parity); the branches are
		// emitted only when the program can create them.
		final JvmRuntimeBuilder.@Nullable FuturePrint futurePrint = usesAsyncRuntime
				? new JvmRuntimeBuilder.FuturePrint(cp.addClass(cp.addUtf8("java/util/concurrent/CompletableFuture")),
						cp.addString("#<FUTURE>"), objectArrayClass, cp.addString(JvmAsyncRuntimeBuilder.SMARKER),
						cp.addString(JvmAsyncRuntimeBuilder.RMARKER), cp.addString("#<STREAM>"))
				: null;

		// Build _lispToString and _consToString helper method bodies
		List<Integer> ltsCode = JvmRuntimeBuilder.buildLispToStringBody(longClass, doubleClass, stringClass,
				objectArrayClass, integerClass, longToString, doubleToString, objectToString, consToStringMethod,
				nilStr, funcStr, ratioArrayClass, stringConcat, slashStr, characterClass, charValue, charPrin1Method,
				arrayListClassForPrint, arrayToStringMethod, strvMethod, javaPrint, futurePrint, packedPrint);
		List<Integer> ctsCode = JvmRuntimeBuilder.buildConsToStringBody(objectArrayClass, stringBuilderClass, sbInitStr,
				sbAppendStr, sbToString, lispToStringMethod, openParenStr, closeParenStr, spaceStr, dotStr,
				ratioArrayClass);
		List<Integer> ltdsCode = JvmRuntimeBuilder.buildLispToDisplayStringBody(longClass, doubleClass, stringClass,
				objectArrayClass, integerClass, longToString, doubleToString, objectToString, consToDisplayStringMethod,
				nilStr, funcStr, stringCharAt, stringLength, stringSubstring, ratioArrayClass, stringConcat, slashStr,
				characterClass, charValue, stringValueOfChar, arrayListClassForPrint, arrayToDisplayStringMethod,
				strvMethod, javaPrint, futurePrint, packedPrint);
		List<Integer> charPrin1Code = JvmRuntimeBuilder.buildCharPrin1Body(cp, stringConcat, stringValueOfChar);
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
		final JvmSocketRuntimeBuilder.@Nullable SocketRuntime socketRuntime = usesSockets
				? JvmSocketRuntimeBuilder.build(cp, thisClass, objectClass, stringClass, longClass, longValueOf,
						longValue, stringLengthForIo, stringSubstring, stringConcat)
				: null;
		List<JvmIoRuntimeBuilder.IoMethod> ioMethods = JvmIoRuntimeBuilder
			.create(cp, thisClass, objectClass, stringClass, longClass, longValueOf, longValue, stringLengthForIo,
					stringSubstring, stringConcat, systemOut, printlnStr, readLineHelperMethod, socketRuntime)
			.methods();
		Utf8Constant streamsFieldName = cp.addUtf8(JvmIoRuntimeBuilder.STREAMS_FIELD);
		Utf8Constant streamsFieldDesc = cp.addUtf8(JvmIoRuntimeBuilder.STREAMS_DESC);
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
				objectArrayClass, stringClass, longValueOf);

		Utf8Constant mainUtf8 = cp.addUtf8("main");
		Utf8Constant mainDesc = cp.addUtf8("([Ljava/lang/String;)V");
		Utf8Constant codeUtf8 = cp.addUtf8("Code");

		// Effectively-final aliases for capture in the writer lambda
		final List<Integer> evalBody = evalCode;
		final List<Integer> applyBody = applyCode;
		final List<Integer> storeBody = storeCode;
		final List<Integer> envLookupBody = envLookupCode;
		final List<Integer> lookupBody = lookupCode;

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
			})
			.writeFields(f -> {
				f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
					.writeU2(stdinReaderFieldName)
					.writeU2(stdinReaderFieldDesc)
					.writeU2(0));
				f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
					.writeU2(streamsFieldName)
					.writeU2(streamsFieldDesc)
					.writeU2(0));
				f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
					.writeU2(streamCountFieldName)
					.writeU2(streamCountFieldDesc)
					.writeU2(0));
				f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
					.writeU2(colFieldName)
					.writeU2(colFieldDesc)
					.writeU2(0));
				f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
					.writeU2(gensymCtrFieldName)
					.writeU2(gensymCtrFieldDesc)
					.writeU2(0));
				if (httpHandlerRuntime != null) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
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
				// One static Object field per top-level global variable (default null =
				// nil); written by setq/defvar, read by getstatic from any method body.
				for (Utf8Constant gfName : globalFieldNameUtfs) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(gfName)
						.writeU2(globalFieldDescUtf)
						.writeU2(0));
				}
				if (javaRuntime != null) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(javaRuntime.initedFieldName())
						.writeU2(javaRuntime.initedFieldDesc())
						.writeU2(0));
				}
				if (simdRuntime != null) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(simdRuntime.initedFieldName())
						.writeU2(simdRuntime.initedFieldDesc())
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
			})
			.writeMethods(methods -> {
				methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, mainUtf8, mainDesc,
						method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(mainCtx.maxStack())
								.writeU2(mainCtx.maxLocals)
								.writeCode((Object[]) mainCtx.code.toArray(new Integer[0]))
								.writeExceptionTable(mainCtx.exceptionTable)
								.writeU2(0);
						})));
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
				if (mainCtx.conditionChannel.used) {
					// <clinit>: _condTl = new ThreadLocal(); (initialValue null, so get()
					// on a thread with no pending condition returns null). The async
					// runtime's _handoffTl (the eager-start handoff) joins the same
					// initializer when present.
					ConditionChannel channel = mainCtx.conditionChannel;
					List<Integer> clinitCode = new java.util.ArrayList<>();
					for (FieldrefConstant tlField : (handoffFieldRef != null)
							? List.of(java.util.Objects.requireNonNull(channel.condTlField),
									java.util.Objects.requireNonNull(channel.depthTlField), handoffFieldRef)
							: List.of(java.util.Objects.requireNonNull(channel.condTlField),
									java.util.Objects.requireNonNull(channel.depthTlField))) {
						clinitCode.add(Opcode.NEW);
						JvmRuntimeBuilder.emitU2(clinitCode,
								java.util.Objects.requireNonNull(channel.threadLocalClass).index());
						clinitCode.add(Opcode.DUP);
						clinitCode.add(Opcode.INVOKESPECIAL);
						JvmRuntimeBuilder.emitU2(clinitCode, java.util.Objects.requireNonNull(channel.tlCtor).index());
						clinitCode.add(Opcode.PUTSTATIC);
						JvmRuntimeBuilder.emitU2(clinitCode, tlField.index());
					}
					clinitCode.add(Opcode.RETURN);
					methods.add(AccessFlag.ACC_STATIC, java.util.Objects.requireNonNull(channel.clinitName),
							java.util.Objects.requireNonNull(channel.clinitDesc),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(2)
									.writeU2(0)
									.writeCode((Object[]) clinitCode.toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, lispToStringName, lispToStringDescUtf,
						method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(3)
								.writeU2(2)
								.writeCode((Object[]) ltsCode.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
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
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, im.name(), im.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(im.maxStack())
									.writeU2(im.maxLocals())
									.writeCode((Object[]) im.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				if (javaRuntime != null) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, javaRuntime.initName(),
							javaRuntime.initDesc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(javaRuntime.maxStack())
									.writeU2(javaRuntime.maxLocals())
									.writeCode((Object[]) javaRuntime.initCode().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				if (simdRuntime != null) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, simdRuntime.initName(),
							simdRuntime.initDesc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(simdRuntime.maxStack())
									.writeU2(simdRuntime.maxLocals())
									.writeCode((Object[]) simdRuntime.initCode().toArray(new Integer[0]))
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
					// same for the HttpHandlerSupport.Handler instance.
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
					// handle(Request): the HttpHandlerSupport.Handler implementation
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
				if (usesEval) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, lookupName, lookupDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8,
									attr -> attr.writeU2(8)
										.writeU2(2)
										.writeCode((Object[]) lookupBody.toArray(new Integer[0]))
										.writeU2(0)
										.writeU2(0))));
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
		if (this.optimize) {
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
			java.util.Set<String> roots = new java.util.HashSet<>(Set.of("main"));
			if (usesJava) {
				roots.add("_apply");
			}
			if (usesTlsConnect) {
				roots.add("checkClientTrusted");
				roots.add("checkServerTrusted");
				roots.add("getAcceptedIssuers");
			}
			// HttpHandlerSupport invokes handle through the Handler interface, another
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
			classBytes = JvmClassShaker.shake(classBytes, roots);
		}
		// Insert the StackMapTable every class version above 50 requires (and the shaker
		// could not have preserved), stamping the target version. Must stay after the
		// shake: the shaker rejects Code sub-attributes and would not rewrite the
		// constant-pool entries the frames reference.
		return StackMapAugmenter.augment(classBytes, CLASS_MAJOR_VERSION);
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

	/**
	 * Whether the program references any of the seven vectorizable {@code vec:} kernels
	 * ({@code add}/{@code sub}/{@code mul}/{@code scale}/{@code dot}/{@code sum}/
	 * {@code matvec}) or any of the fifteen accelerated {@code linalg:} ones, so that
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
		for (String member : JvmLinalgSimdCompiler.members()) {
			if (programUsesSymbol(program, JvmLinalgSimdCompiler.qualifiedName(member))) {
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
		// vector/svref/coerce/array-rank/array-dimension/array-total-size/
		// row-major-aref/array-row-major-index expand into make-array/aref/%aset/
		// array-dimensions/_aref1 during compileExpr, after this scan runs, so the
		// derived names gate the helpers too.
		return programUsesSymbol(program, LispNames.MAKE_ARRAY) || programUsesSymbol(program, LispNames.AREF)
				|| programUsesSymbol(program, LispNames.ASET) || programUsesSymbol(program, LispNames.ARRAY_DIMENSIONS)
				|| programUsesSymbol(program, LispNames.VECTOR) || programUsesSymbol(program, LispNames.SVREF)
				|| programUsesSymbol(program, LispNames.ARRAY_RANK)
				|| programUsesSymbol(program, LispNames.ARRAY_DIMENSION)
				|| programUsesSymbol(program, LispNames.ARRAY_TOTAL_SIZE)
				|| programUsesSymbol(program, LispNames.ROW_MAJOR_AREF)
				|| programUsesSymbol(program, LispNames.ROW_MAJOR_ASET)
				|| programUsesSymbol(program, LispNames.ARRAY_ROW_MAJOR_INDEX)
				|| programUsesSymbol(program, LispNames.FILL_POINTER)
				|| programUsesSymbol(program, LispNames.SET_FILL_POINTER)
				|| programUsesSymbol(program, LispNames.ARRAY_HAS_FILL_POINTER_P)
				|| programUsesSymbol(program, LispNames.ADJUSTABLE_ARRAY_P)
				|| programUsesSymbol(program, LispNames.ARRAY_ELEMENT_TYPE)
				|| programUsesSymbol(program, LispNames.VECTOR_PUSH) || programUsesSymbol(program, LispNames.VECTOR_POP)
				|| programUsesSymbol(program, LispNames.VECTOR_PUSH_EXTEND)
				|| programUsesSymbol(program, LispNames.ADJUST_ARRAY)
				|| programUsesSymbol(program, LispNames.ARRAY_BECOME)
				|| programUsesSymbol(program, LispNames.ARRAY_DISPLACEMENT)
				|| programUsesSymbol(program, LispNames.ARRAY_DISP_TARGET)
				|| programUsesSymbol(program, LispNames.ARRAY_DISP_OFFSET)
				|| programUsesSymbol(program, LispNames.COERCE) || programContainsArrayLiteral(program);
	}

	// True when a self-evaluating array literal (#(...)) appears anywhere in the program,
	// so the array runtime helpers (used to print it) are emitted even without an
	// explicit
	// make-array/aref call.
	private static boolean programContainsArrayLiteral(List<LispVal> program) {
		for (LispVal expr : program) {
			if (containsArrayLiteral(expr)) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsArrayLiteral(LispVal val) {
		// A packed #d(...) literal lowers to a general array here, so it counts as an
		// array
		// literal for the runtime/print gate exactly like #(...)/#nA.
		if (val instanceof am.ik.rontolisp.LispArray || val instanceof am.ik.rontolisp.LispFloatArray) {
			return true;
		}
		if (val instanceof LispCons cons) {
			return containsArrayLiteral(cons.car()) || containsArrayLiteral(cons.cdr());
		}
		return false;
	}

	// True when the program can produce a packed float array: a #d(...) literal
	// (LispFloatArray) or a (make-array ... :element-type 'double-float ...) form. Gates
	// the _fv* dispatch helpers and their routing; when false the array op compilers call
	// the general _array* helpers directly, keeping the default build byte-identical.
	private static boolean programUsesFloatArray(List<LispVal> program) {
		for (LispVal expr : program) {
			if (usesFloatArray(expr)) {
				return true;
			}
		}
		return false;
	}

	private static boolean usesFloatArray(LispVal val) {
		if (val instanceof am.ik.rontolisp.LispFloatArray) {
			return true;
		}
		if (val instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head && LispNames.MAKE_ARRAY.equals(head.name())
					&& makeArrayIsPackedFloat(cons)) {
				return true;
			}
			return usesFloatArray(cons.car()) || usesFloatArray(cons.cdr());
		}
		return false;
	}

	// Whether a (make-array ...) call carries :element-type 'double-float or
	// 'single-float (a literal quoted symbol at the call site, package qualifier ignored)
	// --
	// either produces a packed float array.
	private static boolean makeArrayIsPackedFloat(LispCons makeArray) {
		List<LispVal> args = makeArray.toList();
		for (int i = 2; i + 1 < args.size(); i++) {
			if (args.get(i) instanceof LispSymbol kw && LispNames.ELEMENT_TYPE_KEYWORD.equals(kw.name())) {
				LispVal type = args.get(i + 1);
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
		for (int i = 0; i < directive.paramTypes().size(); i++) {
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
	 * An active {@code %block} return boundary during compilation. {@code rvSlot} is the
	 * local that holds the block's value; {@code exitPatches} collects the positions of
	 * the {@code goto} instructions emitted by {@code return} forms, all back-patched to
	 * the block's exit once its body has been compiled; {@code entryStack} is the operand
	 * stack the block was entered with, which is the shape its exit is reached with on
	 * every path -- a {@code return} discards whatever the body had pushed on top of it
	 * (see {@link JvmReturnCompiler}).
	 */
	record BlockTarget(int rvSlot, List<Integer> exitPatches, List<OperandStack.Slot> entryStack) {
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

		/** The highest local slot a one-byte load/store operand can name. */
		private static final int MAX_LOCAL_SLOT = 255;

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

		final @Nullable MethodrefConstant tlsConnectHelper;

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
		 * The accelerated {@code vec:} bridge references ({@code init} plus one per
		 * vectorizable kernel member name -- {@code add}/{@code sub}/{@code mul}/
		 * {@code scale}/{@code dot}/{@code sum}); null unless {@code --simd} emitted the
		 * acceleration runtime for a program that uses a vectorizable {@code vec:}
		 * kernel.
		 */
		final @Nullable Map<String, MethodrefConstant> simdOps;

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

		int[] nextFuncId;

		int nextLocal = 1;

		int maxLocals = 1;

		boolean dynamic = false;

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
		 * True when the array runtime helper group ({@link JvmArrayRuntimeBuilder}) is
		 * emitted for this program. Gates the mutable-character-vector consumers (the
		 * {@code stringp} extension and the per-site {@code _strv} normalization), so an
		 * array-free program compiles byte-identically. Shared across every context.
		 */
		boolean usesArrays = false;

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

		String className = "";

		Set<String> userDefunNames = Set.of();

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
		 * This method's {@code Code} attribute exception table, in dispatch order.
		 * {@code unwind-protect} appends catch-any entries covering its protected region
		 * (class version 50 verifies handlers without a StackMapTable).
		 */
		final List<ByteCodeWriter.ExceptionTableEntry> exceptionTable = new ArrayList<>();

		/**
		 * The compilation-wide condition-channel state (the {@code _condTl} ThreadLocal
		 * field constants); one instance shared across every context of a compilation
		 * through the single builder, like {@link #nextFuncId}.
		 */
		final ConditionChannel conditionChannel;

		private Ctx(Builder builder) {
			this.conditionChannel = builder.conditionChannel;
			this.dynamic = builder.dynamic;
			this.usesFloatArray = builder.usesFloatArray;
			this.usesArrays = builder.usesArrays;
			this.className = builder.className;
			this.userDefunNames = builder.userDefunNames;
			this.structAccessors = builder.structAccessors;
			this.closRegistry = builder.closRegistry;
			this.globals = builder.globals;
			this.specialVars = builder.specialVars;
			this.globalFields = builder.globalFields;
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
			this.tlsConnectHelper = builder.tlsConnectHelper;
			this.tlsListenHelper = builder.tlsListenHelper;
			this.tlsListenP12Helper = builder.tlsListenP12Helper;
			this.httpHandlerRuntime = builder.httpHandlerRuntime;
			this.javaOps = builder.javaOps;
			this.simdOps = builder.simdOps;
			this.functions = builder.functions;
			this.lambdaDecls = builder.lambdaDecls;
			this.indirectCallArities = builder.indirectCallArities;
			this.nextFuncId = builder.nextFuncId;
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

			private @Nullable MethodrefConstant tlsConnectHelper;

			private @Nullable MethodrefConstant tlsListenHelper;

			private @Nullable MethodrefConstant tlsListenP12Helper;

			private JvmHttpHandlerRuntimeBuilder.@Nullable HttpHandlerRuntime httpHandlerRuntime;

			private @Nullable Map<String, MethodrefConstant> javaOps;

			private @Nullable Map<String, MethodrefConstant> simdOps;

			private Map<String, FunctionInfo> functions = Map.of();

			private List<LambdaInfo> lambdaDecls = new ArrayList<>();

			private Set<Integer> indirectCallArities = new HashSet<>();

			private int[] nextFuncId = new int[1];

			private boolean dynamic = false;

			private boolean usesFloatArray = false;

			private boolean usesArrays = false;

			private String className = "";

			private Set<String> userDefunNames = Set.of();

			private Map<String, Integer> structAccessors = Map.of();

			private ClosRegistry closRegistry = new ClosRegistry();

			private Set<String> globals = Set.of();

			private Set<String> specialVars = Set.of();

			private Map<String, FieldrefConstant> globalFields = Map.of();

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

			Builder tcpLocalPortHelper(@Nullable MethodrefConstant tcpLocalPortHelper) {
				this.tcpLocalPortHelper = tcpLocalPortHelper;
				return this;
			}

			Builder tlsConnectHelper(@Nullable MethodrefConstant tlsConnectHelper) {
				this.tlsConnectHelper = tlsConnectHelper;
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

			Builder simdOps(@Nullable Map<String, MethodrefConstant> simdOps) {
				this.simdOps = simdOps;
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

			Builder nextFuncId(int[] nextFuncId) {
				this.nextFuncId = nextFuncId;
				return this;
			}

			Builder dynamic(boolean dynamic) {
				this.dynamic = dynamic;
				return this;
			}

			Builder usesFloatArray(boolean usesFloatArray) {
				this.usesFloatArray = usesFloatArray;
				return this;
			}

			Builder usesArrays(boolean usesArrays) {
				this.usesArrays = usesArrays;
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

			Builder globalFields(Map<String, FieldrefConstant> globalFields) {
				this.globalFields = globalFields;
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
						"Cannot compile a catching form while an object is under construction");
			}
			int[] slots = new int[live.size()];
			for (int i = live.size() - 1; i >= 0; i--) {
				OperandStack.Slot slot = live.get(i);
				slots[i] = this.allocTemp();
				if (slot.wide()) {
					this.allocTemp();
				}
				if (this.nextLocal - 1 > MAX_LOCAL_SLOT) {
					// A spilled value must survive the protected region, so it cannot
					// ride
					// a slot whose number the one-byte operand of the load/store opcodes
					// (there is no `wide` form here) silently wraps into another slot's.
					throw new UnsupportedOperationException(
							"Cannot compile a catching form here: the function is out of local variable slots");
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

		int allocTemp() {
			int slot = this.nextLocal++;
			if (this.nextLocal > this.maxLocals) {
				this.maxLocals = this.nextLocal;
			}
			return slot;
		}

	}

}
