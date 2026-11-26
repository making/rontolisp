package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;

import am.ik.jvm.AccessFlag;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;

/**
 * Builds the {@code _argv} runtime helper behind the {@code %host-argv} primitive: the
 * program's argument vector as a Lisp list of strings, argv0 first. The five public
 * {@code uiop/image} names are Lisp over it ({@code uiop-image.lisp}), so
 * {@code (uiop:command-line-arguments)} is this list's rest on the JVM exactly as it is
 * everywhere else.
 *
 * <p>
 * {@code main(String[] args)} carries the user arguments and no argv0, so the PROGRAM is
 * prepended: the jar the class was loaded from, which is the executable file of a program
 * jar ({@code java -jar prog.jar a b}, what deploy finds its data beside), else the CLASS
 * NAME, what stood on the command line ({@code java Prog a b}). Either way the vector has
 * the same SHAPE the other three backends answer. The array itself reaches the helper
 * through the static {@code _argv} field, stored by main's own prologue -- a defun that
 * reads the command line is an ordinary static method with no access to main's locals.
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
	record ArgvRuntime(Utf8Entry name, Utf8Entry desc, MethodCode code, Utf8Entry fieldName, Utf8Entry fieldDesc,
			FieldRefEntry field) {
	}

	private JvmArgvRuntimeBuilder() {
	}

	static int fieldAccessFlags() {
		return AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC;
	}

	static ArgvRuntime build(ConstantPool cp, ClassEntry thisClass, ClassEntry objectClass, MethodRefEntry stringConcat,
			String className) {
		Utf8Entry fieldName = cp.utf8Entry(FIELD);
		Utf8Entry fieldDesc = cp.utf8Entry(FIELD_DESC);
		FieldRefEntry field = cp.fieldRef(thisClass, fieldName, fieldDesc);
		// Runtime strings carry their quotes, argv0 included: the class name is a
		// compile-time constant, so it is minted already quoted.
		StringEntry argv0Str = cp.stringEntry("\"" + className.replace('/', '.') + "\"");
		StringEntry quoteStr = cp.stringEntry("\"");
		StringEntry jarStr = cp.stringEntry(".jar");
		MethodRefEntry protectionDomain = cp.methodRef("java/lang/Class", "getProtectionDomain",
				"()Ljava/security/ProtectionDomain;");
		MethodRefEntry codeSource = cp.methodRef("java/security/ProtectionDomain", "getCodeSource",
				"()Ljava/security/CodeSource;");
		MethodRefEntry location = cp.methodRef("java/security/CodeSource", "getLocation", "()Ljava/net/URL;");
		MethodRefEntry urlPath = cp.methodRef("java/net/URL", "getPath", "()Ljava/lang/String;");
		MethodRefEntry endsWith = cp.methodRef("java/lang/String", "endsWith", "(Ljava/lang/String;)Z");
		MethodRefEntry toUri = cp.methodRef("java/net/URL", "toURI", "()Ljava/net/URI;");
		ClassEntry fileClass = cp.classEntry("java/io/File");
		MethodRefEntry fileInit = cp.methodRef(fileClass, "<init>", "(Ljava/net/URI;)V");
		MethodRefEntry filePath = cp.methodRef(fileClass, "getPath", "()Ljava/lang/String;");

		// Slots: 0=args (String[]), 1=i (int), 2=acc (Object), 3=argv0 (String), 4=url
		MethodCode a = new MethodCode();
		MethodCode.Label noArgv = a.newLabel();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.getstatic(field);
		a.astore(0);
		a.aload(0);
		a.ifnull(noArgv);
		// acc = null; for (i = args.length - 1; i >= 0; i--)
		a.aconst_null();
		a.astore(2);
		a.aload(0);
		a.arraylength();
		a.loadConstant(1);
		a.isub();
		a.istore(1);
		a.labelBinding(loop);
		a.iload(1);
		a.iflt(done);
		// acc = new Object[] { "\"" + args[i] + "\"", acc };
		a.loadConstant(2);
		a.anewarray(objectClass);
		a.dup();
		a.loadConstant(0);
		a.ldc(quoteStr);
		a.aload(0);
		a.iload(1);
		a.aaload();
		a.invokevirtual(stringConcat);
		a.ldc(quoteStr);
		a.invokevirtual(stringConcat);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aload(2);
		a.aastore();
		a.astore(2);
		a.iinc(1, -1);
		a.goto_(loop);
		a.labelBinding(done);
		// argv0 = "<class name>";
		// try {
		// URL url = Prog.class.getProtectionDomain().getCodeSource().getLocation();
		// if (url.getPath().endsWith(".jar"))
		// argv0 = "\"" + new File(url.toURI()).getPath() + "\"";
		// } catch (Exception e) { }
		// A class with no code source (defined from bytes) answers its name.
		a.ldc(argv0Str);
		a.astore(3);
		MethodCode.Label tryStart = a.newBoundLabel();
		a.ldc(thisClass);
		a.invokevirtual(protectionDomain);
		a.invokevirtual(codeSource);
		a.invokevirtual(location);
		a.astore(4);
		a.aload(4);
		a.invokevirtual(urlPath);
		a.ldc(jarStr);
		a.invokevirtual(endsWith);
		MethodCode.Label notJar = a.newLabel();
		a.ifeq(notJar);
		a.ldc(quoteStr);
		a.new_(fileClass);
		a.dup();
		a.aload(4);
		a.invokevirtual(toUri);
		a.invokespecial(fileInit);
		a.invokevirtual(filePath);
		a.invokevirtual(stringConcat);
		a.ldc(quoteStr);
		a.invokevirtual(stringConcat);
		a.astore(3);
		MethodCode.Label tryEnd = a.newBoundLabel();
		a.goto_(notJar);
		MethodCode.Label handler = a.newBoundLabel();
		a.pop();
		a.labelBinding(notJar);
		// return new Object[] { argv0, acc };
		a.loadConstant(2);
		a.anewarray(objectClass);
		a.dup();
		a.loadConstant(0);
		a.aload(3);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aload(2);
		a.aastore();
		a.areturn();
		// No main ran: there is no command line to answer, and nil says so.
		a.labelBinding(noArgv);
		a.aconst_null();
		a.areturn();
		a.exceptionCatch(tryStart, tryEnd, handler, cp.classEntry("java/lang/Exception"));
		return new ArgvRuntime(cp.utf8Entry(METHOD), cp.utf8Entry(DESC), a, fieldName, fieldDesc, field);
	}

}
