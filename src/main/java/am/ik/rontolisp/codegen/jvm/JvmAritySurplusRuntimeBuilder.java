package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.ClosRegistry;

/**
 * Builds the {@code _aritySurplus} runtime helper behind the internal
 * {@code %arity-surplus-message} primitive -- the message of the {@code &optional}
 * surplus-argument check ({@code LambdaLists}) -- and the {@code _arityMissing} helper
 * behind {@code %arity-missing-message}, the message of the destructuring missing-element
 * check:
 *
 * <pre>{@code _aritySurplus(String operator, int max, int required, Object rest) -> String}</pre>
 *
 * <pre>{@code _arityMissing(int required, int got) -> String}</pre>
 *
 * <p>
 * The operator is the built-in a wrapper's surplus reports under, or null for
 * {@code Function} (a program's own function), as the dispatchers' missing-argument
 * report names it.
 *
 * <p>
 * Both answer the runtime (quote-framed) string, assembled out of the constants
 * {@link ClosRegistry#aritySurplusMessage} / {@link ClosRegistry#arityMessage} compose.
 * The framing is load-bearing, not cosmetic: a {@code java.lang.String} is a Lisp string
 * on this backend only when quote-framed ({@code JvmStringpCompiler}), and both messages
 * land in a condition's {@code format-control} slot, whose report path funcalls a
 * non-string control. An unframed message (what {@code _arityMsg} answers for the
 * dispatchers, which never re-enters Lisp) printed through {@code princ-to-string} would
 * fail {@code stringp} and be invoked as a function --
 * {@code The function Function expects at least 2 arguments, got 1 is undefined}.
 * Spelling the message in Lisp instead rendered the count with {@code prin1-to-string}
 * over {@code (+ required (length rest))}, which dragged the mutable-string wrap, the
 * generic {@code length} and the code-point helpers into a program that used none of
 * them.
 */
final class JvmAritySurplusRuntimeBuilder {

	/**
	 * An arity-surplus runtime method body ready to be emitted into the generated class.
	 */
	record AritySurplusMethod(Utf8Constant name, Utf8Constant desc, MethodCode code) {
	}

	static final String METHOD = "_aritySurplus";

	static final String DESC = "(Ljava/lang/String;IILjava/lang/Object;)Ljava/lang/String;";

	/**
	 * An arity-missing runtime method body ready to be emitted into the generated class.
	 */
	record ArityMissingMethod(Utf8Constant name, Utf8Constant desc, MethodCode code) {
	}

	static final String MISSING_METHOD = "_arityMissing";

	static final String MISSING_DESC = "(II)Ljava/lang/String;";

	private JvmAritySurplusRuntimeBuilder() {
	}

	static AritySurplusMethod build(ConstantPool cp, ClassEntry objectArrayClass) {
		ClassEntry sb = cp.classEntry("java/lang/StringBuilder");
		MethodRefEntry sbInit = cp.methodRef(sb, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry appendStr = cp.methodRef(sb, "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
		MethodRefEntry appendInt = cp.methodRef(sb, "append", "(I)Ljava/lang/StringBuilder;");
		MethodRefEntry toString = cp.methodRef(sb, "toString", "()Ljava/lang/String;");
		// Slots: 0 = the operator (null: Function), 1 = max, 2 = the running count
		// (starts at required), 3 = the rest cursor, 4 = the builder.
		MethodCode a = new MethodCode();
		MethodCode.Label named = a.newLabel();
		a.aload(0);
		a.ifnonnull(named);
		a.ldc(cp.stringEntry(ClosRegistry.ARITY_ANONYMOUS_OPERATOR));
		a.astore(0);
		a.labelBinding(named);
		a.new_(sb);
		a.dup();
		a.ldc(cp.stringEntry("\""));
		a.invokespecial(sbInit);
		a.astore(4);
		a.aload(4);
		a.aload(0);
		a.invokevirtual(appendStr);
		a.ldc(cp.stringEntry(ClosRegistry.ARITY_VERB + ClosRegistry.ARITY_AT_MOST));
		a.invokevirtual(appendStr);
		a.iload(1);
		a.invokevirtual(appendInt);
		a.ldc(cp.stringEntry(ClosRegistry.ARITY_ARGUMENT));
		a.invokevirtual(appendStr);
		a.pop();
		MethodCode.Label singular = a.newLabel();
		a.iload(1);
		a.loadConstant(1);
		a.if_icmpeq(singular);
		a.aload(4);
		a.ldc(cp.stringEntry(ClosRegistry.ARITY_PLURAL));
		a.invokevirtual(appendStr);
		a.pop();
		a.labelBinding(singular);
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.labelBinding(loop);
		a.aload(3);
		a.ifnull(done);
		a.iinc(2, 1);
		a.aload(3);
		a.checkcast(objectArrayClass);
		a.loadConstant(1);
		a.aaload();
		a.astore(3);
		a.goto_(loop);
		a.labelBinding(done);
		a.aload(4);
		a.ldc(cp.stringEntry(ClosRegistry.ARITY_MESSAGE_INFIX));
		a.invokevirtual(appendStr);
		a.iload(2);
		a.invokevirtual(appendInt);
		a.ldc(cp.stringEntry("\""));
		a.invokevirtual(appendStr);
		a.invokevirtual(toString);
		a.areturn();
		return new AritySurplusMethod(cp.addUtf8(METHOD), cp.addUtf8(DESC), a);
	}

	/**
	 * Builds the {@code _arityMissing} helper: the framed
	 * {@code "Function expects at least REQUIRED argument(s), got GOT"} out of the very
	 * constants {@link ClosRegistry#arityMessage} composes, so the wording cannot drift
	 * from the interpreter's. Both counts arrive as parameters (the check's literals), so
	 * unlike {@link #build} no list is walked.
	 * @param cp the constant pool
	 * @return the method body
	 */
	static ArityMissingMethod buildMissing(ConstantPool cp) {
		ClassEntry sb = cp.classEntry("java/lang/StringBuilder");
		MethodRefEntry sbInit = cp.methodRef(sb, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry appendStr = cp.methodRef(sb, "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
		MethodRefEntry appendInt = cp.methodRef(sb, "append", "(I)Ljava/lang/StringBuilder;");
		MethodRefEntry toString = cp.methodRef(sb, "toString", "()Ljava/lang/String;");
		// Slots: 0 = required, 1 = got, 2 = the builder.
		MethodCode a = new MethodCode();
		a.new_(sb);
		a.dup();
		a.ldc(cp.stringEntry("\"" + ClosRegistry.ARITY_MESSAGE_PREFIX + ClosRegistry.ARITY_AT_LEAST));
		a.invokespecial(sbInit);
		a.astore(2);
		a.aload(2);
		a.iload(0);
		a.invokevirtual(appendInt);
		a.ldc(cp.stringEntry(ClosRegistry.ARITY_ARGUMENT));
		a.invokevirtual(appendStr);
		a.pop();
		MethodCode.Label singular = a.newLabel();
		a.iload(0);
		a.loadConstant(1);
		a.if_icmpeq(singular);
		a.aload(2);
		a.ldc(cp.stringEntry(ClosRegistry.ARITY_PLURAL));
		a.invokevirtual(appendStr);
		a.pop();
		a.labelBinding(singular);
		a.aload(2);
		a.ldc(cp.stringEntry(ClosRegistry.ARITY_MESSAGE_INFIX));
		a.invokevirtual(appendStr);
		a.iload(1);
		a.invokevirtual(appendInt);
		a.ldc(cp.stringEntry("\""));
		a.invokevirtual(appendStr);
		a.invokevirtual(toString);
		a.areturn();
		return new ArityMissingMethod(cp.addUtf8(MISSING_METHOD), cp.addUtf8(MISSING_DESC), a);
	}

}
