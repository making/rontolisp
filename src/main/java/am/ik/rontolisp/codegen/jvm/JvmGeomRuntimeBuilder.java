package am.ik.rontolisp.codegen.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.LinkedHashMap;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;

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
	record GeomRuntime(Utf8Entry initName, Utf8Entry initDesc, MethodCode initCode, Utf8Entry initedFieldName,
			Utf8Entry initedFieldDesc, Utf8Entry availableFieldName, Utf8Entry availableFieldDesc, Utf8Entry readyName,
			Utf8Entry readyDesc, MethodCode readyCode, Map<String, MethodRefEntry> ops,
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
	static GeomRuntime build(ConstantPool cp, ClassEntry thisClass, String programInternalName) {
		String bridgeName = bridgeName(programInternalName);
		byte[] bridgeBytes = JvmJavaRuntimeBuilder.renameClass(loadTemplateBytes(), TEMPLATE_INTERNAL_NAME, bridgeName);

		Utf8Entry initedFieldName = cp.utf8Entry("_geomInited");
		Utf8Entry initedFieldDesc = cp.utf8Entry("I");
		FieldRefEntry initedField = cp.fieldRef(thisClass, initedFieldName, initedFieldDesc);
		Utf8Entry availableFieldName = cp.utf8Entry(AVAILABLE_FIELD);
		Utf8Entry availableFieldDesc = cp.utf8Entry("I");
		FieldRefEntry availableField = cp.fieldRef(thisClass, availableFieldName, availableFieldDesc);

		ClassEntry linkageErrorClass = cp.classEntry("java/lang/LinkageError");

		ClassEntry bridgeClass = cp.classEntry(bridgeName);
		Map<String, MethodRefEntry> ops = new LinkedHashMap<>();
		Utf8Entry initName = cp.utf8Entry(INIT_METHOD);
		Utf8Entry initDesc = cp.utf8Entry("()V");
		ops.put("init", cp.methodRef(thisClass, initName, initDesc));
		Utf8Entry readyName = cp.utf8Entry(READY_METHOD);
		Utf8Entry readyDesc = cp.utf8Entry("()Z");
		ops.put(AVAILABLE, cp.methodRef(thisClass, readyName, readyDesc));
		for (String member : JvmGeomKernelCompiler.members()) {
			String desc = "(" + "Ljava/lang/Object;".repeat(JvmGeomKernelCompiler.arity(member))
					+ ")Ljava/lang/Object;";
			ops.put(member, cp.methodRef(bridgeClass, JvmGeomKernelCompiler.bridgeMethod(member), desc));
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
		MethodCode code = new MethodCode();
		code.getstatic(initedField);
		MethodCode.Label guard = code.newLabel();
		code.ifne(guard);
		MethodCode.Label tryStart = code.newBoundLabel();
		code.ldc(bridgeClass); // [class]
		code.pop();
		code.iconst_1();
		code.putstatic(availableField);
		MethodCode.Label skipHandler = code.newLabel();
		code.goto_(skipHandler);
		// catch (LinkageError e) -- the operand stack holds just the caught throwable;
		// discard it and leave _geomAvailable false. Nothing is printed: this is not a
		// flag the user asked for, so there is nothing for them to act on.
		MethodCode.Label handler = code.newBoundLabel();
		code.pop();
		code.labelBinding(skipHandler);
		// _geomInited = 1 (tried, either way -- never re-attempt)
		code.iconst_1();
		code.putstatic(initedField);
		code.labelBinding(guard);
		code.return_();

		code.exceptionCatch(tryStart, handler, handler, linkageErrorClass);

		// --- _geomReady body: return _geomAvailable != 0; ---
		MethodCode readyCode = new MethodCode();
		readyCode.getstatic(availableField);
		readyCode.ireturn();

		return new GeomRuntime(initName, initDesc, code, initedFieldName, initedFieldDesc, availableFieldName,
				availableFieldDesc, readyName, readyDesc, readyCode, ops, Map.of(bridgeName + ".class", bridgeBytes));
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
