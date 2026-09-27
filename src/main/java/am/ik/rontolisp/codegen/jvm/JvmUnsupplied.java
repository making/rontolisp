package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

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

	private @Nullable MethodrefConstant accessor;

	private @Nullable MethodrefConstant optArg;

	private @Nullable ConstantPool cp;

	private @Nullable ClassConstant owner;

	private boolean frozen;

	/**
	 * The accessor's reference, created on first use.
	 * @param cp the class's constant pool
	 * @param className the internal name of the class being emitted
	 * @return the methodref of {@code _unsupp()}
	 */
	MethodrefConstant ref(ConstantPool cp, String className) {
		return ref(cp, cp.addClass(cp.addUtf8(className)));
	}

	/**
	 * The accessor's reference, created on first use.
	 * @param cp the class's constant pool
	 * @param owner the class being emitted
	 * @return the methodref of {@code _unsupp()}
	 */
	MethodrefConstant ref(ConstantPool cp, ClassConstant owner) {
		MethodrefConstant existing = this.accessor;
		if (existing != null) {
			return existing;
		}
		if (this.frozen) {
			throw new IllegalStateException("the UNSUPPLIED marker was first referenced after the class's methods"
					+ " were assembled: a caller of a callee with physical optionals was built too late");
		}
		MethodrefConstant created = cp.addMethodref(owner,
				cp.addNameAndType(cp.addUtf8(ACCESSOR_NAME), cp.addUtf8(ACCESSOR_DESC)));
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
	MethodrefConstant optArgRef(ConstantPool cp, ClassConstant owner) {
		ref(cp, owner);
		MethodrefConstant existing = this.optArg;
		if (existing != null) {
			return existing;
		}
		if (this.frozen) {
			throw new IllegalStateException("_optArg was first referenced after the class's methods were assembled");
		}
		MethodrefConstant created = cp.addMethodref(owner,
				cp.addNameAndType(cp.addUtf8(OPT_ARG_NAME), cp.addUtf8(OPT_ARG_DESC)));
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
		ClassConstant thisClass = java.util.Objects.requireNonNull(this.owner);
		Utf8Constant fieldName = pool.addUtf8(FIELD_NAME);
		Utf8Constant fieldDesc = pool.addUtf8(OBJECT_DESC);
		FieldrefConstant field = pool.addFieldref(thisClass, pool.addNameAndType(fieldName, fieldDesc));
		Utf8Constant initName = pool.addUtf8(INIT_NAME);
		Utf8Constant accessorDesc = pool.addUtf8(ACCESSOR_DESC);
		MethodrefConstant init = pool.addMethodref(thisClass, pool.addNameAndType(initName, accessorDesc));
		ClassConstant objectClass = pool.addClass(pool.addUtf8("java/lang/Object"));
		MethodrefConstant objectCtor = pool.addMethodref(objectClass,
				pool.addNameAndType(pool.addUtf8("<init>"), pool.addUtf8("()V")));
		// _unsupp(): Object m = _unsupplied; return m != null ? m : _unsuppInit();
		List<Integer> accessorCode = new ArrayList<>();
		accessorCode.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(accessorCode, field.index());
		accessorCode.add(Opcode.DUP);
		int ifNullPos = accessorCode.size();
		accessorCode.add(Opcode.IFNULL);
		JvmRuntimeBuilder.emitU2(accessorCode, 0);
		accessorCode.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(accessorCode, ifNullPos, accessorCode.size());
		accessorCode.add(Opcode.POP);
		accessorCode.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(accessorCode, init.index());
		accessorCode.add(Opcode.ARETURN);
		// synchronized _unsuppInit(): the re-check under the class monitor, so a race
		// creates one object; a racing reader that saw null lands here and gets it.
		List<Integer> initCode = new ArrayList<>();
		initCode.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(initCode, field.index());
		initCode.add(Opcode.DUP);
		int ifSetPos = initCode.size();
		initCode.add(Opcode.IFNONNULL);
		JvmRuntimeBuilder.emitU2(initCode, 0);
		initCode.add(Opcode.POP);
		initCode.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(initCode, objectClass.index());
		initCode.add(Opcode.DUP);
		initCode.add(Opcode.INVOKESPECIAL);
		JvmRuntimeBuilder.emitU2(initCode, objectCtor.index());
		initCode.add(Opcode.DUP);
		initCode.add(Opcode.PUTSTATIC);
		JvmRuntimeBuilder.emitU2(initCode, field.index());
		JvmRuntimeBuilder.patchBranch(initCode, ifSetPos, initCode.size());
		initCode.add(Opcode.ARETURN);
		// _optArg(cell): cell == null ? _unsupp() : ((Object[]) cell)[0]
		List<Integer> optArgCode = null;
		if (this.optArg != null) {
			ClassConstant objectArray = pool.addClass(pool.addUtf8("[Ljava/lang/Object;"));
			optArgCode = new ArrayList<>();
			optArgCode.add(Opcode.ALOAD_0);
			int ifNonNullPos = optArgCode.size();
			optArgCode.add(Opcode.IFNONNULL);
			JvmRuntimeBuilder.emitU2(optArgCode, 0);
			optArgCode.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(optArgCode, java.util.Objects.requireNonNull(this.accessor).index());
			optArgCode.add(Opcode.ARETURN);
			JvmRuntimeBuilder.patchBranch(optArgCode, ifNonNullPos, optArgCode.size());
			optArgCode.add(Opcode.ALOAD_0);
			optArgCode.add(Opcode.CHECKCAST);
			JvmRuntimeBuilder.emitU2(optArgCode, objectArray.index());
			optArgCode.add(Opcode.ICONST_0);
			optArgCode.add(Opcode.AALOAD);
			optArgCode.add(Opcode.ARETURN);
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
	 * @param accessorCode {@code _unsupp}'s bytecode, two stack slots, no locals
	 * @param initCode {@code _unsuppInit}'s bytecode, two stack slots, no locals
	 * @param optArgName {@code _optArg}, or {@code null} when nothing reads an optional
	 * out of an argument list
	 * @param optArgDesc its descriptor, or {@code null} with it
	 * @param optArgCode its bytecode (two stack slots, one local), or {@code null} with
	 * it
	 */
	record Members(Utf8Constant fieldName, Utf8Constant fieldDesc, Utf8Constant accessorName, Utf8Constant initName,
			Utf8Constant methodDesc, List<Integer> accessorCode, List<Integer> initCode,
			@Nullable Utf8Constant optArgName, @Nullable Utf8Constant optArgDesc, @Nullable List<Integer> optArgCode) {
	}

}
