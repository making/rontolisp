package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.compiler.OperandTypes;

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
	record StringIndexMethod(Utf8Entry name, Utf8Entry desc, MethodCode code) {
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

	static List<StringIndexMethod> build(ConstantPool cp, ClassEntry selfClass, ClassEntry stringClass,
			boolean usesArrays) {
		MethodRefEntry stringLength = cp.methodRef(stringClass, "length", "()I");
		MethodRefEntry codePointCount = cp.methodRef(stringClass, "codePointCount", "(II)I");
		MethodRefEntry offsetByCodePoints = cp.methodRef(stringClass, "offsetByCodePoints", "(II)I");
		FieldRefEntry slot0 = cp.fieldRef(selfClass, FIELDS[0], FIELD_DESC);
		FieldRefEntry slot1 = cp.fieldRef(selfClass, FIELDS[1], FIELD_DESC);
		FieldRefEntry wide0 = cp.fieldRef(selfClass, WIDE_FIELDS[0], WIDE_FIELD_DESC);
		FieldRefEntry wide1 = cp.fieldRef(selfClass, WIDE_FIELDS[1], WIDE_FIELD_DESC);
		ClassEntry intArrayClass = cp.classEntry("[I");
		ClassEntry objectClass = cp.classEntry("java/lang/Object");
		MethodRefEntry indexOf = cp.methodRef(selfClass, INDEX_METHOD, INDEX_DESC);
		return List.of(buildOffset(cp, offsetByCodePoints, slot0, slot1, indexOf),
				buildCount(cp, stringLength, slot0, slot1, indexOf), buildIndex(cp, stringLength, codePointCount,
						offsetByCodePoints, slot0, slot1, wide0, wide1, intArrayClass, objectClass, stringClass),
				buildCharRef(cp, selfClass, stringClass, usesArrays));
	}

	// _charRef(o, i): the element read for a mutable character vector (only when the
	// array runtime exists -- a character vector can only come from make-array, which
	// raises the same gate), else _cpoff + codePointAt on the immutable string. A
	// non-string, non-character-vector argument throws the unnamed STRING report, which
	// the site's operator wrapper names (JvmCharCompiler).
	private static StringIndexMethod buildCharRef(ConstantPool cp, ClassEntry selfClass, ClassEntry stringClass,
			boolean usesArrays) {
		// Slots: 0 = o, 1 = i, 2 = header scratch, 3 = s.
		MethodRefEntry strCpOffset = cp.methodRef(selfClass, OFFSET_METHOD, OFFSET_DESC);
		MethodRefEntry strCodePointAt = cp.methodRef(stringClass, "codePointAt", "(I)I");
		MethodCode a = new MethodCode();
		if (usesArrays) {
			ClassEntry arrayListClass = cp.classEntry("java/util/ArrayList");
			ClassEntry objectArrayClass = cp.classEntry("[Ljava/lang/Object;");
			ClassEntry intArrayClass = cp.classEntry("[I");
			MethodRefEntry alSize = cp.methodRef(arrayListClass, "size", "()I");
			MethodRefEntry alGet = cp.methodRef(arrayListClass, "get", "(I)Ljava/lang/Object;");
			MethodRefEntry rmGet = cp.methodRef(selfClass, JvmArrayRuntimeBuilder.RM_GET,
					JvmArrayRuntimeBuilder.RM_GET_DESC);
			MethodCode.Label str = a.newLabel();
			MethodCode.Label vec = a.newLabel();
			a.aload(0);
			a.instanceOf(arrayListClass);
			a.ifeq(str);
			a.aload(0);
			a.checkcast(arrayListClass);
			a.invokevirtual(alSize);
			a.ifle(str);
			a.aload(0);
			a.checkcast(arrayListClass);
			a.loadConstant(0);
			a.invokevirtual(alGet);
			a.astore(2);
			a.aload(2);
			a.instanceOf(objectArrayClass);
			a.ifeq(str);
			// header length 4 = character vector, 7 = string view; both read their
			// element (a boxed CHARACTER int[]{cp}) through _rmGet's displacement walk.
			a.aload(2);
			a.checkcast(objectArrayClass);
			a.arraylength();
			a.loadConstant(4);
			a.if_icmpeq(vec);
			a.aload(2);
			a.checkcast(objectArrayClass);
			a.arraylength();
			a.loadConstant(7);
			a.if_icmpne(str);
			a.labelBinding(vec);
			a.aload(0);
			a.loadConstant(1);
			a.iload(1);
			a.iadd();
			a.invokestatic(rmGet);
			a.checkcast(intArrayClass);
			a.loadConstant(0);
			a.iaload();
			a.ireturn();
			a.labelBinding(str);
		}
		// Anything but a quote-framed String (a symbol is a bare one) is no string: the
		// unnamed STRING report, named CHAR's / SCHAR's by the call site's wrapper.
		MethodCode.Label notString = a.newLabel();
		a.aload(0);
		a.instanceOf(stringClass);
		a.ifeq(notString);
		a.aload(0);
		a.checkcast(stringClass);
		a.astore(3);
		a.aload(3);
		a.ldc(cp.stringEntry("\""));
		a.invokevirtual(cp.methodRef(stringClass, "startsWith", "(Ljava/lang/String;)Z"));
		a.ifeq(notString);
		a.aload(3);
		a.aload(3);
		a.iload(1);
		a.invokestatic(strCpOffset);
		a.invokevirtual(strCodePointAt);
		a.ireturn();
		a.labelBinding(notString);
		a.aload(0);
		a.ldc(cp.stringEntry(OperandTypes.Kind.STRING.name()));
		a.invokestatic(JvmOperandTypeRuntime.self(cp, selfClass, JvmOperandTypeRuntime.TE_RAW,
				JvmOperandTypeRuntime.TE_RAW_DESC));
		a.athrow();
		return new StringIndexMethod(cp.utf8Entry(CHARREF_METHOD), cp.utf8Entry(CHARREF_DESC), a);
	}

	// _cpoff(s, i): 1 + i for a flat string, else the nearest breakpoint plus a walk of
	// at most STRIDE - 1 characters.
	private static StringIndexMethod buildOffset(ConstantPool cp, MethodRefEntry offsetByCodePoints,
			FieldRefEntry slot0, FieldRefEntry slot1, MethodRefEntry indexOf) {
		// Slots: 0 = s, 1 = i, 2 = table, 3 = breakpoint number.
		MethodCode a = new MethodCode();
		MethodCode.Label direct = a.newLabel();
		emitRememberedProbe(a, slot0, slot1, direct);
		a.aload(0);
		a.invokestatic(indexOf);
		a.astore(2);
		a.aload(2);
		a.ifnull(direct);
		// k = i >>> STRIDE_SHIFT; return s.offsetByCodePoints(t[1 + k], i - (k << SHIFT))
		a.iload(1);
		a.loadConstant(STRIDE_SHIFT);
		a.iushr();
		a.istore(3);
		a.aload(0);
		a.aload(2);
		a.loadConstant(1);
		a.iload(3);
		a.iadd();
		a.iaload();
		a.iload(1);
		a.iload(3);
		a.loadConstant(STRIDE_SHIFT);
		a.ishl();
		a.isub();
		a.invokevirtual(offsetByCodePoints);
		a.ireturn();
		a.labelBinding(direct);
		a.loadConstant(1);
		a.iload(1);
		a.iadd();
		a.ireturn();
		return new StringIndexMethod(cp.utf8Entry(OFFSET_METHOD), cp.utf8Entry(OFFSET_DESC), a);
	}

	// _scount(s): length - 2 for a flat string -- the same fact, so the same memory
	// answers both -- else the count the breakpoint table's slot 0 carries.
	private static StringIndexMethod buildCount(ConstantPool cp, MethodRefEntry stringLength, FieldRefEntry slot0,
			FieldRefEntry slot1, MethodRefEntry indexOf) {
		// Slots: 0 = s, 1 = table.
		MethodCode a = new MethodCode();
		MethodCode.Label direct = a.newLabel();
		emitRememberedProbe(a, slot0, slot1, direct);
		a.aload(0);
		a.invokestatic(indexOf);
		a.astore(1);
		a.aload(1);
		a.ifnull(direct);
		a.aload(1);
		a.loadConstant(0);
		a.iaload();
		a.ireturn();
		a.labelBinding(direct);
		a.aload(0);
		a.invokevirtual(stringLength);
		a.loadConstant(2);
		a.isub();
		a.ireturn();
		return new StringIndexMethod(cp.utf8Entry(COUNT_METHOD), cp.utf8Entry(COUNT_DESC), a);
	}

	// _cpidx(s): the remembered breakpoint table, or null once s is proven flat. The
	// slow half of both helpers: the surrogate-pair probe, the one-pass table build and
	// the two memories live here so neither caller repeats them.
	private static StringIndexMethod buildIndex(ConstantPool cp, MethodRefEntry stringLength,
			MethodRefEntry codePointCount, MethodRefEntry offsetByCodePoints, FieldRefEntry slot0, FieldRefEntry slot1,
			FieldRefEntry wide0, FieldRefEntry wide1, ClassEntry intArrayClass, ClassEntry objectClass,
			ClassEntry stringClass) {
		// Slots: 0 = s, 1 = entry, 2 = len, 3 = count, 4 = table, 5 = last breakpoint,
		// 6 = breakpoint number, 7 = code-unit offset.
		MethodCode a = new MethodCode();
		MethodCode.Label miss0 = a.newLabel();
		MethodCode.Label miss1 = a.newLabel();
		MethodCode.Label build = a.newLabel();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		emitWideProbe(a, wide0, intArrayClass, miss0);
		a.labelBinding(miss0);
		emitWideProbe(a, wide1, intArrayClass, miss1);
		a.labelBinding(miss1);
		// len = s.length(); count = s.codePointCount(1, len - 1);
		a.aload(0);
		a.invokevirtual(stringLength);
		a.istore(2);
		a.aload(0);
		a.loadConstant(1);
		a.iload(2);
		a.loadConstant(1);
		a.isub();
		a.invokevirtual(codePointCount);
		a.istore(3);
		a.iload(3);
		a.iload(2);
		a.loadConstant(2);
		a.isub();
		a.if_icmpne(build);
		// Flat: remember the proof and answer "no table".
		emitRememberFlat(a, stringLength, slot0, slot1);
		a.aconst_null();
		a.areturn();
		a.labelBinding(build);
		// last = count >>> STRIDE_SHIFT; t = new int[last + 2]; t[0] = count; t[1] = 1;
		a.iload(3);
		a.loadConstant(STRIDE_SHIFT);
		a.iushr();
		a.istore(5);
		a.iload(5);
		a.loadConstant(2);
		a.iadd();
		a.newarray(TypeKind.INT);
		a.astore(4);
		a.aload(4);
		a.loadConstant(0);
		a.iload(3);
		a.iastore();
		a.aload(4);
		a.loadConstant(1);
		a.loadConstant(1);
		a.iastore();
		a.loadConstant(1);
		a.istore(7);
		a.loadConstant(1);
		a.istore(6);
		// for (k = 1; k <= last; k++) { off = s.offsetByCodePoints(off, STRIDE);
		// t[1 + k] = off; }
		a.labelBinding(loop);
		a.iload(6);
		a.iload(5);
		a.if_icmpgt(done);
		a.aload(0);
		a.iload(7);
		a.loadConstant(STRIDE);
		a.invokevirtual(offsetByCodePoints);
		a.istore(7);
		a.aload(4);
		a.loadConstant(1);
		a.iload(6);
		a.iadd();
		a.iload(7);
		a.iastore();
		a.iinc(6, 1);
		a.goto_(loop);
		a.labelBinding(done);
		// entry = new Object[]{s, t}; wide1 = wide0; wide0 = entry (VOLATILE stores, so
		// the filled table is published with the reference that names it).
		a.loadConstant(2);
		a.anewarray(objectClass);
		a.astore(1);
		a.aload(1);
		a.loadConstant(0);
		a.aload(0);
		a.aastore();
		a.aload(1);
		a.loadConstant(1);
		a.aload(4);
		a.aastore();
		emitRememberWide(a, stringLength, stringClass, wide0, wide1);
		a.aload(4);
		a.areturn();
		return new StringIndexMethod(cp.utf8Entry(INDEX_METHOD), cp.utf8Entry(INDEX_DESC), a);
	}

	// Stores s into whichever flat slot holds the SHORTER string (an empty slot first).
	// Recency is the wrong thing to keep: what the memory buys is the cost of the walk
	// it skips, and that cost is the string's LENGTH. Under the old "shift slot0 into
	// slot1" rule any two short strings evicted a long one -- and a JSON parse produces
	// exactly that, one fresh short string per token interleaved with every index into
	// the document, so the document was re-proven at O(n) every few characters and a
	// 10.8-million-character parse never finished (.kb/string-index-cost.md).
	private static void emitRememberFlat(MethodCode a, MethodRefEntry stringLength, FieldRefEntry slot0,
			FieldRefEntry slot1) {
		MethodCode.Label store0 = a.newLabel();
		MethodCode.Label store1 = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.getstatic(slot0);
		a.ifnull(store0);
		a.getstatic(slot1);
		a.ifnull(store1);
		a.getstatic(slot0);
		a.invokevirtual(stringLength);
		a.getstatic(slot1);
		a.invokevirtual(stringLength);
		a.if_icmpgt(store1);
		a.labelBinding(store0);
		a.aload(0);
		a.putstatic(slot0);
		a.goto_(done);
		a.labelBinding(store1);
		a.aload(0);
		a.putstatic(slot1);
		a.labelBinding(done);
	}

	// The same rule for the breakpoint-table pair, reading each incumbent's length
	// through its entry's string. Local 1 holds the entry to store.
	private static void emitRememberWide(MethodCode a, MethodRefEntry stringLength, ClassEntry stringClass,
			FieldRefEntry wide0, FieldRefEntry wide1) {
		MethodCode.Label store0 = a.newLabel();
		MethodCode.Label store1 = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.getstatic(wide0);
		a.ifnull(store0);
		a.getstatic(wide1);
		a.ifnull(store1);
		emitEntryStringLength(a, wide0, stringClass, stringLength);
		emitEntryStringLength(a, wide1, stringClass, stringLength);
		a.if_icmpgt(store1);
		a.labelBinding(store0);
		a.aload(1);
		a.putstatic(wide0);
		a.goto_(done);
		a.labelBinding(store1);
		a.aload(1);
		a.putstatic(wide1);
		a.labelBinding(done);
	}

	// Pushes ((String) slot[0]).length().
	private static void emitEntryStringLength(MethodCode a, FieldRefEntry slot, ClassEntry stringClass,
			MethodRefEntry stringLength) {
		a.getstatic(slot);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(stringClass);
		a.invokevirtual(stringLength);
	}

	// entry = slot; if (entry != null && entry[0] == s) return (int[]) entry[1]; else
	// fall through to miss with an empty operand stack.
	private static void emitWideProbe(MethodCode a, FieldRefEntry slot, ClassEntry intArrayClass,
			MethodCode.Label miss) {
		a.getstatic(slot);
		a.astore(1);
		a.aload(1);
		a.ifnull(miss);
		a.aload(1);
		a.loadConstant(0);
		a.aaload();
		a.aload(0);
		a.if_acmpne(miss);
		a.aload(1);
		a.loadConstant(1);
		a.aaload();
		a.checkcast(intArrayClass);
		a.areturn();
	}

	// if (s == slot0 || s == slot1) goto hit -- the remembered "no surrogate pair" fact.
	private static void emitRememberedProbe(MethodCode a, FieldRefEntry slot0, FieldRefEntry slot1,
			MethodCode.Label hit) {
		a.aload(0);
		a.getstatic(slot0);
		a.if_acmpeq(hit);
		a.aload(0);
		a.getstatic(slot1);
		a.if_acmpeq(hit);
	}

}
