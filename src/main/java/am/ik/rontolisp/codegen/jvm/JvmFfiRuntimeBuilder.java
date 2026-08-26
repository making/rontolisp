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
 * Builds the {@code ffi:} runtime for the generated standalone {@code .class}: the
 * {@link JvmObjcRuntimeBuilder} mechanism -- a closure of embedded classes renamed into
 * the emitted program's own package -- over {@code am.ik.ffi}, plus the two classes the
 * call sites add, {@link JvmFfiTemplate} (the bridge) and {@link JvmFfiHandle} (the
 * pointer value).
 *
 * <p>
 * Why the whole library travels: the parts of the binding that were expensive to get
 * right -- the carrier canonicalisation that makes the native-image shape grid finite,
 * one downcall handle per shape, the per-thread {@code errno} capture, the
 * struct-by-value layouts, the upcall dispatcher bound from a CONSTANT
 * {@code findStatic}, the actionable unregistered-shape signal -- are exactly the parts a
 * hand-kept copy would fork. So every class file of {@code am.ik.ffi} is renamed by one
 * prefix rule ({@code am/ik/ffi/} -> the emitted program's own package plus
 * {@value #FFI_PREFIX}), the bridge and the handle are renamed the same way, and the
 * compiled backend runs the very bytes the interpreter runs. Like the {@code objc:} blob,
 * the embedded classes make UPCALLS into the compiled program: an {@code ffi:callback}'s
 * Lisp function runs through {@code _apply}, handed over by {@code bind(Class)}.
 *
 * <p>
 * The emitted {@code private static void _ffiInit()} decodes and defines every class on
 * first use (guarded by the {@code _ffiInited} int field), then binds the callback; every
 * {@code ffi:} call site is preceded by an {@code _ffiInit} call, so a bridge method
 * reference resolves only once the class it names exists.
 */
final class JvmFfiRuntimeBuilder {

	/**
	 * The prefix the library's classes are renamed onto ({@code am/ik/ffi/X} -> ...X),
	 * relative to the generated program's own package (see {@link #build}).
	 */
	static final String FFI_PREFIX = "RontoLispFfi";

	/**
	 * The name the embedded bridge is defined under, relative to the generated program's
	 * own package (see {@link #build}).
	 */
	static final String BRIDGE_NAME = "RontoLispFfiBridge";

	/**
	 * The name the embedded pointer class is defined under, relative to the generated
	 * program's own package.
	 */
	static final String HANDLE_NAME = "RontoLispFfiPointer";

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
	 * The order is NOT free (the {@code JvmObjcRuntimeBuilder} rule): the VERIFIER loads
	 * a class it has to check assignability against while the referencing class is being
	 * defined -- the type of a {@code catch} clause must be a {@code Throwable} -- so
	 * {@code FfiException} is defined before everything that throws or catches it, and
	 * the {@code FfiType} hierarchy before the runtime that operates on it.
	 * {@code FfiRuntime$1} is the {@code $SwitchMap} synthetic of the enum switches.
	 */
	private static final List<String> FFI_CLASSES = List.of("FfiException", "FfiType", "FfiType$Scalar",
			"FfiType$Struct", "FfiRuntime$Callback", "FfiRuntime$CallbackShape", "FfiRuntime$CallRequest",
			"FfiRuntime$PokeRequest", "FfiRuntime$1", "FfiRuntime");

	/** The emitted init helper method name. */
	static final String INIT_METHOD = "_ffiInit";

	/** The {@code ops} key of the print hook's method reference. */
	static final String PRINT = "print";

	/** Keeps each base64 string constant well under the 65535-byte Utf8 limit. */
	private static final int CHUNK_SIZE = 40000;

	private JvmFfiRuntimeBuilder() {
	}

	/**
	 * The ready-to-emit {@code _ffiInit} method, its guard field, and the constant-pool
	 * references the {@code ffi:} call-site compiler needs ({@code ops} keys:
	 * {@code init}, one per verb, {@value #PRINT}).
	 */
	record FfiRuntime(Utf8Constant initName, Utf8Constant initDesc, List<Integer> initCode, int maxStack, int maxLocals,
			Utf8Constant initedFieldName, Utf8Constant initedFieldDesc, FieldrefConstant initedField,
			Map<String, MethodrefConstant> ops) {
	}

	/**
	 * Builds the {@code _ffiInit} method body and registers the bridge references.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param stringConcat {@code String.concat(String)}
	 * @param packagePrefix the generated class's package as an internal-name prefix
	 * ({@code ""} for the default package, otherwise e.g. {@code "com/example/"}) --
	 * {@code Lookup.defineClass(byte[])} requires the defined class to share the lookup
	 * class's package, so the whole embedded library is renamed into this one too
	 * @return the runtime pieces
	 */
	static FfiRuntime build(ConstantPool cp, ClassConstant thisClass, MethodrefConstant stringConcat,
			String packagePrefix) {
		String bridgeName = packagePrefix + BRIDGE_NAME;
		String handleName = packagePrefix + HANDLE_NAME;
		String ffiPrefix = packagePrefix + FFI_PREFIX;
		List<List<ConstantPool.StringConstant>> blobs = new ArrayList<>();
		for (String name : FFI_CLASSES) {
			blobs.add(chunks(cp,
					rename(loadResource(FFI_INTERNAL_PREFIX + name + ".class"), bridgeName, handleName, ffiPrefix)));
		}
		blobs.add(chunks(cp, rename(loadResource(HANDLE_INTERNAL_NAME + ".class"), bridgeName, handleName, ffiPrefix)));
		blobs.add(
				chunks(cp, rename(loadResource(TEMPLATE_INTERNAL_NAME + ".class"), bridgeName, handleName, ffiPrefix)));

		Utf8Constant initedFieldName = cp.addUtf8("_ffiInited");
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
		// One MethodHandles.lookup().defineClass(...) per embedded class, in the list's
		// order (see FFI_CLASSES: the exception type first, the bridge and the handle
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
		// RontoLispFfiBridge.bind(ThisClass.class) -- the bridge class reference
		// resolves here, right after defineClass registered it in this class's loader.
		JvmRuntimeBuilder.emitLdc(code, thisClass.index());
		code.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(code, bind.index());
		// _ffiInited = 1
		code.add(Opcode.ICONST_1);
		code.add(Opcode.PUTSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		JvmRuntimeBuilder.patchBranch(code, guardPos, code.size());
		code.add(Opcode.RETURN);

		// The deepest stack is [lookup, decoder, chunk, chunk] inside a class blob.
		return new FfiRuntime(initName, initDesc, code, 4, 1, initedFieldName, initedFieldDesc, initedField, ops);
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
	 * Renames one class file out of its own package and out of {@code am.ik.ffi}, into
	 * the generated program's own package. All three renames run over every file: the
	 * bridge names the handle and the library, the library names itself, and a name that
	 * is not in a given file simply does not match. The library's rename is a PREFIX
	 * rule, so a nested class ({@code am/ik/ffi/FfiType$Scalar}) follows its outer one
	 * without being listed.
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

	/** The class files this builder embeds, for the test that pins the list. */
	static List<String> embeddedFfiClasses() {
		return FFI_CLASSES;
	}

}
