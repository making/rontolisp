package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.StringConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.compiler.OperandTypes;
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
 * <li>Under a landing pad the thread-local {@code _teTl} holds {@code {exception, datum,
 * type}} for the last such exception, identity-checked by {@code _teSlot(e, i)}, which is
 * how the pad fills a {@code type-error}'s {@code datum} and {@code expected-type}: a
 * {@code RuntimeException} has nowhere to carry an object, and a compiled program ships
 * no exception class of its own. A program without a pad keeps neither.</li>
 * </ul>
 */
final class JvmOperandTypeRuntime {

	/** The funnels' exception builder. */
	static final String TE_RAW = "_teRaw";

	static final String TE_RAW_DESC = "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/RuntimeException;";

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
	record ThrowRefs(MethodrefConstant teRaw, StringConstant integerKind, StringConstant numberKind,
			StringConstant realKind) {

		static ThrowRefs of(ConstantPool cp, ClassConstant thisClass) {
			return new ThrowRefs(self(cp, thisClass, TE_RAW, TE_RAW_DESC),
					cp.addString(OperandTypes.Kind.INTEGER.name()), cp.addString(OperandTypes.Kind.NUMBER.name()),
					cp.addString(OperandTypes.Kind.REAL.name()));
		}

		/**
		 * Emits {@code throw _teRaw(<slot>, kind)}. Peak operand stack: 2.
		 * @param c the bytecode sink
		 * @param slot the local holding the rejected operand
		 * @param kind the kind constant
		 */
		void emitThrow(List<Integer> c, int slot, StringConstant kind) {
			c.add(Opcode.ALOAD);
			c.add(slot);
			emitThrowLoaded(c, kind);
		}

		/**
		 * Emits {@code throw _teRaw(<top of stack>, kind)}. Peak operand stack: 2.
		 * @param c the bytecode sink
		 * @param kind the kind constant
		 */
		void emitThrowLoaded(List<Integer> c, StringConstant kind) {
			JvmRuntimeBuilder.emitLdc(c, kind.index());
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, this.teRaw.index());
			c.add(Opcode.ATHROW);
		}

	}

	static MethodrefConstant self(ConstantPool cp, ClassConstant thisClass, String name, String desc) {
		return cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(name), cp.addUtf8(desc)));
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
	static List<JvmNumericRuntimeBuilder.NumericMethod> build(ConstantPool cp, ClassConstant thisClass,
			@Nullable FieldrefConstant teTl) {
		ClassConstant rte = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		ClassConstant string = cp.addClass(cp.addUtf8("java/lang/String"));
		ClassConstant objArr = cp.addClass(cp.addUtf8("[Ljava/lang/Object;"));
		ClassConstant object = cp.addClass(cp.addUtf8("java/lang/Object"));
		ClassConstant throwable = cp.addClass(cp.addUtf8("java/lang/Throwable"));
		ClassConstant threadLocal = cp.addClass(cp.addUtf8("java/lang/ThreadLocal"));
		MethodrefConstant rteInit = cp.addMethodref(rte,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant concat = cp.addMethodref(string,
				cp.addNameAndType(cp.addUtf8("concat"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;")));
		MethodrefConstant lispToString = self(cp, thisClass, "_lispToString", "(Ljava/lang/Object;)Ljava/lang/String;");
		MethodrefConstant tlGet = cp.addMethodref(threadLocal,
				cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("()Ljava/lang/Object;")));
		MethodrefConstant tlSet = cp.addMethodref(threadLocal,
				cp.addNameAndType(cp.addUtf8("set"), cp.addUtf8("(Ljava/lang/Object;)V")));
		if (teTl != null) {
			// Minted now, while the pool is still open: <clinit> initializes the field
			// through ConditionChannel's ThreadLocal constants, which resolve to these.
			cp.addMethodref(threadLocal, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
			cp.addUtf8("<clinit>");
		}
		StringConstant valuePrefix = cp.addString(OperandTypes.VALUE_PREFIX);
		StringConstant typeInfix = cp.addString(OperandTypes.TYPE_INFIX);

		List<JvmNumericRuntimeBuilder.NumericMethod> methods = new ArrayList<>();
		MethodrefConstant teRaw = self(cp, thisClass, TE_RAW, TE_RAW_DESC);
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		ClassConstant bigClass = cp.addClass(cp.addUtf8("java/math/BigInteger"));
		ClassConstant ratioClass = cp.addClass(cp.addUtf8("[Ljava/math/BigInteger;"));
		MethodrefConstant opTypeErr = self(cp, thisClass, OP_TYPE_ERR, OP_TYPE_ERR_DESC);
		StringConstant listKind = cp.addString(OperandTypes.Kind.LIST.name());
		StringConstant funnelType = cp.addString(OperandTypes.FUNNEL_TYPE);
		methods.add(field(cp, CAR, 0, objArr, teRaw, opTypeErr, listKind, funnelType));
		methods.add(field(cp, CDR, 1, objArr, teRaw, opTypeErr, listKind, funnelType));
		methods.add(check(cp, CK_IDX, CK_IDX_DESC, List.of(longClass, bigClass), null, teRaw,
				cp.addString(OperandTypes.Kind.INTEGER.name())));
		methods.add(check(cp, CK_RAT, CK_RAT_DESC, List.of(longClass, bigClass, ratioClass), null, teRaw,
				cp.addString(OperandTypes.Kind.RATIONAL.name())));

		// _teRaw(Object x, String kind): new RuntimeException("The value " + prin1(x) +
		// " is not of type " + kind), recorded under a pad.
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(c, rte.index());
		c.add(Opcode.DUP);
		JvmRuntimeBuilder.emitLdc(c, valuePrefix.index());
		c.add(Opcode.ALOAD_0);
		invoke(c, Opcode.INVOKESTATIC, lispToString);
		invoke(c, Opcode.INVOKEVIRTUAL, concat);
		JvmRuntimeBuilder.emitLdc(c, typeInfix.index());
		invoke(c, Opcode.INVOKEVIRTUAL, concat);
		c.add(Opcode.ALOAD_1);
		invoke(c, Opcode.INVOKEVIRTUAL, concat);
		invoke(c, Opcode.INVOKESPECIAL, rteInit);
		if (teTl != null) {
			c.add(Opcode.ASTORE_2);
			emitRecord(c, teTl, object, tlSet, 2, () -> c.add(Opcode.ALOAD_0), () -> c.add(Opcode.ALOAD_1));
			c.add(Opcode.ALOAD_2);
		}
		c.add(Opcode.ARETURN);
		methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.addUtf8(TE_RAW), cp.addUtf8(TE_RAW_DESC), c, 6, 3,
				List.of()));

		// _opTypeErr(Throwable e, String op, String opType): an unnamed report renamed
		// "OP: The value X is not of type T" (T = opType, narrowed NUMBER -> REAL when
		// the funnel wanted a real); anything else is answered unchanged.
		MethodrefConstant getMessage = cp.addMethodref(throwable,
				cp.addNameAndType(cp.addUtf8("getMessage"), cp.addUtf8("()Ljava/lang/String;")));
		MethodrefConstant startsWith = cp.addMethodref(string,
				cp.addNameAndType(cp.addUtf8("startsWith"), cp.addUtf8("(Ljava/lang/String;)Z")));
		MethodrefConstant endsWith = cp.addMethodref(string,
				cp.addNameAndType(cp.addUtf8("endsWith"), cp.addUtf8("(Ljava/lang/String;)Z")));
		MethodrefConstant lastIndexOf = cp.addMethodref(string,
				cp.addNameAndType(cp.addUtf8("lastIndexOf"), cp.addUtf8("(I)I")));
		MethodrefConstant substring = cp.addMethodref(string,
				cp.addNameAndType(cp.addUtf8("substring"), cp.addUtf8("(II)Ljava/lang/String;")));
		List<Integer> o = new ArrayList<>();
		o.add(Opcode.ALOAD_0);
		invoke(o, Opcode.INVOKEVIRTUAL, getMessage);
		o.add(Opcode.ASTORE_3);
		o.add(Opcode.ALOAD_3);
		int ifNull = branch(o, Opcode.IFNULL);
		o.add(Opcode.ALOAD_3);
		JvmRuntimeBuilder.emitLdc(o, valuePrefix.index());
		invoke(o, Opcode.INVOKEVIRTUAL, startsWith);
		int ifNotRaw = branch(o, Opcode.IFEQ);
		o.add(Opcode.ALOAD_3);
		o.add(Opcode.BIPUSH);
		o.add((int) ' ');
		invoke(o, Opcode.INVOKEVIRTUAL, lastIndexOf);
		o.add(Opcode.ISTORE);
		o.add(4);
		o.add(Opcode.ALOAD_2);
		o.add(Opcode.ASTORE);
		o.add(5);
		// opType is always a wrapper's ldc constant, and string constants are interned,
		// so identity decides it. A funnel-typed operator's is FUNNEL_TYPE: the type is
		// the funnel's own kind, the report's last word, a to-double funnel's NUMBER
		// read as REAL.
		o.add(Opcode.ALOAD_2);
		JvmRuntimeBuilder.emitLdc(o, cp.addString(OperandTypes.FUNNEL_TYPE).index());
		int ifNotFunnelTyped = branch(o, Opcode.IF_ACMPNE);
		o.add(Opcode.ALOAD_3);
		o.add(Opcode.ILOAD);
		o.add(4);
		o.add(Opcode.ICONST_1);
		o.add(Opcode.IADD);
		invoke(o, Opcode.INVOKEVIRTUAL, cp.addMethodref(string,
				cp.addNameAndType(cp.addUtf8("substring"), cp.addUtf8("(I)Ljava/lang/String;"))));
		o.add(Opcode.ASTORE);
		o.add(5);
		o.add(Opcode.ALOAD_3);
		JvmRuntimeBuilder.emitLdc(o, cp.addString(" " + OperandTypes.Kind.NUMBER.name()).index());
		invoke(o, Opcode.INVOKEVIRTUAL, endsWith);
		int ifKindNotNumber = branch(o, Opcode.IFEQ);
		JvmRuntimeBuilder.emitLdc(o, cp.addString(OperandTypes.Kind.REAL.name()).index());
		o.add(Opcode.ASTORE);
		o.add(5);
		int toBuild = branch(o, Opcode.GOTO);
		JvmRuntimeBuilder.patchBranch(o, ifNotFunnelTyped, o.size());
		o.add(Opcode.ALOAD_2);
		JvmRuntimeBuilder.emitLdc(o, cp.addString(OperandTypes.Kind.NUMBER.name()).index());
		int ifNotNumber = branch(o, Opcode.IF_ACMPNE);
		o.add(Opcode.ALOAD_3);
		JvmRuntimeBuilder.emitLdc(o, cp.addString(" " + OperandTypes.Kind.REAL.name()).index());
		invoke(o, Opcode.INVOKEVIRTUAL, endsWith);
		int ifNotReal = branch(o, Opcode.IFEQ);
		JvmRuntimeBuilder.emitLdc(o, cp.addString(OperandTypes.Kind.REAL.name()).index());
		o.add(Opcode.ASTORE);
		o.add(5);
		int build = o.size();
		JvmRuntimeBuilder.patchBranch(o, ifNotNumber, build);
		JvmRuntimeBuilder.patchBranch(o, ifNotReal, build);
		JvmRuntimeBuilder.patchBranch(o, ifKindNotNumber, build);
		JvmRuntimeBuilder.patchBranch(o, toBuild, build);
		o.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(o, rte.index());
		o.add(Opcode.DUP);
		o.add(Opcode.ALOAD_1);
		JvmRuntimeBuilder.emitLdc(o, cp.addString(OperandTypes.OPERATOR_SEPARATOR).index());
		invoke(o, Opcode.INVOKEVIRTUAL, concat);
		o.add(Opcode.ALOAD_3);
		o.add(Opcode.ICONST_0);
		o.add(Opcode.ILOAD);
		o.add(4);
		o.add(Opcode.ICONST_1);
		o.add(Opcode.IADD);
		invoke(o, Opcode.INVOKEVIRTUAL, substring);
		invoke(o, Opcode.INVOKEVIRTUAL, concat);
		o.add(Opcode.ALOAD);
		o.add(5);
		invoke(o, Opcode.INVOKEVIRTUAL, concat);
		invoke(o, Opcode.INVOKESPECIAL, rteInit);
		o.add(Opcode.ASTORE);
		o.add(6);
		if (teTl != null) {
			// The datum travels from the funnel's record when it is THIS exception's.
			o.add(Opcode.GETSTATIC);
			JvmRuntimeBuilder.emitU2(o, teTl.index());
			invoke(o, Opcode.INVOKEVIRTUAL, tlGet);
			o.add(Opcode.CHECKCAST);
			JvmRuntimeBuilder.emitU2(o, objArr.index());
			o.add(Opcode.ASTORE);
			o.add(7);
			o.add(Opcode.ALOAD);
			o.add(7);
			int ifNoRecord = branch(o, Opcode.IFNULL);
			o.add(Opcode.ALOAD);
			o.add(7);
			o.add(Opcode.ICONST_0);
			o.add(Opcode.AALOAD);
			o.add(Opcode.ALOAD_0);
			int ifOther = branch(o, Opcode.IF_ACMPNE);
			emitRecord(o, teTl, object, tlSet, 6, () -> {
				o.add(Opcode.ALOAD);
				o.add(7);
				o.add(Opcode.ICONST_1);
				o.add(Opcode.AALOAD);
			}, () -> {
				o.add(Opcode.ALOAD);
				o.add(5);
			});
			int done = o.size();
			JvmRuntimeBuilder.patchBranch(o, ifNoRecord, done);
			JvmRuntimeBuilder.patchBranch(o, ifOther, done);
		}
		o.add(Opcode.ALOAD);
		o.add(6);
		o.add(Opcode.ARETURN);
		int unchanged = o.size();
		JvmRuntimeBuilder.patchBranch(o, ifNull, unchanged);
		JvmRuntimeBuilder.patchBranch(o, ifNotRaw, unchanged);
		o.add(Opcode.ALOAD_0);
		o.add(Opcode.ARETURN);
		methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.addUtf8(OP_TYPE_ERR), cp.addUtf8(OP_TYPE_ERR_DESC), o,
				8, 8, List.of()));

		if (teTl != null) {
			// _teSlot(Throwable e, int i): the record's slot i when the record is e's,
			// else null.
			List<Integer> s = new ArrayList<>();
			s.add(Opcode.GETSTATIC);
			JvmRuntimeBuilder.emitU2(s, teTl.index());
			invoke(s, Opcode.INVOKEVIRTUAL, tlGet);
			s.add(Opcode.CHECKCAST);
			JvmRuntimeBuilder.emitU2(s, objArr.index());
			s.add(Opcode.ASTORE_2);
			s.add(Opcode.ALOAD_2);
			int ifNoRecord = branch(s, Opcode.IFNULL);
			s.add(Opcode.ALOAD_2);
			s.add(Opcode.ICONST_0);
			s.add(Opcode.AALOAD);
			s.add(Opcode.ALOAD_0);
			int ifOther = branch(s, Opcode.IF_ACMPNE);
			s.add(Opcode.ALOAD_2);
			s.add(Opcode.ILOAD_1);
			s.add(Opcode.AALOAD);
			s.add(Opcode.ARETURN);
			int none = s.size();
			JvmRuntimeBuilder.patchBranch(s, ifNoRecord, none);
			JvmRuntimeBuilder.patchBranch(s, ifOther, none);
			s.add(Opcode.ACONST_NULL);
			s.add(Opcode.ARETURN);
			methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.addUtf8(TE_SLOT), cp.addUtf8(TE_SLOT_DESC), s, 2,
					3, List.of()));
		}
		return methods;
	}

	/**
	 * Builds {@code _car}/{@code _cdr}: nil answers nil, a cons ({@code Object[]}) its
	 * field, anything else
	 * {@code throw _opTypeErr(_teRaw(x, "LIST"), "CAR", FUNNEL_TYPE)}.
	 */
	private static JvmNumericRuntimeBuilder.NumericMethod field(ConstantPool cp, String name, int index,
			ClassConstant objArr, MethodrefConstant teRaw, MethodrefConstant opTypeErr, StringConstant listKind,
			StringConstant funnelType) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.ALOAD_0);
		int ifNotNull = branch(c, Opcode.IFNONNULL);
		c.add(Opcode.ACONST_NULL);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotNull, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, objArr.index());
		int ifNotCons = branch(c, Opcode.IFEQ);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, objArr.index());
		c.add(index == 0 ? Opcode.ICONST_0 : Opcode.ICONST_1);
		c.add(Opcode.AALOAD);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotCons, c.size());
		c.add(Opcode.ALOAD_0);
		JvmRuntimeBuilder.emitLdc(c, listKind.index());
		invoke(c, Opcode.INVOKESTATIC, teRaw);
		JvmRuntimeBuilder.emitLdc(c,
				cp.addString(index == 0 ? am.ik.rontolisp.LispNames.CAR : am.ik.rontolisp.LispNames.CDR).index());
		JvmRuntimeBuilder.emitLdc(c, funnelType.index());
		invoke(c, Opcode.INVOKESTATIC, opTypeErr);
		c.add(Opcode.ATHROW);
		return new JvmNumericRuntimeBuilder.NumericMethod(cp.addUtf8(name), cp.addUtf8(FIELD_DESC), c, 3, 1, List.of());
	}

	/**
	 * Builds an argument check: {@code x} (cast to {@code result} when given) when it is
	 * an instance of one of {@code accepted}, else {@code throw _teRaw(x, kind)}.
	 */
	private static JvmNumericRuntimeBuilder.NumericMethod check(ConstantPool cp, String name, String desc,
			List<ClassConstant> accepted, @Nullable ClassConstant result, MethodrefConstant teRaw,
			StringConstant kind) {
		List<Integer> c = new ArrayList<>();
		List<Integer> hits = new ArrayList<>();
		for (ClassConstant type : accepted) {
			c.add(Opcode.ALOAD_0);
			c.add(Opcode.INSTANCEOF);
			JvmRuntimeBuilder.emitU2(c, type.index());
			hits.add(branch(c, Opcode.IFNE));
		}
		c.add(Opcode.ALOAD_0);
		JvmRuntimeBuilder.emitLdc(c, kind.index());
		invoke(c, Opcode.INVOKESTATIC, teRaw);
		c.add(Opcode.ATHROW);
		int ok = c.size();
		for (int hit : hits) {
			JvmRuntimeBuilder.patchBranch(c, hit, ok);
		}
		c.add(Opcode.ALOAD_0);
		if (result != null) {
			c.add(Opcode.CHECKCAST);
			JvmRuntimeBuilder.emitU2(c, result.index());
		}
		c.add(Opcode.ARETURN);
		return new JvmNumericRuntimeBuilder.NumericMethod(cp.addUtf8(name), cp.addUtf8(desc), c, 2, 1, List.of());
	}

	/**
	 * Emits {@code _teTl.set(new Object[] {<local excSlot>, datum, type})}. Peak operand
	 * stack: 6.
	 */
	private static void emitRecord(List<Integer> c, FieldrefConstant teTl, ClassConstant object,
			MethodrefConstant tlSet, int excSlot, Runnable datum, Runnable type) {
		c.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(c, teTl.index());
		c.add(Opcode.ICONST_3);
		c.add(Opcode.ANEWARRAY);
		JvmRuntimeBuilder.emitU2(c, object.index());
		c.add(Opcode.DUP);
		c.add(Opcode.ICONST_0);
		c.add(Opcode.ALOAD);
		c.add(excSlot);
		c.add(Opcode.AASTORE);
		c.add(Opcode.DUP);
		c.add(Opcode.ICONST_1);
		datum.run();
		c.add(Opcode.AASTORE);
		c.add(Opcode.DUP);
		c.add(Opcode.ICONST_2);
		type.run();
		c.add(Opcode.AASTORE);
		invoke(c, Opcode.INVOKEVIRTUAL, tlSet);
	}

	/**
	 * The per-(helper, operator) wrappers of one compilation, built on first use into the
	 * numeric runtime's method list. A wrapper is the helper's own invocation under a
	 * catch-any entry whose handler throws {@code _opTypeErr(e, "OP", "TYPE")}.
	 */
	static final class Wrappers {

		private final ConstantPool cp;

		private final ClassConstant thisClass;

		private final List<JvmNumericRuntimeBuilder.NumericMethod> sink;

		private final Map<String, MethodrefConstant> made = new HashMap<>();

		Wrappers(ConstantPool cp, ClassConstant thisClass, List<JvmNumericRuntimeBuilder.NumericMethod> sink) {
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
		MethodrefConstant wrap(@Nullable String operator, String helper, String desc, MethodrefConstant target) {
			String op = OperandTypes.reportedOperator(operator);
			if (op == null) {
				return target;
			}
			String name = helper + "$op" + OperandTypes.operators().indexOf(op);
			MethodrefConstant ref = this.made.get(name);
			if (ref != null) {
				return ref;
			}
			ref = self(this.cp, this.thisClass, name, desc);
			this.made.put(name, ref);
			List<Integer> c = new ArrayList<>();
			int slot = 0;
			int i = 1;
			while (desc.charAt(i) != ')') {
				char t = desc.charAt(i);
				int load = switch (t) {
					case 'I', 'Z', 'B', 'C', 'S' -> Opcode.ILOAD;
					case 'J' -> Opcode.LLOAD;
					case 'F' -> Opcode.FLOAD;
					case 'D' -> Opcode.DLOAD;
					default -> Opcode.ALOAD;
				};
				c.add(load);
				c.add(slot);
				slot += (t == 'J' || t == 'D') ? 2 : 1;
				if (t == '[') {
					while (desc.charAt(i) == '[') {
						i++;
					}
				}
				i = desc.charAt(i) == 'L' ? desc.indexOf(';', i) + 1 : i + 1;
			}
			invoke(c, Opcode.INVOKESTATIC, target);
			int end = c.size();
			c.add(switch (desc.charAt(i + 1)) {
				case 'V' -> Opcode.RETURN;
				case 'I', 'Z', 'B', 'C', 'S' -> Opcode.IRETURN;
				case 'J' -> Opcode.LRETURN;
				case 'F' -> Opcode.FRETURN;
				case 'D' -> Opcode.DRETURN;
				default -> Opcode.ARETURN;
			});
			int handler = c.size();
			JvmRuntimeBuilder.emitLdc(c, this.cp.addString(op).index());
			JvmRuntimeBuilder.emitLdc(c,
					this.cp.addString(java.util.Objects.requireNonNull(OperandTypes.operatorType(op))).index());
			invoke(c, Opcode.INVOKESTATIC, self(this.cp, this.thisClass, OP_TYPE_ERR, OP_TYPE_ERR_DESC));
			c.add(Opcode.ATHROW);
			this.sink.add(new JvmNumericRuntimeBuilder.NumericMethod(this.cp.addUtf8(name), this.cp.addUtf8(desc), c,
					Math.max(slot, 3), slot, List.<int[]>of(new int[] { 0, end, handler, 0 })));
			return ref;
		}

	}

	private static void invoke(List<Integer> c, int opcode, MethodrefConstant ref) {
		c.add(opcode);
		JvmRuntimeBuilder.emitU2(c, ref.index());
	}

	private static int branch(List<Integer> c, int opcode) {
		int pos = c.size();
		c.add(opcode);
		JvmRuntimeBuilder.emitU2(c, 0);
		return pos;
	}

}
