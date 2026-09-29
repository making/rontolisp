package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;

import am.ik.jvm.AccessFlag;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;

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

	/** The emitted helper: its name/descriptor plus the body. */
	record SecureRandomRuntime(Utf8Entry name, Utf8Entry desc, MethodCode code, Utf8Entry fieldName,
			Utf8Entry fieldDesc) {
	}

	private JvmSecureRandomRuntimeBuilder() {
	}

	static int fieldAccessFlags() {
		return AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC;
	}

	static SecureRandomRuntime build(ConstantPool cp, ClassEntry thisClass, MethodRefEntry longValueOf) {
		Utf8Entry fieldName = cp.utf8Entry(FIELD);
		Utf8Entry fieldDesc = cp.utf8Entry(FIELD_DESC);
		FieldRefEntry field = cp.fieldRef(thisClass, fieldName, fieldDesc);
		ClassEntry secureRandomClass = cp.classEntry("java/security/SecureRandom");
		MethodRefEntry init = cp.methodRef(secureRandomClass, "<init>", "()V");
		MethodRefEntry nextInt = cp.methodRef(secureRandomClass, "nextInt", "(I)I");
		MethodCode code = new MethodCode();
		// if (_secureRandom == null) _secureRandom = new SecureRandom();
		code.getstatic(field);
		MethodCode.Label ifInit = code.newLabel();
		code.ifnonnull(ifInit);
		code.new_(secureRandomClass);
		code.dup();
		code.invokespecial(init);
		code.putstatic(field);
		code.labelBinding(ifInit);
		// return Long.valueOf(_secureRandom.nextInt(256));
		code.getstatic(field);
		code.loadConstant(256);
		code.invokevirtual(nextInt);
		code.i2l();
		code.invokestatic(longValueOf);
		code.areturn();
		return new SecureRandomRuntime(cp.utf8Entry(METHOD), cp.utf8Entry(DESC), code, fieldName, fieldDesc);
	}

}
