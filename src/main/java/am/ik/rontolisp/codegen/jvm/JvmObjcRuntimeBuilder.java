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
 * Builds the {@code objc:} runtime for the generated standalone {@code .class}: the
 * {@link JvmGpuRuntimeBuilder} mechanism -- a CLOSURE of embedded classes rather than one
 * flat template -- over {@code am.ik.objc}, plus the two classes the call sites add,
 * {@link JvmObjcTemplate} (the bridge) and {@link JvmObjcHandle} (the value).
 *
 * <p>
 * Why the whole library travels: the parts of the binding that were expensive to get
 * right -- the type-encoding parser, one {@code objc_msgSend} handle per shape, the hop
 * to thread 0 with its re-entrancy rule, the closed set of upcall shapes bound from
 * CONSTANT {@code findStatic}s, the run-loop pump -- are exactly the parts a hand-kept
 * copy would fork. So every class file of {@code am.ik.objc} is renamed by one prefix
 * rule ({@code am/ik/objc/} -> the emitted program's own package plus
 * {@value #OBJC_PREFIX}), the bridge and the handle are renamed the same way, and the
 * compiled backend runs the very bytes the interpreter runs. This is the first embedded
 * blob whose classes make UPCALLS into the compiled program: a method of
 * {@code objc:define-class} runs on thread 0 through {@code _apply}, handed over by
 * {@code bind(Class)} like the {@code java:} bridge's.
 *
 * <p>
 * The emitted {@code private static void _objcInit()} decodes and defines every class on
 * first use (guarded by the {@code _objcInited} int field), then binds the callback;
 * every {@code objc:} call site is preceded by an {@code _objcInit} call, so a bridge
 * method reference resolves only once the class it names exists.
 */
final class JvmObjcRuntimeBuilder {

	/**
	 * The prefix the library's classes are renamed onto ({@code am/ik/objc/X} -> ...X),
	 * relative to the generated program's own package (see {@link #build}).
	 */
	static final String OBJC_PREFIX = "RontoLispObjc";

	/**
	 * The name the embedded bridge is defined under, relative to the generated program's
	 * own package (see {@link #build}).
	 */
	static final String BRIDGE_NAME = "RontoLispObjcBridge";

	/**
	 * The name the embedded value class is defined under, relative to the generated
	 * program's own package.
	 */
	static final String HANDLE_NAME = "RontoLispObjcObject";

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
	 * The order is NOT free, unlike the {@code --gpu} blob's: a sibling reference in a
	 * method body resolves lazily, but the VERIFIER loads a class it has to check
	 * assignability against while the referencing class is being defined -- the type of a
	 * {@code catch} clause must be a {@code Throwable} -- so {@code ObjcException} is
	 * defined before the classes that catch it. (The first cut listed it alphabetically
	 * and every {@code _objcInit} died in {@code defineClass} with
	 * {@code NoClassDefFoundError: RontoLispObjcObjcException}.)
	 */
	private static final List<String> OBJC_CLASSES = List.of("ObjcException", "MainThread", "MainThread$Slot",
			"ObjcClasses", "ObjcClasses$Bound", "ObjcClasses$Method", "ObjcClasses$Shape", "ObjcClasses$Spec",
			"ObjcRuntime", "ObjcRuntime$1", "ObjcRuntime$Out", "ObjcRuntime$Sent", "TypeEncoding", "TypeEncoding$Kind",
			"TypeEncoding$Parser", "TypeEncoding$Type");

	/** The emitted init helper method name. */
	static final String INIT_METHOD = "_objcInit";

	/** The {@code ops} key of the print hook's method reference. */
	static final String PRINT = "print";

	/** Keeps each base64 string constant well under the 65535-byte Utf8 limit. */
	private static final int CHUNK_SIZE = 40000;

	private JvmObjcRuntimeBuilder() {
	}

	/**
	 * The ready-to-emit {@code _objcInit} method, its guard field, and the constant-pool
	 * references the {@code objc:} call-site compiler needs ({@code ops} keys:
	 * {@code init}, {@code class}, {@code send}, {@code define-class}, {@code on-main},
	 * {@code string}, {@code data}, {@code bytes}, {@code address}, {@code objectp},
	 * {@value #PRINT}).
	 */
	record ObjcRuntime(Utf8Constant initName, Utf8Constant initDesc, List<Integer> initCode, int maxStack,
			int maxLocals, Utf8Constant initedFieldName, Utf8Constant initedFieldDesc, FieldrefConstant initedField,
			Map<String, MethodrefConstant> ops) {
	}

	/**
	 * Builds the {@code _objcInit} method body and registers the bridge references.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param stringConcat {@code String.concat(String)}
	 * @param packagePrefix the generated class's package as an internal-name prefix
	 * ({@code ""} for the default package, otherwise e.g. {@code "com/example/"}) --
	 * {@code Lookup.defineClass(byte[])} requires the defined class to share the lookup
	 * class's package, so the whole embedded library is renamed into this one too
	 * @return the runtime pieces
	 */
	static ObjcRuntime build(ConstantPool cp, ClassConstant thisClass, MethodrefConstant stringConcat,
			String packagePrefix) {
		String bridgeName = packagePrefix + BRIDGE_NAME;
		String handleName = packagePrefix + HANDLE_NAME;
		String objcPrefix = packagePrefix + OBJC_PREFIX;
		List<List<ConstantPool.StringConstant>> blobs = new ArrayList<>();
		for (String name : OBJC_CLASSES) {
			blobs.add(chunks(cp,
					rename(loadResource(OBJC_INTERNAL_PREFIX + name + ".class"), bridgeName, handleName, objcPrefix)));
		}
		blobs
			.add(chunks(cp, rename(loadResource(HANDLE_INTERNAL_NAME + ".class"), bridgeName, handleName, objcPrefix)));
		blobs.add(chunks(cp,
				rename(loadResource(TEMPLATE_INTERNAL_NAME + ".class"), bridgeName, handleName, objcPrefix)));

		Utf8Constant initedFieldName = cp.addUtf8("_objcInited");
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
		// One MethodHandles.lookup().defineClass(...) per embedded class, in the list's
		// order (see OBJC_CLASSES: the exception type first, the bridge and the handle
		// last); a reference from a method body resolves lazily, long after all of them
		// are defined.
		for (List<ConstantPool.StringConstant> blob : blobs) {
			code.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(code, lookup.index()); // [lookup]
			code.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(code, getDecoder.index()); // [lookup, decoder]
			emitConcatenated(code, blob, stringConcat); // [lookup, decoder, str]
			code.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(code, decode.index()); // [lookup, bytes]
			code.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(code, defineClass.index()); // [class]
			code.add(Opcode.POP);
		}
		// RontoLispObjcBridge.bind(ThisClass.class) -- the bridge class reference
		// resolves here, right after defineClass registered it in this class's loader.
		JvmRuntimeBuilder.emitLdc(code, thisClass.index());
		code.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(code, bind.index());
		// _objcInited = 1
		code.add(Opcode.ICONST_1);
		code.add(Opcode.PUTSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		JvmRuntimeBuilder.patchBranch(code, guardPos, code.size());
		code.add(Opcode.RETURN);

		// The deepest stack is [lookup, decoder, chunk, chunk] inside a class blob.
		return new ObjcRuntime(initName, initDesc, code, 4, 1, initedFieldName, initedFieldDesc, initedField, ops);
	}

	/** Loads one chunk sequence onto the stack, concatenated back into one string. */
	private static void emitConcatenated(List<Integer> code, List<ConstantPool.StringConstant> chunks,
			MethodrefConstant stringConcat) {
		JvmRuntimeBuilder.emitLdc(code, chunks.get(0).index());
		for (int i = 1; i < chunks.size(); i++) {
			JvmRuntimeBuilder.emitLdc(code, chunks.get(i).index());
			code.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(code, stringConcat.index());
		}
	}

	/** The base64 of a class file, split into Utf8-sized string constants. */
	private static List<ConstantPool.StringConstant> chunks(ConstantPool cp, byte[] classFile) {
		String text = Base64.getEncoder().encodeToString(classFile);
		List<ConstantPool.StringConstant> chunks = new ArrayList<>();
		for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
			chunks.add(cp.addString(text.substring(i, Math.min(text.length(), i + CHUNK_SIZE))));
		}
		return chunks;
	}

	/**
	 * Renames one class file out of its own package and out of {@code am.ik.objc}, into
	 * the generated program's own package. All three renames run over every file: the
	 * bridge names the handle and the library, the library names itself, and a name that
	 * is not in a given file simply does not match. The library's rename is a PREFIX
	 * rule, so a nested class ({@code am/ik/objc/ObjcClasses$Spec}) follows its outer one
	 * without being listed.
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

	/** The class files this builder embeds, for the test that pins the list. */
	static List<String> embeddedObjcClasses() {
		return OBJC_CLASSES;
	}

}
