package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
 * Builds the {@code java:} interop runtime for the generated standalone {@code .class}.
 * Unlike the other runtime builders it does not hand-assemble the interop logic: the
 * logic lives in {@link JavaBridgeTemplate} (plain Java, compiled by the project build),
 * whose bytecode is read from the classpath at compile time, renamed into the default
 * package (the generated program's package -- a {@code Lookup.defineClass(byte[])}
 * requirement) as {@value #BRIDGE_NAME}, base64-encoded, and embedded as string
 * constants. The emitted {@code private static void _javaInit()} decodes and defines the
 * class on first use (guarded by the {@code _javaInited} int field) and hands the
 * program's {@code _apply} callback over via {@code bind(Class)}, so the compiled output
 * stays a single self-contained {@code .class} file. Every {@code java:} call site is
 * preceded by an {@code _javaInit} call; the bridge method references resolve lazily (at
 * their first execution), by which time the class is defined in the program's own class
 * loader.
 */
final class JvmJavaRuntimeBuilder {

	/**
	 * The default-package name the embedded bridge class is defined under at runtime.
	 */
	static final String BRIDGE_NAME = "RontoLispJavaBridge";

	/** The template's internal (constant-pool) class name before renaming. */
	private static final String TEMPLATE_INTERNAL_NAME = "am/ik/rontolisp/codegen/jvm/JavaBridgeTemplate";

	/** The emitted init helper method name. */
	static final String INIT_METHOD = "_javaInit";

	/** Keeps each base64 string constant well under the 65535-byte Utf8 limit. */
	private static final int CHUNK_SIZE = 40000;

	private JvmJavaRuntimeBuilder() {
	}

	/**
	 * The ready-to-emit {@code _javaInit} method, its guard field, and the constant-pool
	 * references the {@code java:} call-site compiler needs ({@code ops} keys:
	 * {@code init}, {@code new}, {@code call}, {@code static}, {@code field},
	 * {@code proxy}).
	 */
	record JavaRuntime(Utf8Constant initName, Utf8Constant initDesc, List<Integer> initCode, int maxStack,
			int maxLocals, Utf8Constant initedFieldName, Utf8Constant initedFieldDesc,
			Map<String, MethodrefConstant> ops) {
	}

	/**
	 * Builds the {@code _javaInit} method body and registers the bridge references.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param stringConcat {@code String.concat(String)}
	 * @return the runtime pieces
	 */
	static JavaRuntime build(ConstantPool cp, ClassConstant thisClass, MethodrefConstant stringConcat) {
		byte[] bridgeBytes = renameClass(loadTemplateBytes(), TEMPLATE_INTERNAL_NAME, BRIDGE_NAME);
		String base64 = Base64.getEncoder().encodeToString(bridgeBytes);
		List<ConstantPool.StringConstant> chunks = new ArrayList<>();
		for (int i = 0; i < base64.length(); i += CHUNK_SIZE) {
			chunks.add(cp.addString(base64.substring(i, Math.min(base64.length(), i + CHUNK_SIZE))));
		}

		Utf8Constant initedFieldName = cp.addUtf8("_javaInited");
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
		MethodrefConstant bind = cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("bind"), cp.addUtf8("(Ljava/lang/Class;)V")));
		String twoArgDesc = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
		String newDesc = "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";
		String callDesc = "(Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";
		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		Utf8Constant initName = cp.addUtf8(INIT_METHOD);
		Utf8Constant initDesc = cp.addUtf8("()V");
		ops.put("init", cp.addMethodref(thisClass, cp.addNameAndType(initName, initDesc)));
		ops.put("new", cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("javaNew"), cp.addUtf8(newDesc))));
		ops.put("call", cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("javaCall"), cp.addUtf8(callDesc))));
		ops.put("static",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("javaStatic"), cp.addUtf8(callDesc))));
		ops.put("field",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("javaField"), cp.addUtf8(twoArgDesc))));
		ops.put("proxy",
				cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("javaProxy"), cp.addUtf8(twoArgDesc))));

		// --- _javaInit body ---
		List<Integer> code = new ArrayList<>();
		// if (_javaInited != 0) return;
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
		// RontoLispJavaBridge.bind(ThisClass.class) -- the bridge class reference
		// resolves here, right after defineClass registered it in this class's loader.
		JvmRuntimeBuilder.emitLdc(code, thisClass.index());
		code.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(code, bind.index());
		// _javaInited = 1
		code.add(Opcode.ICONST_1);
		code.add(Opcode.PUTSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		JvmRuntimeBuilder.patchBranch(code, guardPos, code.size());
		code.add(Opcode.RETURN);

		return new JavaRuntime(initName, initDesc, code, 4, 1, initedFieldName, initedFieldDesc, ops);
	}

	/** Reads the compiled {@link JavaBridgeTemplate} bytecode from the classpath. */
	private static byte[] loadTemplateBytes() {
		try (InputStream in = JvmJavaRuntimeBuilder.class.getResourceAsStream("JavaBridgeTemplate.class")) {
			if (in == null) {
				throw new IllegalStateException("JavaBridgeTemplate.class not found on the classpath");
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Renames a class by rewriting its constant pool: every occurrence of the old
	 * internal name inside a {@code CONSTANT_Utf8} entry (the {@code this_class} name,
	 * self-referencing method owners including lambda implementation handles, and any
	 * descriptor embedding the name) is replaced. Only Utf8 contents change, so all
	 * constant-pool indices -- and therefore the rest of the class file, copied verbatim
	 * -- stay valid. Both names must be plain ASCII.
	 * @param classFile the class file bytes
	 * @param oldInternalName the internal (slash-separated) name to replace
	 * @param newInternalName the replacement internal name
	 * @return the renamed class file bytes
	 */
	static byte[] renameClass(byte[] classFile, String oldInternalName, String newInternalName) {
		byte[] oldBytes = oldInternalName.getBytes(StandardCharsets.US_ASCII);
		byte[] newBytes = newInternalName.getBytes(StandardCharsets.US_ASCII);
		ByteArrayOutputStream out = new ByteArrayOutputStream(classFile.length);
		// magic (u4) + minor/major version (u2+u2) + constant_pool_count (u2)
		out.write(classFile, 0, 10);
		int count = readU2(classFile, 8);
		int pos = 10;
		for (int i = 1; i < count; i++) {
			int tag = classFile[pos] & 0xFF;
			switch (tag) {
				case 1 -> { // CONSTANT_Utf8: length (u2) + bytes
					int len = readU2(classFile, pos + 1);
					byte[] replaced = replace(classFile, pos + 3, len, oldBytes, newBytes);
					out.write(1);
					out.write((replaced.length >> 8) & 0xFF);
					out.write(replaced.length & 0xFF);
					out.write(replaced, 0, replaced.length);
					pos += 3 + len;
				}
				case 7, 8, 16, 19, 20 -> { // Class/String/MethodType/Module/Package
					out.write(classFile, pos, 3);
					pos += 3;
				}
				case 15 -> { // MethodHandle
					out.write(classFile, pos, 4);
					pos += 4;
				}
				case 3, 4, 9, 10, 11, 12, 17, 18 -> { // int/float/refs/NameAndType/Dynamic
					out.write(classFile, pos, 5);
					pos += 5;
				}
				case 5, 6 -> { // long/double take two constant-pool slots
					out.write(classFile, pos, 9);
					pos += 9;
					i++;
				}
				default -> throw new IllegalStateException("Unknown constant pool tag: " + tag);
			}
		}
		out.write(classFile, pos, classFile.length - pos);
		return out.toByteArray();
	}

	private static int readU2(byte[] bytes, int pos) {
		return ((bytes[pos] & 0xFF) << 8) | (bytes[pos + 1] & 0xFF);
	}

	/** Replaces every occurrence of {@code target} in the given region. */
	private static byte[] replace(byte[] source, int offset, int length, byte[] target, byte[] replacement) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(length);
		int i = offset;
		int end = offset + length;
		while (i < end) {
			if (i + target.length <= end && regionMatches(source, i, target)) {
				out.write(replacement, 0, replacement.length);
				i += target.length;
			}
			else {
				out.write(source[i]);
				i++;
			}
		}
		return out.toByteArray();
	}

	private static boolean regionMatches(byte[] source, int pos, byte[] target) {
		for (int i = 0; i < target.length; i++) {
			if (source[pos + i] != target[i]) {
				return false;
			}
		}
		return true;
	}

}
