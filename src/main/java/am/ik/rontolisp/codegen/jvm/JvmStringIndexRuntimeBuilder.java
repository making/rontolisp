package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Builds the two helpers every CHARACTER index into a string reads through:
 *
 * <pre>{@code _cpoff(String s, int i) -> int   // UTF-16 offset of character i
 * _scount(String s)        -> int   // character count of the framed content}</pre>
 *
 * <p>
 * A string on this backend is a UTF-16 {@code java.lang.String} framed by quote
 * characters, so its content is {@code [1, length - 1)} and a Lisp character index is a
 * CODE POINT index -- which is not a code-unit index the moment a surrogate pair appears.
 * {@link String#offsetByCodePoints(int, int)} translates one by walking, so a
 * left-to-right scan of one string used to be quadratic
 * ({@code .kb/characters-code-points.md}).
 *
 * <p>
 * The walk is skipped for the strings that cannot need it: <b>a string with no surrogate
 * pair indexes at {@code 1 + i} directly</b>, and that is decided by one comparison,
 * {@code s.codePointCount(1, len - 1) == len - 2}. The probe is itself constant time for
 * a LATIN1-backed string (the JDK returns the range width without looking at it), which
 * is every ASCII and Latin-1 string; for a wider one the answer is remembered, so a scan
 * pays the count once rather than once per character.
 *
 * <p>
 * The memory is two plain static fields holding the last two strings PROVEN to have no
 * surrogate pair. Both properties that makes it safe under the one-virtual-thread-per-
 * request rule ({@code .kb/concurrent-served-requests.md}) are worth naming: a
 * {@code String} is immutable, so the remembered fact can never go stale, and a reference
 * field is written atomically, so a racing thread reads either some earlier string or
 * this one -- never a torn pair. A miss costs a re-probe, nothing else, so neither field
 * needs to be volatile.
 *
 * <p>
 * Two entries rather than one because the character-by-character walks come in pairs: the
 * {@code %string-compare} family steps two strings at once, and a one-entry memory would
 * thrash between them.
 */
final class JvmStringIndexRuntimeBuilder {

	/**
	 * A string-index runtime method body ready to be emitted into the generated class.
	 */
	record StringIndexMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	/** {@code _cpoff(String, int) -> int}: the UTF-16 offset of character {@code i}. */
	static final String OFFSET_METHOD = "_cpoff";

	static final String OFFSET_DESC = "(Ljava/lang/String;I)I";

	/** {@code _scount(String) -> int}: the character count of the framed content. */
	static final String COUNT_METHOD = "_scount";

	static final String COUNT_DESC = "(Ljava/lang/String;)I";

	/**
	 * {@code _charRef(Object, int) -> int}: the code point of character {@code i} of a
	 * string in EITHER representation. A mutable character vector (the length-4-header
	 * array, or the length-7 string view) reads its ELEMENT through {@code _rmGet} --
	 * never rendering the vector into a string, which made
	 * {@code (dotimes (j (length s)) (char s j))} O(n^2) on a {@code make-string} buffer;
	 * anything else takes the immutable path, {@code _cpoff} + {@code codePointAt}. Every
	 * {@code (char s i)} / {@code (schar s i)} site calls this (and {@code (elt s i)}
	 * reaches it through its {@code stringp} arm), so the site is ONE invokestatic
	 * instead of the old {@code _strv} + cast + {@code _cpoff} + {@code codePointAt}
	 * sequence.
	 */
	static final String CHARREF_METHOD = "_charRef";

	static final String CHARREF_DESC = "(Ljava/lang/Object;I)I";

	/** The two "this string has no surrogate pair" slots. */
	static final String[] FIELDS = { "_cpsimple0", "_cpsimple1" };

	static final String FIELD_DESC = "Ljava/lang/String;";

	private JvmStringIndexRuntimeBuilder() {
	}

	static List<StringIndexMethod> build(ConstantPool cp, ClassConstant selfClass, ClassConstant stringClass,
			boolean usesArrays) {
		MethodrefConstant stringLength = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("length"), cp.addUtf8("()I")));
		MethodrefConstant codePointCount = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("codePointCount"), cp.addUtf8("(II)I")));
		MethodrefConstant offsetByCodePoints = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("offsetByCodePoints"), cp.addUtf8("(II)I")));
		Utf8Constant fieldDesc = cp.addUtf8(FIELD_DESC);
		FieldrefConstant slot0 = cp.addFieldref(selfClass, cp.addNameAndType(cp.addUtf8(FIELDS[0]), fieldDesc));
		FieldrefConstant slot1 = cp.addFieldref(selfClass, cp.addNameAndType(cp.addUtf8(FIELDS[1]), fieldDesc));
		return List.of(buildOffset(cp, stringLength, codePointCount, offsetByCodePoints, slot0, slot1),
				buildCount(cp, stringLength, codePointCount, slot0, slot1),
				buildCharRef(cp, selfClass, stringClass, usesArrays));
	}

	// _charRef(o, i): the element read for a mutable character vector (only when the
	// array runtime exists -- a character vector can only come from make-array, which
	// raises the same gate), else _cpoff + codePointAt on the immutable string. A
	// non-string, non-character-vector argument fails the String cast exactly as the
	// old _strv + CHECKCAST site did.
	private static StringIndexMethod buildCharRef(ConstantPool cp, ClassConstant selfClass, ClassConstant stringClass,
			boolean usesArrays) {
		// Slots: 0 = o, 1 = i, 2 = header scratch, 3 = s.
		MethodrefConstant strCpOffset = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(OFFSET_METHOD), cp.addUtf8(OFFSET_DESC)));
		MethodrefConstant strCodePointAt = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("codePointAt"), cp.addUtf8("(I)I")));
		JvmAsm a = new JvmAsm();
		if (usesArrays) {
			ClassConstant arrayListClass = cp.addClass(cp.addUtf8("java/util/ArrayList"));
			ClassConstant objectArrayClass = cp.addClass(cp.addUtf8("[Ljava/lang/Object;"));
			ClassConstant intArrayClass = cp.addClass(cp.addUtf8("[I"));
			MethodrefConstant alSize = cp.addMethodref(arrayListClass,
					cp.addNameAndType(cp.addUtf8("size"), cp.addUtf8("()I")));
			MethodrefConstant alGet = cp.addMethodref(arrayListClass,
					cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("(I)Ljava/lang/Object;")));
			MethodrefConstant rmGet = cp.addMethodref(selfClass, cp.addNameAndType(
					cp.addUtf8(JvmArrayRuntimeBuilder.RM_GET), cp.addUtf8(JvmArrayRuntimeBuilder.RM_GET_DESC)));
			int str = a.label();
			int vec = a.label();
			a.aload(0);
			a.instanceOf(arrayListClass);
			a.branch(Opcode.IFEQ, str);
			a.aload(0);
			a.checkcast(arrayListClass);
			a.invokevirtual(alSize);
			a.branch(Opcode.IFLE, str);
			a.aload(0);
			a.checkcast(arrayListClass);
			a.iconst(0);
			a.invokevirtual(alGet);
			a.astore(2);
			a.aload(2);
			a.instanceOf(objectArrayClass);
			a.branch(Opcode.IFEQ, str);
			// header length 4 = character vector, 7 = string view; both read their
			// element (a boxed CHARACTER int[]{cp}) through _rmGet's displacement walk.
			a.aload(2);
			a.checkcast(objectArrayClass);
			a.arraylength();
			a.iconst(4);
			a.branch(Opcode.IF_ICMPEQ, vec);
			a.aload(2);
			a.checkcast(objectArrayClass);
			a.arraylength();
			a.iconst(7);
			a.branch(Opcode.IF_ICMPNE, str);
			a.bind(vec);
			a.aload(0);
			a.iconst(1);
			a.iload(1);
			a.op(Opcode.IADD);
			a.invokestatic(rmGet);
			a.checkcast(intArrayClass);
			a.iconst(0);
			a.iaload();
			a.ireturn();
			a.bind(str);
		}
		a.aload(0);
		a.checkcast(stringClass);
		a.astore(3);
		a.aload(3);
		a.aload(3);
		a.iload(1);
		a.invokestatic(strCpOffset);
		a.invokevirtual(strCodePointAt);
		a.ireturn();
		return new StringIndexMethod(cp.addUtf8(CHARREF_METHOD), cp.addUtf8(CHARREF_DESC), 4, 4, a.finish());
	}

	// _cpoff(s, i): 1 + i when s has no surrogate pair, else the walk.
	private static StringIndexMethod buildOffset(ConstantPool cp, MethodrefConstant stringLength,
			MethodrefConstant codePointCount, MethodrefConstant offsetByCodePoints, FieldrefConstant slot0,
			FieldrefConstant slot1) {
		// Slots: 0 = s, 1 = i, 2 = len, 3 = count.
		JvmAsm a = new JvmAsm();
		int direct = a.label();
		emitRememberedProbe(a, slot0, slot1, direct);
		// len = s.length(); if (s.codePointCount(1, len - 1) != len - 2) walk.
		int walk = a.label();
		emitCountProbe(a, stringLength, codePointCount, walk, 2, 3);
		emitRemember(a, slot0, slot1);
		a.bind(direct);
		a.iconst(1);
		a.iload(1);
		a.op(Opcode.IADD);
		a.ireturn();
		a.bind(walk);
		a.aload(0);
		a.iconst(1);
		a.iload(1);
		a.invokevirtual(offsetByCodePoints);
		a.ireturn();
		return new StringIndexMethod(cp.addUtf8(OFFSET_METHOD), cp.addUtf8(OFFSET_DESC), 4, 4, a.finish());
	}

	// _scount(s): the character count, which is length - 2 for a string with no
	// surrogate pair -- the same fact, so the same memory answers both.
	private static StringIndexMethod buildCount(ConstantPool cp, MethodrefConstant stringLength,
			MethodrefConstant codePointCount, FieldrefConstant slot0, FieldrefConstant slot1) {
		// Slots: 0 = s, 1 = len, 2 = count.
		JvmAsm a = new JvmAsm();
		int direct = a.label();
		emitRememberedProbe(a, slot0, slot1, direct);
		int wide = a.label();
		emitCountProbe(a, stringLength, codePointCount, wide, 1, 2);
		emitRemember(a, slot0, slot1);
		a.bind(direct);
		a.aload(0);
		a.invokevirtual(stringLength);
		a.iconst(2);
		a.op(Opcode.ISUB);
		a.ireturn();
		a.bind(wide);
		a.iload(2);
		a.ireturn();
		return new StringIndexMethod(cp.addUtf8(COUNT_METHOD), cp.addUtf8(COUNT_DESC), 4, 3, a.finish());
	}

	// if (s == slot0 || s == slot1) goto hit -- the remembered "no surrogate pair" fact.
	private static void emitRememberedProbe(JvmAsm a, FieldrefConstant slot0, FieldrefConstant slot1, int hit) {
		a.aload(0);
		a.getstatic(slot0);
		a.branch(Opcode.IF_ACMPEQ, hit);
		a.aload(0);
		a.getstatic(slot1);
		a.branch(Opcode.IF_ACMPEQ, hit);
	}

	// len = s.length(); count = s.codePointCount(1, len - 1); branch to wide when the
	// two disagree (a surrogate pair is in there), leaving count in its slot; every
	// branch target is reached with an empty operand stack.
	private static void emitCountProbe(JvmAsm a, MethodrefConstant stringLength, MethodrefConstant codePointCount,
			int wide, int lenSlot, int countSlot) {
		a.aload(0);
		a.invokevirtual(stringLength);
		a.istore(lenSlot);
		a.aload(0);
		a.iconst(1);
		a.iload(lenSlot);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.invokevirtual(codePointCount);
		a.istore(countSlot);
		a.iload(countSlot);
		a.iload(lenSlot);
		a.iconst(2);
		a.op(Opcode.ISUB);
		a.branch(Opcode.IF_ICMPNE, wide);
	}

	// slot1 = slot0; slot0 = s -- keep the two most recent proofs.
	private static void emitRemember(JvmAsm a, FieldrefConstant slot0, FieldrefConstant slot1) {
		a.getstatic(slot0);
		a.putstatic(slot1);
		a.aload(0);
		a.putstatic(slot0);
	}

}
