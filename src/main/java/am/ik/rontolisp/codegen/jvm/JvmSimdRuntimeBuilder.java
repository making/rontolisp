package am.ik.rontolisp.codegen.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispNames;

/**
 * Builds the {@code vec:} acceleration runtime for the generated standalone
 * {@code .class} when the {@code --simd} flag is on. Like {@link JvmJavaRuntimeBuilder}
 * it does not hand-assemble the kernel logic: the Vector API kernels live in
 * {@link JvmSimdVectorTemplate} (plain Java, compiled by the project build), whose
 * bytecode is read from the classpath at compile time, renamed into the default package
 * (a {@code Lookup.defineClass(byte[])} requirement) as {@value #BRIDGE_NAME},
 * base64-encoded, and embedded as string constants. The emitted
 * {@code private static void _simdInit()} decodes and defines the class on first use
 * (guarded by the {@code _simdInited} int field); unlike the {@code java:} bridge there
 * is no {@code bind} callback -- the kernels are self-contained. Every accelerated
 * {@code vec:} call site is preceded by a {@code _simdInit} call so the bridge method
 * references resolve lazily (at their first execution), by which time the class is
 * defined in the program's own class loader.
 */
final class JvmSimdRuntimeBuilder {

	/** The default-package name the embedded bridge class is defined under at runtime. */
	static final String BRIDGE_NAME = "RontoLispSimdBridge";

	/** The template's internal (constant-pool) class name before renaming. */
	private static final String TEMPLATE_INTERNAL_NAME = "am/ik/rontolisp/codegen/jvm/JvmSimdVectorTemplate";

	/** The emitted init helper method name. */
	static final String INIT_METHOD = "_simdInit";

	/** Keeps each base64 string constant well under the 65535-byte Utf8 limit. */
	private static final int CHUNK_SIZE = 40000;

	private JvmSimdRuntimeBuilder() {
	}

	/**
	 * The ready-to-emit {@code _simdInit} method, its guard field, and the constant-pool
	 * references the accelerated {@code vec:} call-site compiler needs ({@code ops} keys:
	 * {@code init}, plus one per kernel member name --
	 * {@code add}/{@code sub}/{@code mul}/ {@code scale}/{@code dot}/{@code sum}/
	 * {@code matvec}).
	 */
	record SimdRuntime(Utf8Constant initName, Utf8Constant initDesc, List<Integer> initCode, int maxStack,
			int maxLocals, Utf8Constant initedFieldName, Utf8Constant initedFieldDesc,
			Map<String, MethodrefConstant> ops) {
	}

	/**
	 * Builds the {@code _simdInit} method body and registers the bridge references.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param stringConcat {@code String.concat(String)}
	 * @return the runtime pieces
	 */
	static SimdRuntime build(ConstantPool cp, ClassConstant thisClass, MethodrefConstant stringConcat) {
		byte[] bridgeBytes = JvmJavaRuntimeBuilder.renameClass(loadTemplateBytes(), TEMPLATE_INTERNAL_NAME,
				BRIDGE_NAME);
		String base64 = Base64.getEncoder().encodeToString(bridgeBytes);
		List<ConstantPool.StringConstant> chunks = new ArrayList<>();
		for (int i = 0; i < base64.length(); i += CHUNK_SIZE) {
			chunks.add(cp.addString(base64.substring(i, Math.min(base64.length(), i + CHUNK_SIZE))));
		}

		Utf8Constant initedFieldName = cp.addUtf8("_simdInited");
		Utf8Constant initedFieldDesc = cp.addUtf8("I");
		FieldrefConstant initedField = cp.addFieldref(thisClass, cp.addNameAndType(initedFieldName, initedFieldDesc));

		ClassConstant base64Class = cp.addClass(cp.addUtf8("java/util/Base64"));
		MethodrefConstant getDecoder = cp.addMethodref(base64Class,
				cp.addNameAndType(cp.addUtf8("getDecoder"), cp.addUtf8("()Ljava/util/Base64$Decoder;")));
		ClassConstant decoderClass = cp.addClass(cp.addUtf8("java/util/Base64$Decoder"));
		MethodrefConstant decode = cp.addMethodref(decoderClass,
				cp.addNameAndType(cp.addUtf8("decode"), cp.addUtf8("(Ljava/lang/String;)[B")));
		ClassConstant methodHandlesClass = cp.addClass(cp.addUtf8("java/lang/invoke/MethodHandles"));
		MethodrefConstant lookup = cp.addMethodref(methodHandlesClass,
				cp.addNameAndType(cp.addUtf8("lookup"), cp.addUtf8("()Ljava/lang/invoke/MethodHandles$Lookup;")));
		ClassConstant lookupClass = cp.addClass(cp.addUtf8("java/lang/invoke/MethodHandles$Lookup"));
		MethodrefConstant defineClass = cp.addMethodref(lookupClass,
				cp.addNameAndType(cp.addUtf8("defineClass"), cp.addUtf8("([B)Ljava/lang/Class;")));

		ClassConstant bridgeClass = cp.addClass(cp.addUtf8(BRIDGE_NAME));
		String binaryDesc = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
		String unaryDesc = "(Ljava/lang/Object;)Ljava/lang/Object;";
		// The destination-passing kernels take the destination as a leading argument.
		String ternaryDesc = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		Utf8Constant initName = cp.addUtf8(INIT_METHOD);
		Utf8Constant initDesc = cp.addUtf8("()V");
		ops.put("init", cp.addMethodref(thisClass, cp.addNameAndType(initName, initDesc)));
		ops.put(LispNames.VEC_ADD,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdAdd"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_SUB,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdSub"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_MUL,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdMul"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_SCALE,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdScale"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_DOT,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdDot"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_MATVEC,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdMatvec"), cp.addUtf8(binaryDesc))));
		ops.put(LispNames.VEC_SUM,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdSum"), cp.addUtf8(unaryDesc))));
		ops.put(LispNames.VEC_ADD_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdAddInto"), cp.addUtf8(ternaryDesc))));
		ops.put(LispNames.VEC_SUB_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdSubInto"), cp.addUtf8(ternaryDesc))));
		ops.put(LispNames.VEC_MUL_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdMulInto"), cp.addUtf8(ternaryDesc))));
		ops.put(LispNames.VEC_SCALE_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdScaleInto"), cp.addUtf8(ternaryDesc))));
		ops.put(LispNames.VEC_MATVEC_INTO,
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("simdMatvecInto"), cp.addUtf8(ternaryDesc))));
		// The element-wise unary ufuncs (todo 109): one operand (unary), or a
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
		// The comparison-select ufuncs (todo 109 Phase 3). vec:clip carries two scalar
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
		for (String member : JvmLinalgSimdCompiler.members()) {
			String desc = "(" + "Ljava/lang/Object;".repeat(JvmLinalgSimdCompiler.arity(member))
					+ ")Ljava/lang/Object;";
			ops.put(JvmLinalgSimdCompiler.qualifiedName(member), cp.addMethodref(bridgeClass,
					cp.addNameAndType(cp.addUtf8(JvmLinalgSimdCompiler.bridgeMethod(member)), cp.addUtf8(desc))));
		}

		// --- _simdInit body (self-contained: no bind callback) ---
		List<Integer> code = new ArrayList<>();
		// if (_simdInited != 0) return;
		code.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		int guardPos = code.size();
		code.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(code, 0);
		// MethodHandles.lookup().defineClass(Base64.getDecoder().decode(chunks...))
		code.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(code, getDecoder.index()); // [decoder]
		JvmRuntimeBuilder.emitLdc(code, chunks.get(0).index()); // [decoder, str]
		for (int i = 1; i < chunks.size(); i++) {
			JvmRuntimeBuilder.emitLdc(code, chunks.get(i).index());
			code.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(code, stringConcat.index());
		}
		code.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(code, decode.index()); // [bytes]
		code.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(code, lookup.index()); // [bytes, lookup]
		code.add(Opcode.SWAP); // [lookup, bytes]
		code.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(code, defineClass.index()); // [class]
		code.add(Opcode.POP);
		// _simdInited = 1
		code.add(Opcode.ICONST_1);
		code.add(Opcode.PUTSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		JvmRuntimeBuilder.patchBranch(code, guardPos, code.size());
		code.add(Opcode.RETURN);

		return new SimdRuntime(initName, initDesc, code, 3, 1, initedFieldName, initedFieldDesc, ops);
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
