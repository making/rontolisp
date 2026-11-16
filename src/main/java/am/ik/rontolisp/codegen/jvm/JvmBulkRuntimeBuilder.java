package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.LongEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;

/**
 * Builds the JVM halves of the primitives that take a whole sequence
 * ({@code .kb/bulk-primitives.md}): {@code %string-split}, {@code %string-join},
 * {@code %string-count}, {@code %sort-by-keys}, and with them {@code %string-search} and
 * {@code %replace-bulk}. Each answers what its caller's own loop answers, or null --
 * nothing decided -- and the loop runs.
 *
 * <pre>{@code _strSearch(needle, s, start1, end1, start2, end2) -> a Long, or null
 * _strCount(needle, s, start, end)                    -> a Long, or null
 * _strSplit(s, separator, limit)                      -> a fresh vector, or null
 * _strJoin(strings, separator)                        -> a String, or null
 * _sortKeys(keys, elements, out, descending)          -> out, or null
 * _bulkCopy(dst, src, start1, start2, n)              -> "T", or null}</pre>
 *
 * <p>
 * The methods read the two representations they serve directly and call nothing of the
 * array runtime, so a program without arrays may carry them: a string is a
 * {@code java.lang.String} framed by quote characters, its content {@code [1, length -
 * 1)} in UTF-16 units; a vector is a {@code java.util.ArrayList} whose slot 0 is its
 * header {@code Object[]{dims, fillPointer, adjustable}} with the elements boxed behind
 * it, or -- a header of length 6 -- unboxed in the {@code long[]} of its last slot,
 * {@code Long.MIN_VALUE} standing for nil ({@link JvmArrayRuntimeBuilder}). A view, a
 * character vector and any other rank decline.
 *
 * <p>
 * A needle is searched for as UTF-16 units ({@code String.indexOf} for its first unit,
 * {@code regionMatches} for the rest), which finds exactly the code point matches when
 * the needle neither begins with a low surrogate nor ends with a high one; one that does
 * declines.
 *
 * <p>
 * The sort reads each key once into a 64-bit word that orders, unsigned, as the key does
 * -- an integer with its sign bit flipped; of a string its first seven units, each below
 * 256, then its length saturating at eight, and from the first unit of 256 or more on
 * every bit set -- complemented when the order is descending. The keys' PLACES are then
 * sorted by their words: by insertion up to {@value #INSERTION_LIMIT} keys, else by a
 * radix sort from the low byte up that skips a byte every key agrees on. Two strings of
 * equal words are equal when the word's low byte says the string ended within seven
 * units; a run of any other equal words is merged by {@code _strOrder}.
 */
final class JvmBulkRuntimeBuilder {

	/** A bulk runtime method body ready to be emitted into the generated class. */
	record BulkMethod(Utf8Entry name, Utf8Entry desc, MethodCode code) {
	}

	private static final String OBJECT = "Ljava/lang/Object;";

	private static final String STRING = "Ljava/lang/String;";

	/** {@code _strSearch}: the {@code %string-search} primitive. */
	static final String SEARCH = "_strSearch";

	static final String SEARCH_DESC = "(" + OBJECT.repeat(6) + ")" + OBJECT;

	/** {@code _strCount}: the {@code %string-count} primitive. */
	static final String COUNT = "_strCount";

	static final String COUNT_DESC = "(" + OBJECT.repeat(4) + ")" + OBJECT;

	/** {@code _strSplit}: the {@code %string-split} primitive. */
	static final String SPLIT = "_strSplit";

	static final String SPLIT_DESC = "(" + OBJECT.repeat(3) + ")" + OBJECT;

	/** {@code _strJoin}: the {@code %string-join} primitive. */
	static final String JOIN = "_strJoin";

	static final String JOIN_DESC = "(" + OBJECT.repeat(2) + ")" + OBJECT;

	/** {@code _sortKeys}: the {@code %sort-by-keys} primitive. */
	static final String SORT = "_sortKeys";

	static final String SORT_DESC = "(" + OBJECT.repeat(4) + ")" + OBJECT;

	/** {@code _bulkCopy}: the {@code %replace-bulk} primitive. */
	static final String COPY = "_bulkCopy";

	static final String COPY_DESC = "(" + OBJECT.repeat(5) + ")" + OBJECT;

	private static final String FRAMED = "_bulkStr";

	private static final String FRAMED_DESC = "(" + OBJECT + ")" + STRING;

	private static final String VECTOR = "_bulkVec";

	private static final String VECTOR_DESC = "(" + OBJECT + ")[" + OBJECT;

	private static final String LENGTH = "_bulkLen";

	private static final String LENGTH_DESC = "([" + OBJECT + ")I";

	private static final String WHOLE = "_bulkWhole";

	private static final String WHOLE_DESC = "(" + STRING + "II)Z";

	private static final String FIND = "_strFind";

	private static final String FIND_DESC = "(" + STRING + "II" + STRING + "II)I";

	private static final String WORD = "_bulkWord";

	private static final String WORD_DESC = "(" + STRING + ")J";

	private static final String MERGE = "_bulkMerge";

	private static final String MERGE_DESC = "(Ljava/util/ArrayList;[I[IIII)V";

	private static final String RADIX = "_bulkRadix";

	private static final String RADIX_DESC = "([J[I[J[I[II)V";

	/** Up to this many keys are sorted by insertion: a radix pass is 256 counters. */
	static final int INSERTION_LIMIT = 32;

	private final ConstantPool cp;

	private final ClassEntry objectClass;

	private final ClassEntry objectArrayClass;

	private final ClassEntry stringClass;

	private final ClassEntry longClass;

	private final ClassEntry longArrayClass;

	private final ClassEntry listClass;

	private final ClassEntry builderClass;

	private final MethodRefEntry stringLength;

	private final MethodRefEntry charAt;

	private final MethodRefEntry longValueOf;

	private final MethodRefEntry longIntValue;

	private final MethodRefEntry longLongValue;

	private final MethodRefEntry listGet;

	private final MethodRefEntry listSet;

	private final MethodRefEntry listAdd;

	private final MethodRefEntry listSize;

	private final MethodRefEntry arraycopy;

	private final MethodRefEntry codePointOffset;

	private final MethodRefEntry framed;

	private final MethodRefEntry vector;

	private final MethodRefEntry length;

	private final MethodRefEntry whole;

	private final MethodRefEntry find;

	private final LongEntry nilSentinel;

	private JvmBulkRuntimeBuilder(ConstantPool cp, ClassEntry selfClass) {
		this.cp = cp;
		this.objectClass = cp.classEntry("java/lang/Object");
		this.objectArrayClass = cp.classEntry("[Ljava/lang/Object;");
		this.stringClass = cp.classEntry("java/lang/String");
		this.longClass = cp.classEntry("java/lang/Long");
		this.longArrayClass = cp.classEntry("[J");
		this.listClass = cp.classEntry("java/util/ArrayList");
		this.builderClass = cp.classEntry("java/lang/StringBuilder");
		this.stringLength = cp.methodRef(this.stringClass, "length", "()I");
		this.charAt = cp.methodRef(this.stringClass, "charAt", "(I)C");
		this.longValueOf = cp.methodRef(this.longClass, "valueOf", "(J)Ljava/lang/Long;");
		this.longIntValue = cp.methodRef(this.longClass, "intValue", "()I");
		this.longLongValue = cp.methodRef(this.longClass, "longValue", "()J");
		this.listGet = cp.methodRef(this.listClass, "get", "(I)" + OBJECT);
		this.listSet = cp.methodRef(this.listClass, "set", "(I" + OBJECT + ")" + OBJECT);
		this.listAdd = cp.methodRef(this.listClass, "add", "(" + OBJECT + ")Z");
		this.listSize = cp.methodRef(this.listClass, "size", "()I");
		this.arraycopy = cp.methodRef(cp.classEntry("java/lang/System"), "arraycopy",
				"(" + OBJECT + "I" + OBJECT + "II)V");
		this.codePointOffset = cp.methodRef(selfClass, JvmStringIndexRuntimeBuilder.OFFSET_METHOD,
				JvmStringIndexRuntimeBuilder.OFFSET_DESC);
		this.framed = cp.methodRef(selfClass, FRAMED, FRAMED_DESC);
		this.vector = cp.methodRef(selfClass, VECTOR, VECTOR_DESC);
		this.length = cp.methodRef(selfClass, LENGTH, LENGTH_DESC);
		this.whole = cp.methodRef(selfClass, WHOLE, WHOLE_DESC);
		this.find = cp.methodRef(selfClass, FIND, FIND_DESC);
		this.nilSentinel = cp.entries().longEntry(JvmArrayRuntimeBuilder.NIL_SENTINEL);
	}

	/**
	 * Builds the bulk runtime methods.
	 * @param cp the constant pool of the generated class
	 * @param selfClass the generated class
	 * @return the methods, each private and static
	 */
	static List<BulkMethod> build(ConstantPool cp, ClassEntry selfClass) {
		JvmBulkRuntimeBuilder b = new JvmBulkRuntimeBuilder(cp, selfClass);
		List<BulkMethod> methods = new ArrayList<>();
		methods.add(b.method(FRAMED, FRAMED_DESC, b.buildFramed()));
		methods.add(b.method(VECTOR, VECTOR_DESC, b.buildVector()));
		methods.add(b.method(LENGTH, LENGTH_DESC, b.buildLength()));
		methods.add(b.method(WHOLE, WHOLE_DESC, b.buildWhole()));
		methods.add(b.method(FIND, FIND_DESC, b.buildFind()));
		methods.add(b.method(SEARCH, SEARCH_DESC, b.buildSearch()));
		methods.add(b.method(COUNT, COUNT_DESC, b.buildCount()));
		methods.add(b.method(SPLIT, SPLIT_DESC, b.buildSplit()));
		methods.add(b.method(JOIN, JOIN_DESC, b.buildJoin()));
		methods.add(b.method(WORD, WORD_DESC, b.buildWord()));
		methods.add(b.method(MERGE, MERGE_DESC, b.buildMerge(selfClass)));
		methods.add(b.method(RADIX, RADIX_DESC, b.buildRadix()));
		methods.add(b.method(SORT, SORT_DESC, b.buildSort(selfClass)));
		methods.add(b.method(COPY, COPY_DESC, b.buildCopy()));
		return methods;
	}

	private BulkMethod method(String name, String desc, MethodCode code) {
		return new BulkMethod(this.cp.utf8Entry(name), this.cp.utf8Entry(desc), code);
	}

	// _bulkStr(o): o when it is an immutable string -- a String of two units or more
	// that begins with the framing quote (a symbol is the other String) -- else null.
	private MethodCode buildFramed() {
		// Slots: 0 = o, 1 = s.
		MethodCode a = new MethodCode();
		MethodCode.Label no = a.newLabel();
		a.aload(0).instanceOf(this.stringClass).ifeq(no);
		a.aload(0).checkcast(this.stringClass).astore(1);
		a.aload(1).invokevirtual(this.stringLength).loadConstant(2).if_icmplt(no);
		a.aload(1).loadConstant(0).invokevirtual(this.charAt).loadConstant(34).if_icmpne(no);
		a.aload(1).areturn();
		a.labelBinding(no);
		a.aconst_null().areturn();
		return a;
	}

	// _bulkVec(v): the header of v when it is a vector with storage of its own -- an
	// ArrayList whose slot 0 is an Object[] of length 3 (boxed elements), 5 with no
	// target in slot 3 (boxed, an element type remembered) or 6 (a long[] in slot 5),
	// and one dimension -- else null. A host ArrayList of a java: program fails the
	// same test _jlarr makes.
	private MethodCode buildVector() {
		// Slots: 0 = v, 1 = the list, 2 = its slot 0, 3 = the header, 4 = its length.
		MethodCode a = new MethodCode();
		MethodCode.Label no = a.newLabel();
		MethodCode.Label boxed = a.newLabel();
		MethodCode.Label packed = a.newLabel();
		a.aload(0).instanceOf(this.listClass).ifeq(no);
		a.aload(0).checkcast(this.listClass).astore(1);
		a.aload(1).invokevirtual(this.listSize).ifeq(no);
		a.aload(1).loadConstant(0).invokevirtual(this.listGet).astore(2);
		a.aload(2).instanceOf(this.objectArrayClass).ifeq(no);
		a.aload(2).checkcast(this.objectArrayClass).astore(3);
		a.aload(3).arraylength().istore(4);
		a.iload(4).loadConstant(3).if_icmpeq(boxed);
		a.iload(4).loadConstant(6).if_icmpeq(packed);
		a.iload(4).loadConstant(5).if_icmpne(no);
		a.aload(3).loadConstant(3).aaload().ifnonnull(no);
		a.goto_(boxed);
		a.labelBinding(packed);
		a.aload(3).loadConstant(5).aaload().instanceOf(this.longArrayClass).ifeq(no);
		a.labelBinding(boxed);
		a.aload(3).loadConstant(0).aaload().instanceOf(this.objectArrayClass).ifeq(no);
		a.aload(3).loadConstant(0).aaload().checkcast(this.objectArrayClass).arraylength();
		a.loadConstant(1).if_icmpne(no);
		a.aload(3).areturn();
		a.labelBinding(no);
		a.aconst_null().areturn();
		return a;
	}

	// _bulkLen(h): the element count of the vector whose header h is -- its fill
	// pointer, else its one dimension.
	private MethodCode buildLength() {
		MethodCode a = new MethodCode();
		MethodCode.Label dimension = a.newLabel();
		a.aload(0).loadConstant(1).aaload().ifnull(dimension);
		a.aload(0).loadConstant(1).aaload().checkcast(this.longClass).invokevirtual(this.longIntValue).ireturn();
		a.labelBinding(dimension);
		a.aload(0).loadConstant(0).aaload().checkcast(this.objectArrayClass).loadConstant(0).aaload();
		a.checkcast(this.longClass).invokevirtual(this.longIntValue).ireturn();
		return a;
	}

	// _bulkWhole(needle, from, m): whether the m units of needle from FROM begin and end
	// at a character: the first is no low surrogate and the last no high one. A match
	// of such units is a match of code points.
	private MethodCode buildWhole() {
		MethodCode a = new MethodCode();
		MethodCode.Label no = a.newLabel();
		a.aload(0).iload(1).invokevirtual(this.charAt);
		a.loadConstant(this.cp, 0xFC00).iand().loadConstant(this.cp, 0xDC00).if_icmpeq(no);
		a.aload(0).iload(1).iload(2).iadd().loadConstant(1).isub().invokevirtual(this.charAt);
		a.loadConstant(this.cp, 0xFC00).iand().loadConstant(this.cp, 0xD800).if_icmpeq(no);
		a.loadConstant(1).ireturn();
		a.labelBinding(no);
		a.loadConstant(0).ireturn();
		return a;
	}

	// _strFind(s, lo, hi, needle, from, m): the least unit offset r of s, lo <= r and
	// r + m <= hi, where the m units of needle from FROM stand, or -1. m is positive.
	private MethodCode buildFind() {
		// Slots: 0 = s, 1 = lo, 2 = hi, 3 = needle, 4 = from, 5 = m, 6 = the first
		// unit, 7 = the last offset a match may start at, 8 = r.
		MethodRefEntry indexOf = this.cp.methodRef(this.stringClass, "indexOf", "(II)I");
		MethodRefEntry regionMatches = this.cp.methodRef(this.stringClass, "regionMatches", "(I" + STRING + "II)Z");
		MethodCode a = new MethodCode();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label found = a.newLabel();
		MethodCode.Label none = a.newLabel();
		a.aload(3).iload(4).invokevirtual(this.charAt).istore(6);
		a.iload(2).iload(5).isub().istore(7);
		a.iload(1).istore(8);
		a.labelBinding(loop);
		a.iload(8).iload(7).if_icmpgt(none);
		a.aload(0).iload(6).iload(8).invokevirtual(indexOf).istore(8);
		a.iload(8).iflt(none);
		a.iload(8).iload(7).if_icmpgt(none);
		a.aload(0).iload(8).loadConstant(1).iadd().aload(3).iload(4).loadConstant(1).iadd();
		a.iload(5).loadConstant(1).isub().invokevirtual(regionMatches).ifne(found);
		a.iinc(8, 1);
		a.goto_(loop);
		a.labelBinding(found);
		a.iload(8).ireturn();
		a.labelBinding(none);
		a.loadConstant(-1).ireturn();
		return a;
	}

	// Stores the int of the Long in local FROM into local TO.
	private void emitUnbox(MethodCode a, int from, int to) {
		a.aload(from).checkcast(this.longClass).invokevirtual(this.longIntValue).istore(to);
	}

	// _strSearch(needle, s, start1, end1, start2, end2): the character index of the
	// leftmost match at or after start2 that ends by end2, -1 when there is none; null
	// for an operand that is no immutable string or a bound that is no fixnum (an end
	// may be nil: the length). The answer is start2 plus the characters passed, which a
	// Latin-1 string counts without a walk.
	private MethodCode buildSearch() {
		// Slots: 0..5 = the arguments, 6 = needle, 7 = s, 8 = the needle's first unit
		// offset, 9 = its end, 10 = lo, 11 = hi, 12 = m, 13 = r, 14 = start2.
		MethodRefEntry codePointCount = this.cp.methodRef(this.stringClass, "codePointCount", "(II)I");
		MethodCode a = new MethodCode();
		MethodCode.Label nil = a.newLabel();
		MethodCode.Label miss = a.newLabel();
		MethodCode.Label nonEmpty = a.newLabel();
		a.aload(0).invokestatic(this.framed).astore(6);
		a.aload(6).ifnull(nil);
		a.aload(1).invokestatic(this.framed).astore(7);
		a.aload(7).ifnull(nil);
		a.aload(2).instanceOf(this.longClass).ifeq(nil);
		a.aload(4).instanceOf(this.longClass).ifeq(nil);
		emitEndOffset(a, 3, 6, 9, nil);
		emitUnbox(a, 2, 8);
		a.aload(6).iload(8).invokestatic(this.codePointOffset).istore(8);
		emitUnbox(a, 4, 14);
		a.aload(7).iload(14).invokestatic(this.codePointOffset).istore(10);
		emitEndOffset(a, 5, 7, 11, nil);
		a.iload(9).iload(8).isub().istore(12);
		a.iload(12).ifgt(nonEmpty);
		// an empty needle stands at start2, when that is no later than the end
		a.iload(10).iload(11).if_icmpgt(miss);
		a.aload(4).areturn();
		a.labelBinding(nonEmpty);
		a.aload(6).iload(8).iload(12).invokestatic(this.whole).ifeq(nil);
		a.aload(7).iload(10).iload(11).aload(6).iload(8).iload(12).invokestatic(this.find).istore(13);
		a.iload(13).iflt(miss);
		a.iload(14).aload(7).iload(10).iload(13).invokevirtual(codePointCount).iadd().i2l();
		a.invokestatic(this.longValueOf).areturn();
		a.labelBinding(miss);
		a.loadConstant(-1).i2l().invokestatic(this.longValueOf).areturn();
		a.labelBinding(nil);
		a.aconst_null().areturn();
		return a;
	}

	// Stores into local TO the unit offset an end bound names in the string of local
	// STRING: the closing quote's when the bound of local BOUND is nil, else that of
	// the character index it holds; a bound that is neither jumps to NIL.
	private void emitEndOffset(MethodCode a, int bound, int string, int to, MethodCode.Label nil) {
		MethodCode.Label whole = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aload(bound).ifnull(whole);
		a.aload(bound).instanceOf(this.longClass).ifeq(nil);
		a.aload(string).aload(bound).checkcast(this.longClass).invokevirtual(this.longIntValue);
		a.invokestatic(this.codePointOffset).istore(to);
		a.goto_(done);
		a.labelBinding(whole);
		a.aload(string).invokevirtual(this.stringLength).loadConstant(1).isub().istore(to);
		a.labelBinding(done);
	}

	// _strCount(needle, s, start, end): how many occurrences of needle that do not
	// overlap, leftmost first, lie between the character indices start and end; null
	// for an operand that is no immutable string, an empty needle or a bound that is no
	// fixnum.
	private MethodCode buildCount() {
		// Slots: 0..3 = the arguments, 4 = needle, 5 = s, 6 = m, 7 = lo, 8 = hi,
		// 9 = the count, 10 = r.
		MethodCode a = new MethodCode();
		MethodCode.Label nil = a.newLabel();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aload(0).invokestatic(this.framed).astore(4);
		a.aload(4).ifnull(nil);
		a.aload(1).invokestatic(this.framed).astore(5);
		a.aload(5).ifnull(nil);
		a.aload(2).instanceOf(this.longClass).ifeq(nil);
		a.aload(3).instanceOf(this.longClass).ifeq(nil);
		a.aload(4).invokevirtual(this.stringLength).loadConstant(2).isub().istore(6);
		a.iload(6).ifeq(nil);
		a.aload(4).loadConstant(1).iload(6).invokestatic(this.whole).ifeq(nil);
		emitUnbox(a, 2, 7);
		a.aload(5).iload(7).invokestatic(this.codePointOffset).istore(7);
		emitUnbox(a, 3, 8);
		a.aload(5).iload(8).invokestatic(this.codePointOffset).istore(8);
		a.loadConstant(0).istore(9);
		a.labelBinding(loop);
		a.aload(5).iload(7).iload(8).aload(4).loadConstant(1).iload(6).invokestatic(this.find).istore(10);
		a.iload(10).iflt(done);
		a.iinc(9, 1);
		a.iload(10).iload(6).iadd().istore(7);
		a.goto_(loop);
		a.labelBinding(done);
		a.iload(9).i2l().invokestatic(this.longValueOf).areturn();
		a.labelBinding(nil);
		a.aconst_null().areturn();
		return a;
	}

	// _strSplit(s, separator, limit): the pieces of s between the leftmost LIMIT
	// occurrences of the separator that do not overlap (all when the limit is
	// negative), in a fresh vector as (make-array n :adjustable t :fill-pointer n)
	// makes one; null for an operand that is no immutable string, an empty separator or
	// a limit that is no fixnum. A piece is cut from one char[] of the string with the
	// framing quotes written over the unit before it and the one behind it -- the
	// opening quote or a separator's last unit, a separator's first unit or the
	// closing quote, none of them read again -- so it is one String made in one pass.
	private MethodCode buildSplit() {
		// Slots: 0..2 = the arguments, 3 = s, 4 = the separator, 5 = m, 6-7 = the
		// limit, 8 = the closing quote's offset, 9 = the units, 10 = the list, 11 = i,
		// 12 = r, 13 = the count boxed, 14 = the header.
		ClassEntry charArrayClass = this.cp.classEntry("[C");
		MethodRefEntry toCharArray = this.cp.methodRef(this.stringClass, "toCharArray", "()[C");
		MethodRefEntry stringOfChars = this.cp.methodRef(this.stringClass, "<init>", "([CII)V");
		MethodRefEntry listInit = this.cp.methodRef(this.listClass, "<init>", "()V");
		MethodCode a = new MethodCode();
		MethodCode.Label nil = a.newLabel();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label last = a.newLabel();
		a.aload(0).invokestatic(this.framed).astore(3);
		a.aload(3).ifnull(nil);
		a.aload(1).invokestatic(this.framed).astore(4);
		a.aload(4).ifnull(nil);
		a.aload(2).instanceOf(this.longClass).ifeq(nil);
		a.aload(4).invokevirtual(this.stringLength).loadConstant(2).isub().istore(5);
		a.iload(5).ifeq(nil);
		a.aload(4).loadConstant(1).iload(5).invokestatic(this.whole).ifeq(nil);
		a.aload(2).checkcast(this.longClass).invokevirtual(this.longLongValue).lstore(6);
		a.aload(3).invokevirtual(this.stringLength).loadConstant(1).isub().istore(8);
		a.aload(3).invokevirtual(toCharArray).checkcast(charArrayClass).astore(9);
		a.new_(this.listClass).dup().invokespecial(listInit).astore(10);
		a.aload(10).aconst_null().invokevirtual(this.listAdd).pop();
		a.loadConstant(1).istore(11);
		a.labelBinding(loop);
		a.lload(6).lconst_0().lcmp().ifeq(last);
		a.aload(3).iload(11).iload(8).aload(4).loadConstant(1).iload(5).invokestatic(this.find).istore(12);
		a.iload(12).iflt(last);
		emitPiece(a, stringOfChars, 12);
		a.iload(12).iload(5).iadd().istore(11);
		a.lload(6).lconst_1().lsub().lstore(6);
		a.goto_(loop);
		a.labelBinding(last);
		emitPiece(a, stringOfChars, 8);
		// the header: {{n}, n, t} over the n pieces
		a.aload(10).invokevirtual(this.listSize).loadConstant(1).isub().i2l().invokestatic(this.longValueOf).astore(13);
		a.loadConstant(3).anewarray(this.objectClass).astore(14);
		a.aload(14).loadConstant(0).loadConstant(1).anewarray(this.objectClass).dup().loadConstant(0).aload(13);
		a.aastore().aastore();
		a.aload(14).loadConstant(1).aload(13).aastore();
		a.aload(14).loadConstant(2).ldc(this.cp.stringEntry("T")).aastore();
		a.aload(10).loadConstant(0).aload(14).invokevirtual(this.listSet).pop();
		a.aload(10).areturn();
		a.labelBinding(nil);
		a.aconst_null().areturn();
		return a;
	}

	// Adds to the list of local 10 the piece of the units of local 9 from the offset
	// of local 11 short of the one of local END, framed in place.
	private void emitPiece(MethodCode a, MethodRefEntry stringOfChars, int end) {
		a.aload(9).iload(11).loadConstant(1).isub().loadConstant(34).castore();
		a.aload(9).iload(end).loadConstant(34).castore();
		a.aload(10);
		a.new_(this.stringClass).dup().aload(9).iload(11).loadConstant(1).isub();
		a.iload(end).iload(11).isub().loadConstant(2).iadd().invokespecial(stringOfChars);
		a.invokevirtual(this.listAdd).pop();
	}

	// _strJoin(strings, separator): the strings of a vector of boxed elements as one
	// string, the separator between them; null for a vector that is none, or an
	// element or separator that is no immutable string. The elements are measured
	// first, so the builder is made at its size and each string is copied into it once.
	private MethodCode buildJoin() {
		// Slots: 0, 1 = the arguments, 2 = the header, 3 = the separator, 4 = the list,
		// 5 = n, 6-7 = the capacity, 8 = i, 9 = an element, 10 = m, 11 = the builder.
		MethodRefEntry builderInit = this.cp.methodRef(this.builderClass, "<init>", "(I)V");
		MethodRefEntry appendChar = this.cp.methodRef(this.builderClass, "append", "(C)Ljava/lang/StringBuilder;");
		MethodRefEntry appendRange = this.cp.methodRef(this.builderClass, "append",
				"(Ljava/lang/CharSequence;II)Ljava/lang/StringBuilder;");
		MethodRefEntry builderToString = this.cp.methodRef(this.builderClass, "toString", "()" + STRING);
		MethodCode a = new MethodCode();
		MethodCode.Label nil = a.newLabel();
		MethodCode.Label measure = a.newLabel();
		MethodCode.Label measured = a.newLabel();
		MethodCode.Label copy = a.newLabel();
		MethodCode.Label first = a.newLabel();
		MethodCode.Label copied = a.newLabel();
		a.aload(0).invokestatic(this.vector).astore(2);
		a.aload(2).ifnull(nil);
		a.aload(2).arraylength().loadConstant(6).if_icmpeq(nil);
		a.aload(1).invokestatic(this.framed).astore(3);
		a.aload(3).ifnull(nil);
		a.aload(0).checkcast(this.listClass).astore(4);
		a.aload(2).invokestatic(this.length).istore(5);
		a.aload(3).invokevirtual(this.stringLength).loadConstant(2).isub().istore(10);
		a.loadConstant(2).i2l().lstore(6);
		a.loadConstant(0).istore(8);
		a.labelBinding(measure);
		a.iload(8).iload(5).if_icmpge(measured);
		a.aload(4).iload(8).loadConstant(1).iadd().invokevirtual(this.listGet).invokestatic(this.framed).astore(9);
		a.aload(9).ifnull(nil);
		a.lload(6).aload(9).checkcast(this.stringClass).invokevirtual(this.stringLength).loadConstant(2).isub();
		a.iload(10).iadd().i2l().ladd().lstore(6);
		a.iinc(8, 1);
		a.goto_(measure);
		a.labelBinding(measured);
		a.lload(6).ldc(this.cp.entries().longEntry(Integer.MAX_VALUE)).lcmp().ifgt(nil);
		a.new_(this.builderClass).dup().lload(6).l2i().invokespecial(builderInit).astore(11);
		a.aload(11).loadConstant(34).invokevirtual(appendChar).pop();
		a.loadConstant(0).istore(8);
		a.labelBinding(copy);
		a.iload(8).iload(5).if_icmpge(copied);
		a.iload(8).ifeq(first);
		a.aload(11).aload(3).loadConstant(1).iload(10).loadConstant(1).iadd().invokevirtual(appendRange).pop();
		a.labelBinding(first);
		a.aload(4).iload(8).loadConstant(1).iadd().invokevirtual(this.listGet).checkcast(this.stringClass).astore(9);
		a.aload(11).aload(9).loadConstant(1);
		a.aload(9).checkcast(this.stringClass).invokevirtual(this.stringLength).loadConstant(1).isub();
		a.invokevirtual(appendRange).pop();
		a.iinc(8, 1);
		a.goto_(copy);
		a.labelBinding(copied);
		a.aload(11).loadConstant(34).invokevirtual(appendChar).invokevirtual(builderToString).areturn();
		a.labelBinding(nil);
		a.aconst_null().areturn();
		return a;
	}

	// _bulkWord(s): the word the immutable string s sorts by (the class comment).
	private MethodCode buildWord() {
		// Slots: 0 = s, 1 = the closing quote's offset, 2-3 = the word, 4 = the unit's
		// place, 5 = the unit.
		MethodRefEntry min = this.cp.methodRef(this.cp.classEntry("java/lang/Math"), "min", "(II)I");
		MethodCode a = new MethodCode();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label ended = a.newLabel();
		MethodCode.Label wide = a.newLabel();
		a.aload(0).invokevirtual(this.stringLength).loadConstant(1).isub().istore(1);
		a.lconst_0().lstore(2);
		a.loadConstant(0).istore(4);
		a.labelBinding(loop);
		a.iload(4).loadConstant(7).if_icmpge(ended);
		a.iload(4).loadConstant(1).iadd().iload(1).if_icmpge(ended);
		a.aload(0).iload(4).loadConstant(1).iadd().invokevirtual(this.charAt).istore(5);
		a.iload(5).loadConstant(255).if_icmpgt(wide);
		a.lload(2).iload(5).i2l().loadConstant(56).iload(4).loadConstant(3).ishl().isub().lshl().lor().lstore(2);
		a.iinc(4, 1);
		a.goto_(loop);
		a.labelBinding(ended);
		a.lload(2).iload(1).loadConstant(1).isub().loadConstant(8).invokestatic(min).i2l().lor().lreturn();
		a.labelBinding(wide);
		a.lload(2).loadConstant(-1).i2l().iload(4).loadConstant(3).ishl().lushr().lor().lreturn();
		return a;
	}

	// _bulkMerge(keys, places, scratch, lo, hi, descending): sorts the places from lo
	// short of hi by the strings of the list KEYS they name, as _strOrder orders them
	// -- from the greatest down when DESCENDING is not 0 --, equal ones in the order
	// they stand in. A bottom-up merge between the two arrays, which are the same
	// length.
	private MethodCode buildMerge(ClassEntry selfClass) {
		// Slots: 0 = keys, 1 = places, 2 = scratch, 3 = lo, 4 = hi, 5 = descending,
		// 6 = the source, 7 = the destination, 8 = the width, 9 = a pair's start,
		// 10 = its middle, 11 = its end, 12 = i, 13 = j, 14 = k, 15 = the order,
		// 16 = the array swapped.
		MethodRefEntry order = this.cp.methodRef(selfClass, JvmStringIndexRuntimeBuilder.ORDER_METHOD,
				JvmStringIndexRuntimeBuilder.ORDER_DESC);
		MethodRefEntry min = this.cp.methodRef(this.cp.classEntry("java/lang/Math"), "min", "(II)I");
		MethodCode a = new MethodCode();
		MethodCode.Label widths = a.newLabel();
		MethodCode.Label sorted = a.newLabel();
		MethodCode.Label pairs = a.newLabel();
		MethodCode.Label paired = a.newLabel();
		MethodCode.Label merge = a.newLabel();
		MethodCode.Label ascending = a.newLabel();
		MethodCode.Label takeRight = a.newLabel();
		MethodCode.Label leftOnly = a.newLabel();
		MethodCode.Label rightOnly = a.newLabel();
		MethodCode.Label next = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aload(1).astore(6);
		a.aload(2).astore(7);
		a.loadConstant(1).istore(8);
		a.labelBinding(widths);
		a.iload(8).iload(4).iload(3).isub().if_icmpge(sorted);
		a.iload(3).istore(9);
		a.labelBinding(pairs);
		a.iload(9).iload(4).if_icmpge(paired);
		a.iload(9).iload(8).iadd().iload(4).invokestatic(min).istore(10);
		a.iload(10).iload(8).iadd().iload(4).invokestatic(min).istore(11);
		a.iload(9).istore(12);
		a.iload(10).istore(13);
		a.iload(9).istore(14);
		a.labelBinding(merge);
		a.iload(12).iload(10).if_icmpge(rightOnly);
		a.iload(13).iload(11).if_icmpge(leftOnly);
		// the right one is taken only when it sorts strictly before the left one
		a.aload(0).aload(6).iload(13).iaload().loadConstant(1).iadd().invokevirtual(this.listGet);
		a.aload(0).aload(6).iload(12).iaload().loadConstant(1).iadd().invokevirtual(this.listGet);
		a.invokestatic(order).checkcast(this.longClass).invokevirtual(this.longLongValue).lconst_0().lcmp().istore(15);
		a.iload(5).ifeq(ascending);
		a.iload(15).ineg().istore(15);
		a.labelBinding(ascending);
		a.iload(15).iflt(takeRight);
		a.aload(7).iload(14).aload(6).iload(12).iaload().iastore();
		a.iinc(12, 1);
		a.iinc(14, 1);
		a.goto_(merge);
		a.labelBinding(takeRight);
		a.aload(7).iload(14).aload(6).iload(13).iaload().iastore();
		a.iinc(13, 1);
		a.iinc(14, 1);
		a.goto_(merge);
		a.labelBinding(leftOnly);
		a.aload(6).iload(12).aload(7).iload(14).iload(10).iload(12).isub().invokestatic(this.arraycopy);
		a.goto_(next);
		a.labelBinding(rightOnly);
		a.aload(6).iload(13).aload(7).iload(14).iload(11).iload(13).isub().invokestatic(this.arraycopy);
		a.labelBinding(next);
		a.iload(11).istore(9);
		a.goto_(pairs);
		a.labelBinding(paired);
		a.aload(6).astore(16);
		a.aload(7).astore(6);
		a.aload(16).astore(7);
		a.iload(8).loadConstant(1).ishl().istore(8);
		a.goto_(widths);
		a.labelBinding(sorted);
		a.aload(6).aload(1).if_acmpeq(done);
		a.aload(6).iload(3).aload(1).iload(3).iload(4).iload(3).isub().invokestatic(this.arraycopy);
		a.labelBinding(done);
		a.return_();
		return a;
	}

	// _bulkRadix(words, places, toWords, toPlaces, counters, shift): one pass of the
	// radix sort -- the words and the place beside each into the other two arrays, in
	// the order of the byte SHIFT bits up, words of one byte in the order they stand
	// in. A method of its own, called a pass: the three loops are compiled as one small
	// body where the sort's whole body would be compiled again from each of them.
	private MethodCode buildRadix() {
		// Slots: 0 = the words, 1 = the places, 2 = the words' destination, 3 = the
		// places', 4 = the 256 counters, 5 = the shift, 6 = n, 7 = i, 8-9 = a word,
		// 10 = its byte, 11 = a counter, 12 = the running sum.
		MethodRefEntry fill = this.cp.methodRef(this.cp.classEntry("java/util/Arrays"), "fill", "([II)V");
		MethodCode a = new MethodCode();
		MethodCode.Label count = a.newLabel();
		MethodCode.Label counted = a.newLabel();
		MethodCode.Label sum = a.newLabel();
		MethodCode.Label summed = a.newLabel();
		MethodCode.Label move = a.newLabel();
		MethodCode.Label moved = a.newLabel();
		a.aload(0).arraylength().istore(6);
		a.aload(4).loadConstant(0).invokestatic(fill);
		a.loadConstant(0).istore(7);
		a.labelBinding(count);
		a.iload(7).iload(6).if_icmpge(counted);
		a.aload(4).aload(0).iload(7).laload().iload(5).lushr().l2i().loadConstant(255).iand();
		a.dup2().iaload().loadConstant(1).iadd().iastore();
		a.iinc(7, 1);
		a.goto_(count);
		a.labelBinding(counted);
		// a counter becomes where its byte's first word goes
		a.loadConstant(0).istore(12);
		a.loadConstant(0).istore(10);
		a.labelBinding(sum);
		a.iload(10).loadConstant(256).if_icmpge(summed);
		a.aload(4).iload(10).iaload().istore(11);
		a.aload(4).iload(10).iload(12).iastore();
		a.iload(12).iload(11).iadd().istore(12);
		a.iinc(10, 1);
		a.goto_(sum);
		a.labelBinding(summed);
		a.loadConstant(0).istore(7);
		a.labelBinding(move);
		a.iload(7).iload(6).if_icmpge(moved);
		a.aload(0).iload(7).laload().lstore(8);
		a.lload(8).iload(5).lushr().l2i().loadConstant(255).iand().istore(10);
		a.aload(4).iload(10).iaload().istore(11);
		a.aload(4).iload(10).iload(11).loadConstant(1).iadd().iastore();
		a.aload(2).iload(11).lload(8).lastore();
		a.aload(3).iload(11).aload(1).iload(7).iaload().iastore();
		a.iinc(7, 1);
		a.goto_(move);
		a.labelBinding(moved);
		a.return_();
		return a;
	}

	// _sortKeys(keys, elements, out, descending): out, the elements (the keys when
	// ELEMENTS is nil) stored into it in the order of their keys, equal keys in the
	// order they stood in; null, out as it was, for keys that are not all fixnums or
	// all immutable strings, a vector without storage of its own, an out that is no
	// plain vector of boxed elements, or elements or out shorter than the keys.
	private MethodCode buildSort(ClassEntry selfClass) {
		// Slots: 0..3 = the arguments, 4 = the keys' header, 5 = out's, 6 = the
		// elements', 7 = n, 8 = the keys' list, 9 = the words, 10 = whether the keys
		// are strings, 11 = i, 12-13 = a word, 14-15 = what a word is xored with,
		// 16 = the places, 17 = the other words, 18 = the other places, 19 = the
		// counters, 20-21 = the bits the words differ in, 22 = the shift, 23 = a run's
		// end, 24 = a place, 25 = a long[] of unboxed elements, 26 = a key, 28-29 = the
		// or of the words, 30-31 = their and, 33 = an array swapped, 34 = j or a run's
		// start, 35 = the low byte's complement (255 when descending), 36 = out's
		// list, 37 = the list the answer is read from, 38 = its header.
		ClassEntry intArrayClass = this.cp.classEntry("[I");
		MethodRefEntry word = this.cp.methodRef(selfClass, WORD, WORD_DESC);
		MethodRefEntry merge = this.cp.methodRef(selfClass, MERGE, MERGE_DESC);
		MethodRefEntry compareUnsigned = this.cp.methodRef(this.longClass, "compareUnsigned", "(JJ)I");
		MethodRefEntry radixPass = this.cp.methodRef(selfClass, RADIX, RADIX_DESC);
		LongEntry signBit = this.cp.entries().longEntry(Long.MIN_VALUE);
		MethodCode a = new MethodCode();
		MethodCode.Label nil = a.newLabel();
		a.aload(0).invokestatic(this.vector).astore(4);
		a.aload(4).ifnull(nil);
		a.aload(2).invokestatic(this.vector).astore(5);
		a.aload(5).ifnull(nil);
		a.aload(5).arraylength().loadConstant(3).if_icmpne(nil);
		a.aload(0).aload(2).if_acmpeq(nil);
		a.aload(1).aload(2).if_acmpeq(nil);
		a.aload(4).invokestatic(this.length).istore(7);
		a.aload(5).invokestatic(this.length).iload(7).if_icmplt(nil);
		a.aconst_null().astore(6);
		MethodCode.Label noElements = a.newLabel();
		a.aload(1).ifnull(noElements);
		a.aload(1).invokestatic(this.vector).astore(6);
		a.aload(6).ifnull(nil);
		a.aload(6).invokestatic(this.length).iload(7).if_icmplt(nil);
		a.labelBinding(noElements);
		a.aload(0).checkcast(this.listClass).astore(8);
		a.iload(7).newarray(TypeKind.LONG).astore(9);
		a.loadConstant(0).istore(10);
		a.loadConstant(0).istore(35);
		a.lconst_0().lstore(14);
		MethodCode.Label ascending = a.newLabel();
		a.aload(3).ifnull(ascending);
		a.loadConstant(255).istore(35);
		a.loadConstant(-1).i2l().lstore(14);
		a.labelBinding(ascending);

		// the words: an unboxed key, a boxed fixnum, a string
		MethodCode.Label boxedKeys = a.newLabel();
		MethodCode.Label stringKeys = a.newLabel();
		MethodCode.Label read = a.newLabel();
		a.loadConstant(0).istore(11);
		a.aload(4).arraylength().loadConstant(6).if_icmpne(boxedKeys);
		a.aload(4).loadConstant(5).aaload().checkcast(this.longArrayClass).astore(25);
		{
			MethodCode.Label loop = a.newLabel();
			a.labelBinding(loop);
			a.iload(11).iload(7).if_icmpge(read);
			a.aload(25).iload(11).laload().lstore(12);
			a.lload(12).ldc(this.nilSentinel).lcmp().ifeq(nil);
			a.aload(9).iload(11).lload(12).ldc(signBit).lxor().lload(14).lxor().lastore();
			a.iinc(11, 1);
			a.goto_(loop);
		}
		a.labelBinding(boxedKeys);
		a.iload(7).ifeq(stringKeys);
		a.aload(8).loadConstant(1).invokevirtual(this.listGet).instanceOf(this.longClass).ifeq(stringKeys);
		{
			MethodCode.Label loop = a.newLabel();
			a.labelBinding(loop);
			a.iload(11).iload(7).if_icmpge(read);
			a.aload(8).iload(11).loadConstant(1).iadd().invokevirtual(this.listGet).astore(26);
			a.aload(26).instanceOf(this.longClass).ifeq(nil);
			a.aload(9).iload(11).aload(26).checkcast(this.longClass).invokevirtual(this.longLongValue);
			a.ldc(signBit).lxor().lload(14).lxor().lastore();
			a.iinc(11, 1);
			a.goto_(loop);
		}
		a.labelBinding(stringKeys);
		a.loadConstant(1).istore(10);
		{
			MethodCode.Label loop = a.newLabel();
			a.labelBinding(loop);
			a.iload(11).iload(7).if_icmpge(read);
			a.aload(8)
				.iload(11)
				.loadConstant(1)
				.iadd()
				.invokevirtual(this.listGet)
				.invokestatic(this.framed)
				.astore(26);
			a.aload(26).ifnull(nil);
			a.aload(9).iload(11).aload(26).checkcast(this.stringClass).invokestatic(word).lload(14).lxor().lastore();
			a.iinc(11, 1);
			a.goto_(loop);
		}
		a.labelBinding(read);

		// the places, and the scratch a merge and a radix pass move them through
		a.iload(7).newarray(TypeKind.INT).astore(16);
		a.iload(7).newarray(TypeKind.INT).astore(18);
		{
			MethodCode.Label loop = a.newLabel();
			MethodCode.Label done = a.newLabel();
			a.loadConstant(0).istore(11);
			a.labelBinding(loop);
			a.iload(11).iload(7).if_icmpge(done);
			a.aload(16).iload(11).iload(11).iastore();
			a.iinc(11, 1);
			a.goto_(loop);
			a.labelBinding(done);
		}
		MethodCode.Label radix = a.newLabel();
		MethodCode.Label ordered = a.newLabel();
		a.iload(7).loadConstant(INSERTION_LIMIT).if_icmpgt(radix);
		{
			// by insertion: a word moves left past every word above it
			MethodCode.Label outer = a.newLabel();
			MethodCode.Label inner = a.newLabel();
			MethodCode.Label place = a.newLabel();
			a.loadConstant(1).istore(11);
			a.labelBinding(outer);
			a.iload(11).iload(7).if_icmpge(ordered);
			a.aload(9).iload(11).laload().lstore(12);
			a.aload(16).iload(11).iaload().istore(24);
			a.iload(11).loadConstant(1).isub().istore(34);
			a.labelBinding(inner);
			a.iload(34).iflt(place);
			a.aload(9).iload(34).laload().lload(12).invokestatic(compareUnsigned).ifle(place);
			a.aload(9).iload(34).loadConstant(1).iadd().aload(9).iload(34).laload().lastore();
			a.aload(16).iload(34).loadConstant(1).iadd().aload(16).iload(34).iaload().iastore();
			a.iinc(34, -1);
			a.goto_(inner);
			a.labelBinding(place);
			a.aload(9).iload(34).loadConstant(1).iadd().lload(12).lastore();
			a.aload(16).iload(34).loadConstant(1).iadd().iload(24).iastore();
			a.iinc(11, 1);
			a.goto_(outer);
		}
		a.labelBinding(radix);
		{
			// by radix, from the low byte up; a byte every word agrees on is skipped
			MethodCode.Label bits = a.newLabel();
			MethodCode.Label bitsDone = a.newLabel();
			MethodCode.Label pass = a.newLabel();
			MethodCode.Label nextPass = a.newLabel();
			a.lconst_0().lstore(28);
			a.loadConstant(-1).i2l().lstore(30);
			a.loadConstant(0).istore(11);
			a.labelBinding(bits);
			a.iload(11).iload(7).if_icmpge(bitsDone);
			a.aload(9).iload(11).laload().lstore(12);
			a.lload(28).lload(12).lor().lstore(28);
			a.lload(30).lload(12).land().lstore(30);
			a.iinc(11, 1);
			a.goto_(bits);
			a.labelBinding(bitsDone);
			a.lload(28).lload(30).lxor().lstore(20);
			a.iload(7).newarray(TypeKind.LONG).astore(17);
			a.loadConstant(256).newarray(TypeKind.INT).astore(19);
			a.loadConstant(0).istore(22);
			a.labelBinding(pass);
			a.iload(22).loadConstant(64).if_icmpge(ordered);
			a.lload(20).iload(22).lushr().l2i().loadConstant(255).iand().ifeq(nextPass);
			a.aload(9).aload(16).aload(17).aload(18).aload(19).iload(22).invokestatic(radixPass);
			a.aload(9).astore(33);
			a.aload(17).astore(9);
			a.aload(33).checkcast(this.longArrayClass).astore(17);
			a.aload(16).astore(33);
			a.aload(18).astore(16);
			a.aload(33).checkcast(intArrayClass).astore(18);
			a.labelBinding(nextPass);
			a.iinc(22, 8);
			a.goto_(pass);
		}
		a.labelBinding(ordered);

		// strings: a run of equal words that do not hold their whole string is merged
		MethodCode.Label store = a.newLabel();
		a.iload(10).ifeq(store);
		{
			MethodCode.Label runs = a.newLabel();
			MethodCode.Label extend = a.newLabel();
			MethodCode.Label extended = a.newLabel();
			MethodCode.Label nextRun = a.newLabel();
			a.loadConstant(0).istore(34);
			a.labelBinding(runs);
			a.iload(34).iload(7).if_icmpge(store);
			a.aload(9).iload(34).laload().lstore(12);
			a.iload(34).loadConstant(1).iadd().istore(23);
			a.labelBinding(extend);
			a.iload(23).iload(7).if_icmpge(extended);
			a.aload(9).iload(23).laload().lload(12).lcmp().ifne(extended);
			a.iinc(23, 1);
			a.goto_(extend);
			a.labelBinding(extended);
			a.iload(23).iload(34).isub().loadConstant(2).if_icmplt(nextRun);
			a.lload(12).l2i().loadConstant(255).iand().iload(35).ixor().loadConstant(8).if_icmplt(nextRun);
			a.aload(8).aload(16).aload(18).iload(34).iload(23).iload(35).invokestatic(merge);
			a.labelBinding(nextRun);
			a.iload(23).istore(34);
			a.goto_(runs);
		}
		a.labelBinding(store);

		// out's element i is the element the place i names
		MethodCode.Label ownKeys = a.newLabel();
		MethodCode.Label source = a.newLabel();
		MethodCode.Label boxedSource = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aload(2).checkcast(this.listClass).astore(36);
		a.aload(1).ifnull(ownKeys);
		a.aload(1).checkcast(this.listClass).astore(37);
		a.aload(6).astore(38);
		a.goto_(source);
		a.labelBinding(ownKeys);
		a.aload(8).astore(37);
		a.aload(4).astore(38);
		a.labelBinding(source);
		a.loadConstant(0).istore(11);
		a.aload(38).checkcast(this.objectArrayClass).arraylength().loadConstant(6).if_icmpne(boxedSource);
		a.aload(38).checkcast(this.objectArrayClass).loadConstant(5).aaload().checkcast(this.longArrayClass).astore(25);
		{
			MethodCode.Label loop = a.newLabel();
			MethodCode.Label isNil = a.newLabel();
			MethodCode.Label put = a.newLabel();
			a.labelBinding(loop);
			a.iload(11).iload(7).if_icmpge(done);
			a.aload(25).aload(16).iload(11).iaload().laload().lstore(12);
			a.aload(36).iload(11).loadConstant(1).iadd();
			a.lload(12).ldc(this.nilSentinel).lcmp().ifeq(isNil);
			a.lload(12).invokestatic(this.longValueOf);
			a.goto_(put);
			a.labelBinding(isNil);
			a.aconst_null();
			a.labelBinding(put);
			a.invokevirtual(this.listSet).pop();
			a.iinc(11, 1);
			a.goto_(loop);
		}
		a.labelBinding(boxedSource);
		{
			MethodCode.Label loop = a.newLabel();
			a.labelBinding(loop);
			a.iload(11).iload(7).if_icmpge(done);
			a.aload(36).iload(11).loadConstant(1).iadd();
			a.aload(37).checkcast(this.listClass).aload(16).iload(11).iaload().loadConstant(1).iadd();
			a.invokevirtual(this.listGet).invokevirtual(this.listSet).pop();
			a.iinc(11, 1);
			a.goto_(loop);
		}
		a.labelBinding(done);
		a.aload(2).areturn();
		a.labelBinding(nil);
		a.aconst_null().areturn();
		return a;
	}

	// _bulkCopy(dst, src, start1, start2, n): "T" once the n elements of src from
	// start2 are those of dst from start1; null, nothing copied, unless the two are
	// distinct vectors with storage of their own, dst a plain one of boxed elements
	// (it remembers no element type, whose stores check their value) or both unboxed,
	// and both ranges lie within the storage, as the loop's own accesses must.
	private MethodCode buildCopy() {
		// Slots: 0..4 = the arguments, 5 = dst's header, 6 = src's, 7 = dst's list,
		// 8 = src's, 9 = start1, 10 = start2, 11 = n, 12 = i, 13 = src's long[],
		// 14-15 = an unboxed element.
		MethodCode a = new MethodCode();
		MethodCode.Label nil = a.newLabel();
		MethodCode.Label boxedDst = a.newLabel();
		MethodCode.Label boxedSrc = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aload(0).invokestatic(this.vector).astore(5);
		a.aload(5).ifnull(nil);
		a.aload(1).invokestatic(this.vector).astore(6);
		a.aload(6).ifnull(nil);
		a.aload(0).aload(1).if_acmpeq(nil);
		for (int slot = 2; slot < 5; slot++) {
			a.aload(slot).instanceOf(this.longClass).ifeq(nil);
			emitUnbox(a, slot, 7 + slot);
			a.iload(7 + slot).iflt(nil);
		}
		a.aload(0).checkcast(this.listClass).astore(7);
		a.aload(1).checkcast(this.listClass).astore(8);
		a.aload(5).arraylength().loadConstant(6).if_icmpne(boxedDst);
		// both unboxed: one copy of the longs, nil's stand-in among them
		a.aload(6).arraylength().loadConstant(6).if_icmpne(nil);
		a.aload(6).loadConstant(5).aaload().checkcast(this.longArrayClass).astore(13);
		a.iload(10).i2l().iload(11).i2l().ladd().aload(13).arraylength().i2l().lcmp().ifgt(nil);
		a.iload(9).i2l().iload(11).i2l().ladd();
		a.aload(5).loadConstant(5).aaload().checkcast(this.longArrayClass).arraylength().i2l().lcmp().ifgt(nil);
		a.aload(13).iload(10).aload(5).loadConstant(5).aaload().iload(9).iload(11).invokestatic(this.arraycopy);
		a.goto_(done);
		a.labelBinding(boxedDst);
		a.aload(5).arraylength().loadConstant(3).if_icmpne(nil);
		a.iload(9).i2l().iload(11).i2l().ladd().aload(7).invokevirtual(this.listSize).loadConstant(1).isub().i2l();
		a.lcmp().ifgt(nil);
		a.loadConstant(0).istore(12);
		a.aload(6).arraylength().loadConstant(6).if_icmpne(boxedSrc);
		a.aload(6).loadConstant(5).aaload().checkcast(this.longArrayClass).astore(13);
		a.iload(10).i2l().iload(11).i2l().ladd().aload(13).arraylength().i2l().lcmp().ifgt(nil);
		{
			MethodCode.Label loop = a.newLabel();
			MethodCode.Label isNil = a.newLabel();
			MethodCode.Label put = a.newLabel();
			a.labelBinding(loop);
			a.iload(12).iload(11).if_icmpge(done);
			a.aload(13).iload(10).iload(12).iadd().laload().lstore(14);
			a.aload(7).iload(9).iload(12).iadd().loadConstant(1).iadd();
			a.lload(14).ldc(this.nilSentinel).lcmp().ifeq(isNil);
			a.lload(14).invokestatic(this.longValueOf);
			a.goto_(put);
			a.labelBinding(isNil);
			a.aconst_null();
			a.labelBinding(put);
			a.invokevirtual(this.listSet).pop();
			a.iinc(12, 1);
			a.goto_(loop);
		}
		a.labelBinding(boxedSrc);
		a.iload(10).i2l().iload(11).i2l().ladd().aload(8).invokevirtual(this.listSize).loadConstant(1).isub().i2l();
		a.lcmp().ifgt(nil);
		{
			MethodCode.Label loop = a.newLabel();
			a.labelBinding(loop);
			a.iload(12).iload(11).if_icmpge(done);
			a.aload(7).iload(9).iload(12).iadd().loadConstant(1).iadd();
			a.aload(8).iload(10).iload(12).iadd().loadConstant(1).iadd().invokevirtual(this.listGet);
			a.invokevirtual(this.listSet).pop();
			a.iinc(12, 1);
			a.goto_(loop);
		}
		a.labelBinding(done);
		a.ldc(this.cp.stringEntry("T")).areturn();
		a.labelBinding(nil);
		a.aconst_null().areturn();
		return a;
	}

}
