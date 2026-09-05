package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Builds the three helpers every CHARACTER index into a string reads through:
 *
 * <pre>{@code _cpoff(String s, int i) -> int   // UTF-16 offset of character i
 * _scount(String s)        -> int   // character count of the framed content
 * _cpidx(String s)         -> int[] // the breakpoint table, or null for a flat string}</pre>
 *
 * <p>
 * A string on this backend is a UTF-16 {@code java.lang.String} framed by quote
 * characters, so its content is {@code [1, length - 1)} and a Lisp character index is a
 * CODE POINT index -- which is not a code-unit index the moment a surrogate pair appears.
 * {@link String#offsetByCodePoints(int, int)} translates one by walking, so a
 * left-to-right scan of one string is quadratic unless something is remembered
 * ({@code .kb/string-index-cost.md}).
 *
 * <h2>Two answers, because a string is one of two shapes</h2>
 *
 * <b>Flat</b> -- no surrogate pair anywhere -- is decided by one comparison,
 * {@code s.codePointCount(1, len - 1) == len - 2}, and then character {@code i} is at
 * {@code 1 + i} and the count is {@code len - 2}. The probe is itself constant time for a
 * LATIN1-backed string (the JDK returns the range width without looking at it), which is
 * every ASCII and Latin-1 string; for a wider one the answer is remembered, so a scan
 * pays the count once rather than once per character.
 *
 * <p>
 * <b>Wide</b> -- one surrogate pair is enough -- has no such arithmetic, and used to fall
 * back to {@code offsetByCodePoints(1, i)} on EVERY index with nothing remembered: a
 * 30,721-character string with a single emoji at the front cost 45x its own chunked scan.
 * Such a string gets a BREAKPOINT TABLE instead: {@code t[1 + k]} is the code-unit offset
 * of character {@code k << }{@value #STRIDE_SHIFT}, so an index walks at most
 * {@value #STRIDE} characters from the nearest breakpoint and the cost of an index no
 * longer depends on where it lands. {@code t[0]} carries the character count, which is
 * the same walk's other question. The table is built once, in one pass, and costs
 * {@code count >> }{@value #STRIDE_SHIFT} ints.
 *
 * <h2>The two memories, and how each is published</h2>
 *
 * The flat memory is two plain static fields holding the last two strings PROVEN to have
 * no surrogate pair. Both properties that make it safe under the one-virtual-thread-per-
 * request rule ({@code .kb/concurrent-served-requests.md}) are worth naming: a
 * {@code String} is immutable, so the remembered fact can never go stale, and a reference
 * field is written atomically, so a racing thread reads either some earlier string or
 * this one -- never a torn pair. A miss costs a re-probe, nothing else, so neither field
 * needs to be volatile.
 *
 * <p>
 * The wide memory holds a table as well as a string, and a plain field cannot carry that
 * pair: a reader that saw the {@code Object[]} could still read its second slot as
 * {@code null}, or the table's entries as the zeros they were allocated with, and answer
 * an offset pointing at the opening quote. So those two fields ARE volatile -- the
 * write's release fence is what publishes the filled table, and the read is free on every
 * ordering-strong architecture. It costs nothing on the flat path, which never touches
 * them.
 *
 * <p>
 * Two entries in each rather than one because the character-by-character walks come in
 * pairs: the {@code %string-compare} family steps two strings at once, and a one-entry
 * memory would thrash between them.
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
	 * {@code _cpidx(String) -> int[]}: the breakpoint table of a string that holds a
	 * surrogate pair, or {@code null} when the string is flat (in which case the string
	 * has been remembered in the flat memory on the way out). Both public helpers reach
	 * their slow path through this one, so the probe, the table build and the two
	 * memories are written once.
	 */
	static final String INDEX_METHOD = "_cpidx";

	static final String INDEX_DESC = "(Ljava/lang/String;)[I";

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

	/**
	 * The two breakpoint-table slots, each an {@code Object[]{String, int[]}}. VOLATILE:
	 * see the class comment -- the release fence is what publishes the table's contents.
	 */
	static final String[] WIDE_FIELDS = { "_cpwide0", "_cpwide1" };

	static final String WIDE_FIELD_DESC = "[Ljava/lang/Object;";

	/**
	 * How many characters one breakpoint covers, as a shift. 32 bounds the walk from a
	 * breakpoint while costing one int per 32 characters of a surrogate-bearing string.
	 */
	static final int STRIDE_SHIFT = 5;

	/** The breakpoint spacing in characters ({@code 1 << }{@value #STRIDE_SHIFT}). */
	static final int STRIDE = 1 << STRIDE_SHIFT;

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
		Utf8Constant wideDesc = cp.addUtf8(WIDE_FIELD_DESC);
		FieldrefConstant wide0 = cp.addFieldref(selfClass, cp.addNameAndType(cp.addUtf8(WIDE_FIELDS[0]), wideDesc));
		FieldrefConstant wide1 = cp.addFieldref(selfClass, cp.addNameAndType(cp.addUtf8(WIDE_FIELDS[1]), wideDesc));
		ClassConstant intArrayClass = cp.addClass(cp.addUtf8("[I"));
		ClassConstant objectClass = cp.addClass(cp.addUtf8("java/lang/Object"));
		MethodrefConstant indexOf = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(INDEX_METHOD), cp.addUtf8(INDEX_DESC)));
		return List.of(buildOffset(cp, offsetByCodePoints, slot0, slot1, indexOf),
				buildCount(cp, stringLength, slot0, slot1, indexOf), buildIndex(cp, stringLength, codePointCount,
						offsetByCodePoints, slot0, slot1, wide0, wide1, intArrayClass, objectClass, stringClass),
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

	// _cpoff(s, i): 1 + i for a flat string, else the nearest breakpoint plus a walk of
	// at most STRIDE - 1 characters.
	private static StringIndexMethod buildOffset(ConstantPool cp, MethodrefConstant offsetByCodePoints,
			FieldrefConstant slot0, FieldrefConstant slot1, MethodrefConstant indexOf) {
		// Slots: 0 = s, 1 = i, 2 = table, 3 = breakpoint number.
		JvmAsm a = new JvmAsm();
		int direct = a.label();
		emitRememberedProbe(a, slot0, slot1, direct);
		a.aload(0);
		a.invokestatic(indexOf);
		a.astore(2);
		a.aload(2);
		a.branch(Opcode.IFNULL, direct);
		// k = i >>> STRIDE_SHIFT; return s.offsetByCodePoints(t[1 + k], i - (k << SHIFT))
		a.iload(1);
		a.iconst(STRIDE_SHIFT);
		a.op(Opcode.IUSHR);
		a.istore(3);
		a.aload(0);
		a.aload(2);
		a.iconst(1);
		a.iload(3);
		a.op(Opcode.IADD);
		a.iaload();
		a.iload(1);
		a.iload(3);
		a.iconst(STRIDE_SHIFT);
		a.op(Opcode.ISHL);
		a.op(Opcode.ISUB);
		a.invokevirtual(offsetByCodePoints);
		a.ireturn();
		a.bind(direct);
		a.iconst(1);
		a.iload(1);
		a.op(Opcode.IADD);
		a.ireturn();
		return new StringIndexMethod(cp.addUtf8(OFFSET_METHOD), cp.addUtf8(OFFSET_DESC), 5, 4, a.finish());
	}

	// _scount(s): length - 2 for a flat string -- the same fact, so the same memory
	// answers both -- else the count the breakpoint table's slot 0 carries.
	private static StringIndexMethod buildCount(ConstantPool cp, MethodrefConstant stringLength, FieldrefConstant slot0,
			FieldrefConstant slot1, MethodrefConstant indexOf) {
		// Slots: 0 = s, 1 = table.
		JvmAsm a = new JvmAsm();
		int direct = a.label();
		emitRememberedProbe(a, slot0, slot1, direct);
		a.aload(0);
		a.invokestatic(indexOf);
		a.astore(1);
		a.aload(1);
		a.branch(Opcode.IFNULL, direct);
		a.aload(1);
		a.iconst(0);
		a.iaload();
		a.ireturn();
		a.bind(direct);
		a.aload(0);
		a.invokevirtual(stringLength);
		a.iconst(2);
		a.op(Opcode.ISUB);
		a.ireturn();
		return new StringIndexMethod(cp.addUtf8(COUNT_METHOD), cp.addUtf8(COUNT_DESC), 3, 2, a.finish());
	}

	// _cpidx(s): the remembered breakpoint table, or null once s is proven flat. The
	// slow half of both helpers: the surrogate-pair probe, the one-pass table build and
	// the two memories live here so neither caller repeats them.
	private static StringIndexMethod buildIndex(ConstantPool cp, MethodrefConstant stringLength,
			MethodrefConstant codePointCount, MethodrefConstant offsetByCodePoints, FieldrefConstant slot0,
			FieldrefConstant slot1, FieldrefConstant wide0, FieldrefConstant wide1, ClassConstant intArrayClass,
			ClassConstant objectClass, ClassConstant stringClass) {
		// Slots: 0 = s, 1 = entry, 2 = len, 3 = count, 4 = table, 5 = last breakpoint,
		// 6 = breakpoint number, 7 = code-unit offset.
		JvmAsm a = new JvmAsm();
		int miss0 = a.label();
		int miss1 = a.label();
		int build = a.label();
		int loop = a.label();
		int done = a.label();
		emitWideProbe(a, wide0, intArrayClass, miss0);
		a.bind(miss0);
		emitWideProbe(a, wide1, intArrayClass, miss1);
		a.bind(miss1);
		// len = s.length(); count = s.codePointCount(1, len - 1);
		a.aload(0);
		a.invokevirtual(stringLength);
		a.istore(2);
		a.aload(0);
		a.iconst(1);
		a.iload(2);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.invokevirtual(codePointCount);
		a.istore(3);
		a.iload(3);
		a.iload(2);
		a.iconst(2);
		a.op(Opcode.ISUB);
		a.branch(Opcode.IF_ICMPNE, build);
		// Flat: remember the proof and answer "no table".
		emitRememberFlat(a, stringLength, slot0, slot1);
		a.aconstNull();
		a.areturn();
		a.bind(build);
		// last = count >>> STRIDE_SHIFT; t = new int[last + 2]; t[0] = count; t[1] = 1;
		a.iload(3);
		a.iconst(STRIDE_SHIFT);
		a.op(Opcode.IUSHR);
		a.istore(5);
		a.iload(5);
		a.iconst(2);
		a.op(Opcode.IADD);
		a.newarrayInt();
		a.astore(4);
		a.aload(4);
		a.iconst(0);
		a.iload(3);
		a.iastore();
		a.aload(4);
		a.iconst(1);
		a.iconst(1);
		a.iastore();
		a.iconst(1);
		a.istore(7);
		a.iconst(1);
		a.istore(6);
		// for (k = 1; k <= last; k++) { off = s.offsetByCodePoints(off, STRIDE);
		// t[1 + k] = off; }
		a.bind(loop);
		a.iload(6);
		a.iload(5);
		a.branch(Opcode.IF_ICMPGT, done);
		a.aload(0);
		a.iload(7);
		a.iconst(STRIDE);
		a.invokevirtual(offsetByCodePoints);
		a.istore(7);
		a.aload(4);
		a.iconst(1);
		a.iload(6);
		a.op(Opcode.IADD);
		a.iload(7);
		a.iastore();
		a.iinc(6, 1);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		// entry = new Object[]{s, t}; wide1 = wide0; wide0 = entry (VOLATILE stores, so
		// the filled table is published with the reference that names it).
		a.iconst(2);
		a.anewarray(objectClass);
		a.astore(1);
		a.aload(1);
		a.iconst(0);
		a.aload(0);
		a.aastore();
		a.aload(1);
		a.iconst(1);
		a.aload(4);
		a.aastore();
		emitRememberWide(a, stringLength, stringClass, wide0, wide1);
		a.aload(4);
		a.areturn();
		return new StringIndexMethod(cp.addUtf8(INDEX_METHOD), cp.addUtf8(INDEX_DESC), 5, 8, a.finish());
	}

	// Stores s into whichever flat slot holds the SHORTER string (an empty slot first).
	// Recency is the wrong thing to keep: what the memory buys is the cost of the walk
	// it skips, and that cost is the string's LENGTH. Under the old "shift slot0 into
	// slot1" rule any two short strings evicted a long one -- and a JSON parse produces
	// exactly that, one fresh short string per token interleaved with every index into
	// the document, so the document was re-proven at O(n) every few characters and a
	// 10.8-million-character parse never finished (.kb/string-index-cost.md).
	private static void emitRememberFlat(JvmAsm a, MethodrefConstant stringLength, FieldrefConstant slot0,
			FieldrefConstant slot1) {
		int store0 = a.label();
		int store1 = a.label();
		int done = a.label();
		a.getstatic(slot0);
		a.branch(Opcode.IFNULL, store0);
		a.getstatic(slot1);
		a.branch(Opcode.IFNULL, store1);
		a.getstatic(slot0);
		a.invokevirtual(stringLength);
		a.getstatic(slot1);
		a.invokevirtual(stringLength);
		a.branch(Opcode.IF_ICMPGT, store1);
		a.bind(store0);
		a.aload(0);
		a.putstatic(slot0);
		a.branch(Opcode.GOTO, done);
		a.bind(store1);
		a.aload(0);
		a.putstatic(slot1);
		a.bind(done);
	}

	// The same rule for the breakpoint-table pair, reading each incumbent's length
	// through its entry's string. Local 1 holds the entry to store.
	private static void emitRememberWide(JvmAsm a, MethodrefConstant stringLength, ClassConstant stringClass,
			FieldrefConstant wide0, FieldrefConstant wide1) {
		int store0 = a.label();
		int store1 = a.label();
		int done = a.label();
		a.getstatic(wide0);
		a.branch(Opcode.IFNULL, store0);
		a.getstatic(wide1);
		a.branch(Opcode.IFNULL, store1);
		emitEntryStringLength(a, wide0, stringClass, stringLength);
		emitEntryStringLength(a, wide1, stringClass, stringLength);
		a.branch(Opcode.IF_ICMPGT, store1);
		a.bind(store0);
		a.aload(1);
		a.putstatic(wide0);
		a.branch(Opcode.GOTO, done);
		a.bind(store1);
		a.aload(1);
		a.putstatic(wide1);
		a.bind(done);
	}

	// Pushes ((String) slot[0]).length().
	private static void emitEntryStringLength(JvmAsm a, FieldrefConstant slot, ClassConstant stringClass,
			MethodrefConstant stringLength) {
		a.getstatic(slot);
		a.iconst(0);
		a.aaload();
		a.checkcast(stringClass);
		a.invokevirtual(stringLength);
	}

	// entry = slot; if (entry != null && entry[0] == s) return (int[]) entry[1]; else
	// fall through to miss with an empty operand stack.
	private static void emitWideProbe(JvmAsm a, FieldrefConstant slot, ClassConstant intArrayClass, int miss) {
		a.getstatic(slot);
		a.astore(1);
		a.aload(1);
		a.branch(Opcode.IFNULL, miss);
		a.aload(1);
		a.iconst(0);
		a.aaload();
		a.aload(0);
		a.branch(Opcode.IF_ACMPNE, miss);
		a.aload(1);
		a.iconst(1);
		a.aaload();
		a.checkcast(intArrayClass);
		a.areturn();
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

}
