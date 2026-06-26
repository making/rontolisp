package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Builds the {@code _parseInt} runtime helper for the {@code parse-integer} built-in:
 *
 * <pre>{@code _parseInt(Object sObj, Object radixObj, Object junkObj) -> Object}</pre>
 *
 * <p>
 * {@code sObj} is the quote-wrapped runtime string; {@code radixObj} is a {@code Long}
 * radix (or {@code null} for base 10); {@code junkObj} is the {@code :junk-allowed} flag
 * (non-{@code null} = true). It strips the quotes, skips surrounding whitespace, reads an
 * optional sign, and accumulates digits with {@link Character#digit(char, int)} into a
 * {@code long}. With junk disallowed (the default) trailing non-whitespace signals an
 * error; with junk allowed an empty parse returns {@code null} (nil).
 */
final class JvmParseIntegerRuntimeBuilder {

	/**
	 * A parse-integer runtime method body ready to be emitted into the generated class.
	 */
	record ParseIntMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	private JvmParseIntegerRuntimeBuilder() {
	}

	static final String METHOD = "_parseInt";

	static final String DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static ParseIntMethod build(ConstantPool cp, ClassConstant stringClass, ClassConstant longClass,
			MethodrefConstant longValueOf) {
		MethodrefConstant stringLength = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("length"), cp.addUtf8("()I")));
		MethodrefConstant stringCharAt = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("charAt"), cp.addUtf8("(I)C")));
		MethodrefConstant stringSubstring = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("substring"), cp.addUtf8("(II)Ljava/lang/String;")));
		MethodrefConstant longValue = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J")));
		ClassConstant characterClass = cp.addClass(cp.addUtf8("java/lang/Character"));
		MethodrefConstant isWhitespace = cp.addMethodref(characterClass,
				cp.addNameAndType(cp.addUtf8("isWhitespace"), cp.addUtf8("(C)Z")));
		MethodrefConstant digit = cp.addMethodref(characterClass,
				cp.addNameAndType(cp.addUtf8("digit"), cp.addUtf8("(CI)I")));
		ClassConstant rtExClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		MethodrefConstant rtExInit = cp.addMethodref(rtExClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));

		// Slots: 0=sObj 1=radixObj 2=sawDigit(after junk captured) 3=s 4=len 5=i 6=radix
		// 7=sign 8/9=acc(long) 10=junk 11=ch/digit
		JvmAsm a = new JvmAsm();
		int rDef = a.label();
		int rDone = a.label();
		int junk0 = a.label();
		int jDone = a.label();
		int ws1 = a.label();
		int ws1End = a.label();
		int signDone = a.label();
		int plus = a.label();
		int dLoop = a.label();
		int dEnd = a.label();
		int skipJunk = a.label();
		int ws2 = a.label();
		int ws2End = a.label();
		int haveDigit = a.label();
		int noDigErr = a.label();

		// s = ((String) sObj).substring(1, length - 1)
		a.aload(0);
		a.checkcast(stringClass);
		a.astore(3);
		a.aload(3);
		a.iconst(1);
		a.aload(3);
		a.invokevirtual(stringLength);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.invokevirtual(stringSubstring);
		a.astore(3);
		// len = s.length()
		a.aload(3);
		a.invokevirtual(stringLength);
		a.istore(4);
		// radix = radixObj == null ? 10 : (int) ((Long) radixObj).longValue()
		a.aload(1);
		a.branch(Opcode.IFNULL, rDef);
		a.aload(1);
		a.checkcast(longClass);
		a.invokevirtual(longValue);
		a.op(Opcode.L2I);
		a.istore(6);
		a.branch(Opcode.GOTO, rDone);
		a.bind(rDef);
		a.iconst(10);
		a.istore(6);
		a.bind(rDone);
		// junk = junkObj != null ? 1 : 0
		a.aload(2);
		a.branch(Opcode.IFNULL, junk0);
		a.iconst(1);
		a.istore(10);
		a.branch(Opcode.GOTO, jDone);
		a.bind(junk0);
		a.iconst(0);
		a.istore(10);
		a.bind(jDone);
		// i = 0
		a.iconst(0);
		a.istore(5);
		// skip leading whitespace
		a.bind(ws1);
		a.iload(5);
		a.iload(4);
		a.branch(Opcode.IF_ICMPGE, ws1End);
		a.aload(3);
		a.iload(5);
		a.invokevirtual(stringCharAt);
		a.invokestatic(isWhitespace);
		a.branch(Opcode.IFEQ, ws1End);
		a.iinc(5, 1);
		a.branch(Opcode.GOTO, ws1);
		a.bind(ws1End);
		// sign = 1
		a.iconst(1);
		a.istore(7);
		// optional sign
		a.iload(5);
		a.iload(4);
		a.branch(Opcode.IF_ICMPGE, signDone);
		a.aload(3);
		a.iload(5);
		a.invokevirtual(stringCharAt);
		a.istore(11);
		a.iload(11);
		a.iconst('+');
		a.branch(Opcode.IF_ICMPEQ, plus);
		a.iload(11);
		a.iconst('-');
		a.branch(Opcode.IF_ICMPNE, signDone);
		a.iconst(-1);
		a.istore(7);
		a.bind(plus);
		a.iinc(5, 1);
		a.bind(signDone);
		// acc = 0; sawDigit = 0
		a.op(Opcode.LCONST_0);
		a.op(Opcode.LSTORE);
		a.op0(8);
		a.iconst(0);
		a.istore(2);
		// digit loop
		a.bind(dLoop);
		a.iload(5);
		a.iload(4);
		a.branch(Opcode.IF_ICMPGE, dEnd);
		a.aload(3);
		a.iload(5);
		a.invokevirtual(stringCharAt);
		a.iload(6);
		a.invokestatic(digit);
		a.istore(11);
		a.iload(11);
		a.branch(Opcode.IFLT, dEnd);
		// acc = acc * radix + digit
		a.op(Opcode.LLOAD);
		a.op0(8);
		a.iload(6);
		a.op(Opcode.I2L);
		a.op(Opcode.LMUL);
		a.iload(11);
		a.op(Opcode.I2L);
		a.op(Opcode.LADD);
		a.op(Opcode.LSTORE);
		a.op0(8);
		a.iconst(1);
		a.istore(2);
		a.iinc(5, 1);
		a.branch(Opcode.GOTO, dLoop);
		a.bind(dEnd);
		// if (junk == 0) { skip trailing ws; if (i != len) throw; }
		a.iload(10);
		a.branch(Opcode.IFNE, skipJunk);
		a.bind(ws2);
		a.iload(5);
		a.iload(4);
		a.branch(Opcode.IF_ICMPGE, ws2End);
		a.aload(3);
		a.iload(5);
		a.invokevirtual(stringCharAt);
		a.invokestatic(isWhitespace);
		a.branch(Opcode.IFEQ, ws2End);
		a.iinc(5, 1);
		a.branch(Opcode.GOTO, ws2);
		a.bind(ws2End);
		a.iload(5);
		a.iload(4);
		a.branch(Opcode.IF_ICMPEQ, skipJunk);
		throwRuntime(a, cp, rtExClass, rtExInit, "parse-integer: junk in string");
		a.bind(skipJunk);
		// if (sawDigit == 0) { junk ? return null : throw }
		a.iload(2);
		a.branch(Opcode.IFNE, haveDigit);
		a.iload(10);
		a.branch(Opcode.IFEQ, noDigErr);
		a.aconstNull();
		a.areturn();
		a.bind(noDigErr);
		throwRuntime(a, cp, rtExClass, rtExInit, "parse-integer: no integer in string");
		a.bind(haveDigit);
		// return Long.valueOf((long) sign * acc)
		a.iload(7);
		a.op(Opcode.I2L);
		a.op(Opcode.LLOAD);
		a.op0(8);
		a.op(Opcode.LMUL);
		a.invokestatic(longValueOf);
		a.areturn();

		return new ParseIntMethod(cp.addUtf8(METHOD), cp.addUtf8(DESC), 6, 12, a.finish());
	}

	private static void throwRuntime(JvmAsm a, ConstantPool cp, ClassConstant exClass, MethodrefConstant exInit,
			String message) {
		a.anew(exClass);
		a.dup();
		a.ldcString(cp.addString(message));
		a.invokespecial(exInit);
		a.op(Opcode.ATHROW);
	}

}
