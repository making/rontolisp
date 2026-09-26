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
 * Builds the {@code ffi:} runtime for the generated {@code .class}: the
 * {@link JvmObjcRuntimeBuilder} mechanism -- a closure of shipped classes renamed after
 * the emitted program -- over {@code am.ik.ffi}, plus the two classes the call sites add,
 * {@link JvmFfiTemplate} (the bridge) and {@link JvmFfiHandle} (the pointer value).
 *
 * <p>
 * Why the whole library travels: the parts of the binding that were expensive to get
 * right -- the carrier canonicalisation that makes the native-image shape grid finite,
 * one downcall handle per shape, the per-thread {@code errno} capture, the
 * struct-by-value layouts, the upcall dispatcher bound from a CONSTANT
 * {@code findStatic}, the actionable unregistered-shape signal -- are exactly the parts a
 * hand-kept copy would fork. So every class file of {@code am.ik.ffi} is renamed by one
 * prefix rule ({@code am/ik/ffi/} -> {@code <Program>}{@value #FFI_SUFFIX}), the bridge
 * and the handle are renamed the same way, and the compiled backend runs the very bytes
 * the interpreter runs. The renamed files are shipped BESIDE the program
 * ({@link FfiRuntime#classFiles()}, joined into
 * {@link JvmLispCompiler#runtimeClassFiles()}) -- never defined at run time, which a
 * GraalVM native image refuses ({@code .kb/template-class-embedding.md}) -- and named
 * after it, because {@code bind} keeps that program's {@code _apply}. Like the
 * {@code objc:} classes, they make UPCALLS into the compiled program: an
 * {@code ffi:callback}'s Lisp function runs through {@code _apply}, handed over by
 * {@code bind(Class)}.
 *
 * <p>
 * The emitted {@code private static void _ffiInit()} binds the callback on first use
 * (guarded by the {@code _ffiInited} int field); every {@code ffi:} call site is preceded
 * by an {@code _ffiInit} call.
 */
final class JvmFfiRuntimeBuilder {

	/**
	 * Appended to the generated program's internal name to form the prefix the library's
	 * classes are renamed onto ({@code am/ik/ffi/X} -> {@code <Program>$FfiX}).
	 */
	static final String FFI_SUFFIX = "$Ffi";

	/**
	 * Appended to the generated program's internal name to name the bridge
	 * ({@link JvmFfiTemplate}).
	 */
	static final String BRIDGE_SUFFIX = "$FfiBridge";

	/**
	 * Appended to the generated program's internal name to name the pointer class
	 * ({@link JvmFfiHandle}).
	 */
	static final String HANDLE_SUFFIX = "$FfiPointer";

	/** The bridge's internal (constant-pool) class name before renaming. */
	private static final String TEMPLATE_INTERNAL_NAME = "am/ik/rontolisp/codegen/jvm/JvmFfiTemplate";

	/** The handle's internal (constant-pool) class name before renaming. */
	private static final String HANDLE_INTERNAL_NAME = "am/ik/rontolisp/codegen/jvm/JvmFfiHandle";

	/** The package prefix of the embedded library, before renaming. */
	private static final String FFI_INTERNAL_PREFIX = "am/ik/ffi/";

	/**
	 * Every class file of {@code am.ik.ffi}, nested and synthetic classes included --
	 * there is no way to enumerate a package from the classpath (let alone from inside a
	 * native image), so the list is written down and {@code JvmFfiInteropCompilerTest}
	 * pins it against what the build actually produced. {@code package-info} carries only
	 * annotations and is left behind.
	 *
	 * <p>
	 * The list names what ships; its order no longer matters. It did while the classes
	 * were defined one by one at run time: the verifier loads a {@code catch} clause's
	 * type while defining the class that has it, so {@code FfiException} had to come
	 * first. Shipped as files, every class is on disk before any of them loads.
	 */
	private static final List<String> FFI_CLASSES = List.of("FfiException", "FfiType", "FfiType$Scalar",
			"FfiType$Struct", "FfiRuntime$Callback", "FfiRuntime$CallbackShape", "FfiRuntime$CallRequest",
			"FfiRuntime$PokeRequest", "FfiRuntime$1", "FfiRuntime");

	/** The emitted init helper method name. */
	static final String INIT_METHOD = "_ffiInit";

	/** The {@code ops} key of the print hook's method reference. */
	static final String PRINT = "print";

	private JvmFfiRuntimeBuilder() {
	}

	/**
	 * The ready-to-emit {@code _ffiInit} method, its guard field, and the constant-pool
	 * references the {@code ffi:} call-site compiler needs ({@code ops} keys:
	 * {@code init}, one per verb, {@value #PRINT}). The class files that travel beside
	 * the program are keyed by their paths within an output tree.
	 */
	record FfiRuntime(Utf8Constant initName, Utf8Constant initDesc, List<Integer> initCode, int maxStack, int maxLocals,
			Utf8Constant initedFieldName, Utf8Constant initedFieldDesc, FieldrefConstant initedField,
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
	 * The internal name of a program's pointer class.
	 * @param programInternalName the generated class's internal (slash-separated) name
	 * @return the handle's internal name, in the program's own package
	 */
	static String handleName(String programInternalName) {
		return programInternalName + HANDLE_SUFFIX;
	}

	/**
	 * The prefix a program's copy of {@code am.ik.ffi} is renamed onto.
	 * @param programInternalName the generated class's internal (slash-separated) name
	 * @return the prefix, e.g. {@code com/example/Prog$Ffi}
	 */
	static String ffiPrefix(String programInternalName) {
		return programInternalName + FFI_SUFFIX;
	}

	/**
	 * Builds the {@code _ffiInit} method body, registers the bridge references and
	 * renames the class files that travel beside the program.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param programInternalName the generated class's internal name -- the classes are
	 * named after it and live in its package (their members are package-private)
	 * @return the runtime pieces
	 */
	static FfiRuntime build(ConstantPool cp, ClassConstant thisClass, String programInternalName) {
		String bridgeName = bridgeName(programInternalName);
		String handleName = handleName(programInternalName);
		String ffiPrefix = ffiPrefix(programInternalName);
		Map<String, byte[]> classFiles = new LinkedHashMap<>();
		for (String name : FFI_CLASSES) {
			classFiles.put(ffiPrefix + name + ".class",
					rename(loadResource(FFI_INTERNAL_PREFIX + name + ".class"), bridgeName, handleName, ffiPrefix));
		}
		classFiles.put(handleName + ".class",
				rename(loadResource(HANDLE_INTERNAL_NAME + ".class"), bridgeName, handleName, ffiPrefix));
		classFiles.put(bridgeName + ".class",
				rename(loadResource(TEMPLATE_INTERNAL_NAME + ".class"), bridgeName, handleName, ffiPrefix));

		Utf8Constant initedFieldName = cp.addUtf8("_ffiInited");
		Utf8Constant initedFieldDesc = cp.addUtf8("I");
		FieldrefConstant initedField = cp.addFieldref(thisClass, cp.addNameAndType(initedFieldName, initedFieldDesc));

		ClassConstant bridgeClass = cp.addClass(cp.addUtf8(bridgeName));
		MethodrefConstant bind = cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("bind"), cp.addUtf8("(Ljava/lang/Class;)V")));
		String oneArgDesc = "(Ljava/lang/Object;)Ljava/lang/Object;";
		String twoArgDesc = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
		String threeArgDesc = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
		String fourArgDesc = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)"
				+ "Ljava/lang/Object;";
		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		Utf8Constant initName = cp.addUtf8(INIT_METHOD);
		Utf8Constant initDesc = cp.addUtf8("()V");
		ops.put("init", cp.addMethodref(thisClass, cp.addNameAndType(initName, initDesc)));
		ops.put("open", cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("ffiOpen"), cp.addUtf8(oneArgDesc))));
		ops.put("symbol",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("ffiSymbol"), cp.addUtf8(twoArgDesc))));
		ops.put("call", cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("ffiCall"), cp.addUtf8(
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put("%apply-call",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("ffiApplyCall"), cp.addUtf8(fourArgDesc))));
		ops.put("callback",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("ffiCallback"), cp.addUtf8(threeArgDesc))));
		ops.put("alloc",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("ffiAlloc"), cp.addUtf8(oneArgDesc))));
		ops.put("free", cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("ffiFree"), cp.addUtf8(oneArgDesc))));
		ops.put("peek",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("ffiPeek"), cp.addUtf8(threeArgDesc))));
		ops.put("poke",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("ffiPoke"), cp.addUtf8(fourArgDesc))));
		ops.put("size", cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("ffiSize"), cp.addUtf8(oneArgDesc))));
		ops.put("align",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("ffiAlign"), cp.addUtf8(oneArgDesc))));
		ops.put("pointerp",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("ffiPointerp"), cp.addUtf8(oneArgDesc))));
		ops.put("address",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("ffiAddress"), cp.addUtf8(oneArgDesc))));
		ops.put("errno", cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("ffiErrno"), cp.addUtf8("()Ljava/lang/Object;"))));
		ops.put(PRINT, cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("ffiPrint"), cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/String;"))));

		// --- _ffiInit body ---------------------------------------------------------
		// if (_ffiInited != 0) return;
		List<Integer> code = new ArrayList<>();
		code.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		int guardPos = code.size();
		code.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(code, 0);
		// <Program>$FfiBridge.bind(<Program>.class) -- the bridge loads from the
		// program's own class loader like any other class beside it.
		JvmRuntimeBuilder.emitLdc(code, thisClass.index());
		code.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(code, bind.index());
		// _ffiInited = 1
		code.add(Opcode.ICONST_1);
		code.add(Opcode.PUTSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		JvmRuntimeBuilder.patchBranch(code, guardPos, code.size());
		code.add(Opcode.RETURN);

		return new FfiRuntime(initName, initDesc, code, 1, 0, initedFieldName, initedFieldDesc, initedField, ops,
				classFiles);
	}

	/**
	 * Renames one class file out of its own package and out of {@code am.ik.ffi}, after
	 * the generated program. All three renames run over every file: the bridge names the
	 * handle and the library, the library names itself, and a name that is not in a given
	 * file simply does not match. The library's rename is a PREFIX rule, so a nested
	 * class ({@code am/ik/ffi/FfiType$Scalar}) follows its outer one without being
	 * listed.
	 */
	private static byte[] rename(byte[] classFile, String bridgeName, String handleName, String ffiPrefix) {
		byte[] renamed = JvmJavaRuntimeBuilder.renameClass(classFile, TEMPLATE_INTERNAL_NAME, bridgeName);
		renamed = JvmJavaRuntimeBuilder.renameClass(renamed, HANDLE_INTERNAL_NAME, handleName);
		return JvmJavaRuntimeBuilder.renameClass(renamed, FFI_INTERNAL_PREFIX, ffiPrefix);
	}

	/** Reads one of the embedded files from the compiler's own classpath. */
	private static byte[] loadResource(String path) {
		try (InputStream in = JvmFfiRuntimeBuilder.class.getClassLoader().getResourceAsStream(path)) {
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
	static List<String> embeddedFfiClasses() {
		return FFI_CLASSES;
	}

}
