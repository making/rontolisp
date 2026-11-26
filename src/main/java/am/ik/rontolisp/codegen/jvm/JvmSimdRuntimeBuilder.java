package am.ik.rontolisp.codegen.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.LinkedHashMap;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispNames;

/**
 * Builds the {@code vec:} acceleration runtime for the generated {@code .class} when the
 * {@code --simd} flag is on. Like {@link JvmJavaRuntimeBuilder} it does not hand-assemble
 * the kernel logic: the Vector API kernels live in {@link JvmSimdVectorTemplate} (plain
 * Java, compiled by the project build), whose bytecode is read from the classpath at
 * compile time, renamed after the generated program ({@code <Program>$SimdBridge}, in its
 * package) and shipped BESIDE it as an ordinary class file
 * ({@link SimdRuntime#classFiles()}, joined into
 * {@link JvmLispCompiler#runtimeClassFiles()}) -- never defined at run time, which a
 * GraalVM native image refuses ({@code .kb/template-class-embedding.md}). The emitted
 * {@code private static void _simdInit()} (guarded by the {@code _simdInited} int field)
 * only initializes it; unlike the {@code java:} bridge there is no {@code bind} callback
 * -- the kernels are self-contained. Every accelerated {@code vec:} call site is preceded
 * by a {@code _simdInit} call.
 *
 * <p>
 * {@code jdk.incubator.vector} is an OPTIONAL JDK module: on a runtime started without
 * {@code --add-modules jdk.incubator.vector}, linking the bridge fails with a
 * {@link LinkageError} ({@code NoClassDefFoundError} in practice) -- the verifier
 * resolves the template's incubator types, and its static initializer reads a species.
 * Loading a class file does not link it (a class constant resolves lazily and verifies at
 * its first use), so {@code _simdInit} forces the whole of it, loading, linking and
 * initializing, with {@code MethodHandles.lookup().ensureInitialized(<bridge>.class)}
 * inside the protected region: without that the failure would move to the first kernel
 * call. It catches the error, leaves {@value #AVAILABLE_FIELD} false and prints the same
 * one-line warning the interpreter prints ({@code RontoLispCli.enableSimd}).
 * {@code _simdReady()} exposes that flag as one more {@code ops} entry
 * ({@value #AVAILABLE}) so every accelerated call site -- {@link JvmSimdCompiler} and
 * {@link JvmLinalgKernelCompiler}'s {@code --simd} rung -- can check it BEFORE resolving
 * a method reference into the (possibly unusable) bridge class, and fall back to the
 * scalar defun instead, exactly the interpreter's degrade (unlike
 * {@code --blas}/{@code --gpu}, whose bridges always link: their "is it there" probe runs
 * a method call inside the bridge).
 */
final class JvmSimdRuntimeBuilder {

	/** The template's internal (constant-pool) class name before renaming. */
	private static final String TEMPLATE_INTERNAL_NAME = "am/ik/rontolisp/codegen/jvm/JvmSimdVectorTemplate";

	/** The emitted init helper method name. */
	static final String INIT_METHOD = "_simdInit";

	/** The emitted availability accessor method name. */
	private static final String READY_METHOD = "_simdReady";

	/** The guard field backing {@link #READY_METHOD}. */
	private static final String AVAILABLE_FIELD = "_simdAvailable";

	/** The {@code ops} key of the availability accessor ({@link #READY_METHOD}). */
	static final String AVAILABLE = "available";

	/** Printed once, to {@code System.err}, when the bridge fails to link. */
	private static final String UNAVAILABLE_WARNING = "rontolisp: warning: --simd: jdk.incubator.vector is unavailable, "
			+ "running the scalar vec:/linalg: kernels; re-run with "
			+ "`java --add-modules jdk.incubator.vector ...`, or use the native binary.";

	private JvmSimdRuntimeBuilder() {
	}

	/**
	 * The internal name of a program's bridge class: named after the program, in its
	 * package, so programs built by different rontolisp versions never share one file.
	 * @param programInternalName the generated class's internal (slash-separated) name
	 * @return the bridge's internal name
	 */
	static String bridgeName(String programInternalName) {
		return programInternalName + "$SimdBridge";
	}

	/**
	 * The ready-to-emit {@code _simdInit} and {@code _simdReady} methods, their guard
	 * fields, and the constant-pool references the accelerated {@code vec:} /
	 * {@code linalg:} call-site compilers need ({@code ops} keys: {@code init},
	 * {@value #AVAILABLE}, plus one per kernel member name --
	 * {@code add}/{@code sub}/{@code mul}/ {@code scale}/{@code dot}/{@code sum}/
	 * {@code matvec}), and the bridge class file that travels beside the program, keyed
	 * by its path within an output tree.
	 */
	record SimdRuntime(Utf8Entry initName, Utf8Entry initDesc, MethodCode initCode, Utf8Entry initedFieldName,
			Utf8Entry initedFieldDesc, Utf8Entry availableFieldName, Utf8Entry availableFieldDesc, Utf8Entry readyName,
			Utf8Entry readyDesc, MethodCode readyCode, Map<String, MethodRefEntry> ops,
			Map<String, byte[]> classFiles) {
	}

	/**
	 * Builds the {@code _simdInit} method body, registers the bridge references and
	 * renames the bridge class file.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param parallel {@code --parallel}: bind the GEMV / GEMM members
	 * ({@code vec:matvec}, {@code vec:matvec-into}, {@code linalg:dot},
	 * {@code linalg::%la-matmul-nd}) to the bridge entries that split their rows across
	 * threads; every other member and every other byte of the runtime is the same
	 * @param programInternalName the generated class's internal name -- the bridge is
	 * named after it and lives in its package (the bridge methods are package-private)
	 * @return the runtime pieces
	 */
	static SimdRuntime build(ConstantPool cp, ClassEntry thisClass, boolean parallel, String programInternalName) {
		String bridgeName = bridgeName(programInternalName);
		byte[] bridgeBytes = JvmJavaRuntimeBuilder.renameClass(loadTemplateBytes(), TEMPLATE_INTERNAL_NAME, bridgeName);

		Utf8Entry initedFieldName = cp.utf8Entry("_simdInited");
		Utf8Entry initedFieldDesc = cp.utf8Entry("I");
		FieldRefEntry initedField = cp.fieldRef(thisClass, initedFieldName, initedFieldDesc);
		Utf8Entry availableFieldName = cp.utf8Entry(AVAILABLE_FIELD);
		Utf8Entry availableFieldDesc = cp.utf8Entry("I");
		FieldRefEntry availableField = cp.fieldRef(thisClass, availableFieldName, availableFieldDesc);

		ClassEntry methodHandlesClass = cp.classEntry("java/lang/invoke/MethodHandles");
		MethodRefEntry lookup = cp.methodRef(methodHandlesClass, "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;");
		ClassEntry lookupClass = cp.classEntry("java/lang/invoke/MethodHandles$Lookup");
		MethodRefEntry ensureInitialized = cp.methodRef(lookupClass, "ensureInitialized",
				"(Ljava/lang/Class;)Ljava/lang/Class;");
		ClassEntry linkageErrorClass = cp.classEntry("java/lang/LinkageError");
		ClassEntry systemClass = cp.classEntry("java/lang/System");
		FieldRefEntry systemErr = cp.fieldRef(systemClass, "err", "Ljava/io/PrintStream;");
		ClassEntry printStreamClass = cp.classEntry("java/io/PrintStream");
		MethodRefEntry println = cp.methodRef(printStreamClass, "println", "(Ljava/lang/String;)V");
		StringEntry warning = cp.stringEntry(UNAVAILABLE_WARNING);

		ClassEntry bridgeClass = cp.classEntry(bridgeName);
		String binaryDesc = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
		String unaryDesc = "(Ljava/lang/Object;)Ljava/lang/Object;";
		// The destination-passing kernels take the destination as a leading argument.
		String ternaryDesc = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
		Map<String, MethodRefEntry> ops = new LinkedHashMap<>();
		Utf8Entry initName = cp.utf8Entry(INIT_METHOD);
		Utf8Entry initDesc = cp.utf8Entry("()V");
		ops.put("init", cp.methodRef(thisClass, initName, initDesc));
		Utf8Entry readyName = cp.utf8Entry(READY_METHOD);
		Utf8Entry readyDesc = cp.utf8Entry("()Z");
		ops.put(AVAILABLE, cp.methodRef(thisClass, readyName, readyDesc));
		ops.put(LispNames.VEC_ADD, cp.methodRef(bridgeClass, "simdAdd", binaryDesc));
		ops.put(LispNames.VEC_SUB, cp.methodRef(bridgeClass, "simdSub", binaryDesc));
		ops.put(LispNames.VEC_MUL, cp.methodRef(bridgeClass, "simdMul", binaryDesc));
		ops.put(LispNames.VEC_DIV, cp.methodRef(bridgeClass, "simdDiv", binaryDesc));
		ops.put(LispNames.VEC_SCALE, cp.methodRef(bridgeClass, "simdScale", binaryDesc));
		// The CL operator spellings share the very bridge methods their named siblings
		// use, so (vec:+ a b) is compiled to the same call as (vec:add a b) -- the alias
		// defun never runs on an accelerated build.
		ops.put(LispNames.VEC_PLUS, cp.methodRef(bridgeClass, "simdAdd", binaryDesc));
		ops.put(LispNames.VEC_MINUS, cp.methodRef(bridgeClass, "simdSub", binaryDesc));
		ops.put(LispNames.VEC_STAR, cp.methodRef(bridgeClass, "simdMul", binaryDesc));
		ops.put(LispNames.VEC_SLASH, cp.methodRef(bridgeClass, "simdDiv", binaryDesc));
		ops.put(LispNames.VEC_DOT, cp.methodRef(bridgeClass, "simdDot", binaryDesc));
		// The GEMV, serial or row-parallel: the --parallel build binds the same call
		// site to the entry that splits the rows (the kernel, and so the bits, are one).
		ops.put(LispNames.VEC_MATVEC,
				cp.methodRef(bridgeClass, parallel ? "simdMatvecParallel" : "simdMatvec", binaryDesc));
		ops.put(LispNames.VEC_SUM, cp.methodRef(bridgeClass, "simdSum", unaryDesc));
		ops.put(LispNames.VEC_ADD_INTO, cp.methodRef(bridgeClass, "simdAddInto", ternaryDesc));
		ops.put(LispNames.VEC_SUB_INTO, cp.methodRef(bridgeClass, "simdSubInto", ternaryDesc));
		ops.put(LispNames.VEC_MUL_INTO, cp.methodRef(bridgeClass, "simdMulInto", ternaryDesc));
		ops.put(LispNames.VEC_DIV_INTO, cp.methodRef(bridgeClass, "simdDivInto", ternaryDesc));
		ops.put(LispNames.VEC_SCALE_INTO, cp.methodRef(bridgeClass, "simdScaleInto", ternaryDesc));
		ops.put(LispNames.VEC_MATVEC_INTO,
				cp.methodRef(bridgeClass, parallel ? "simdMatvecIntoParallel" : "simdMatvecInto", ternaryDesc));
		// The element-wise unary ufuncs: one operand (unary), or a
		// destination plus one operand (binary) for the -into siblings.
		ops.put(LispNames.VEC_EXP, cp.methodRef(bridgeClass, "simdExp", unaryDesc));
		ops.put(LispNames.VEC_LOG, cp.methodRef(bridgeClass, "simdLog", unaryDesc));
		ops.put(LispNames.VEC_TANH, cp.methodRef(bridgeClass, "simdTanh", unaryDesc));
		ops.put(LispNames.VEC_SIN, cp.methodRef(bridgeClass, "simdSin", unaryDesc));
		ops.put(LispNames.VEC_COS, cp.methodRef(bridgeClass, "simdCos", unaryDesc));
		ops.put(LispNames.VEC_TAN, cp.methodRef(bridgeClass, "simdTan", unaryDesc));
		ops.put(LispNames.VEC_ASIN, cp.methodRef(bridgeClass, "simdAsin", unaryDesc));
		ops.put(LispNames.VEC_ACOS, cp.methodRef(bridgeClass, "simdAcos", unaryDesc));
		ops.put(LispNames.VEC_ATAN, cp.methodRef(bridgeClass, "simdAtan", unaryDesc));
		ops.put(LispNames.VEC_SINH, cp.methodRef(bridgeClass, "simdSinh", unaryDesc));
		ops.put(LispNames.VEC_COSH, cp.methodRef(bridgeClass, "simdCosh", unaryDesc));
		ops.put(LispNames.VEC_SQRT, cp.methodRef(bridgeClass, "simdSqrt", unaryDesc));
		ops.put(LispNames.VEC_ABS, cp.methodRef(bridgeClass, "simdAbs", unaryDesc));
		ops.put(LispNames.VEC_NEGATIVE, cp.methodRef(bridgeClass, "simdNegative", unaryDesc));
		ops.put(LispNames.VEC_SIGN, cp.methodRef(bridgeClass, "simdSign", unaryDesc));
		ops.put(LispNames.VEC_RECIPROCAL, cp.methodRef(bridgeClass, "simdReciprocal", unaryDesc));
		ops.put(LispNames.VEC_EXP_INTO, cp.methodRef(bridgeClass, "simdExpInto", binaryDesc));
		ops.put(LispNames.VEC_LOG_INTO, cp.methodRef(bridgeClass, "simdLogInto", binaryDesc));
		ops.put(LispNames.VEC_TANH_INTO, cp.methodRef(bridgeClass, "simdTanhInto", binaryDesc));
		ops.put(LispNames.VEC_SIN_INTO, cp.methodRef(bridgeClass, "simdSinInto", binaryDesc));
		ops.put(LispNames.VEC_COS_INTO, cp.methodRef(bridgeClass, "simdCosInto", binaryDesc));
		ops.put(LispNames.VEC_TAN_INTO, cp.methodRef(bridgeClass, "simdTanInto", binaryDesc));
		ops.put(LispNames.VEC_ASIN_INTO, cp.methodRef(bridgeClass, "simdAsinInto", binaryDesc));
		ops.put(LispNames.VEC_ACOS_INTO, cp.methodRef(bridgeClass, "simdAcosInto", binaryDesc));
		ops.put(LispNames.VEC_ATAN_INTO, cp.methodRef(bridgeClass, "simdAtanInto", binaryDesc));
		ops.put(LispNames.VEC_SINH_INTO, cp.methodRef(bridgeClass, "simdSinhInto", binaryDesc));
		ops.put(LispNames.VEC_COSH_INTO, cp.methodRef(bridgeClass, "simdCoshInto", binaryDesc));
		ops.put(LispNames.VEC_SQRT_INTO, cp.methodRef(bridgeClass, "simdSqrtInto", binaryDesc));
		ops.put(LispNames.VEC_ABS_INTO, cp.methodRef(bridgeClass, "simdAbsInto", binaryDesc));
		ops.put(LispNames.VEC_NEGATIVE_INTO, cp.methodRef(bridgeClass, "simdNegativeInto", binaryDesc));
		ops.put(LispNames.VEC_SIGN_INTO, cp.methodRef(bridgeClass, "simdSignInto", binaryDesc));
		ops.put(LispNames.VEC_RECIPROCAL_INTO, cp.methodRef(bridgeClass, "simdReciprocalInto", binaryDesc));
		// The comparison-select ufuncs. vec:clip carries two scalar
		// bounds, so its -into sibling is the one four-argument bridge entry.
		String quaternaryDesc = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
		ops.put(LispNames.VEC_MAXIMUM, cp.methodRef(bridgeClass, "simdMaximum", binaryDesc));
		ops.put(LispNames.VEC_MINIMUM, cp.methodRef(bridgeClass, "simdMinimum", binaryDesc));
		ops.put(LispNames.VEC_RELU, cp.methodRef(bridgeClass, "simdRelu", unaryDesc));
		ops.put(LispNames.VEC_CLIP, cp.methodRef(bridgeClass, "simdClip", ternaryDesc));
		ops.put(LispNames.VEC_MAXIMUM_INTO, cp.methodRef(bridgeClass, "simdMaximumInto", ternaryDesc));
		ops.put(LispNames.VEC_MINIMUM_INTO, cp.methodRef(bridgeClass, "simdMinimumInto", ternaryDesc));
		ops.put(LispNames.VEC_RELU_INTO, cp.methodRef(bridgeClass, "simdReluInto", binaryDesc));
		ops.put(LispNames.VEC_CLIP_INTO, cp.methodRef(bridgeClass, "simdClipInto", quaternaryDesc));

		// The linalg: kernels share the one bridge class (and so the one _simdInit and
		// the
		// one resource-config entry). Their ops keys carry the package prefix because
		// vec:add and linalg:add have the same member name.
		for (String member : JvmLinalgKernelCompiler.members()) {
			String desc = "(" + "Ljava/lang/Object;".repeat(JvmLinalgKernelCompiler.arity(member))
					+ ")Ljava/lang/Object;";
			ops.put(JvmLinalgKernelCompiler.qualifiedName(member),
					cp.methodRef(bridgeClass, JvmLinalgKernelCompiler.bridgeMethod(member, parallel), desc));
			// The option-form (:axis / axes) kernels ride the same bridge under a
			// distinct
			// ops key, one extra methodref per extended member.
			JvmLinalgKernelCompiler.Extended ext = JvmLinalgKernelCompiler.extended(member);
			if (ext != null) {
				String extDesc = "(" + "Ljava/lang/Object;".repeat(ext.params()) + ")Ljava/lang/Object;";
				ops.put(JvmLinalgKernelCompiler.extendedKey(member),
						cp.methodRef(bridgeClass, ext.bridgeMethod(), extDesc));
			}
		}

		// --- _simdInit body (self-contained: no bind callback) ---
		// if (_simdInited != 0) return;
		// try {
		// MethodHandles.lookup().ensureInitialized(<Program>$SimdBridge.class);
		// _simdAvailable = 1;
		// } catch (LinkageError e) {
		// // jdk.incubator.vector missing: leave _simdAvailable false, warn once.
		// System.err.println(UNAVAILABLE_WARNING);
		// }
		// _simdInited = 1;
		MethodCode code = new MethodCode();
		code.getstatic(initedField);
		MethodCode.Label guard = code.newLabel();
		code.ifne(guard);
		// MethodHandles.lookup().ensureInitialized(<Program>$SimdBridge.class) -- the
		// protected region: a runtime missing jdk.incubator.vector fails to LINK the
		// bridge here. The class constant alone only loads the file (a class verifies
		// at its first use), so without the forced initialization the failure would
		// surface as a raw NoClassDefFoundError at the first kernel call instead.
		MethodCode.Label tryStart = code.newBoundLabel();
		code.invokestatic(lookup); // [lookup]
		code.ldc(bridgeClass); // [lookup, class]
		code.invokevirtual(ensureInitialized); // [class]
		code.pop();
		// _simdAvailable = 1
		code.iconst_1();
		code.putstatic(availableField);
		MethodCode.Label skipHandler = code.newLabel();
		code.goto_(skipHandler);
		// catch (LinkageError e) -- the operand stack holds just the caught
		// throwable; discard it and print the interpreter's warning once.
		MethodCode.Label handler = code.newBoundLabel();
		code.pop();
		code.getstatic(systemErr); // [err]
		code.ldc(warning); // [err, msg]
		code.invokevirtual(println);
		code.labelBinding(skipHandler);
		// _simdInited = 1 (tried, either way -- never re-attempt, never warn twice)
		code.iconst_1();
		code.putstatic(initedField);
		code.labelBinding(guard);
		code.return_();

		code.exceptionCatch(tryStart, handler, handler, linkageErrorClass);

		// --- _simdReady body: return _simdAvailable != 0; ---
		MethodCode readyCode = new MethodCode();
		readyCode.getstatic(availableField);
		readyCode.ireturn();

		return new SimdRuntime(initName, initDesc, code, initedFieldName, initedFieldDesc, availableFieldName,
				availableFieldDesc, readyName, readyDesc, readyCode, ops, Map.of(bridgeName + ".class", bridgeBytes));
	}

	/** Reads the compiled {@link JvmSimdVectorTemplate} bytecode from the classpath. */
	private static byte[] loadTemplateBytes() {
		try (InputStream in = JvmSimdRuntimeBuilder.class.getResourceAsStream("JvmSimdVectorTemplate.class")) {
			if (in == null) {
				throw new IllegalStateException("JvmSimdVectorTemplate.class not found on the classpath");
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
