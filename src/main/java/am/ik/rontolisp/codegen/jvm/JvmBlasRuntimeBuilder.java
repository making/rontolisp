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

/**
 * Builds the {@code --blas} CBLAS bridge for the generated standalone {@code .class}, the
 * exact shape of {@link JvmSimdRuntimeBuilder} one kernel wide: the logic lives in
 * {@link JvmBlasTemplate} (plain Java, compiled by the project build), whose bytecode is
 * read from the classpath at compile time, renamed into the generated program's own
 * package as {@value #BRIDGE_NAME}, base64-encoded and embedded as string constants, and
 * the emitted {@code private static void _blasInit()} decodes and defines it on first
 * use.
 *
 * <p>
 * It is a SECOND bridge rather than more methods on the {@code --simd} one because the
 * two flags are orthogonal: {@code --blas} must work on a build that never asked for
 * {@code --simd} (and so must not drag in the incubator Vector API, which would make the
 * class need {@code --add-modules} to run), and {@code --simd} must keep producing
 * exactly the bytes it produced before.
 */
final class JvmBlasRuntimeBuilder {

	/**
	 * The name the embedded bridge class is defined under at runtime, relative to the
	 * generated program's own package (see {@link #build}).
	 */
	static final String BRIDGE_NAME = "RontoLispBlasBridge";

	/** The template's internal (constant-pool) class name before renaming. */
	private static final String TEMPLATE_INTERNAL_NAME = "am/ik/rontolisp/codegen/jvm/JvmBlasTemplate";

	/** The emitted init helper method name. */
	static final String INIT_METHOD = "_blasInit";

	/** The {@code ops} key of the {@code linalg:dot} kernel. */
	static final String DOT = "dot";

	/** The {@code ops} key of the {@code vec:matvec} kernel. */
	static final String MATVEC = "matvec";

	/** The {@code ops} key of the {@code vec:matvec-into} kernel. */
	static final String MATVEC_INTO = "matvecInto";

	/** Keeps each base64 string constant well under the 65535-byte Utf8 limit. */
	private static final int CHUNK_SIZE = 40000;

	private JvmBlasRuntimeBuilder() {
	}

	/**
	 * The ready-to-emit {@code _blasInit} method, its guard field, and the constant-pool
	 * references the accelerated call sites need ({@code ops} keys: {@code init},
	 * {@value #DOT}, {@value #MATVEC} and {@value #MATVEC_INTO}).
	 */
	record BlasRuntime(Utf8Constant initName, Utf8Constant initDesc, List<Integer> initCode, int maxStack,
			int maxLocals, Utf8Constant initedFieldName, Utf8Constant initedFieldDesc,
			Map<String, MethodrefConstant> ops) {
	}

	/**
	 * Builds the {@code _blasInit} method body and registers the bridge references.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param stringConcat {@code String.concat(String)}
	 * @param packagePrefix the generated class's package as an internal-name prefix
	 * ({@code ""} for the default package, otherwise e.g. {@code "com/example/"}) --
	 * {@code Lookup.defineClass(byte[])} requires the defined class to share the lookup
	 * class's package, so the bridge is renamed into this one too
	 * @return the runtime pieces
	 */
	static BlasRuntime build(ConstantPool cp, ClassConstant thisClass, MethodrefConstant stringConcat,
			String packagePrefix) {
		String bridgeName = packagePrefix + BRIDGE_NAME;
		byte[] bridgeBytes = JvmJavaRuntimeBuilder.renameClass(loadTemplateBytes(), TEMPLATE_INTERNAL_NAME, bridgeName);
		String base64 = Base64.getEncoder().encodeToString(bridgeBytes);
		List<ConstantPool.StringConstant> chunks = new ArrayList<>();
		for (int i = 0; i < base64.length(); i += CHUNK_SIZE) {
			chunks.add(cp.addString(base64.substring(i, Math.min(base64.length(), i + CHUNK_SIZE))));
		}

		Utf8Constant initedFieldName = cp.addUtf8("_blasInited");
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

		ClassConstant bridgeClass = cp.addClass(cp.addUtf8(bridgeName));
		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		Utf8Constant initName = cp.addUtf8(INIT_METHOD);
		Utf8Constant initDesc = cp.addUtf8("()V");
		ops.put("init", cp.addMethodref(thisClass, cp.addNameAndType(initName, initDesc)));
		ops.put(DOT, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("blasDot"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put(MATVEC, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("blasMatvec"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put(MATVEC_INTO, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("blasMatvecInto"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));

		// --- _blasInit body (self-contained: no bind callback) ---
		List<Integer> code = new ArrayList<>();
		code.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		int guardPos = code.size();
		code.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(code, 0);
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
		code.add(Opcode.ICONST_1);
		code.add(Opcode.PUTSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		JvmRuntimeBuilder.patchBranch(code, guardPos, code.size());
		code.add(Opcode.RETURN);

		return new BlasRuntime(initName, initDesc, code, 3, 1, initedFieldName, initedFieldDesc, ops);
	}

	/** Reads the compiled {@link JvmBlasTemplate} bytecode from the classpath. */
	private static byte[] loadTemplateBytes() {
		try (InputStream in = JvmBlasRuntimeBuilder.class.getResourceAsStream("JvmBlasTemplate.class")) {
			if (in == null) {
				throw new IllegalStateException("JvmBlasTemplate.class not found on the classpath");
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
