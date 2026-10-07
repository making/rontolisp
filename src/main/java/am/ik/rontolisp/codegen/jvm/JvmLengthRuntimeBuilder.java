package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.compiler.OperandTypes;

/**
 * Builds the {@code _length} runtime helper for the {@code length} built-in:
 *
 * <pre>{@code _length(Object v) -> Object}</pre>
 *
 * <p>
 * A string returns its character count (the stored length minus the two surrounding
 * quotes); a vector (rank-1 array) returns its element count; a list's cons cells are
 * counted (Common Lisp sequences). A rank-2+ array is not a sequence, so it throws; any
 * other value -- a symbol, a number, a hash table, a dotted list's tail -- is
 * {@code LENGTH}'s {@code SEQUENCE} type-error ({@link JvmOperandTypeRuntime}). An array
 * is an {@link java.util.ArrayList} whose slot 0 holds the {@code {dims, fillPointer,
 * adjustable}} header (see {@link JvmArrayRuntimeBuilder}), so its element count is the
 * fill pointer when the header carries one, otherwise {@code size() - 1}.
 *
 * <p>
 * The whole computation lives in this single helper (emitted once) rather than inline at
 * every {@code length} call site, so each site is just an {@code invokestatic}. Keeping
 * the call sites tiny matters because top-level forms compile into one {@code main}
 * method bounded by the JVM's 64&nbsp;KB per-method code limit.
 *
 * <p>
 * In a {@code java:} program a call can answer a host {@code ArrayList}, which is no
 * sequence: the array arm then asks the program's shared {@code _jlarr} instead of the
 * class, and a host list reaches the {@code SEQUENCE} type-error the interpreter signals.
 */
final class JvmLengthRuntimeBuilder {

	/** A length runtime method body ready to be emitted into the generated class. */
	record LengthMethod(Utf8Entry name, Utf8Entry desc, MethodCode code) {
	}

	static final String METHOD = "_length";

	static final String DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	private JvmLengthRuntimeBuilder() {
	}

	/**
	 * Builds the helper.
	 * @param cp the constant pool
	 * @param objectArrayClass {@code Object[]}
	 * @param stringClass {@code String}
	 * @param longValueOf {@code Long.valueOf(long)}
	 * @param selfClass the generated class
	 * @param lispArray in a {@code java:} program, the shared {@code _jlarr} test
	 * ({@code JvmJavaDirectSites#lispArray}); null elsewhere, where the class alone
	 * decides
	 * @return the method
	 */
	static LengthMethod build(ConstantPool cp, ClassEntry objectArrayClass, ClassEntry stringClass,
			MethodRefEntry longValueOf, ClassEntry selfClass, @Nullable MethodRefEntry lispArray) {
		ClassEntry arrayListClass = cp.classEntry("java/util/ArrayList");
		ClassEntry rtExClass = cp.classEntry("java/lang/RuntimeException");
		// _scount(s) returns the CHARACTER-visible length of the content inside the
		// surrounding quote framing, so a supplementary code point in it counts as one
		// character -- and answers without re-counting a string it has already proven
		// free of surrogate pairs (JvmStringIndexRuntimeBuilder).
		MethodRefEntry stringCharCount = cp.methodRef(selfClass, JvmStringIndexRuntimeBuilder.COUNT_METHOD,
				JvmStringIndexRuntimeBuilder.COUNT_DESC);
		MethodRefEntry alGet = cp.methodRef(arrayListClass, "get", "(I)Ljava/lang/Object;");
		MethodRefEntry alSize = cp.methodRef(arrayListClass, "size", "()I");
		MethodRefEntry startsWith = cp.methodRef(stringClass, "startsWith", "(Ljava/lang/String;)Z");
		MethodRefEntry rtExInit = cp.methodRef(rtExClass, "<init>", "(Ljava/lang/String;)V");

		// Slots: 0 = v, 1/2 = count (long accumulator for the list case), 3 = the
		// array's slot-0 header (Object[]).
		MethodCode a = new MethodCode();
		MethodCode.Label notString = a.newLabel();
		MethodCode.Label notArray = a.newLabel();
		MethodCode.Label rank1 = a.newLabel();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();

		// String: return _scount(v) -- the character-visible length inside the
		// surrounding quote framing. A supplementary code point counts as one character,
		// matching (length "😀") == 1 on every backend (.kb/characters-code-points.md).
		MethodCode.Label notSequence = a.newLabel();
		a.aload(0);
		a.instanceOf(stringClass);
		a.ifeq(notString);
		// A symbol is a String too, without the quote framing: no sequence.
		a.aload(0);
		a.checkcast(stringClass);
		a.ldc(cp.stringEntry("\""));
		a.invokevirtual(startsWith);
		a.ifeq(notSequence);
		a.aload(0);
		a.checkcast(stringClass);
		a.invokestatic(stringCharCount);
		a.i2l();
		a.invokestatic(longValueOf);
		a.areturn();
		a.labelBinding(notString);

		// Array: an ArrayList whose slot 0 is the {dims, fillPointer, adjustable}
		// header. The fill pointer, when present, is the effective length. A host
		// ArrayList falls through to the list walk, which refuses it.
		a.aload(0);
		if (lispArray != null) {
			a.invokestatic(lispArray);
		}
		else {
			a.instanceOf(arrayListClass);
		}
		a.ifeq(notArray);
		a.aload(0);
		a.checkcast(arrayListClass);
		a.loadConstant(0);
		a.invokevirtual(alGet);
		a.checkcast(objectArrayClass);
		a.astore(3);
		a.aload(3);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.arraylength();
		a.loadConstant(1);
		a.if_icmpeq(rank1);
		// rank 2+: not a sequence.
		a.new_(rtExClass);
		a.dup();
		a.ldc(cp.stringEntry("length: argument is not a sequence (multidimensional array)"));
		a.invokespecial(rtExInit);
		a.athrow();
		a.labelBinding(rank1);
		MethodCode.Label noFillPointer = a.newLabel();
		a.aload(3);
		a.loadConstant(1);
		a.aaload();
		a.ifnull(noFillPointer);
		a.aload(3);
		a.loadConstant(1);
		a.aaload();
		a.areturn();
		a.labelBinding(noFillPointer);
		// dims[0] (already a boxed Long): equals size() - 1 for an ordinary vector and
		// stays correct for a displaced one (which holds no data slots).
		a.aload(3);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.areturn();
		a.labelBinding(notArray);

		// List: count cons cells (Object[]) until the value is no longer a cons. The walk
		// must end at nil: anything else -- a non-list, a dotted list's tail -- is no
		// sequence.
		a.lconst_0();
		a.lstore(1);
		a.labelBinding(loop);
		a.aload(0);
		a.instanceOf(objectArrayClass);
		a.ifeq(done);
		a.lload(1);
		a.lconst_1();
		a.ladd();
		a.lstore(1);
		a.aload(0);
		a.checkcast(objectArrayClass);
		a.loadConstant(1);
		a.aaload();
		a.astore(0);
		a.goto_(loop);
		a.labelBinding(done);
		a.aload(0);
		a.ifnonnull(notSequence);
		a.lload(1);
		a.invokestatic(longValueOf);
		a.areturn();
		// throw _opTypeErr(_teRaw(v, "SEQUENCE"), "LENGTH", "SEQUENCE")
		a.labelBinding(notSequence);
		a.aload(0);
		a.ldc(cp.stringEntry(OperandTypes.Kind.SEQUENCE.name()));
		a.invokestatic(JvmOperandTypeRuntime.self(cp, selfClass, JvmOperandTypeRuntime.TE_RAW,
				JvmOperandTypeRuntime.TE_RAW_DESC));
		a.ldc(cp.stringEntry(LispNames.LENGTH));
		a.ldc(cp.stringEntry(OperandTypes.Kind.SEQUENCE.name()));
		a.invokestatic(JvmOperandTypeRuntime.self(cp, selfClass, JvmOperandTypeRuntime.OP_TYPE_ERR,
				JvmOperandTypeRuntime.OP_TYPE_ERR_DESC));
		a.athrow();

		return new LengthMethod(cp.utf8Entry(METHOD), cp.utf8Entry(DESC), a);
	}

}
