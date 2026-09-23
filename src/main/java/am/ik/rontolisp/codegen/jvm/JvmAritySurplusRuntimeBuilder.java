package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.ClosRegistry;

/**
 * Builds the {@code _aritySurplus} runtime helper behind the internal
 * {@code %arity-surplus-message} primitive -- the message of the {@code &optional}
 * surplus-argument check ({@code LambdaLists}) -- and the {@code _arityMissing} helper
 * behind {@code %arity-missing-message}, the message of the destructuring missing-element
 * check:
 *
 * <pre>{@code _aritySurplus(int max, int required, Object rest) -> String}</pre>
 *
 * <pre>{@code _arityMissing(int required, int got) -> String}</pre>
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
	record AritySurplusMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	static final String METHOD = "_aritySurplus";

	static final String DESC = "(IILjava/lang/Object;)Ljava/lang/String;";

	/**
	 * An arity-missing runtime method body ready to be emitted into the generated class.
	 */
	record ArityMissingMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	static final String MISSING_METHOD = "_arityMissing";

	static final String MISSING_DESC = "(II)Ljava/lang/String;";

	private JvmAritySurplusRuntimeBuilder() {
	}

	static AritySurplusMethod build(ConstantPool cp, ClassConstant objectArrayClass) {
		ClassConstant sb = cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
		MethodrefConstant sbInit = cp.addMethodref(sb,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant appendStr = cp.addMethodref(sb,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/StringBuilder;")));
		MethodrefConstant appendInt = cp.addMethodref(sb,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(I)Ljava/lang/StringBuilder;")));
		MethodrefConstant toString = cp.addMethodref(sb,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		// Slots: 0 = max, 1 = the running count (starts at required), 2 = the rest
		// cursor, 3 = the builder.
		JvmAsm a = new JvmAsm();
		a.anew(sb);
		a.dup();
		a.ldcString(cp.addString("\"" + ClosRegistry.ARITY_MESSAGE_PREFIX + ClosRegistry.ARITY_AT_MOST));
		a.invokespecial(sbInit);
		a.astore(3);
		a.aload(3);
		a.iload(0);
		a.invokevirtual(appendInt);
		a.ldcString(cp.addString(ClosRegistry.ARITY_ARGUMENT));
		a.invokevirtual(appendStr);
		a.pop();
		int singular = a.label();
		a.iload(0);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPEQ, singular);
		a.aload(3);
		a.ldcString(cp.addString(ClosRegistry.ARITY_PLURAL));
		a.invokevirtual(appendStr);
		a.pop();
		a.bind(singular);
		int loop = a.label();
		int done = a.label();
		a.bind(loop);
		a.aload(2);
		a.branch(Opcode.IFNULL, done);
		a.iinc(1, 1);
		a.aload(2);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.astore(2);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		a.aload(3);
		a.ldcString(cp.addString(ClosRegistry.ARITY_MESSAGE_INFIX));
		a.invokevirtual(appendStr);
		a.iload(1);
		a.invokevirtual(appendInt);
		a.ldcString(cp.addString("\""));
		a.invokevirtual(appendStr);
		a.invokevirtual(toString);
		a.areturn();
		return new AritySurplusMethod(cp.addUtf8(METHOD), cp.addUtf8(DESC), 3, 4, a.finish());
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
		ClassConstant sb = cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
		MethodrefConstant sbInit = cp.addMethodref(sb,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant appendStr = cp.addMethodref(sb,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/StringBuilder;")));
		MethodrefConstant appendInt = cp.addMethodref(sb,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(I)Ljava/lang/StringBuilder;")));
		MethodrefConstant toString = cp.addMethodref(sb,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		// Slots: 0 = required, 1 = got, 2 = the builder.
		JvmAsm a = new JvmAsm();
		a.anew(sb);
		a.dup();
		a.ldcString(cp.addString("\"" + ClosRegistry.ARITY_MESSAGE_PREFIX + ClosRegistry.ARITY_AT_LEAST));
		a.invokespecial(sbInit);
		a.astore(2);
		a.aload(2);
		a.iload(0);
		a.invokevirtual(appendInt);
		a.ldcString(cp.addString(ClosRegistry.ARITY_ARGUMENT));
		a.invokevirtual(appendStr);
		a.pop();
		int singular = a.label();
		a.iload(0);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPEQ, singular);
		a.aload(2);
		a.ldcString(cp.addString(ClosRegistry.ARITY_PLURAL));
		a.invokevirtual(appendStr);
		a.pop();
		a.bind(singular);
		a.aload(2);
		a.ldcString(cp.addString(ClosRegistry.ARITY_MESSAGE_INFIX));
		a.invokevirtual(appendStr);
		a.iload(1);
		a.invokevirtual(appendInt);
		a.ldcString(cp.addString("\""));
		a.invokevirtual(appendStr);
		a.invokevirtual(toString);
		a.areturn();
		return new ArityMissingMethod(cp.addUtf8(MISSING_METHOD), cp.addUtf8(MISSING_DESC), 3, 3, a.finish());
	}

}
