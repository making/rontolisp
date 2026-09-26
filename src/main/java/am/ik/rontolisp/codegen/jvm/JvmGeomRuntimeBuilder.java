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

/**
 * Builds the {@code geom} kernel bridge for the generated {@code .class}: the logic lives
 * in {@link JvmGeomTemplate} (plain Java, compiled by the project build), whose bytecode
 * is read from the classpath at compile time, renamed after the generated program
 * ({@code <Program>$GeomBridge}, in its package) and shipped BESIDE it as an ordinary
 * class file ({@link GeomRuntime#classFiles()}, joined into
 * {@link JvmLispCompiler#runtimeClassFiles()}) -- never defined at run time, which a
 * GraalVM native image refuses ({@code .kb/template-class-embedding.md}). The emitted
 * {@code private static void _geomInit()} only loads it.
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
 * {@link LinkageError} loading the bridge can raise -- the template carries the project's
 * class version, so a JRE older than the toolchain answers
 * {@code UnsupportedClassVersionError}, and a class copied without the file beside it
 * answers {@code NoClassDefFoundError} -- leaves {@value #AVAILABLE_FIELD} false and says
 * nothing, and {@code _geomReady()} lets every call site skip the attempt and run the
 * spliced {@code geom.lisp} defun instead. That is {@code --simd}'s degrade with the
 * warning removed: a flagless acceleration has nothing to tell the user about.
 */
final class JvmGeomRuntimeBuilder {

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

	private JvmGeomRuntimeBuilder() {
	}

	/**
	 * The internal name of a program's bridge class: named after the program, in its
	 * package, so programs built by different rontolisp versions never share one file.
	 * @param programInternalName the generated class's internal (slash-separated) name
	 * @return the bridge's internal name
	 */
	static String bridgeName(String programInternalName) {
		return programInternalName + "$GeomBridge";
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
			Utf8Constant readyName, Utf8Constant readyDesc, List<Integer> readyCode, Map<String, MethodrefConstant> ops,
			Map<String, byte[]> classFiles) {
	}

	/**
	 * Builds the {@code _geomInit} / {@code _geomReady} method bodies, registers the
	 * bridge references and renames the bridge class file.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param programInternalName the generated class's internal name -- the bridge is
	 * named after it and lives in its package (the bridge methods are package-private)
	 * @return the runtime pieces
	 */
	static GeomRuntime build(ConstantPool cp, ClassConstant thisClass, String programInternalName) {
		String bridgeName = bridgeName(programInternalName);
		byte[] bridgeBytes = JvmJavaRuntimeBuilder.renameClass(loadTemplateBytes(), TEMPLATE_INTERNAL_NAME, bridgeName);

		Utf8Constant initedFieldName = cp.addUtf8("_geomInited");
		Utf8Constant initedFieldDesc = cp.addUtf8("I");
		FieldrefConstant initedField = cp.addFieldref(thisClass, cp.addNameAndType(initedFieldName, initedFieldDesc));
		Utf8Constant availableFieldName = cp.addUtf8(AVAILABLE_FIELD);
		Utf8Constant availableFieldDesc = cp.addUtf8("I");
		FieldrefConstant availableField = cp.addFieldref(thisClass,
				cp.addNameAndType(availableFieldName, availableFieldDesc));

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
		// <Program>$GeomBridge.class; -- loads the class file beside the program
		// _geomAvailable = 1;
		// } catch (LinkageError e) {
		// // missing, or an older JRE than the template's class version: stay on the
		// defuns.
		// }
		// _geomInited = 1;
		List<Integer> code = new ArrayList<>();
		code.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		int guardPos = code.size();
		code.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(code, 0);
		int tryStart = code.size();
		JvmRuntimeBuilder.emitLdc(code, bridgeClass.index()); // [class]
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

		return new GeomRuntime(initName, initDesc, code, 1, 0, initExceptionTable, initedFieldName, initedFieldDesc,
				availableFieldName, availableFieldDesc, readyName, readyDesc, readyCode, ops,
				Map.of(bridgeName + ".class", bridgeBytes));
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
