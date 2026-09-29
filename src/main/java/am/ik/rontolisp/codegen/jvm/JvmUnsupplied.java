package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.MethodCode;

/**
 * The UNSUPPLIED marker of one compiled class: the value a caller passes for a physical
 * optional parameter it has no argument for ({@code LambdaLists.toNative}), and the one a
 * callee's {@code %supplied-p} prologue tests for. It is a {@code new Object()} no Lisp
 * value can ever be -- nil is {@code null} on this backend and cannot mark an argument
 * that was not passed.
 *
 * <p>
 * Every use is a call of {@code _unsupp()}, which reads the {@code _unsupplied} field and
 * creates the object on the first read through the {@code synchronized}
 * {@code _unsuppInit()}, so there is exactly one however many threads race to it. A
 * {@code <clinit>} initializer was the first shape, and it pinned the field in every
 * class that had a callee with optionals at emission time, even where the shake then
 * dropped every one of them: {@code (print (+ 1 2))} grew a static initializer. Behind a
 * method, the field and both helpers live and fall with their callers, like any runtime
 * helper. The first reference creates the constants; {@link #used} is what the class
 * assembly asks.
 */
final class JvmUnsupplied {

	/** The field's name. */
	static final String FIELD_NAME = "_unsupplied";

	/** The accessor's name. */
	static final String ACCESSOR_NAME = "_unsupp";

	/** The first-use initializer's name. */
	static final String INIT_NAME = "_unsuppInit";

	/** The argument-list reader's name ({@link #optArgRef}). */
	static final String OPT_ARG_NAME = "_optArg";

	private static final String OPT_ARG_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	private static final String OBJECT_DESC = "Ljava/lang/Object;";

	private static final String ACCESSOR_DESC = "()Ljava/lang/Object;";

	private @Nullable MethodRefEntry accessor;

	private @Nullable MethodRefEntry optArg;

	private @Nullable ConstantPool cp;

	private @Nullable ClassEntry owner;

	private boolean frozen;

	/**
	 * The accessor's reference, created on first use.
	 * @param cp the class's constant pool
	 * @param className the internal name of the class being emitted
	 * @return the methodref of {@code _unsupp()}
	 */
	MethodRefEntry ref(ConstantPool cp, String className) {
		return ref(cp, cp.classEntry(className));
	}

	/**
	 * The accessor's reference, created on first use.
	 * @param cp the class's constant pool
	 * @param owner the class being emitted
	 * @return the methodref of {@code _unsupp()}
	 */
	MethodRefEntry ref(ConstantPool cp, ClassEntry owner) {
		MethodRefEntry existing = this.accessor;
		if (existing != null) {
			return existing;
		}
		if (this.frozen) {
			throw new IllegalStateException("the UNSUPPLIED marker was first referenced after the class's methods"
					+ " were assembled: a caller of a callee with physical optionals was built too late");
		}
		MethodRefEntry created = cp.methodRef(owner, ACCESSOR_NAME, ACCESSOR_DESC);
		this.cp = cp;
		this.owner = owner;
		this.accessor = created;
		return created;
	}

	/**
	 * The reference of {@code _optArg(cell)}, created on first use: the physical optional
	 * a call reads out of an argument LIST -- {@code car(cell)}, or the marker when the
	 * list has run out ({@code cell} is nil). A spread dispatcher case and a literal
	 * {@code apply}'s walk call it once per optional; spelled inline, each was a branch
	 * pair and so two stack-map frames, which in the spread dispatcher -- a case per
	 * callable, each frame naming every local -- came to kilobytes.
	 * @param cp the class's constant pool
	 * @param owner the class being emitted
	 * @return the methodref of {@code _optArg(Object)}
	 */
	MethodRefEntry optArgRef(ConstantPool cp, ClassEntry owner) {
		ref(cp, owner);
		MethodRefEntry existing = this.optArg;
		if (existing != null) {
			return existing;
		}
		if (this.frozen) {
			throw new IllegalStateException("_optArg was first referenced after the class's methods were assembled");
		}
		MethodRefEntry created = cp.methodRef(owner, OPT_ARG_NAME, OPT_ARG_DESC);
		this.optArg = created;
		return created;
	}

	/**
	 * Closes the marker to a FIRST reference: the class assembly calls it where it emits
	 * the helpers, so a call site built later than that -- which would call a method the
	 * class does not declare -- fails the compile instead of the class's verification.
	 */
	void freeze() {
		this.frozen = true;
	}

	/**
	 * {@return whether anything referenced the marker} -- the helpers are emitted exactly
	 * then
	 */
	boolean used() {
		return this.accessor != null;
	}

	/**
	 * The field and the two helpers, for a class that {@link #used} the marker.
	 * @return the members to add
	 */
	Members members() {
		ConstantPool pool = java.util.Objects.requireNonNull(this.cp);
		ClassEntry thisClass = java.util.Objects.requireNonNull(this.owner);
		Utf8Constant fieldName = pool.addUtf8(FIELD_NAME);
		Utf8Constant fieldDesc = pool.addUtf8(OBJECT_DESC);
		FieldRefEntry field = pool.fieldRef(thisClass, fieldName.entry(), fieldDesc.entry());
		Utf8Constant initName = pool.addUtf8(INIT_NAME);
		Utf8Constant accessorDesc = pool.addUtf8(ACCESSOR_DESC);
		MethodRefEntry init = pool.methodRef(thisClass, initName.entry(), accessorDesc.entry());
		ClassEntry objectClass = pool.classEntry("java/lang/Object");
		MethodRefEntry objectCtor = pool.methodRef(objectClass, "<init>", "()V");
		// _unsupp(): Object m = _unsupplied; return m != null ? m : _unsuppInit();
		MethodCode accessorCode = new MethodCode();
		accessorCode.getstatic(field);
		accessorCode.dup();
		MethodCode.Label ifNull = accessorCode.newLabel();
		accessorCode.ifnull(ifNull);
		accessorCode.areturn();
		accessorCode.labelBinding(ifNull);
		accessorCode.pop();
		accessorCode.invokestatic(init);
		accessorCode.areturn();
		// synchronized _unsuppInit(): the re-check under the class monitor, so a race
		// creates one object; a racing reader that saw null lands here and gets it.
		MethodCode initCode = new MethodCode();
		initCode.getstatic(field);
		initCode.dup();
		MethodCode.Label ifSet = initCode.newLabel();
		initCode.ifnonnull(ifSet);
		initCode.pop();
		initCode.new_(objectClass);
		initCode.dup();
		initCode.invokespecial(objectCtor);
		initCode.dup();
		initCode.putstatic(field);
		initCode.labelBinding(ifSet);
		initCode.areturn();
		// _optArg(cell): cell == null ? _unsupp() : ((Object[]) cell)[0]
		MethodCode optArgCode = null;
		if (this.optArg != null) {
			ClassEntry objectArray = pool.classEntry("[Ljava/lang/Object;");
			optArgCode = new MethodCode();
			optArgCode.aload(0);
			MethodCode.Label ifNonNull = optArgCode.newLabel();
			optArgCode.ifnonnull(ifNonNull);
			optArgCode.invokestatic(java.util.Objects.requireNonNull(this.accessor));
			optArgCode.areturn();
			optArgCode.labelBinding(ifNonNull);
			optArgCode.aload(0);
			optArgCode.checkcast(objectArray);
			optArgCode.iconst_0();
			optArgCode.aaload();
			optArgCode.areturn();
		}
		return new Members(fieldName, fieldDesc, pool.addUtf8(ACCESSOR_NAME), initName, accessorDesc, accessorCode,
				initCode, this.optArg == null ? null : pool.addUtf8(OPT_ARG_NAME),
				this.optArg == null ? null : pool.addUtf8(OPT_ARG_DESC), optArgCode);
	}

	/**
	 * What a class that uses the marker declares.
	 *
	 * @param fieldName the field's name
	 * @param fieldDesc the field's descriptor
	 * @param accessorName {@code _unsupp}
	 * @param initName {@code _unsuppInit}
	 * @param methodDesc both helpers' descriptor
	 * @param accessorCode {@code _unsupp}'s body
	 * @param initCode {@code _unsuppInit}'s body
	 * @param optArgName {@code _optArg}, or {@code null} when nothing reads an optional
	 * out of an argument list
	 * @param optArgDesc its descriptor, or {@code null} with it
	 * @param optArgCode its body, or {@code null} with it
	 */
	record Members(Utf8Constant fieldName, Utf8Constant fieldDesc, Utf8Constant accessorName, Utf8Constant initName,
			Utf8Constant methodDesc, MethodCode accessorCode, MethodCode initCode, @Nullable Utf8Constant optArgName,
			@Nullable Utf8Constant optArgDesc, @Nullable MethodCode optArgCode) {
	}

}
