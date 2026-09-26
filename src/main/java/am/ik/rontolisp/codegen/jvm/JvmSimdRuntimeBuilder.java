package am.ik.rontolisp.codegen.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ByteCodeWriter;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
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
	record SimdRuntime(Utf8Constant initName, Utf8Constant initDesc, List<Integer> initCode, int maxStack,
			int maxLocals, List<ByteCodeWriter.ExceptionTableEntry> initExceptionTable, Utf8Constant initedFieldName,
			Utf8Constant initedFieldDesc, Utf8Constant availableFieldName, Utf8Constant availableFieldDesc,
			Utf8Constant readyName, Utf8Constant readyDesc, List<Integer> readyCode, Map<String, MethodrefConstant> ops,
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
	static SimdRuntime build(ConstantPool cp, ClassConstant thisClass, boolean parallel, String programInternalName) {
		String bridgeName = bridgeName(programInternalName);
		byte[] bridgeBytes = JvmJavaRuntimeBuilder.renameClass(loadTemplateBytes(), TEMPLATE_INTERNAL_NAME, bridgeName);

		Utf8Constant initedFieldName = cp.addUtf8("_simdInited");
		Utf8Constant initedFieldDesc = cp.addUtf8("I");
		FieldrefConstant initedField = cp.addFieldref(thisClass, cp.addNameAndType(initedFieldName, initedFieldDesc));
		Utf8Constant availableFieldName = cp.addUtf8(AVAILABLE_FIELD);
		Utf8Constant availableFieldDesc = cp.addUtf8("I");
		FieldrefConstant availableField = cp.addFieldref(thisClass,
				cp.addNameAndType(availableFieldName, availableFieldDesc));

		ClassConstant methodHandlesClass = cp.addClass(cp.addUtf8("java/lang/invoke/MethodHandles"));
		MethodrefConstant lookup = cp.addMethodref(methodHandlesClass,
				cp.addNameAndType(cp.addUtf8("lookup"), cp.addUtf8("()Ljava/lang/invoke/MethodHandles$Lookup;")));
		ClassConstant lookupClass = cp.addClass(cp.addUtf8("java/lang/invoke/MethodHandles$Lookup"));
		MethodrefConstant ensureInitialized = cp.addMethodref(lookupClass,
				cp.addNameAndType(cp.addUtf8("ensureInitialized"), cp.addUtf8("(Ljava/lang/Class;)Ljava/lang/Class;")));
		ClassConstant linkageErrorClass = cp.addClass(cp.addUtf8("java/lang/LinkageError"));
		ClassConstant systemClass = cp.addClass(cp.addUtf8("java/lang/System"));
		FieldrefConstant systemErr = cp.addFieldref(systemClass,
				cp.addNameAndType(cp.addUtf8("err"), cp.addUtf8("Ljava/io/PrintStream;")));
		ClassConstant printStreamClass = cp.addClass(cp.addUtf8("java/io/PrintStream"));
		MethodrefConstant println = cp.addMethodref(printStreamClass,
				cp.addNameAndType(cp.addUtf8("println"), cp.addUtf8("(Ljava/lang/String;)V")));
		ConstantPool.StringConstant warning = cp.addString(UNAVAILABLE_WARNING);

		ClassConstant bridgeClass = cp.addClass(cp.addUtf8(bridgeName));
		String binaryDesc = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
		String unaryDesc = "(Ljava/lang/Object;)Ljava/lang/Object;";
		// The destination-passing kernels take the destination as a leading argument.
		String ternaryDesc = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		Utf8Constant initName = cp.addUtf8(INIT_METHOD);
		Utf8Constant initDesc = cp.addUtf8("()V");
		ops.put("init", cp.addMethodref(thisClass, cp.addNameAndType(initName, initDesc)));
		Utf8Constant readyName = cp.addUtf8(READY_METHOD);
		Utf8Constant readyDesc = cp.addUtf8("()Z");
		ops.put(AVAILABLE, cp.addMethodref(thisClass, cp.addNameAndType(readyName, readyDesc)));
		ops.put(LispNames.VEC_ADD,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdAdd"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_SUB,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdSub"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_MUL,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdMul"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_DIV,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdDiv"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_SCALE,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdScale"), cp.addUtf8(binaryDesc))));
		// The CL operator spellings share the very bridge methods their named siblings
		// use, so (vec:+ a b) is compiled to the same call as (vec:add a b) -- the alias
		// defun never runs on an accelerated build.
		ops.put(LispNames.VEC_PLUS,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdAdd"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_MINUS,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdSub"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_STAR,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdMul"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_SLASH,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdDiv"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_DOT,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdDot"), cp.addUtf8(binaryDesc))));
		// The GEMV, serial or row-parallel: the --parallel build binds the same call
		// site to the entry that splits the rows (the kernel, and so the bits, are one).
		ops.put(LispNames.VEC_MATVEC, cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8(parallel ? "simdMatvecParallel" : "simdMatvec"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_SUM,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdSum"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_ADD_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdAddInto"), cp.addUtf8(ternaryDesc))));
		ops.put(LispNames.VEC_SUB_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdSubInto"), cp.addUtf8(ternaryDesc))));
		ops.put(LispNames.VEC_MUL_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdMulInto"), cp.addUtf8(ternaryDesc))));
		ops.put(LispNames.VEC_DIV_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdDivInto"), cp.addUtf8(ternaryDesc))));
		ops.put(LispNames.VEC_SCALE_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdScaleInto"), cp.addUtf8(ternaryDesc))));
		ops.put(LispNames.VEC_MATVEC_INTO, cp.addMethodref(bridgeClass, cp.addNameAndType(
				cp.addUtf8(parallel ? "simdMatvecIntoParallel" : "simdMatvecInto"), cp.addUtf8(ternaryDesc))));
		// The element-wise unary ufuncs: one operand (unary), or a
		// destination plus one operand (binary) for the -into siblings.
		ops.put(LispNames.VEC_EXP,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdExp"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_LOG,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdLog"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_TANH,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdTanh"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_SIN,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdSin"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_COS,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdCos"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_TAN,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdTan"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_ASIN,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdAsin"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_ACOS,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdAcos"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_ATAN,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdAtan"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_SINH,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdSinh"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_COSH,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdCosh"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_SQRT,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdSqrt"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_ABS,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdAbs"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_NEGATIVE,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdNegative"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_SIGN,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdSign"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_RECIPROCAL,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdReciprocal"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_EXP_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdExpInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_LOG_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdLogInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_TANH_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdTanhInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_SIN_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdSinInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_COS_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdCosInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_TAN_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdTanInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_ASIN_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdAsinInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_ACOS_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdAcosInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_ATAN_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdAtanInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_SINH_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdSinhInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_COSH_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdCoshInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_SQRT_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdSqrtInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_ABS_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdAbsInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_NEGATIVE_INTO, cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("simdNegativeInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_SIGN_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdSignInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_RECIPROCAL_INTO, cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("simdReciprocalInto"), cp.addUtf8(binaryDesc))));
		// The comparison-select ufuncs. vec:clip carries two scalar
		// bounds, so its -into sibling is the one four-argument bridge entry.
		String quaternaryDesc = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
		ops.put(LispNames.VEC_MAXIMUM,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdMaximum"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_MINIMUM,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdMinimum"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_RELU,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdRelu"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_CLIP,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdClip"), cp.addUtf8(ternaryDesc))));
		ops.put(LispNames.VEC_MAXIMUM_INTO, cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("simdMaximumInto"), cp.addUtf8(ternaryDesc))));
		ops.put(LispNames.VEC_MINIMUM_INTO, cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("simdMinimumInto"), cp.addUtf8(ternaryDesc))));
		ops.put(LispNames.VEC_RELU_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdReluInto"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_CLIP_INTO, cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("simdClipInto"), cp.addUtf8(quaternaryDesc))));

		// The linalg: kernels share the one bridge class (and so the one _simdInit and
		// the
		// one resource-config entry). Their ops keys carry the package prefix because
		// vec:add and linalg:add have the same member name.
		for (String member : JvmLinalgKernelCompiler.members()) {
			String desc = "(" + "Ljava/lang/Object;".repeat(JvmLinalgKernelCompiler.arity(member))
					+ ")Ljava/lang/Object;";
			ops.put(JvmLinalgKernelCompiler.qualifiedName(member), cp.addMethodref(bridgeClass, cp
				.addNameAndType(cp.addUtf8(JvmLinalgKernelCompiler.bridgeMethod(member, parallel)), cp.addUtf8(desc))));
			// The option-form (:axis / axes) kernels ride the same bridge under a
			// distinct
			// ops key, one extra methodref per extended member.
			JvmLinalgKernelCompiler.Extended ext = JvmLinalgKernelCompiler.extended(member);
			if (ext != null) {
				String extDesc = "(" + "Ljava/lang/Object;".repeat(ext.params()) + ")Ljava/lang/Object;";
				ops.put(JvmLinalgKernelCompiler.extendedKey(member), cp.addMethodref(bridgeClass,
						cp.addNameAndType(cp.addUtf8(ext.bridgeMethod()), cp.addUtf8(extDesc))));
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
		List<Integer> code = new ArrayList<>();
		code.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		int guardPos = code.size();
		code.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(code, 0);
		// MethodHandles.lookup().ensureInitialized(<Program>$SimdBridge.class) -- the
		// protected region: a runtime missing jdk.incubator.vector fails to LINK the
		// bridge here. The class constant alone only loads the file (a class verifies
		// at its first use), so without the forced initialization the failure would
		// surface as a raw NoClassDefFoundError at the first kernel call instead.
		int tryStart = code.size();
		code.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(code, lookup.index()); // [lookup]
		JvmRuntimeBuilder.emitLdc(code, bridgeClass.index()); // [lookup, class]
		code.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(code, ensureInitialized.index()); // [class]
		code.add(Opcode.POP);
		// _simdAvailable = 1
		code.add(Opcode.ICONST_1);
		code.add(Opcode.PUTSTATIC);
		JvmRuntimeBuilder.emitU2(code, availableField.index());
		int skipHandlerPos = code.size();
		code.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(code, 0);
		// catch (LinkageError e) -- the operand stack holds just the caught
		// throwable; discard it and print the interpreter's warning once.
		int handlerPc = code.size();
		code.add(Opcode.POP);
		code.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(code, systemErr.index()); // [err]
		JvmRuntimeBuilder.emitLdc(code, warning.index()); // [err, msg]
		code.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(code, println.index());
		JvmRuntimeBuilder.patchBranch(code, skipHandlerPos, code.size());
		// _simdInited = 1 (tried, either way -- never re-attempt, never warn twice)
		code.add(Opcode.ICONST_1);
		code.add(Opcode.PUTSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		JvmRuntimeBuilder.patchBranch(code, guardPos, code.size());
		code.add(Opcode.RETURN);

		List<ByteCodeWriter.ExceptionTableEntry> initExceptionTable = List
			.of(new ByteCodeWriter.ExceptionTableEntry(tryStart, handlerPc, handlerPc, linkageErrorClass.index()));

		// --- _simdReady body: return _simdAvailable != 0; ---
		List<Integer> readyCode = new ArrayList<>();
		readyCode.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(readyCode, availableField.index());
		readyCode.add(Opcode.IRETURN);

		return new SimdRuntime(initName, initDesc, code, 2, 0, initExceptionTable, initedFieldName, initedFieldDesc,
				availableFieldName, availableFieldDesc, readyName, readyDesc, readyCode, ops,
				Map.of(bridgeName + ".class", bridgeBytes));
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
