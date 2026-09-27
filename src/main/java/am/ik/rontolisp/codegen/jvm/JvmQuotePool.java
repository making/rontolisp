package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispVal;

/**
 * The compilation-wide quoted-datum table (.kb/quoted-data.md): one SLOT per DISTINCT
 * quoted aggregate datum -- a cons, a general array, an instance or a packed array under
 * {@code quote}, or a bare instance literal -- built LAZILY by its site
 * ({@code JvmQuoteCompiler.emitSharedConstant}), so every evaluation answers the SAME
 * object: the CL-conformant constant reading, and what the interpreter always did. Keyed
 * by the datum's IDENTITY, so a macro expansion splicing one template datum into several
 * sites shares one slot across them.
 *
 * <p>
 * The slots are one {@code Object[]} in the static field {@code _qd}, read through
 * {@code _qd(int)} and filled through {@code _qdSet(Object, int)}, and a site names its
 * slot by an {@code int} operand. It used to be one volatile static field per datum, and
 * each cost three constant-pool entries (a {@code Fieldref}, its {@code NameAndType} and
 * the name): the ci-spec corpus class held 2,755 of them, 16% of its pool. The table
 * costs a fixed handful of entries however many datums there are.
 *
 * <ul>
 * <li><b>Publication rides a final field, not a volatile one.</b> A slot holds the datum
 * wrapped in a {@code java.util.Optional}, whose {@code value} is final: a thread that
 * reads the slot without synchronizing and sees the wrapper sees the datum as it was when
 * the wrapper was constructed -- after the whole build (JLS 17.5, which covers everything
 * reachable through the final field). So the read is a plain load, which the JIT may
 * schedule and hoist like any other; the volatile field it replaces could not be, and a
 * hot {@code (member x '(a b c d))} ran 25% faster for it (.kb/quoted-data.md).</li>
 * <li>{@code _qdSet} is {@code synchronized} on the class and runs once per slot (plus
 * racing first evaluations): it creates the table on first use, then answers the slot's
 * datum if one is already there and otherwise stores the one it was given. Racing first
 * evaluations each build a datum, the first store wins, and every loser answers the
 * winner -- one object per slot from the first evaluation on.</li>
 * <li>{@code _qd} answers null while the table or the slot is empty, so the read path
 * never locks; a racy read of the field sees null (the site builds and {@code _qdSet}
 * settles it under the lock) or an array whose slots are null or wrappers.</li>
 * <li>The table is created in {@code _qdSet}, not in {@code <clinit>}:
 * {@link am.ik.jvm.JvmClassShaker} cannot edit {@code <clinit>}, so an initializer there
 * would pin the field in every class whose quoted datums all sat in dropped wrapper
 * defuns. Behind the helpers, the field and both methods go with the last surviving site.
 * The site's build itself stays inline at the site for the same reason (a quoted table in
 * a dropped wrapper goes with it). A dropped site leaves an empty slot.</li>
 * </ul>
 * A program with no quoted aggregate references none of it and is emitted byte for byte
 * as before.
 */
final class JvmQuotePool {

	private static final String FIELD_NAME = "_qd";

	private static final String GET_NAME = "_qd";

	private static final String SET_NAME = "_qdSet";

	private static final String TABLE_DESC = "[Ljava/lang/Object;";

	private static final String BOX_CLASS = "java/util/Optional";

	private static final String GET_DESC = "(I)Ljava/lang/Object;";

	private static final String SET_DESC = "(Ljava/lang/Object;I)Ljava/lang/Object;";

	private final IdentityHashMap<LispVal, Integer> slotByDatum = new IdentityHashMap<>();

	private @Nullable Refs refs;

	private @Nullable ConstantPool cp;

	private @Nullable ClassConstant owner;

	private boolean frozen;

	/**
	 * The slot of a datum, interned on first sight (by identity).
	 * @param datum the quoted datum
	 * @return its slot index
	 */
	int slot(LispVal datum) {
		Integer existing = this.slotByDatum.get(datum);
		if (existing != null) {
			return existing;
		}
		if (this.frozen) {
			throw new IllegalStateException("a quoted datum was first compiled after the class's methods were"
					+ " assembled: its slot would lie past the table's size");
		}
		int created = this.slotByDatum.size();
		this.slotByDatum.put(datum, created);
		return created;
	}

	/**
	 * The two helpers' references, created on first use.
	 * @param cp the class's constant pool
	 * @param className the internal name of the class being emitted
	 * @return the methodrefs of {@code _qd(int)} and {@code _qdSet(Object, int)}
	 */
	Refs refs(ConstantPool cp, String className) {
		Refs existing = this.refs;
		if (existing != null) {
			return existing;
		}
		ClassConstant thisClass = cp.addClass(cp.addUtf8(className));
		Refs created = new Refs(
				cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(GET_NAME), cp.addUtf8(GET_DESC))),
				cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(SET_NAME), cp.addUtf8(SET_DESC))));
		this.cp = cp;
		this.owner = thisClass;
		this.refs = created;
		return created;
	}

	/**
	 * Closes the table to a new slot: the class assembly calls it where it emits the
	 * helpers, whose {@code _qdSet} bakes in the slot count.
	 */
	void freeze() {
		this.frozen = true;
	}

	/**
	 * {@return whether any site referenced the table} -- the members are emitted exactly
	 * then
	 */
	boolean used() {
		return this.refs != null;
	}

	/**
	 * {@return the number of slots interned so far}
	 */
	int size() {
		return this.slotByDatum.size();
	}

	/**
	 * The field and the two helpers, for a class that {@link #used} the table.
	 * @return the members to add
	 */
	Members members() {
		ConstantPool pool = java.util.Objects.requireNonNull(this.cp);
		ClassConstant thisClass = java.util.Objects.requireNonNull(this.owner);
		Utf8Constant fieldName = pool.addUtf8(FIELD_NAME);
		ClassConstant tableClass = pool.addClass(pool.addUtf8(TABLE_DESC));
		// The table's class name IS its descriptor: an array type costs no second Utf8.
		Utf8Constant fieldDesc = pool.addUtf8(TABLE_DESC);
		FieldrefConstant field = pool.addFieldref(thisClass, pool.addNameAndType(fieldName, fieldDesc));
		ClassConstant objectClass = pool.addClass(pool.addUtf8("java/lang/Object"));
		ClassConstant boxClass = pool.addClass(pool.addUtf8(BOX_CLASS));
		MethodrefConstant boxGet = pool.addMethodref(boxClass,
				pool.addNameAndType(pool.addUtf8("get"), pool.addUtf8("()Ljava/lang/Object;")));
		MethodrefConstant boxOf = pool.addMethodref(boxClass,
				pool.addNameAndType(pool.addUtf8("of"), pool.addUtf8("(Ljava/lang/Object;)L" + BOX_CLASS + ";")));

		// _qd(i): Object[] t = _qd; if (t == null) return null;
		// Optional b = t[i]; return b == null ? null : b.get();
		List<Integer> getCode = new ArrayList<>();
		getCode.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(getCode, field.index());
		getCode.add(Opcode.DUP);
		int ifNoTablePos = getCode.size();
		getCode.add(Opcode.IFNULL);
		JvmRuntimeBuilder.emitU2(getCode, 0);
		getCode.add(Opcode.ILOAD_0);
		getCode.add(Opcode.AALOAD);
		getCode.add(Opcode.DUP);
		int ifEmptyPos = getCode.size();
		getCode.add(Opcode.IFNULL);
		JvmRuntimeBuilder.emitU2(getCode, 0);
		getCode.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(getCode, boxClass.index());
		getCode.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(getCode, boxGet.index());
		JvmRuntimeBuilder.patchBranch(getCode, ifNoTablePos, getCode.size());
		JvmRuntimeBuilder.patchBranch(getCode, ifEmptyPos, getCode.size());
		getCode.add(Opcode.ARETURN);

		// synchronized _qdSet(v, i):
		// Object[] t = _qd; if (t == null) _qd = t = new Object[size];
		// Optional b = t[i]; if (b != null) return b.get();
		// t[i] = Optional.of(v); return v;
		List<Integer> setCode = new ArrayList<>();
		setCode.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(setCode, field.index());
		setCode.add(Opcode.DUP);
		int ifMadePos = setCode.size();
		setCode.add(Opcode.IFNONNULL);
		JvmRuntimeBuilder.emitU2(setCode, 0);
		setCode.add(Opcode.POP);
		emitIntConst(setCode, pool, Math.max(1, size()));
		setCode.add(Opcode.ANEWARRAY);
		JvmRuntimeBuilder.emitU2(setCode, objectClass.index());
		setCode.add(Opcode.DUP);
		setCode.add(Opcode.PUTSTATIC);
		JvmRuntimeBuilder.emitU2(setCode, field.index());
		JvmRuntimeBuilder.patchBranch(setCode, ifMadePos, setCode.size());
		setCode.add(Opcode.ILOAD_1);
		setCode.add(Opcode.AALOAD);
		setCode.add(Opcode.DUP);
		int ifFreePos = setCode.size();
		setCode.add(Opcode.IFNULL);
		JvmRuntimeBuilder.emitU2(setCode, 0);
		setCode.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(setCode, boxClass.index());
		setCode.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(setCode, boxGet.index());
		setCode.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(setCode, ifFreePos, setCode.size());
		setCode.add(Opcode.POP);
		setCode.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(setCode, field.index());
		setCode.add(Opcode.ILOAD_1);
		setCode.add(Opcode.ALOAD_0);
		setCode.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(setCode, boxOf.index());
		setCode.add(Opcode.AASTORE);
		setCode.add(Opcode.ALOAD_0);
		setCode.add(Opcode.ARETURN);

		return new Members(fieldName, fieldDesc, pool.addUtf8(GET_NAME), pool.addUtf8(GET_DESC), getCode,
				pool.addUtf8(SET_NAME), pool.addUtf8(SET_DESC), setCode);
	}

	private static void emitIntConst(List<Integer> code, ConstantPool pool, int value) {
		if (value <= 5) {
			code.add(Opcode.ICONST_0 + value);
		}
		else if (value <= 127) {
			code.add(Opcode.BIPUSH);
			code.add(value);
		}
		else if (value <= Short.MAX_VALUE) {
			code.add(Opcode.SIPUSH);
			JvmRuntimeBuilder.emitU2(code, value);
		}
		else {
			code.add(Opcode.LDC_W);
			JvmRuntimeBuilder.emitU2(code, pool.addInteger(value).index());
		}
	}

	/**
	 * The helpers a site calls.
	 *
	 * @param get {@code _qd(int)}: the slot's datum, or null before its first build
	 * @param set {@code _qdSet(Object, int)}: fills the slot, answering the datum that
	 * won
	 */
	record Refs(MethodrefConstant get, MethodrefConstant set) {
	}

	/**
	 * What a class that uses the table declares.
	 *
	 * @param fieldName {@code _qd}
	 * @param fieldDesc its descriptor
	 * @param getName {@code _qd}
	 * @param getDesc {@code (I)Object}
	 * @param getCode its bytecode (two stack slots, one local)
	 * @param setName {@code _qdSet}
	 * @param setDesc its descriptor
	 * @param setCode its bytecode (three stack slots, two locals; {@code synchronized})
	 */
	record Members(Utf8Constant fieldName, Utf8Constant fieldDesc, Utf8Constant getName, Utf8Constant getDesc,
			List<Integer> getCode, Utf8Constant setName, Utf8Constant setDesc, List<Integer> setCode) {
	}

}
