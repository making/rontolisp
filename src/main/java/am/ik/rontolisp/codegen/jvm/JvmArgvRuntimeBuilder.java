package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.AccessFlag;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Builds the {@code _argv} runtime helper behind the {@code %host-argv} primitive: the
 * program's argument vector as a Lisp list of strings, argv0 first. The five public
 * {@code uiop/image} names are Lisp over it ({@code uiop-image.lisp}), so
 * {@code (uiop:command-line-arguments)} is this list's rest on the JVM exactly as it is
 * everywhere else.
 *
 * <p>
 * {@code main(String[] args)} carries the user arguments and no argv0, so the CLASS NAME
 * is prepended: it is what stood on the command line ({@code java Prog a b}), and it is
 * what makes the vector the same SHAPE the other three backends answer. The array itself
 * reaches the helper through the static {@code _argv} field, stored by main's own
 * prologue -- a defun that reads the command line is an ordinary static method with no
 * access to main's locals.
 *
 * <p>
 * The field is null until main runs, and the helper answers nil for it rather than
 * pretending: a {@code rontolisp:jvm-export} library is entered through a typed wrapper,
 * which is a Java call with no command line behind it (its top level runs in
 * {@code <clinit>}, before any main could have stored one). Emitted only for a program
 * that references the primitive, so everything else keeps byte-identical output.
 */
final class JvmArgvRuntimeBuilder {

	static final String FIELD = "_argv";

	static final String FIELD_DESC = "[Ljava/lang/String;";

	static final String METHOD = "_argv";

	static final String DESC = "()Ljava/lang/Object;";

	/** The emitted helper: its name/descriptor plus the code and frame sizes. */
	record ArgvRuntime(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code,
			Utf8Constant fieldName, Utf8Constant fieldDesc, FieldrefConstant field) {
	}

	private JvmArgvRuntimeBuilder() {
	}

	static int fieldAccessFlags() {
		return AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC;
	}

	static ArgvRuntime build(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			MethodrefConstant stringConcat, String className) {
		Utf8Constant fieldName = cp.addUtf8(FIELD);
		Utf8Constant fieldDesc = cp.addUtf8(FIELD_DESC);
		FieldrefConstant field = cp.addFieldref(thisClass, cp.addNameAndType(fieldName, fieldDesc));
		// Runtime strings carry their quotes, argv0 included: the class name is a
		// compile-time constant, so it is minted already quoted.
		ConstantPool.StringConstant argv0Str = cp.addString("\"" + className.replace('/', '.') + "\"");
		ConstantPool.StringConstant quoteStr = cp.addString("\"");

		// Slots: 0=args (String[]), 1=i (int), 2=acc (Object)
		JvmAsm a = new JvmAsm();
		int noArgv = a.label();
		int loop = a.label();
		int done = a.label();
		a.getstatic(field);
		a.astore(0);
		a.aload(0);
		a.branch(Opcode.IFNULL, noArgv);
		// acc = null; for (i = args.length - 1; i >= 0; i--)
		a.aconstNull();
		a.astore(2);
		a.aload(0);
		a.arraylength();
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.istore(1);
		a.bind(loop);
		a.iload(1);
		a.branch(Opcode.IFLT, done);
		// acc = new Object[] { "\"" + args[i] + "\"", acc };
		a.iconst(2);
		a.anewarray(objectClass);
		a.dup();
		a.iconst(0);
		a.ldcString(quoteStr);
		a.aload(0);
		a.iload(1);
		a.aaload();
		a.invokevirtual(stringConcat);
		a.ldcString(quoteStr);
		a.invokevirtual(stringConcat);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aload(2);
		a.aastore();
		a.astore(2);
		a.iinc(1, -1);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		// return new Object[] { "<class name>", acc };
		a.iconst(2);
		a.anewarray(objectClass);
		a.dup();
		a.iconst(0);
		a.ldcString(argv0Str);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aload(2);
		a.aastore();
		a.areturn();
		// No main ran: there is no command line to answer, and nil says so.
		a.bind(noArgv);
		a.aconstNull();
		a.areturn();
		return new ArgvRuntime(cp.addUtf8(METHOD), cp.addUtf8(DESC), 6, 3, a.finish(), fieldName, fieldDesc, field);
	}

}
