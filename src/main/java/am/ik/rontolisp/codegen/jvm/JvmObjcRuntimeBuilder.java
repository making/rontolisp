package am.ik.rontolisp.codegen.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
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
 * Builds the {@code objc:} runtime for the generated {@code .class}: the
 * {@link JvmGpuRuntimeBuilder} mechanism -- a CLOSURE of shipped classes rather than one
 * flat template -- over {@code am.ik.objc}, plus the two classes the call sites add,
 * {@link JvmObjcTemplate} (the bridge) and {@link JvmObjcHandle} (the value).
 *
 * <p>
 * Why the whole library travels: the parts of the binding that were expensive to get
 * right -- the type-encoding parser, one {@code objc_msgSend} handle per shape, the hop
 * to thread 0 with its re-entrancy rule, the closed set of upcall shapes bound from
 * CONSTANT {@code findStatic}s, the run-loop pump -- are exactly the parts a hand-kept
 * copy would fork. So every class file of {@code am.ik.objc} is renamed by one prefix
 * rule ({@code am/ik/objc/} -> {@code <Program>}{@value #OBJC_SUFFIX}), the bridge and
 * the handle are renamed the same way, and the compiled backend runs the very bytes the
 * interpreter runs. The renamed files are shipped BESIDE the program
 * ({@link ObjcRuntime#classFiles()}, joined into
 * {@link JvmLispCompiler#runtimeClassFiles()}) -- never defined at run time, which a
 * GraalVM native image refuses ({@code .kb/template-class-embedding.md}) -- and named
 * after it, because {@code bind} keeps that program's {@code _apply}. The classes make
 * UPCALLS into the compiled program: a method of {@code objc:define-class} runs on thread
 * 0 through {@code _apply}, handed over by {@code bind(Class)} like the {@code java:}
 * bridge's.
 *
 * <p>
 * The emitted {@code private static void _objcInit()} binds the callback on first use
 * (guarded by the {@code _objcInited} int field); every {@code objc:} call site is
 * preceded by an {@code _objcInit} call.
 */
final class JvmObjcRuntimeBuilder {

	/**
	 * Appended to the generated program's internal name to form the prefix the library's
	 * classes are renamed onto ({@code am/ik/objc/X} -> {@code <Program>$ObjcX}).
	 */
	static final String OBJC_SUFFIX = "$Objc";

	/**
	 * Appended to the generated program's internal name to name the bridge
	 * ({@link JvmObjcTemplate}).
	 */
	static final String BRIDGE_SUFFIX = "$ObjcBridge";

	/**
	 * Appended to the generated program's internal name to name the value class
	 * ({@link JvmObjcHandle}).
	 */
	static final String HANDLE_SUFFIX = "$ObjcObject";

	/** The bridge's internal (constant-pool) class name before renaming. */
	private static final String TEMPLATE_INTERNAL_NAME = "am/ik/rontolisp/codegen/jvm/JvmObjcTemplate";

	/** The handle's internal (constant-pool) class name before renaming. */
	private static final String HANDLE_INTERNAL_NAME = "am/ik/rontolisp/codegen/jvm/JvmObjcHandle";

	/** The package prefix of the embedded library, before renaming. */
	private static final String OBJC_INTERNAL_PREFIX = "am/ik/objc/";

	/**
	 * Every class file of {@code am.ik.objc}, nested and anonymous classes included --
	 * there is no way to enumerate a package from the classpath (let alone from inside a
	 * native image), so the list is written down and {@code JvmObjcInteropCompilerTest}
	 * pins it against what the build actually produced. {@code package-info} carries only
	 * annotations and is left behind.
	 *
	 * <p>
	 * The list names what ships; its order no longer matters. It did while the classes
	 * were defined one by one at run time: the verifier loads a {@code catch} clause's
	 * type while defining the class that has it, so {@code ObjcException} had to come
	 * first. Shipped as files, every class is on disk before any of them loads.
	 */
	private static final List<String> OBJC_CLASSES = List.of("ObjcException", "MainThread", "MainThread$Slot",
			"ObjcClasses", "ObjcClasses$Bound", "ObjcClasses$Method", "ObjcClasses$Shape", "ObjcClasses$Spec",
			"VariadicSelectors", "ObjcRuntime", "ObjcRuntime$1", "ObjcRuntime$Out", "ObjcRuntime$Sent",
			"ObjcRuntime$Signature", "TypeEncoding", "TypeEncoding$Kind", "TypeEncoding$Parser", "TypeEncoding$Type");

	/** The emitted init helper method name. */
	static final String INIT_METHOD = "_objcInit";

	/** The {@code ops} key of the print hook's method reference. */
	static final String PRINT = "print";

	private JvmObjcRuntimeBuilder() {
	}

	/**
	 * The ready-to-emit {@code _objcInit} method, its guard field, and the constant-pool
	 * references the {@code objc:} call-site compiler needs ({@code ops} keys:
	 * {@code init}, {@code class}, {@code send}, {@code define-class}, {@code on-main},
	 * {@code string}, {@code data}, {@code bytes}, {@code address}, {@code objectp},
	 * {@value #PRINT}). The class files that travel beside the program are keyed by their
	 * paths within an output tree.
	 */
	record ObjcRuntime(Utf8Constant initName, Utf8Constant initDesc, List<Integer> initCode, int maxStack,
			int maxLocals, Utf8Constant initedFieldName, Utf8Constant initedFieldDesc, FieldrefConstant initedField,
			Map<String, MethodrefConstant> ops, Map<String, byte[]> classFiles) {
	}

	/**
	 * The internal name of a program's bridge class.
	 * @param programInternalName the generated class's internal (slash-separated) name
	 * @return the bridge's internal name, in the program's own package
	 */
	static String bridgeName(String programInternalName) {
		return programInternalName + BRIDGE_SUFFIX;
	}

	/**
	 * The internal name of a program's value class.
	 * @param programInternalName the generated class's internal (slash-separated) name
	 * @return the handle's internal name, in the program's own package
	 */
	static String handleName(String programInternalName) {
		return programInternalName + HANDLE_SUFFIX;
	}

	/**
	 * The prefix a program's copy of {@code am.ik.objc} is renamed onto.
	 * @param programInternalName the generated class's internal (slash-separated) name
	 * @return the prefix, e.g. {@code com/example/Prog$Objc}
	 */
	static String objcPrefix(String programInternalName) {
		return programInternalName + OBJC_SUFFIX;
	}

	/**
	 * The internal name of a program's copy of {@code am.ik.objc.MainThread}, whose
	 * {@code handOverRequired()} the sized-stack launcher asks
	 * ({@code JvmSizedMainBuilder}).
	 * @param programInternalName the generated class's internal (slash-separated) name
	 * @return e.g. {@code com/example/Prog$ObjcMainThread}
	 */
	static String mainThreadName(String programInternalName) {
		return objcPrefix(programInternalName) + "MainThread";
	}

	/**
	 * Builds the {@code _objcInit} method body, registers the bridge references and
	 * renames the class files that travel beside the program.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param programInternalName the generated class's internal name -- the classes are
	 * named after it and live in its package (their members are package-private)
	 * @return the runtime pieces
	 */
	static ObjcRuntime build(ConstantPool cp, ClassConstant thisClass, String programInternalName) {
		String bridgeName = bridgeName(programInternalName);
		String handleName = handleName(programInternalName);
		String objcPrefix = objcPrefix(programInternalName);
		Map<String, byte[]> classFiles = new LinkedHashMap<>();
		for (String name : OBJC_CLASSES) {
			classFiles.put(objcPrefix + name + ".class",
					rename(loadResource(OBJC_INTERNAL_PREFIX + name + ".class"), bridgeName, handleName, objcPrefix));
		}
		classFiles.put(handleName + ".class",
				rename(loadResource(HANDLE_INTERNAL_NAME + ".class"), bridgeName, handleName, objcPrefix));
		classFiles.put(bridgeName + ".class",
				rename(loadResource(TEMPLATE_INTERNAL_NAME + ".class"), bridgeName, handleName, objcPrefix));

		Utf8Constant initedFieldName = cp.addUtf8("_objcInited");
		Utf8Constant initedFieldDesc = cp.addUtf8("I");
		FieldrefConstant initedField = cp.addFieldref(thisClass, cp.addNameAndType(initedFieldName, initedFieldDesc));

		ClassConstant bridgeClass = cp.addClass(cp.addUtf8(bridgeName));
		MethodrefConstant bind = cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("bind"), cp.addUtf8("(Ljava/lang/Class;)V")));
		String oneArgDesc = "(Ljava/lang/Object;)Ljava/lang/Object;";
		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		Utf8Constant initName = cp.addUtf8(INIT_METHOD);
		Utf8Constant initDesc = cp.addUtf8("()V");
		ops.put("init", cp.addMethodref(thisClass, cp.addNameAndType(initName, initDesc)));
		ops.put("class",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("objcClass"), cp.addUtf8(oneArgDesc))));
		ops.put("send", cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("objcSend"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put("define-class", cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("objcDefineClass"), cp
			.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put("on-main",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("objcOnMain"), cp.addUtf8(oneArgDesc))));
		ops.put("string",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("objcString"), cp.addUtf8(oneArgDesc))));
		ops.put("data",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("objcData"), cp.addUtf8(oneArgDesc))));
		ops.put("bytes",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("objcBytes"), cp.addUtf8(oneArgDesc))));
		ops.put("address",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("objcAddress"), cp.addUtf8(oneArgDesc))));
		ops.put("objectp",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("objcObjectp"), cp.addUtf8(oneArgDesc))));
		ops.put(PRINT, cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("objcPrint"), cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/String;"))));

		// --- _objcInit body --------------------------------------------------------
		// if (_objcInited != 0) return;
		List<Integer> code = new ArrayList<>();
		code.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		int guardPos = code.size();
		code.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(code, 0);
		// <Program>$ObjcBridge.bind(<Program>.class) -- the bridge loads from the
		// program's own class loader like any other class beside it.
		JvmRuntimeBuilder.emitLdc(code, thisClass.index());
		code.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(code, bind.index());
		// _objcInited = 1
		code.add(Opcode.ICONST_1);
		code.add(Opcode.PUTSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		JvmRuntimeBuilder.patchBranch(code, guardPos, code.size());
		code.add(Opcode.RETURN);

		return new ObjcRuntime(initName, initDesc, code, 1, 0, initedFieldName, initedFieldDesc, initedField, ops,
				classFiles);
	}

	/**
	 * Renames one class file out of its own package and out of {@code am.ik.objc}, after
	 * the generated program. All three renames run over every file: the bridge names the
	 * handle and the library, the library names itself, and a name that is not in a given
	 * file simply does not match. The library's rename is a PREFIX rule, so a nested
	 * class ({@code am/ik/objc/ObjcClasses$Spec}) follows its outer one without being
	 * listed.
	 */
	private static byte[] rename(byte[] classFile, String bridgeName, String handleName, String objcPrefix) {
		byte[] renamed = JvmJavaRuntimeBuilder.renameClass(classFile, TEMPLATE_INTERNAL_NAME, bridgeName);
		renamed = JvmJavaRuntimeBuilder.renameClass(renamed, HANDLE_INTERNAL_NAME, handleName);
		return JvmJavaRuntimeBuilder.renameClass(renamed, OBJC_INTERNAL_PREFIX, objcPrefix);
	}

	/** Reads one of the embedded files from the compiler's own classpath. */
	private static byte[] loadResource(String path) {
		try (InputStream in = JvmObjcRuntimeBuilder.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException(path + " not found on the classpath");
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** The library class files this builder ships, for the test that pins the list. */
	static List<String> embeddedObjcClasses() {
		return OBJC_CLASSES;
	}

}
