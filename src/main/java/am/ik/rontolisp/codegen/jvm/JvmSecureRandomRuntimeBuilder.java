package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.AccessFlag;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Builds the {@code _randomByte} runtime helper backing the internal
 * {@code rontolisp::%random-byte} primitive: one cryptographically strong byte (0-255) as
 * a boxed {@code Long}, drawn from a lazily created {@code java.security.SecureRandom}
 * held in the static {@code _secureRandom} field.
 *
 * <p>
 * Emitted ONLY when the program references the primitive (like the socket and fetch
 * runtimes), so a program that does not ask for cryptographic entropy keeps
 * byte-identical output and never loads {@code java.security} classes. The generator is
 * created once per process and reused: a fresh {@code SecureRandom} per byte would reseed
 * from the OS on every call.
 */
final class JvmSecureRandomRuntimeBuilder {

	static final String FIELD = "_secureRandom";

	static final String FIELD_DESC = "Ljava/security/SecureRandom;";

	static final String METHOD = "_randomByte";

	static final String DESC = "()Ljava/lang/Object;";

	/** The emitted helper: its name/descriptor plus the code and frame sizes. */
	record SecureRandomRuntime(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code,
			Utf8Constant fieldName, Utf8Constant fieldDesc) {
	}

	private JvmSecureRandomRuntimeBuilder() {
	}

	static int fieldAccessFlags() {
		return AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC;
	}

	static SecureRandomRuntime build(ConstantPool cp, ClassConstant thisClass, MethodrefConstant longValueOf) {
		Utf8Constant fieldName = cp.addUtf8(FIELD);
		Utf8Constant fieldDesc = cp.addUtf8(FIELD_DESC);
		FieldrefConstant field = cp.addFieldref(thisClass, cp.addNameAndType(fieldName, fieldDesc));
		ClassConstant secureRandomClass = cp.addClass(cp.addUtf8("java/security/SecureRandom"));
		MethodrefConstant init = cp.addMethodref(secureRandomClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant nextInt = cp.addMethodref(secureRandomClass,
				cp.addNameAndType(cp.addUtf8("nextInt"), cp.addUtf8("(I)I")));
		List<Integer> code = new ArrayList<>();
		// if (_secureRandom == null) _secureRandom = new SecureRandom();
		code.add(Opcode.GETSTATIC);
		emitU2(code, field.index());
		int ifInitPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		code.add(Opcode.NEW);
		emitU2(code, secureRandomClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, init.index());
		code.add(Opcode.PUTSTATIC);
		emitU2(code, field.index());
		patchBranch(code, ifInitPos, code.size());
		// return Long.valueOf(_secureRandom.nextInt(256));
		code.add(Opcode.GETSTATIC);
		emitU2(code, field.index());
		code.add(Opcode.SIPUSH);
		emitU2(code, 256);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, nextInt.index());
		code.add(Opcode.I2L);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, longValueOf.index());
		code.add(Opcode.ARETURN);
		return new SecureRandomRuntime(cp.addUtf8(METHOD), cp.addUtf8(DESC), 3, 0, code, fieldName, fieldDesc);
	}

	private static void emitU2(List<Integer> code, int value) {
		code.add((value >> 8) & 0xFF);
		code.add(value & 0xFF);
	}

	private static void patchBranch(List<Integer> code, int branchPos, int target) {
		int offset = target - branchPos;
		code.set(branchPos + 1, (offset >> 8) & 0xFF);
		code.set(branchPos + 2, offset & 0xFF);
	}

}
