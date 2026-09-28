package am.ik.rontolisp.codegen.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
 * flat template -- over {@code am.ik.objc}, plus {@link JvmObjcPrimitivesTemplate} (the
 * primitive layer, which {@code objc.lisp} compiled into the program calls).
 *
 * <p>
 * Why the whole library travels: the parts of the binding that were expensive to get
 * right -- the type-encoding parser, one {@code objc_msgSend} handle per shape, the hop
 * to thread 0 with its re-entrancy rule, the closed set of upcall shapes bound from
 * CONSTANT {@code findStatic}s, the run-loop pump -- are exactly the parts a hand-kept
 * copy would fork. So every class file of {@code am.ik.objc} is renamed by one prefix
 * rule ({@code am/ik/objc/} -> {@code <Program>}{@value #OBJC_SUFFIX}), the primitive
 * layer is renamed the same way, and the compiled backend runs the very bytes the
 * interpreter runs. The renamed files are shipped BESIDE the program
 * ({@link ObjcRuntime#classFiles()}, joined into
 * {@link JvmLispCompiler#runtimeClassFiles()}) -- never defined at run time, which a
 * GraalVM native image refuses ({@code .kb/template-class-embedding.md}) -- and named
 * after it, because {@code bind} keeps that program's {@code _apply}. The classes make
 * UPCALLS into the compiled program: a method {@code objc:define-objc-method} defined, a
 * block's function and an {@code objc:on-main} body run through {@code _apply}, handed
 * over by {@code bind(Class)} like the {@code java:} bridge's.
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
	 * Appended to the generated program's internal name to name the primitive layer
	 * ({@link JvmObjcPrimitivesTemplate}).
	 */
	static final String PRIMITIVES_SUFFIX = "$ObjcPrimitives";

	/** The primitive layer's internal (constant-pool) class name before renaming. */
	private static final String PRIMITIVES_INTERNAL_NAME = "am/ik/rontolisp/codegen/jvm/JvmObjcPrimitivesTemplate";

	/** The package prefix of the embedded library, before renaming. */
	private static final String OBJC_INTERNAL_PREFIX = "am/ik/objc/";

	/**
	 * Every class file of {@code am.ik.objc}, nested and anonymous classes included --
	 * there is no way to enumerate a package from the classpath (let alone from inside a
	 * native image), so the list is written down and {@code JvmObjcBaseCompilerTest} pins
	 * it against what the build actually produced. {@code package-info} carries only
	 * annotations and is left behind.
	 *
	 * <p>
	 * The list names what ships; its order no longer matters. It did while the classes
	 * were defined one by one at run time: the verifier loads a {@code catch} clause's
	 * type while defining the class that has it, so {@code ObjcException} had to come
	 * first. Shipped as files, every class is on disk before any of them loads.
	 */
	private static final List<String> OBJC_CLASSES = List.of("ObjcException", "ObjcRaised", "ObjcCatch",
			"ObjcCatch$Asm", "ObjcCatch$Bytes", "MainThread", "MainThread$Slot", "ObjcBlocks", "ObjcBlocks$Body",
			"ObjcBlocks$Entry", "ObjcMethods", "ObjcMethods$Body", "ObjcMethods$Target", "ObjcRuntime", "ObjcRuntime$1",
			"ObjcRuntime$Signature", "ObjcReference", "ObjcReference$Entry", "TypeEncoding", "TypeEncoding$Kind",
			"TypeEncoding$Parser", "TypeEncoding$Type");

	/**
	 * The directory a program's copy of the {@code objc:} foreign registration travels in
	 * ({@link #nativeImageMetadataPath}).
	 */
	private static final String NATIVE_IMAGE_FOREIGN = "rontolisp-objc";

	/**
	 * rontolisp's own {@code objc:} foreign registration, which its binary reads and a
	 * compiled program carries verbatim: the runtime's C downcalls, the closed
	 * {@code objc_msgSend} table and the method and block upcalls.
	 */
	private static final String NATIVE_IMAGE_FOREIGN_RESOURCE = "META-INF/native-image/am.ik.rontolisp/"
			+ NATIVE_IMAGE_FOREIGN + "/reachability-metadata.json";

	/**
	 * The directory a program's registration of the primitive layer's reflective lookups
	 * travels in -- the one file written per program rather than copied.
	 */
	private static final String NATIVE_IMAGE_BRIDGE = "rontolisp-objc-bridge";

	/** The emitted init helper method name. */
	static final String INIT_METHOD = "_objcInit";

	private JvmObjcRuntimeBuilder() {
	}

	/**
	 * The ready-to-emit {@code _objcInit} method, its guard field, and the constant-pool
	 * references the primitive call-site compiler needs ({@code ops} keys: {@code init},
	 * and each primitive's qualified name). The class files that travel beside the
	 * program, and their native-image registration, are keyed by their paths within an
	 * output tree.
	 */
	record ObjcRuntime(Utf8Constant initName, Utf8Constant initDesc, List<Integer> initCode, int maxStack,
			int maxLocals, Utf8Constant initedFieldName, Utf8Constant initedFieldDesc, FieldrefConstant initedField,
			Map<String, MethodrefConstant> ops, Map<String, byte[]> classFiles) {
	}

	/**
	 * The internal name of a program's copy of the primitive layer.
	 * @param programInternalName the generated class's internal (slash-separated) name
	 * @return its internal name, in the program's own package
	 */
	static String primitivesName(String programInternalName) {
		return programInternalName + PRIMITIVES_SUFFIX;
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
	 * Where one of a program's native-image registrations travels: under
	 * {@code META-INF/native-image/}, which native-image reads from every class path
	 * entry (a jar, {@code target/classes}, the directory beside {@code -o X.class}), in
	 * a directory named after the program so two programs in one tree keep two files. An
	 * image is built from the user's output, which holds none of rontolisp's own
	 * {@code META-INF} ({@code JvmGpuRuntimeBuilder.nativeImageMetadataPath} is the same
	 * rule for {@code --gpu}).
	 * @param registration {@value #NATIVE_IMAGE_FOREIGN} or {@value #NATIVE_IMAGE_BRIDGE}
	 * @param programInternalName the generated class's internal (slash-separated) name
	 * @return the path within an output tree
	 */
	static String nativeImageMetadataPath(String registration, String programInternalName) {
		return "META-INF/native-image/" + registration + "/" + programInternalName.replace('/', '.')
				+ "/reachability-metadata.json";
	}

	/**
	 * The registration of the two program methods {@link JvmObjcPrimitivesTemplate}'s
	 * {@code bind(Class)} finds by name: {@code _apply}, which every callback runs
	 * through, and {@code _strv}, which renders every string the program built. In an
	 * image without it the lookup of {@code _apply} fails ({@code objc: no _apply
	 * method}) and the first built string dies in
	 * {@code MissingReflectionRegistrationError}. {@code _strv} exists only in a program
	 * with the array runtime; native-image skips a registered method the class does not
	 * declare, and the lookup then answers what it answers on the JVM.
	 */
	private static byte[] bridgeReflection(String programInternalName) {
		return """
				{
				  "reflection": [
				    {
				      "type": "%s",
				      "methods": [
				        { "name": "_apply", "parameterTypes": ["java.lang.Object", "java.lang.Object"] },
				        { "name": "_strv", "parameterTypes": ["java.lang.Object"] }
				      ]
				    }
				  ]
				}
				""".formatted(programInternalName.replace('/', '.')).getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Builds the {@code _objcInit} method body, registers the primitive references and
	 * renames the class files that travel beside the program.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param programInternalName the generated class's internal name -- the classes are
	 * named after it and live in its package (their members are package-private)
	 * @return the runtime pieces
	 */
	static ObjcRuntime build(ConstantPool cp, ClassConstant thisClass, String programInternalName) {
		String primitivesName = primitivesName(programInternalName);
		String objcPrefix = objcPrefix(programInternalName);
		Map<String, byte[]> classFiles = new LinkedHashMap<>();
		for (String name : OBJC_CLASSES) {
			classFiles.put(objcPrefix + name + ".class",
					rename(loadResource(OBJC_INTERNAL_PREFIX + name + ".class"), primitivesName, objcPrefix));
		}
		classFiles.put(primitivesName + ".class",
				rename(loadResource(PRIMITIVES_INTERNAL_NAME + ".class"), primitivesName, objcPrefix));
		// What an image built from the output needs to serve the classes above: the
		// foreign shapes they bind, and the two program methods bind(Class) finds by
		// name. Without them the image refuses every send and runs no callback.
		classFiles.put(nativeImageMetadataPath(NATIVE_IMAGE_FOREIGN, programInternalName),
				loadResource(NATIVE_IMAGE_FOREIGN_RESOURCE));
		classFiles.put(nativeImageMetadataPath(NATIVE_IMAGE_BRIDGE, programInternalName),
				bridgeReflection(programInternalName));

		Utf8Constant initedFieldName = cp.addUtf8("_objcInited");
		Utf8Constant initedFieldDesc = cp.addUtf8("I");
		FieldrefConstant initedField = cp.addFieldref(thisClass, cp.addNameAndType(initedFieldName, initedFieldDesc));

		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		Utf8Constant initName = cp.addUtf8(INIT_METHOD);
		Utf8Constant initDesc = cp.addUtf8("()V");
		ops.put("init", cp.addMethodref(thisClass, cp.addNameAndType(initName, initDesc)));
		// The primitive layer, keyed by the qualified Lisp name it compiles.
		ClassConstant primitivesClass = cp.addClass(cp.addUtf8(primitivesName));
		MethodrefConstant bindPrimitives = cp.addMethodref(primitivesClass,
				cp.addNameAndType(cp.addUtf8("bind"), cp.addUtf8("(Ljava/lang/Class;)V")));
		for (Map.Entry<String, Object[]> entry : JvmObjcPrimitivesCompiler.table().entrySet()) {
			int arity = (Integer) entry.getValue()[0];
			String method = (String) entry.getValue()[1];
			String desc = "(" + "Ljava/lang/Object;".repeat(arity) + ")Ljava/lang/Object;";
			ops.put(entry.getKey(),
					cp.addMethodref(primitivesClass, cp.addNameAndType(cp.addUtf8(method), cp.addUtf8(desc))));
		}

		// --- _objcInit body --------------------------------------------------------
		// if (_objcInited != 0) return;
		List<Integer> code = new ArrayList<>();
		code.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		int guardPos = code.size();
		code.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(code, 0);
		// <Program>$ObjcPrimitives.bind(<Program>.class) -- it loads from the program's
		// own class loader like any other class beside it.
		JvmRuntimeBuilder.emitLdc(code, thisClass.index());
		code.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(code, bindPrimitives.index());
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
	 * the generated program. Both renames run over every file: the primitive layer names
	 * the library, the library names itself, and a name that is not in a given file
	 * simply does not match. The library's rename is a PREFIX rule, so a nested class
	 * ({@code am/ik/objc/ObjcMethods$Body}) follows its outer one without being listed.
	 */
	private static byte[] rename(byte[] classFile, String primitivesName, String objcPrefix) {
		byte[] renamed = JvmJavaRuntimeBuilder.renameClass(classFile, PRIMITIVES_INTERNAL_NAME, primitivesName);
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
