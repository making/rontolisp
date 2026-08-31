package am.ik.rontolisp.codegen.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
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

/**
 * Builds the {@code geom} kernel bridge for the generated standalone {@code .class}, the
 * shape of {@link JvmBlasRuntimeBuilder} with {@link JvmSimdRuntimeBuilder}'s
 * availability guard: the logic lives in {@link JvmGeomTemplate} (plain Java, compiled by
 * the project build), whose bytecode is read from the classpath at compile time, renamed
 * into the generated program's own package as {@value #BRIDGE_NAME}, base64-encoded and
 * embedded as string constants, and the emitted {@code private static void _geomInit()}
 * decodes and defines it on first use.
 *
 * <p>
 * <b>It is emitted only for a program that CALLS one of the four accelerated members</b>
 * ({@link JvmGeomKernelCompiler#members()}), never for one that merely splices
 * {@code geom.lisp}: the gate is {@code JvmLispCompiler}'s member scan over the pruned
 * program, so a program that touches no geom kernel is emitted byte for byte as before.
 *
 * <p>
 * Unlike {@code --blas} there is no flag in front of this, so the bridge must never be
 * able to BREAK a program that used to run. {@code _geomInit} therefore catches the
 * {@link LinkageError} a {@code Lookup.defineClass} can raise -- the template carries the
 * project's class version, so a JRE older than the toolchain answers
 * {@code UnsupportedClassVersionError} here -- leaves {@value #AVAILABLE_FIELD} false and
 * says nothing, and {@code _geomReady()} lets every call site skip the attempt and run
 * the spliced {@code geom.lisp} defun instead. That is {@code --simd}'s degrade with the
 * warning removed: a flagless acceleration has nothing to tell the user about.
 */
final class JvmGeomRuntimeBuilder {

	/**
	 * The name the embedded bridge class is defined under at runtime, relative to the
	 * generated program's own package (see {@link #build}).
	 */
	static final String BRIDGE_NAME = "RontoLispGeomBridge";

	/** The template's internal (constant-pool) class name before renaming. */
	private static final String TEMPLATE_INTERNAL_NAME = "am/ik/rontolisp/codegen/jvm/JvmGeomTemplate";

	/** The emitted init helper method name. */
	static final String INIT_METHOD = "_geomInit";

	/** The emitted availability accessor method name. */
	private static final String READY_METHOD = "_geomReady";

	/** The guard field backing {@link #READY_METHOD}. */
	private static final String AVAILABLE_FIELD = "_geomAvailable";

	/** The {@code ops} key of the availability accessor ({@link #READY_METHOD}). */
	static final String AVAILABLE = "available";

	/** Keeps each base64 string constant well under the 65535-byte Utf8 limit. */
	private static final int CHUNK_SIZE = 40000;

	private JvmGeomRuntimeBuilder() {
	}

	/**
	 * The ready-to-emit {@code _geomInit} and {@code _geomReady} methods, their guard
	 * fields, and the constant-pool references the accelerated call site needs
	 * ({@code ops} keys: {@code init}, {@value #AVAILABLE}, and the qualified name of
	 * each accelerated member).
	 */
	record GeomRuntime(Utf8Constant initName, Utf8Constant initDesc, List<Integer> initCode, int maxStack,
			int maxLocals, List<ByteCodeWriter.ExceptionTableEntry> initExceptionTable, Utf8Constant initedFieldName,
			Utf8Constant initedFieldDesc, Utf8Constant availableFieldName, Utf8Constant availableFieldDesc,
			Utf8Constant readyName, Utf8Constant readyDesc, List<Integer> readyCode,
			Map<String, MethodrefConstant> ops) {
	}

	/**
	 * Builds the {@code _geomInit} / {@code _geomReady} method bodies and registers the
	 * bridge references.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param stringConcat {@code String.concat(String)}
	 * @param packagePrefix the generated class's package as an internal-name prefix
	 * ({@code ""} for the default package, otherwise e.g. {@code "com/example/"}) --
	 * {@code Lookup.defineClass(byte[])} requires the defined class to share the lookup
	 * class's package, so the bridge is renamed into this one too
	 * @return the runtime pieces
	 */
	static GeomRuntime build(ConstantPool cp, ClassConstant thisClass, MethodrefConstant stringConcat,
			String packagePrefix) {
		String bridgeName = packagePrefix + BRIDGE_NAME;
		byte[] bridgeBytes = JvmJavaRuntimeBuilder.renameClass(loadTemplateBytes(), TEMPLATE_INTERNAL_NAME, bridgeName);
		String base64 = Base64.getEncoder().encodeToString(bridgeBytes);
		List<ConstantPool.StringConstant> chunks = new ArrayList<>();
		for (int i = 0; i < base64.length(); i += CHUNK_SIZE) {
			chunks.add(cp.addString(base64.substring(i, Math.min(base64.length(), i + CHUNK_SIZE))));
		}

		Utf8Constant initedFieldName = cp.addUtf8("_geomInited");
		Utf8Constant initedFieldDesc = cp.addUtf8("I");
		FieldrefConstant initedField = cp.addFieldref(thisClass, cp.addNameAndType(initedFieldName, initedFieldDesc));
		Utf8Constant availableFieldName = cp.addUtf8(AVAILABLE_FIELD);
		Utf8Constant availableFieldDesc = cp.addUtf8("I");
		FieldrefConstant availableField = cp.addFieldref(thisClass,
				cp.addNameAndType(availableFieldName, availableFieldDesc));

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
		ClassConstant linkageErrorClass = cp.addClass(cp.addUtf8("java/lang/LinkageError"));

		ClassConstant bridgeClass = cp.addClass(cp.addUtf8(bridgeName));
		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		Utf8Constant initName = cp.addUtf8(INIT_METHOD);
		Utf8Constant initDesc = cp.addUtf8("()V");
		ops.put("init", cp.addMethodref(thisClass, cp.addNameAndType(initName, initDesc)));
		Utf8Constant readyName = cp.addUtf8(READY_METHOD);
		Utf8Constant readyDesc = cp.addUtf8("()Z");
		ops.put(AVAILABLE, cp.addMethodref(thisClass, cp.addNameAndType(readyName, readyDesc)));
		for (String member : JvmGeomKernelCompiler.members()) {
			String desc = "(" + "Ljava/lang/Object;".repeat(JvmGeomKernelCompiler.arity(member))
					+ ")Ljava/lang/Object;";
			ops.put(member, cp.addMethodref(bridgeClass,
					cp.addNameAndType(cp.addUtf8(JvmGeomKernelCompiler.bridgeMethod(member)), cp.addUtf8(desc))));
		}

		// --- _geomInit body (self-contained: no bind callback) ---
		// if (_geomInited != 0) return;
		// try {
		// MethodHandles.lookup().defineClass(Base64.getDecoder().decode(chunks...));
		// _geomAvailable = 1;
		// } catch (LinkageError e) {
		// // an older JRE than the template's class version: stay on the defuns.
		// }
		// _geomInited = 1;
		List<Integer> code = new ArrayList<>();
		code.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		int guardPos = code.size();
		code.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(code, 0);
		int tryStart = code.size();
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
		JvmRuntimeBuilder.emitU2(code, availableField.index());
		int skipHandlerPos = code.size();
		code.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(code, 0);
		// catch (LinkageError e) -- the operand stack holds just the caught throwable;
		// discard it and leave _geomAvailable false. Nothing is printed: this is not a
		// flag the user asked for, so there is nothing for them to act on.
		int handlerPc = code.size();
		code.add(Opcode.POP);
		JvmRuntimeBuilder.patchBranch(code, skipHandlerPos, code.size());
		// _geomInited = 1 (tried, either way -- never re-attempt)
		code.add(Opcode.ICONST_1);
		code.add(Opcode.PUTSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		JvmRuntimeBuilder.patchBranch(code, guardPos, code.size());
		code.add(Opcode.RETURN);

		List<ByteCodeWriter.ExceptionTableEntry> initExceptionTable = List
			.of(new ByteCodeWriter.ExceptionTableEntry(tryStart, handlerPc, handlerPc, linkageErrorClass.index()));

		// --- _geomReady body: return _geomAvailable != 0; ---
		List<Integer> readyCode = new ArrayList<>();
		readyCode.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(readyCode, availableField.index());
		readyCode.add(Opcode.IRETURN);

		return new GeomRuntime(initName, initDesc, code, 3, 1, initExceptionTable, initedFieldName, initedFieldDesc,
				availableFieldName, availableFieldDesc, readyName, readyDesc, readyCode, ops);
	}

	/** Reads the compiled {@link JvmGeomTemplate} bytecode from the classpath. */
	private static byte[] loadTemplateBytes() {
		try (InputStream in = JvmGeomRuntimeBuilder.class.getResourceAsStream("JvmGeomTemplate.class")) {
			if (in == null) {
				throw new IllegalStateException("JvmGeomTemplate.class not found on the classpath");
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
