package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.MethodCode;

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

	/** One emitted helper: its name/descriptor plus the body. */
	record MutexMethod(Utf8Constant name, Utf8Constant desc, MethodCode code) {
	}

	private JvmMutexRuntimeBuilder() {
	}

	static List<MutexMethod> build(ConstantPool cp) {
		ClassEntry lockClass = cp.classEntry("java/util/concurrent/locks/ReentrantLock");
		MethodRefEntry init = cp.methodRef(lockClass, "<init>", "()V");
		MethodRefEntry lock = cp.methodRef(lockClass, "lock", "()V");
		MethodRefEntry unlock = cp.methodRef(lockClass, "unlock", "()V");
		List<MutexMethod> methods = new ArrayList<>();
		// return new ReentrantLock();
		MethodCode newCode = new MethodCode();
		newCode.new_(lockClass);
		newCode.dup();
		newCode.invokespecial(init);
		newCode.areturn();
		methods.add(new MutexMethod(cp.addUtf8(NEW_METHOD), cp.addUtf8(NEW_DESC), newCode));
		// ((ReentrantLock) m).lock(); return m; -- and the unlock twin. unlock() throws
		// IllegalMonitorStateException when this thread does not hold the lock, which is
		// the JVM-side spelling of the interpreter's "not held by this thread" error.
		methods.add(unary(cp, ACQUIRE_METHOD, lockClass, lock));
		methods.add(unary(cp, RELEASE_METHOD, lockClass, unlock));
		return List.copyOf(methods);
	}

	private static MutexMethod unary(ConstantPool cp, String name, ClassEntry lockClass, MethodRefEntry call) {
		MethodCode code = new MethodCode();
		code.aload(0);
		code.checkcast(lockClass);
		code.invokevirtual(call);
		code.aload(0);
		code.areturn();
		return new MutexMethod(cp.addUtf8(name), cp.addUtf8(UNARY_DESC), code);
	}

}
