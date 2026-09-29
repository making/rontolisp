package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.IdentityHashMap;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.MethodCode;
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
 * <li>The table is created in {@code _qdSet}, not in {@code <clinit>}: the writer's shake
 * cannot edit {@code <clinit>}, so an initializer there would pin the field in every
 * class whose quoted datums all sat in dropped wrapper defuns. Behind the helpers, the
 * field and both methods go with the last surviving site. The site's build itself stays
 * inline at the site for the same reason (a quoted table in a dropped wrapper goes with
 * it). A dropped site leaves an empty slot.</li>
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

	private @Nullable ClassEntry owner;

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
		ClassEntry thisClass = cp.classEntry(className);
		Refs created = new Refs(cp.methodRef(thisClass, GET_NAME, GET_DESC),
				cp.methodRef(thisClass, SET_NAME, SET_DESC));
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
		ClassEntry thisClass = java.util.Objects.requireNonNull(this.owner);
		Utf8Constant fieldName = pool.addUtf8(FIELD_NAME);
		ClassEntry tableClass = pool.classEntry(TABLE_DESC);
		// The table's class name IS its descriptor: an array type costs no second Utf8.
		Utf8Constant fieldDesc = pool.addUtf8(TABLE_DESC);
		FieldRefEntry field = pool.fieldRef(thisClass, fieldName.entry(), fieldDesc.entry());
		ClassEntry objectClass = pool.classEntry("java/lang/Object");
		ClassEntry boxClass = pool.classEntry(BOX_CLASS);
		MethodRefEntry boxGet = pool.methodRef(boxClass, "get", "()Ljava/lang/Object;");
		MethodRefEntry boxOf = pool.methodRef(boxClass, "of", "(Ljava/lang/Object;)L" + BOX_CLASS + ";");

		// _qd(i): Object[] t = _qd; if (t == null) return null;
		// Optional b = t[i]; return b == null ? null : b.get();
		MethodCode getCode = new MethodCode();
		getCode.getstatic(field);
		getCode.dup();
		MethodCode.Label ifNoTable = getCode.newLabel();
		getCode.ifnull(ifNoTable);
		getCode.iload(0);
		getCode.aaload();
		getCode.dup();
		MethodCode.Label ifEmpty = getCode.newLabel();
		getCode.ifnull(ifEmpty);
		getCode.checkcast(boxClass);
		getCode.invokevirtual(boxGet);
		getCode.labelBinding(ifNoTable);
		getCode.labelBinding(ifEmpty);
		getCode.areturn();

		// synchronized _qdSet(v, i):
		// Object[] t = _qd; if (t == null) _qd = t = new Object[size];
		// Optional b = t[i]; if (b != null) return b.get();
		// t[i] = Optional.of(v); return v;
		MethodCode setCode = new MethodCode();
		setCode.getstatic(field);
		setCode.dup();
		MethodCode.Label ifMade = setCode.newLabel();
		setCode.ifnonnull(ifMade);
		setCode.pop();
		int slots = Math.max(1, size());
		if (slots <= Short.MAX_VALUE) {
			setCode.loadConstant(slots);
		}
		else {
			setCode.ldc(pool.entries().intEntry(slots));
		}
		setCode.anewarray(objectClass);
		setCode.dup();
		setCode.putstatic(field);
		setCode.labelBinding(ifMade);
		setCode.iload(1);
		setCode.aaload();
		setCode.dup();
		MethodCode.Label ifFree = setCode.newLabel();
		setCode.ifnull(ifFree);
		setCode.checkcast(boxClass);
		setCode.invokevirtual(boxGet);
		setCode.areturn();
		setCode.labelBinding(ifFree);
		setCode.pop();
		setCode.getstatic(field);
		setCode.iload(1);
		setCode.aload(0);
		setCode.invokestatic(boxOf);
		setCode.aastore();
		setCode.aload(0);
		setCode.areturn();

		return new Members(fieldName, fieldDesc, pool.addUtf8(GET_NAME), pool.addUtf8(GET_DESC), getCode,
				pool.addUtf8(SET_NAME), pool.addUtf8(SET_DESC), setCode);
	}

	/**
	 * The helpers a site calls.
	 *
	 * @param get {@code _qd(int)}: the slot's datum, or null before its first build
	 * @param set {@code _qdSet(Object, int)}: fills the slot, answering the datum that
	 * won
	 */
	record Refs(MethodRefEntry get, MethodRefEntry set) {
	}

	/**
	 * What a class that uses the table declares.
	 *
	 * @param fieldName {@code _qd}
	 * @param fieldDesc its descriptor
	 * @param getName {@code _qd}
	 * @param getDesc {@code (I)Object}
	 * @param getCode its body
	 * @param setName {@code _qdSet}
	 * @param setDesc its descriptor
	 * @param setCode its body ({@code synchronized})
	 */
	record Members(Utf8Constant fieldName, Utf8Constant fieldDesc, Utf8Constant getName, Utf8Constant getDesc,
			MethodCode getCode, Utf8Constant setName, Utf8Constant setDesc, MethodCode setCode) {
	}

}
