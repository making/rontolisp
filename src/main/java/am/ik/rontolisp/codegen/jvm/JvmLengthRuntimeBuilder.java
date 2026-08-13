package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Builds the {@code _length} runtime helper for the {@code length} built-in:
 *
 * <pre>{@code _length(Object v) -> Object}</pre>
 *
 * <p>
 * A string returns its character count (the stored length minus the two surrounding
 * quotes); a vector (rank-1 array) returns its element count; any other value is treated
 * as a list and its cons cells are counted (Common Lisp sequences). A rank-2+ array is
 * not a sequence, so it throws. An array is an {@link java.util.ArrayList} whose slot 0
 * holds the {@code {dims, fillPointer, adjustable}} header (see
 * {@link JvmArrayRuntimeBuilder}), so its element count is the fill pointer when the
 * header carries one, otherwise {@code size() - 1}.
 *
 * <p>
 * The whole computation lives in this single helper (emitted once) rather than inline at
 * every {@code length} call site, so each site is just an {@code invokestatic}. Keeping
 * the call sites tiny matters because top-level forms compile into one {@code main}
 * method bounded by the JVM's 64&nbsp;KB per-method code limit.
 */
final class JvmLengthRuntimeBuilder {

	/** A length runtime method body ready to be emitted into the generated class. */
	record LengthMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	static final String METHOD = "_length";

	static final String DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	private JvmLengthRuntimeBuilder() {
	}

	static LengthMethod build(ConstantPool cp, ClassConstant objectArrayClass, ClassConstant stringClass,
			MethodrefConstant longValueOf, ClassConstant selfClass) {
		ClassConstant arrayListClass = cp.addClass(cp.addUtf8("java/util/ArrayList"));
		ClassConstant rtExClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		// _scount(s) returns the CHARACTER-visible length of the content inside the
		// surrounding quote framing, so a supplementary code point in it counts as one
		// character -- and answers without re-counting a string it has already proven
		// free of surrogate pairs (JvmStringIndexRuntimeBuilder).
		MethodrefConstant stringCharCount = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(JvmStringIndexRuntimeBuilder.COUNT_METHOD),
						cp.addUtf8(JvmStringIndexRuntimeBuilder.COUNT_DESC)));
		MethodrefConstant alGet = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("(I)Ljava/lang/Object;")));
		MethodrefConstant alSize = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("size"), cp.addUtf8("()I")));
		MethodrefConstant rtExInit = cp.addMethodref(rtExClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));

		// Slots: 0 = v, 1/2 = count (long accumulator for the list case), 3 = the
		// array's slot-0 header (Object[]).
		JvmAsm a = new JvmAsm();
		int notString = a.label();
		int notArray = a.label();
		int rank1 = a.label();
		int loop = a.label();
		int done = a.label();

		// String: return _scount(v) -- the character-visible length inside the
		// surrounding quote framing. A supplementary code point counts as one character,
		// matching (length "😀") == 1 on every backend after todo 153.
		a.aload(0);
		a.instanceOf(stringClass);
		a.branch(Opcode.IFEQ, notString);
		a.aload(0);
		a.checkcast(stringClass);
		a.invokestatic(stringCharCount);
		a.op(Opcode.I2L);
		a.invokestatic(longValueOf);
		a.areturn();
		a.bind(notString);

		// Array: an ArrayList whose slot 0 is the {dims, fillPointer, adjustable}
		// header. The fill pointer, when present, is the effective length.
		a.aload(0);
		a.instanceOf(arrayListClass);
		a.branch(Opcode.IFEQ, notArray);
		a.aload(0);
		a.checkcast(arrayListClass);
		a.iconst(0);
		a.invokevirtual(alGet);
		a.checkcast(objectArrayClass);
		a.astore(3);
		a.aload(3);
		a.iconst(0);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.arraylength();
		a.iconst(1);
		a.branch(Opcode.IF_ICMPEQ, rank1);
		// rank 2+: not a sequence.
		a.anew(rtExClass);
		a.dup();
		a.ldcString(cp.addString("length: argument is not a sequence (multidimensional array)"));
		a.invokespecial(rtExInit);
		a.op(Opcode.ATHROW);
		a.bind(rank1);
		int noFillPointer = a.label();
		a.aload(3);
		a.iconst(1);
		a.aaload();
		a.branch(Opcode.IFNULL, noFillPointer);
		a.aload(3);
		a.iconst(1);
		a.aaload();
		a.areturn();
		a.bind(noFillPointer);
		// dims[0] (already a boxed Long): equals size() - 1 for an ordinary vector and
		// stays correct for a displaced one (which holds no data slots).
		a.aload(3);
		a.iconst(0);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.areturn();
		a.bind(notArray);

		// List: count cons cells (Object[]) until the value is no longer a cons.
		a.op(Opcode.LCONST_0);
		a.op(Opcode.LSTORE);
		a.op0(1);
		a.bind(loop);
		a.aload(0);
		a.instanceOf(objectArrayClass);
		a.branch(Opcode.IFEQ, done);
		a.op(Opcode.LLOAD);
		a.op0(1);
		a.op(Opcode.LCONST_1);
		a.op(Opcode.LADD);
		a.op(Opcode.LSTORE);
		a.op0(1);
		a.aload(0);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.astore(0);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		a.op(Opcode.LLOAD);
		a.op0(1);
		a.invokestatic(longValueOf);
		a.areturn();

		return new LengthMethod(cp.addUtf8(METHOD), cp.addUtf8(DESC), 4, 4, a.finish());
	}

}
