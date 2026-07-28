package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Builds the {@code _mutexNew} / {@code _mutexAcquire} / {@code _mutexRelease} runtime
 * helpers backing {@code rontolisp:make-mutex} and friends: real mutual exclusion for a
 * compiled program that really runs concurrently (one virtual thread per request under
 * {@code rontolisp:http-handler}).
 *
 * <p>
 * The handle IS the {@code java.util.concurrent.locks.ReentrantLock}, flowing through the
 * program as an ordinary {@code Object} value. That is deliberate: an integer handle
 * would need a table, and a table needs a lazily initialized static field -- whose
 * initialization would itself race, which is precisely the bug the primitive exists to
 * fix. A {@code ReentrantLock} rather than an object monitor because {@code with-mutex}
 * lowers to acquire / body / release as three separate calls, which no
 * {@code synchronized} region can express.
 *
 * <p>
 * Emitted ONLY when the program references one of the three primitives (like the socket
 * and SecureRandom runtimes), so a lock-free program keeps byte-identical output. Nothing
 * portable may print or compare a handle: the interpreter hands out an integer index and
 * WASM a constant.
 */
final class JvmMutexRuntimeBuilder {

	static final String NEW_METHOD = "_mutexNew";

	static final String NEW_DESC = "()Ljava/lang/Object;";

	static final String ACQUIRE_METHOD = "_mutexAcquire";

	static final String RELEASE_METHOD = "_mutexRelease";

	static final String UNARY_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/** One emitted helper: its name/descriptor plus the code and frame sizes. */
	record MutexMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	private JvmMutexRuntimeBuilder() {
	}

	static List<MutexMethod> build(ConstantPool cp) {
		ClassConstant lockClass = cp.addClass(cp.addUtf8("java/util/concurrent/locks/ReentrantLock"));
		MethodrefConstant init = cp.addMethodref(lockClass, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant lock = cp.addMethodref(lockClass, cp.addNameAndType(cp.addUtf8("lock"), cp.addUtf8("()V")));
		MethodrefConstant unlock = cp.addMethodref(lockClass,
				cp.addNameAndType(cp.addUtf8("unlock"), cp.addUtf8("()V")));
		List<MutexMethod> methods = new ArrayList<>();
		// return new ReentrantLock();
		List<Integer> newCode = new ArrayList<>();
		newCode.add(Opcode.NEW);
		emitU2(newCode, lockClass.index());
		newCode.add(Opcode.DUP);
		newCode.add(Opcode.INVOKESPECIAL);
		emitU2(newCode, init.index());
		newCode.add(Opcode.ARETURN);
		methods.add(new MutexMethod(cp.addUtf8(NEW_METHOD), cp.addUtf8(NEW_DESC), 2, 0, newCode));
		// ((ReentrantLock) m).lock(); return m; -- and the unlock twin. unlock() throws
		// IllegalMonitorStateException when this thread does not hold the lock, which is
		// the JVM-side spelling of the interpreter's "not held by this thread" error.
		methods.add(unary(cp, ACQUIRE_METHOD, lockClass, lock));
		methods.add(unary(cp, RELEASE_METHOD, lockClass, unlock));
		return List.copyOf(methods);
	}

	private static MutexMethod unary(ConstantPool cp, String name, ClassConstant lockClass, MethodrefConstant call) {
		List<Integer> code = new ArrayList<>();
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, lockClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, call.index());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ARETURN);
		return new MutexMethod(cp.addUtf8(name), cp.addUtf8(UNARY_DESC), 1, 1, code);
	}

	private static void emitU2(List<Integer> code, int value) {
		code.add((value >> 8) & 0xFF);
		code.add(value & 0xFF);
	}

}
