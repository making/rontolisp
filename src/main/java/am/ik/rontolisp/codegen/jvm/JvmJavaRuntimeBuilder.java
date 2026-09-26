package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
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
 * Builds the {@code java:} interop runtime for the generated {@code .class}. Unlike the
 * other runtime builders it does not hand-assemble the interop logic: the logic lives in
 * {@link JavaBridgeTemplate} (plain Java, compiled by the project build), whose bytecode
 * is read from the classpath at compile time and renamed after the generated program
 * ({@code <Program>}{@value #BRIDGE_SUFFIX}, in the program's own package). The renamed
 * class is NOT embedded: it travels as an ordinary class file beside the program
 * ({@link JavaRuntime#classFiles()}, joined into
 * {@link JvmLispCompiler#runtimeClassFiles()} -- beside a {@code .class}, inside a
 * {@code .jar}/{@code .war}), because a GraalVM native image cannot define a class at run
 * time and the former {@code Lookup.defineClass} of an embedded blob made every
 * {@code java:} program fail there. The emitted {@code private static void _javaInit()}
 * only hands the program's {@code _apply} callback over via {@code bind(Class)} (guarded
 * by the {@code _javaInited} int field); every {@code java:} call site is preceded by an
 * {@code _javaInit} call.
 *
 * <p>
 * The bridge is named after the program, not shared, because {@code bind} stores that
 * program's {@code _apply} in a static field: two programs in one package and one class
 * loader (a Maven module's {@code target/classes}) would otherwise overwrite each other's
 * file and callback.
 */
final class JvmJavaRuntimeBuilder {

	/**
	 * Appended to the generated program's internal class name to name its bridge class
	 * (see {@link #bridgeName}).
	 */
	static final String BRIDGE_SUFFIX = "$JavaBridge";

	/** The template's internal (constant-pool) class name before renaming. */
	private static final String TEMPLATE_INTERNAL_NAME = "am/ik/rontolisp/codegen/jvm/JavaBridgeTemplate";

	/** The emitted init helper method name. */
	static final String INIT_METHOD = "_javaInit";

	private JvmJavaRuntimeBuilder() {
	}

	/**
	 * The ready-to-emit {@code _javaInit} method, its guard field, the constant-pool
	 * references the {@code java:} call-site compiler needs ({@code ops} keys:
	 * {@code init}, {@code new}, {@code call}, {@code static}, {@code field},
	 * {@code proxy}), and the bridge class file that travels beside the program, keyed by
	 * its path within an output tree.
	 */
	record JavaRuntime(Utf8Constant initName, Utf8Constant initDesc, List<Integer> initCode, int maxStack,
			int maxLocals, Utf8Constant initedFieldName, Utf8Constant initedFieldDesc,
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
	 * Builds the {@code _javaInit} method body, registers the bridge references and
	 * renames the bridge class file.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param programInternalName the generated class's internal name -- the bridge is
	 * named after it and lives in its package (the bridge methods are package-private)
	 * @return the runtime pieces
	 */
	static JavaRuntime build(ConstantPool cp, ClassConstant thisClass, String programInternalName) {
		String bridgeName = bridgeName(programInternalName);
		byte[] bridgeBytes = renameClass(loadTemplateBytes(), TEMPLATE_INTERNAL_NAME, bridgeName);

		Utf8Constant initedFieldName = cp.addUtf8("_javaInited");
		Utf8Constant initedFieldDesc = cp.addUtf8("I");
		FieldrefConstant initedField = cp.addFieldref(thisClass, cp.addNameAndType(initedFieldName, initedFieldDesc));

		ClassConstant bridgeClass = cp.addClass(cp.addUtf8(bridgeName));
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
		// <Program>$JavaBridge.bind(<Program>.class) -- the bridge loads from the
		// program's own class loader like any other class beside it.
		JvmRuntimeBuilder.emitLdc(code, thisClass.index());
		code.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(code, bind.index());
		// _javaInited = 1
		code.add(Opcode.ICONST_1);
		code.add(Opcode.PUTSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		JvmRuntimeBuilder.patchBranch(code, guardPos, code.size());
		code.add(Opcode.RETURN);

		return new JavaRuntime(initName, initDesc, code, 1, 0, initedFieldName, initedFieldDesc, ops,
				Map.of(bridgeName + ".class", bridgeBytes));
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
