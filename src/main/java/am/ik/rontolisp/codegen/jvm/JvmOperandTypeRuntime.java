package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.compiler.OperandTypes;
import am.ik.rontolisp.runtime.RontoHashTable;
import org.jspecify.annotations.Nullable;

/**
 * The JVM half of {@link OperandTypes}: a wrong-type operand reaching the numeric runtime
 * reports {@code OP: The value X is not of type T} and, under a landing pad, is caught as
 * a {@code type-error} answering its datum and expected type.
 *
 * <ul>
 * <li>{@code _teRaw(x, kind)} is the ONE place a coercion funnel ({@code _big},
 * {@code _dbl}, the complex runtime's real checks) builds its exception: the unnamed
 * {@code The value X is not of type KIND}.</li>
 * <li>A call to a numeric helper compiled inside a named operator's form goes through a
 * per-(helper, operator) WRAPPER ({@link Wrappers}): the same invocation under an
 * exception-table entry whose handler hands the throwable to
 * {@code _opTypeErr(e, "OP", "TYPE")}, which renames an unnamed report and passes
 * anything else through. Zero cost on the normal path; a helper is shared by many
 * operators ({@code _cmpb} serves {@code < > <= >= = min max}), so the operator can only
 * be known at the call site, never inside the helper.</li>
 * <li>Under a landing pad the thread-local {@code _teTl} maps such an exception to its
 * {@code {datum, type}} record ({@link JvmThrowableRecords}: keyed by the exception, so a
 * record made while another is on its way out replaces nothing), read by
 * {@code _teSlot(e, i)}, which is how the pad fills a {@code type-error}'s {@code datum}
 * and {@code expected-type}: a {@code RuntimeException} has nowhere to carry an object,
 * and a compiled program ships no exception class of its own. A program without a pad
 * keeps neither.</li>
 * </ul>
 */
final class JvmOperandTypeRuntime {

	/** The funnels' exception builder. */
	static final String TE_RAW = "_teRaw";

	static final String TE_RAW_DESC = "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/RuntimeException;";

	/**
	 * A compound type's exception builder, {@code _teOf(x, type)}: the unnamed
	 * {@code The value X is not of type T}, {@code T} the printed type object (a list
	 * such as {@code (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))}), recorded under
	 * a pad with that object as the expected type. {@code _opTypeErr} keeps both under
	 * any operator, as it keeps an out-of-range subscript's.
	 */
	static final String TE_OF = "_teOf";

	static final String TE_OF_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/RuntimeException;";

	/** The wrappers' renamer. */
	static final String OP_TYPE_ERR = "_opTypeErr";

	static final String OP_TYPE_ERR_DESC = "(Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Throwable;";

	/** The pad's reader of the thread-local record. */
	static final String TE_SLOT = "_teSlot";

	static final String TE_SLOT_DESC = "(Ljava/lang/Throwable;I)Ljava/lang/Object;";

	/**
	 * {@code car} and {@code cdr} themselves: nil answers nil, a cons its field, and
	 * anything else throws {@code CAR}'s / {@code CDR}'s {@code LIST} type-error. Whole
	 * readers rather than a check a wrapper names: every site is one call where it was an
	 * inline null test and cast, so a program pays the two small methods once instead of
	 * a check and a wrapper per operator.
	 */
	static final String CAR = "_car";

	static final String CDR = "_cdr";

	static final String FIELD_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * {@code endp}'s check, self-naming like {@link #CAR}: nil or a cons answers itself,
	 * anything else throws {@code ENDP}'s {@code LIST} type-error. Descriptor
	 * {@link #FIELD_DESC}.
	 */
	static final String ENDP = "_endp";

	/**
	 * The argument checks of the other funnel-typed operators ({@code OperandTypes}):
	 * each answers its argument when it has the type and throws the unnamed report
	 * otherwise, for a wrapper to name. {@code _ckIdx} an index (an {@code INTEGER}: a
	 * {@code Long}, or a {@code BigInteger} the access then rejects as out of range),
	 * {@code _ckRat} a rational.
	 */
	static final String CK_IDX = "_ckIdx";

	static final String CK_IDX_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String CK_RAT = "_ckRat";

	static final String CK_RAT_DESC = CK_IDX_DESC;

	/**
	 * A character comparison's operand check: a character (an {@code int[]}) answers
	 * itself, anything else throws the unnamed {@code CHARACTER} report for the
	 * comparison's wrapper to name. Descriptor {@link #CK_IDX_DESC}.
	 */
	static final String CK_CHR = "_ckChr";

	/**
	 * A hash-table accessor's operand check: a Lisp hash table (a {@code LinkedHashMap})
	 * answers itself, anything else throws the unnamed {@code HASH-TABLE} report for the
	 * operator's wrapper to name. A {@code java:} program's host map passes here and is
	 * refused by its own guard after ({@code JvmJavaDirectSites.TABLE_GUARD}). Descriptor
	 * {@link #CK_IDX_DESC}.
	 */
	static final String CK_TAB = "_ckTab";

	/**
	 * A list argument's check ({@code last}, the {@code map*} family,
	 * {@code %check-list}): nil or a cons answers itself, anything else throws the
	 * unnamed {@code LIST} report for a wrapper to name. Descriptor {@link #FIELD_DESC}.
	 */
	static final String CK_LIST = "_ckList";

	/**
	 * A cons argument's check ({@code rplaca}, {@code rplacd}): a cons answers itself as
	 * an {@code Object[]} -- the cast the site used to make -- and anything else, nil
	 * included, throws the unnamed {@code CONS} report for a wrapper to name.
	 */
	static final String CK_CONS = "_ckCons";

	static final String CK_CONS_DESC = "(Ljava/lang/Object;)[Ljava/lang/Object;";

	/**
	 * An out-of-range subscript's exception builder, {@code _oob(datum, dim)}: the
	 * unnamed {@code The value D is not of type (INTEGER 0 (dim))}, recorded under a pad
	 * with the type as the list it spells ({@code OperandTypes.indexType}).
	 */
	static final String OOB = "_oob";

	static final String OOB_DESC = "(Ljava/lang/Object;I)Ljava/lang/RuntimeException;";

	/**
	 * A subscript's bound check, {@code _ckBound(i, dim)}: the index {@code i} as an
	 * {@code int} when it is a {@code Long} in {@code [0, dim)}, else
	 * {@code throw _oob(i, dim)} -- a {@code BigInteger} (an integer {@link #CK_IDX}
	 * passed) included. Every packed and general accessor reads its subscripts through
	 * it, one per axis, so the report is the operator's wrapper's to name.
	 */
	static final String CK_BOUND = "_ckBound";

	static final String CK_BOUND_DESC = "(Ljava/lang/Object;I)I";

	/**
	 * {@link #CK_BOUND} over a raw {@code long} subscript, {@code _ckBoundJ(i, dim)}: a
	 * typed loop's ({@code JvmTypedLoopCompiler}), whose index arithmetic is unboxed, so
	 * the loop reports exactly what the boxed accessor would.
	 */
	static final String CK_BOUND_J = "_ckBoundJ";

	static final String CK_BOUND_J_DESC = "(JI)I";

	/**
	 * The cons test of an inline list walk ({@code mapcar}, {@code mapc},
	 * {@code mapcan}): true for a cons, false for anything else -- nil, an atom, and the
	 * {@code Object[]}-shaped values that are no cons ({@link ConsShape}).
	 */
	static final String IS_CONS = "_isCons";

	static final String IS_CONS_DESC = "(Ljava/lang/Object;)Z";

	/** The thread-local record's field. */
	static final String TL_FIELD = "_teTl";

	static final String TL_DESC = "Ljava/lang/ThreadLocal;";

	private JvmOperandTypeRuntime() {
	}

	/**
	 * The references a funnel's throw site needs.
	 *
	 * @param teRaw {@code _teRaw}
	 * @param integerKind the {@code "INTEGER"} constant
	 * @param numberKind the {@code "NUMBER"} constant
	 * @param realKind the {@code "REAL"} constant
	 */
	record ThrowRefs(MethodRefEntry teRaw, StringEntry integerKind, StringEntry numberKind, StringEntry realKind) {

		static ThrowRefs of(ConstantPool cp, ClassEntry thisClass) {
			return new ThrowRefs(self(cp, thisClass, TE_RAW, TE_RAW_DESC),
					cp.stringEntry(OperandTypes.Kind.INTEGER.name()), cp.stringEntry(OperandTypes.Kind.NUMBER.name()),
					cp.stringEntry(OperandTypes.Kind.REAL.name()));
		}

		/**
		 * Emits {@code throw _teRaw(<slot>, kind)}. Peak operand stack: 2.
		 * @param c the bytecode sink
		 * @param slot the local holding the rejected operand
		 * @param kind the kind constant
		 */
		void emitThrow(MethodCode c, int slot, StringEntry kind) {
			c.aload(slot);
			emitThrowLoaded(c, kind);
		}

		/**
		 * Emits {@code throw _teRaw(<top of stack>, kind)}. Peak operand stack: 2.
		 * @param c the bytecode sink
		 * @param kind the kind constant
		 */
		void emitThrowLoaded(MethodCode c, StringEntry kind) {
			c.ldc(kind);
			c.invokestatic(this.teRaw);
			c.athrow();
		}

	}

	static MethodRefEntry self(ConstantPool cp, ClassEntry thisClass, String name, String desc) {
		return cp.methodRef(thisClass, name, desc);
	}

	/**
	 * Builds {@code _teRaw}, {@code _opTypeErr} and, when {@code teTl} is present,
	 * {@code _teSlot}.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param teTl the thread-local record field, or null when the program has no landing
	 * pad
	 * @return the methods
	 */
	static List<JvmNumericRuntimeBuilder.NumericMethod> build(ConstantPool cp, ClassEntry thisClass,
			@Nullable FieldRefEntry teTl, ConsShape shape) {
		ClassEntry rte = cp.classEntry("java/lang/RuntimeException");
		ClassEntry string = cp.classEntry("java/lang/String");
		ClassEntry objArr = cp.classEntry("[Ljava/lang/Object;");
		ClassEntry object = cp.classEntry("java/lang/Object");
		ClassEntry throwable = cp.classEntry("java/lang/Throwable");
		ClassEntry threadLocal = cp.classEntry("java/lang/ThreadLocal");
		MethodRefEntry rteInit = cp.methodRef(rte, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry concat = cp.methodRef(string, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		MethodRefEntry lispToString = self(cp, thisClass, "_lispToString", "(Ljava/lang/Object;)Ljava/lang/String;");
		MethodRefEntry tlGet = cp.methodRef(threadLocal, "get", "()Ljava/lang/Object;");
		// No record is set any more, but the entry keeps its place in the pool: the
		// pool's
		// order is part of every class's bytes, a class without a pad included.
		cp.methodRef(threadLocal, "set", "(Ljava/lang/Object;)V");
		if (teTl != null) {
			// Minted now, while the pool is still open: <clinit> initializes the field
			// through ConditionChannel's ThreadLocal constants, which resolve to these.
			cp.methodRef(threadLocal, "<init>", "()V");
			cp.utf8Entry("<clinit>");
		}
		Records records = teTl != null ? Records.of(cp, thisClass, teTl, tlGet, object, objArr) : null;
		StringEntry valuePrefix = cp.stringEntry(OperandTypes.VALUE_PREFIX);
		StringEntry typeInfix = cp.stringEntry(OperandTypes.TYPE_INFIX);

		List<JvmNumericRuntimeBuilder.NumericMethod> methods = new ArrayList<>();
		MethodRefEntry teRaw = self(cp, thisClass, TE_RAW, TE_RAW_DESC);
		ClassEntry longClass = cp.classEntry("java/lang/Long");
		ClassEntry bigClass = cp.classEntry("java/math/BigInteger");
		ClassEntry ratioClass = cp.classEntry("[Ljava/math/BigInteger;");
		MethodRefEntry opTypeErr = self(cp, thisClass, OP_TYPE_ERR, OP_TYPE_ERR_DESC);
		StringEntry listKind = cp.stringEntry(OperandTypes.Kind.LIST.name());
		StringEntry funnelType = cp.stringEntry(OperandTypes.FUNNEL_TYPE);
		methods.add(field(cp, CAR, 0, shape, teRaw, opTypeErr, listKind, funnelType));
		methods.add(field(cp, CDR, 1, shape, teRaw, opTypeErr, listKind, funnelType));
		methods.add(listCheck(cp, shape, teRaw, opTypeErr, listKind, funnelType));
		methods.add(consTest(cp, shape));
		methods.add(check(cp, CK_IDX, CK_IDX_DESC, List.of(longClass, bigClass), teRaw,
				cp.stringEntry(OperandTypes.Kind.INTEGER.name())));
		methods.add(check(cp, CK_RAT, CK_RAT_DESC, List.of(longClass, bigClass, ratioClass), teRaw,
				cp.stringEntry(OperandTypes.Kind.RATIONAL.name())));
		methods.add(check(cp, CK_TAB, CK_IDX_DESC, List.of(cp.classEntry(RontoHashTable.MAP_CLASS)), teRaw,
				cp.stringEntry(OperandTypes.Kind.HASH_TABLE.typeName())));
		methods.add(check(cp, CK_CHR, CK_IDX_DESC, List.of(cp.classEntry("[I")), teRaw,
				cp.stringEntry(OperandTypes.Kind.CHARACTER.name())));
		methods.add(consCheck(cp, CK_LIST, FIELD_DESC, true, shape, teRaw, listKind));
		methods.add(consCheck(cp, CK_CONS, CK_CONS_DESC, false, shape, teRaw,
				cp.stringEntry(OperandTypes.Kind.CONS.name())));

		// _teRaw(Object x, String kind): new RuntimeException("The value " + prin1(x) +
		// " is not of type " + kind), recorded under a pad.
		MethodCode c = new MethodCode();
		c.new_(rte);
		c.dup();
		c.ldc(valuePrefix);
		c.aload(0);
		c.invokestatic(lispToString);
		c.invokevirtual(concat);
		c.ldc(typeInfix);
		c.invokevirtual(concat);
		c.aload(1);
		c.invokevirtual(concat);
		c.invokespecial(rteInit);
		if (records != null) {
			c.astore(2);
			records.emit(c, 2, () -> c.aload(0), () -> c.aload(1));
			c.aload(2);
		}
		c.areturn();
		methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.utf8Entry(TE_RAW), cp.utf8Entry(TE_RAW_DESC), c));

		// _teOf(Object x, Object type): new RuntimeException("The value " + prin1(x) +
		// " is not of type " + prin1(type)), recorded under a pad with the type object.
		MethodCode t = new MethodCode();
		t.new_(rte);
		t.dup();
		t.ldc(valuePrefix);
		t.aload(0);
		t.invokestatic(lispToString);
		t.invokevirtual(concat);
		t.ldc(typeInfix);
		t.invokevirtual(concat);
		t.aload(1);
		t.invokestatic(lispToString);
		t.invokevirtual(concat);
		t.invokespecial(rteInit);
		if (records != null) {
			t.astore(2);
			records.emit(t, 2, () -> t.aload(0), () -> t.aload(1));
			t.aload(2);
		}
		t.areturn();
		methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.utf8Entry(TE_OF), cp.utf8Entry(TE_OF_DESC), t));

		// _oob(Object datum, int dim): new RuntimeException("The value " + prin1(datum)
		// + " is not of type (INTEGER 0 (" + dim + "))"), recorded under a pad with the
		// type as the list (INTEGER 0 (dim)).
		MethodRefEntry intToString = cp.methodRef(string, "valueOf", "(I)Ljava/lang/String;");
		MethodRefEntry longValueOf = cp.methodRef(longClass, "valueOf", "(J)Ljava/lang/Long;");
		MethodCode b = new MethodCode();
		b.new_(rte);
		b.dup();
		b.ldc(valuePrefix);
		b.aload(0);
		b.invokestatic(lispToString);
		b.invokevirtual(concat);
		b.ldc(cp.stringEntry(OperandTypes.TYPE_INFIX + OperandTypes.INDEX_TYPE_PREFIX));
		b.invokevirtual(concat);
		b.iload(1);
		b.invokestatic(intToString);
		b.invokevirtual(concat);
		b.ldc(cp.stringEntry(OperandTypes.INDEX_TYPE_SUFFIX));
		b.invokevirtual(concat);
		b.invokespecial(rteInit);
		if (records != null) {
			b.astore(2);
			StringEntry integerKind = cp.stringEntry(OperandTypes.Kind.INTEGER.name());
			records.emit(b, 2, () -> b.aload(0), () -> {
				// (INTEGER 0 (dim)): {"INTEGER", {0L, {{dimL, nil}, nil}}}
				emitConsHead(b, object, () -> b.ldc(integerKind));
				emitConsHead(b, object, () -> {
					b.lconst_0();
					b.invokestatic(longValueOf);
				});
				emitConsHead(b, object, () -> {
					emitConsHead(b, object, () -> {
						b.iload(1);
						b.i2l();
						b.invokestatic(longValueOf);
					});
					b.aconst_null();
					emitConsTail(b);
				});
				b.aconst_null();
				emitConsTail(b);
				emitConsTail(b);
				emitConsTail(b);
			});
			b.aload(2);
		}
		b.areturn();
		methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.utf8Entry(OOB), cp.utf8Entry(OOB_DESC), b));

		// _ckBound(Object i, int dim): (int) i for a Long in [0, dim), else
		// throw _oob(i, dim) -- a BigInteger, which no bound reaches, included.
		MethodRefEntry longLongValue = cp.methodRef(longClass, "longValue", "()J");
		MethodCode k = new MethodCode();
		k.aload(0);
		k.instanceOf(longClass);
		MethodCode.Label ifNotLong = k.newLabel();
		k.ifeq(ifNotLong);
		k.aload(0);
		k.checkcast(longClass);
		k.invokevirtual(longLongValue);
		k.lstore(2);
		k.lload(2);
		k.lconst_0();
		k.lcmp();
		MethodCode.Label ifNegative = k.newLabel();
		k.iflt(ifNegative);
		k.lload(2);
		k.iload(1);
		k.i2l();
		k.lcmp();
		MethodCode.Label ifPast = k.newLabel();
		k.ifge(ifPast);
		k.lload(2);
		k.l2i();
		k.ireturn();
		k.labelBinding(ifNotLong);
		k.labelBinding(ifNegative);
		k.labelBinding(ifPast);
		k.aload(0);
		k.iload(1);
		k.invokestatic(self(cp, thisClass, OOB, OOB_DESC));
		k.athrow();
		methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.utf8Entry(CK_BOUND), cp.utf8Entry(CK_BOUND_DESC), k));

		// _ckBoundJ(long i, int dim): (int) i in [0, dim), else
		// throw _oob(Long.valueOf(i), dim). The check is Objects.checkIndex, the JIT's
		// own range-check intrinsic, so a typed loop's check is hoisted the way its array
		// access's is (a hand-written compare cost a typed double loop 27%); its host
		// exception becomes the access's report. The boxed _ckBound keeps the compare:
		// the intrinsic's extra inline depth made a general-vector store 4x slower.
		MethodRefEntry checkIndex = cp.methodRef(cp.classEntry("java/util/Objects"), "checkIndex", "(JJ)J");
		ClassEntry ioobe = cp.classEntry("java/lang/IndexOutOfBoundsException");
		MethodCode j = new MethodCode();
		MethodCode.Label tryStart = j.newBoundLabel();
		j.lload(0);
		j.iload(2);
		j.i2l();
		j.invokestatic(checkIndex);
		j.l2i();
		MethodCode.Label tryEnd = j.newBoundLabel();
		j.ireturn();
		MethodCode.Label handler = j.newBoundLabel();
		j.pop();
		j.lload(0);
		j.invokestatic(longValueOf);
		j.iload(2);
		j.invokestatic(self(cp, thisClass, OOB, OOB_DESC));
		j.athrow();
		j.exceptionCatch(tryStart, tryEnd, handler, ioobe);
		methods.add(
				new JvmNumericRuntimeBuilder.NumericMethod(cp.utf8Entry(CK_BOUND_J), cp.utf8Entry(CK_BOUND_J_DESC), j));

		// _opTypeErr(Throwable e, String op, String opType): an unnamed report renamed
		// "OP: The value X is not of type T" (T = opType, narrowed NUMBER -> REAL when
		// the funnel wanted a real); anything else is answered unchanged. A COMPOUND
		// type -- an out-of-range subscript's (INTEGER 0 (d)) -- is the report's own,
		// kept verbatim under any operator, and so is the record's type object.
		MethodRefEntry getMessage = cp.methodRef(throwable, "getMessage", "()Ljava/lang/String;");
		MethodRefEntry startsWith = cp.methodRef(string, "startsWith", "(Ljava/lang/String;)Z");
		MethodRefEntry endsWith = cp.methodRef(string, "endsWith", "(Ljava/lang/String;)Z");
		MethodRefEntry lastIndexOf = cp.methodRef(string, "lastIndexOf", "(Ljava/lang/String;)I");
		MethodRefEntry substring = cp.methodRef(string, "substring", "(II)Ljava/lang/String;");
		MethodRefEntry charAt = cp.methodRef(string, "charAt", "(I)C");
		// Locals: 0=e, 1=op, 2=opType, 3=msg, 4=the type's start, 5=type, 6=the renamed
		// exception, 7=e's record, 8=1 when the type is the report's own compound one,
		// 9=the thread's record map.
		MethodCode o = new MethodCode();
		o.aload(0);
		o.invokevirtual(getMessage);
		o.astore(3);
		o.aload(3);
		MethodCode.Label ifNull = o.newLabel();
		o.ifnull(ifNull);
		o.aload(3);
		o.ldc(valuePrefix);
		o.invokevirtual(startsWith);
		MethodCode.Label ifNotRaw = o.newLabel();
		o.ifeq(ifNotRaw);
		o.aload(3);
		o.ldc(typeInfix);
		o.invokevirtual(lastIndexOf);
		o.dup();
		o.istore(4);
		// A text that only opens like a report is some other error's.
		MethodCode.Label ifNoType = o.newLabel();
		o.iflt(ifNoType);
		o.iinc(4, OperandTypes.TYPE_INFIX.length());
		o.aload(2);
		o.astore(5);
		// compound = msg.charAt(start) == '('
		o.aload(3);
		o.iload(4);
		o.invokevirtual(charAt);
		o.loadConstant('(');
		MethodCode.Label ifNotCompound = o.newLabel();
		o.if_icmpne(ifNotCompound);
		o.iconst_1();
		o.istore(8);
		MethodCode.Label toReportsOwn = o.newLabel();
		o.goto_(toReportsOwn);
		o.labelBinding(ifNotCompound);
		o.iconst_0();
		o.istore(8);
		// opType is always a wrapper's ldc constant, and string constants are interned,
		// so identity decides it. A funnel-typed operator's is FUNNEL_TYPE: the type is
		// the funnel's own kind, the report's last word, a to-double funnel's NUMBER
		// read as REAL.
		o.aload(2);
		o.ldc(cp.stringEntry(OperandTypes.FUNNEL_TYPE));
		MethodCode.Label ifNotFunnelTyped = o.newLabel();
		o.if_acmpne(ifNotFunnelTyped);
		o.labelBinding(toReportsOwn);
		o.aload(3);
		o.iload(4);
		o.invokevirtual(cp.methodRef(string, "substring", "(I)Ljava/lang/String;"));
		o.astore(5);
		o.aload(3);
		o.ldc(cp.stringEntry(" " + OperandTypes.Kind.NUMBER.name()));
		o.invokevirtual(endsWith);
		MethodCode.Label ifKindNotNumber = o.newLabel();
		o.ifeq(ifKindNotNumber);
		o.ldc(cp.stringEntry(OperandTypes.Kind.REAL.name()));
		o.astore(5);
		MethodCode.Label toBuild = o.newLabel();
		o.goto_(toBuild);
		o.labelBinding(ifNotFunnelTyped);
		o.aload(2);
		o.ldc(cp.stringEntry(OperandTypes.Kind.NUMBER.name()));
		MethodCode.Label ifNotNumber = o.newLabel();
		o.if_acmpne(ifNotNumber);
		o.aload(3);
		o.ldc(cp.stringEntry(" " + OperandTypes.Kind.REAL.name()));
		o.invokevirtual(endsWith);
		MethodCode.Label ifNotReal = o.newLabel();
		o.ifeq(ifNotReal);
		o.ldc(cp.stringEntry(OperandTypes.Kind.REAL.name()));
		o.astore(5);
		o.labelBinding(ifNotNumber);
		o.labelBinding(ifNotReal);
		o.labelBinding(ifKindNotNumber);
		o.labelBinding(toBuild);
		o.new_(rte);
		o.dup();
		o.aload(1);
		o.ldc(cp.stringEntry(OperandTypes.OPERATOR_SEPARATOR));
		o.invokevirtual(concat);
		o.aload(3);
		o.iconst_0();
		o.iload(4);
		o.invokevirtual(substring);
		o.invokevirtual(concat);
		o.aload(5);
		o.invokevirtual(concat);
		o.invokespecial(rteInit);
		o.astore(6);
		if (records != null) {
			// The datum travels from the funnel's record of e, and so does a compound
			// type's object (a list the text only spells), into the renamed exception's.
			records.emitRead(o, 0, 9, 7);
			o.aload(7);
			MethodCode.Label ifNoRecord = o.newLabel();
			o.ifnull(ifNoRecord);
			o.iload(8);
			MethodCode.Label ifSymbolType = o.newLabel();
			o.ifeq(ifSymbolType);
			o.aload(7);
			o.iconst_1();
			o.aaload();
			o.astore(5);
			o.labelBinding(ifSymbolType);
			records.emit(o, 6, () -> {
				o.aload(7);
				o.iconst_0();
				o.aaload();
			}, () -> {
				o.aload(5);
			});
			o.labelBinding(ifNoRecord);
		}
		o.aload(6);
		o.areturn();
		o.labelBinding(ifNull);
		o.labelBinding(ifNotRaw);
		o.labelBinding(ifNoType);
		o.aload(0);
		o.areturn();
		methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.utf8Entry(OP_TYPE_ERR),
				cp.utf8Entry(OP_TYPE_ERR_DESC), o));

		if (records != null) {
			// _teSlot(Throwable e, int i): e's record for 0 (null when it has none), its
			// datum for 1, its type for 2.
			MethodCode s = new MethodCode();
			records.emitRead(s, 0, 2, 3);
			s.iload(1);
			MethodCode.Label ifElement = s.newLabel();
			s.ifne(ifElement);
			s.aload(3);
			s.areturn();
			s.labelBinding(ifElement);
			s.aload(3);
			MethodCode.Label ifNoRecord = s.newLabel();
			s.ifnull(ifNoRecord);
			s.aload(3);
			s.iload(1);
			s.iconst_m1();
			s.iadd();
			s.aaload();
			s.areturn();
			s.labelBinding(ifNoRecord);
			s.aconst_null();
			s.areturn();
			methods
				.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.utf8Entry(TE_SLOT), cp.utf8Entry(TE_SLOT_DESC), s));
		}
		return methods;
	}

	/**
	 * What tells a cons from the other {@code Object[]}-shaped values of one program: a
	 * ratio ({@code BigInteger[]}), a function reference ({@code Integer} in slot 0), an
	 * instance (its {@code String[]} layout in slot 0) and an async value (an
	 * {@code Object[3]} headed by a marker string). The same exclusions
	 * {@code consp}/{@code listp}/{@code atom} make ({@link JvmConspCompiler}), and gated
	 * the same way: a program that cannot build an instance or an async value tests
	 * neither.
	 *
	 * @param objArr the {@code Object[]} class
	 * @param ratio the {@code BigInteger[]} class
	 * @param funcRefHead the {@code Integer} class
	 * @param instanceLayout the {@code String[]} class, or null when the program builds
	 * no instance
	 * @param asyncMarkers the async values' marker strings, empty when the program builds
	 * none
	 */
	record ConsShape(ClassEntry objArr, ClassEntry ratio, ClassEntry funcRefHead, @Nullable ClassEntry instanceLayout,
			List<StringEntry> asyncMarkers) {

		/**
		 * The shape of one program's conses.
		 * @param cp the constant pool
		 * @param instanceLayout the {@code String[]} class, or null when the program
		 * builds no instance
		 * @param asyncValues whether the program carries the async runtime
		 * @return the shape
		 */
		static ConsShape of(ConstantPool cp, @Nullable ClassEntry instanceLayout, boolean asyncValues) {
			return new ConsShape(cp.classEntry("[Ljava/lang/Object;"), cp.classEntry("[Ljava/math/BigInteger;"),
					cp.classEntry("java/lang/Integer"), instanceLayout,
					asyncValues ? List.of(cp.stringEntry(JvmAsyncRuntimeBuilder.SMARKER),
							cp.stringEntry(JvmAsyncRuntimeBuilder.RMARKER)) : List.of());
		}

		/**
		 * Emits the test of the non-null value in local {@code value}: falls through with
		 * the value as an {@code Object[]} in local {@code arr} and its slot 0 in local
		 * {@code head} when it is a cons, and branches to {@code miss} for every way it
		 * is not. Peak operand stack: 2.
		 * @param a the assembler
		 * @param value the local holding the value
		 * @param arr the local to receive it as an {@code Object[]}
		 * @param head the local to receive its slot 0
		 * @param miss the not-a-cons label
		 */
		void emitTest(MethodCode a, int value, int arr, int head, MethodCode.Label miss) {
			a.aload(value);
			a.instanceOf(this.objArr);
			a.ifeq(miss);
			a.aload(value);
			a.instanceOf(this.ratio);
			a.ifne(miss);
			a.aload(value);
			a.checkcast(this.objArr);
			a.astore(arr);
			a.aload(arr);
			a.loadConstant(0);
			a.aaload();
			a.astore(head);
			a.aload(head);
			a.instanceOf(this.funcRefHead);
			a.ifne(miss);
			if (this.instanceLayout != null) {
				a.aload(head);
				a.instanceOf(this.instanceLayout);
				a.ifne(miss);
			}
			if (!this.asyncMarkers.isEmpty()) {
				// Length first, like the runtime's own marker test: a cons is an
				// Object[2], so no marker comparison is reached on the cons path.
				MethodCode.Label notTriple = a.newLabel();
				a.aload(arr);
				a.arraylength();
				a.loadConstant(3);
				a.if_icmpne(notTriple);
				for (StringEntry marker : this.asyncMarkers) {
					a.aload(head);
					a.ldc(marker);
					a.if_acmpeq(miss);
				}
				a.labelBinding(notTriple);
			}
		}

	}

	/**
	 * Builds {@code _car}/{@code _cdr}: nil answers nil, a cons its field, anything else
	 * {@code throw _opTypeErr(_teRaw(x, "LIST"), "CAR", FUNNEL_TYPE)}.
	 */
	private static JvmNumericRuntimeBuilder.NumericMethod field(ConstantPool cp, String name, int index,
			ConsShape shape, MethodRefEntry teRaw, MethodRefEntry opTypeErr, StringEntry listKind,
			StringEntry funnelType) {
		MethodCode a = new MethodCode();
		MethodCode.Label notNull = a.newLabel();
		MethodCode.Label miss = a.newLabel();
		a.aload(0);
		a.ifnonnull(notNull);
		a.aconst_null();
		a.areturn();
		a.labelBinding(notNull);
		shape.emitTest(a, 0, 1, 2, miss);
		if (index == 0) {
			a.aload(2);
		}
		else {
			a.aload(1);
			a.loadConstant(1);
			a.aaload();
		}
		a.areturn();
		a.labelBinding(miss);
		emitNamedListThrow(a, cp, teRaw, opTypeErr, listKind, funnelType,
				index == 0 ? am.ik.rontolisp.LispNames.CAR : am.ik.rontolisp.LispNames.CDR);
		return new JvmNumericRuntimeBuilder.NumericMethod(cp.utf8Entry(name), cp.utf8Entry(FIELD_DESC), a);
	}

	/**
	 * Builds {@code _endp}: nil or a cons answers itself, anything else
	 * {@code throw _opTypeErr(_teRaw(x, "LIST"), "ENDP", FUNNEL_TYPE)}.
	 */
	private static JvmNumericRuntimeBuilder.NumericMethod listCheck(ConstantPool cp, ConsShape shape,
			MethodRefEntry teRaw, MethodRefEntry opTypeErr, StringEntry listKind, StringEntry funnelType) {
		MethodCode a = new MethodCode();
		MethodCode.Label answer = a.newLabel();
		MethodCode.Label miss = a.newLabel();
		a.aload(0);
		a.ifnull(answer);
		shape.emitTest(a, 0, 1, 2, miss);
		a.labelBinding(answer);
		a.aload(0);
		a.areturn();
		a.labelBinding(miss);
		emitNamedListThrow(a, cp, teRaw, opTypeErr, listKind, funnelType, am.ik.rontolisp.LispNames.ENDP);
		return new JvmNumericRuntimeBuilder.NumericMethod(cp.utf8Entry(ENDP), cp.utf8Entry(FIELD_DESC), a);
	}

	/**
	 * Builds {@code _isCons}: true for a cons, false for anything else.
	 */
	private static JvmNumericRuntimeBuilder.NumericMethod consTest(ConstantPool cp, ConsShape shape) {
		MethodCode a = new MethodCode();
		MethodCode.Label miss = a.newLabel();
		a.aload(0);
		a.ifnull(miss);
		shape.emitTest(a, 0, 1, 2, miss);
		a.loadConstant(1);
		a.ireturn();
		a.labelBinding(miss);
		a.loadConstant(0);
		a.ireturn();
		return new JvmNumericRuntimeBuilder.NumericMethod(cp.utf8Entry(IS_CONS), cp.utf8Entry(IS_CONS_DESC), a);
	}

	/**
	 * Builds {@code _ckList} ({@code nilOk}: nil or a cons answers itself) or
	 * {@code _ckCons} (a cons answers itself as an {@code Object[]}); anything else is
	 * {@code throw _teRaw(x, kind)}, for a wrapper to name.
	 */
	private static JvmNumericRuntimeBuilder.NumericMethod consCheck(ConstantPool cp, String name, String desc,
			boolean nilOk, ConsShape shape, MethodRefEntry teRaw, StringEntry kind) {
		MethodCode a = new MethodCode();
		MethodCode.Label ifNull = a.newLabel();
		MethodCode.Label miss = a.newLabel();
		a.aload(0);
		a.ifnull(nilOk ? ifNull : miss);
		shape.emitTest(a, 0, 1, 2, miss);
		if (nilOk) {
			a.labelBinding(ifNull);
			a.aload(0);
		}
		else {
			a.aload(1);
		}
		a.areturn();
		a.labelBinding(miss);
		a.aload(0);
		a.ldc(kind);
		a.invokestatic(teRaw);
		a.athrow();
		return new JvmNumericRuntimeBuilder.NumericMethod(cp.utf8Entry(name), cp.utf8Entry(desc), a);
	}

	/**
	 * Emits {@code throw _opTypeErr(_teRaw(<local 0>, "LIST"), operator, FUNNEL_TYPE)}: a
	 * list walk's self-named type-error. Peak operand stack: 3.
	 * @param c the bytecode sink
	 * @param cp the constant pool
	 * @param teRaw {@code _teRaw}
	 * @param opTypeErr {@code _opTypeErr}
	 * @param listKind the {@code "LIST"} constant
	 * @param funnelType the {@link OperandTypes#FUNNEL_TYPE} constant
	 * @param operator the operator the report names
	 */
	static void emitNamedListThrow(MethodCode c, ConstantPool cp, MethodRefEntry teRaw, MethodRefEntry opTypeErr,
			StringEntry listKind, StringEntry funnelType, String operator) {
		c.aload(0);
		c.ldc(listKind);
		c.invokestatic(teRaw);
		c.ldc(cp.stringEntry(operator));
		c.ldc(funnelType);
		c.invokestatic(opTypeErr);
		c.athrow();
	}

	/**
	 * Builds an argument check: {@code x} when it is an instance of one of
	 * {@code accepted}, else {@code throw _teRaw(x, kind)}.
	 */
	private static JvmNumericRuntimeBuilder.NumericMethod check(ConstantPool cp, String name, String desc,
			List<ClassEntry> accepted, MethodRefEntry teRaw, StringEntry kind) {
		MethodCode c = new MethodCode();
		MethodCode.Label ok = c.newLabel();
		for (ClassEntry type : accepted) {
			c.aload(0);
			c.instanceOf(type);
			c.ifne(ok);
		}
		c.aload(0);
		c.ldc(kind);
		c.invokestatic(teRaw);
		c.athrow();
		c.labelBinding(ok);
		c.aload(0);
		c.areturn();
		return new JvmNumericRuntimeBuilder.NumericMethod(cp.utf8Entry(name), cp.utf8Entry(desc), c);
	}

	/**
	 * Opens a cons: {@code new Object[2]} with {@code car} stored, leaving the array on
	 * the stack for {@link #emitConsTail} to store the cdr pushed after it. Peak operand
	 * stack: 3 plus the car's.
	 */
	private static void emitConsHead(MethodCode c, ClassEntry object, Runnable car) {
		c.iconst_2();
		c.anewarray(object);
		c.dup();
		c.iconst_0();
		car.run();
		c.aastore();
		c.dup();
		c.iconst_1();
	}

	/**
	 * Closes the cons {@link #emitConsHead} opened, over the cdr on top of the stack.
	 */
	private static void emitConsTail(MethodCode c) {
		c.aastore();
	}

	/**
	 * Where a wrong-type exception's {@code {datum, type}} record lives: the
	 * {@code _teTl} field, whose value on a thread is the map from each exception to its
	 * record ({@link JvmThrowableRecords}), and the calls that write and read it.
	 *
	 * @param teTl the {@code _teTl} field
	 * @param tlGet {@code ThreadLocal.get}
	 * @param tlMap {@code _tlMap}
	 * @param weakMap the {@code java/util/WeakHashMap} class
	 * @param mapPut {@code WeakHashMap.put}
	 * @param mapGet {@code WeakHashMap.get}
	 * @param object the {@code java/lang/Object} class
	 * @param objArr the {@code Object[]} class
	 */
	private record Records(FieldRefEntry teTl, MethodRefEntry tlGet, MethodRefEntry tlMap, ClassEntry weakMap,
			MethodRefEntry mapPut, MethodRefEntry mapGet, ClassEntry object, ClassEntry objArr) {

		static Records of(ConstantPool cp, ClassEntry thisClass, FieldRefEntry teTl, MethodRefEntry tlGet,
				ClassEntry object, ClassEntry objArr) {
			return new Records(teTl, tlGet, JvmThrowableRecords.tlMap(cp, thisClass),
					cp.classEntry(JvmThrowableRecords.WEAK_MAP), JvmThrowableRecords.mapPut(cp),
					JvmThrowableRecords.mapGet(cp), object, objArr);
		}

		/**
		 * Emits {@code _tlMap(_teTl).put(<local excSlot>, new Object[] {datum, type})}.
		 * The record never holds the exception: a map value that reaches its weak key
		 * keeps the entry alive. Peak operand stack: 5 plus what {@code datum} and
		 * {@code type} push.
		 */
		void emit(MethodCode c, int excSlot, Runnable datum, Runnable type) {
			c.getstatic(this.teTl);
			c.invokestatic(this.tlMap);
			c.aload(excSlot);
			c.iconst_2();
			c.anewarray(this.object);
			c.dup();
			c.iconst_0();
			datum.run();
			c.aastore();
			c.dup();
			c.iconst_1();
			type.run();
			c.aastore();
			c.invokevirtual(this.mapPut);
			c.pop();
		}

		/**
		 * Emits the read of the record of the exception in local {@code excSlot} into
		 * local {@code recordSlot} (null when the thread has no map, or the map nothing
		 * for it), the map passing through local {@code mapSlot}. Peak operand stack: 2.
		 */
		void emitRead(MethodCode c, int excSlot, int mapSlot, int recordSlot) {
			c.getstatic(this.teTl);
			c.invokevirtual(this.tlGet);
			c.checkcast(this.weakMap);
			c.dup();
			c.astore(mapSlot);
			MethodCode.Label ifNoMap = c.newLabel();
			c.ifnull(ifNoMap);
			c.aload(mapSlot);
			c.aload(excSlot);
			c.invokevirtual(this.mapGet);
			MethodCode.Label toCast = c.newLabel();
			c.goto_(toCast);
			c.labelBinding(ifNoMap);
			c.aconst_null();
			c.labelBinding(toCast);
			c.checkcast(this.objArr);
			c.astore(recordSlot);
		}

	}

	/**
	 * The per-(helper, operator) wrappers of one compilation, built on first use into the
	 * numeric runtime's method list. A wrapper is the helper's own invocation under a
	 * catch-any entry whose handler throws {@code _opTypeErr(e, "OP", "TYPE")}.
	 */
	static final class Wrappers {

		private final ConstantPool cp;

		private final ClassEntry thisClass;

		private final List<JvmNumericRuntimeBuilder.NumericMethod> sink;

		private final Map<String, MethodRefEntry> made = new HashMap<>();

		Wrappers(ConstantPool cp, ClassEntry thisClass, List<JvmNumericRuntimeBuilder.NumericMethod> sink) {
			this.cp = cp;
			this.thisClass = thisClass;
			this.sink = sink;
		}

		/**
		 * The helper reference a call compiled inside {@code operator}'s form invokes:
		 * the wrapper when the operator is a named one, the helper itself otherwise.
		 * @param operator the innermost form's operator, or null
		 * @param helper the helper's method name
		 * @param desc the helper's descriptor
		 * @param target the helper's reference
		 * @return the reference to invoke
		 */
		MethodRefEntry wrap(@Nullable String operator, String helper, String desc, MethodRefEntry target) {
			String op = OperandTypes.reportedOperator(operator);
			if (op == null) {
				return target;
			}
			String name = helper + "$op" + OperandTypes.operators().indexOf(op);
			MethodRefEntry ref = this.made.get(name);
			if (ref != null) {
				return ref;
			}
			ref = self(this.cp, this.thisClass, name, desc);
			this.made.put(name, ref);
			MethodCode c = new MethodCode();
			MethodTypeDesc type = MethodTypeDesc.ofDescriptor(desc);
			MethodCode.Label start = c.newBoundLabel();
			int slot = 0;
			for (ClassDesc parameter : type.parameterList()) {
				TypeKind kind = TypeKind.from(parameter);
				c.loadLocal(kind, slot);
				slot += kind.slotSize();
			}
			c.invokestatic(target);
			MethodCode.Label end = c.newBoundLabel();
			c.return_(TypeKind.from(type.returnType()));
			MethodCode.Label handler = c.newBoundLabel();
			c.ldc(this.cp.stringEntry(op));
			c.ldc(this.cp.stringEntry(java.util.Objects.requireNonNull(OperandTypes.operatorType(op))));
			c.invokestatic(self(this.cp, this.thisClass, OP_TYPE_ERR, OP_TYPE_ERR_DESC));
			c.athrow();
			c.exceptionCatch(start, end, handler, null);
			this.sink
				.add(new JvmNumericRuntimeBuilder.NumericMethod(this.cp.utf8Entry(name), this.cp.utf8Entry(desc), c));
			return ref;
		}

	}

}
