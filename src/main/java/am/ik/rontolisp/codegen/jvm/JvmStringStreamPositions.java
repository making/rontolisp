package am.ik.rontolisp.codegen.jvm;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.StringConstant;
import am.ik.jvm.Opcode;
import org.jspecify.annotations.Nullable;

/**
 * The STRING-stream arms of {@code _filePosition} ({@code .kb/read-load-streams.md},
 * "String streams"). A string INPUT stream is the travelling
 * {@code runtime.RontoStringInputStream}, which answers its own character position and
 * seeks; a string OUTPUT stream is the table's {@code StringWriter}, whose position is
 * the number of characters written since it was last emptied and which repositions only
 * to where it already is (or to {@code -1}, what {@code :end} is rewritten to on the
 * compile paths).
 *
 * <p>
 * Its own class so {@code JvmIoRuntimeBuilder}'s file arms stay as they were: the arms
 * run FIRST, before the side-table lookups a file stream needs, and answer or fall
 * through.
 */
final class JvmStringStreamPositions {

	/** The class a string input stream is built as when its position can be asked. */
	static final String STRING_INPUT_STREAM_CLASS = "am/ik/rontolisp/runtime/RontoStringInputStream";

	private final @Nullable ClassConstant inputType;

	private final @Nullable MethodrefConstant inputPosition;

	private final @Nullable MethodrefConstant inputSeek;

	private final ClassConstant writerType;

	private final MethodrefConstant writerGetBuffer;

	private final MethodrefConstant bufferLength;

	private final MethodrefConstant bufferCodePointCount;

	private final ConstantPool.LongConstant minusOne;

	private JvmStringStreamPositions(ConstantPool cp, boolean inputs, ClassConstant writerType,
			MethodrefConstant writerGetBuffer) {
		if (inputs) {
			this.inputType = cp.addClass(cp.addUtf8(STRING_INPUT_STREAM_CLASS));
			this.inputPosition = cp.addMethodref(this.inputType,
					cp.addNameAndType(cp.addUtf8("position"), cp.addUtf8("()J")));
			this.inputSeek = cp.addMethodref(this.inputType, cp.addNameAndType(cp.addUtf8("seek"), cp.addUtf8("(J)Z")));
		}
		else {
			this.inputType = null;
			this.inputPosition = null;
			this.inputSeek = null;
		}
		this.writerType = writerType;
		this.writerGetBuffer = writerGetBuffer;
		ClassConstant buffer = cp.addClass(cp.addUtf8("java/lang/StringBuffer"));
		this.bufferLength = cp.addMethodref(buffer, cp.addNameAndType(cp.addUtf8("length"), cp.addUtf8("()I")));
		this.bufferCodePointCount = cp.addMethodref(buffer,
				cp.addNameAndType(cp.addUtf8("codePointCount"), cp.addUtf8("(II)I")));
		this.minusOne = cp.addLong(-1L);
	}

	/**
	 * Mints the constant-pool entries.
	 * @param cp the class's constant pool
	 * @param inputs whether string input streams are built as the positioned class
	 * @param writerType {@code java/io/StringWriter}
	 * @param writerGetBuffer {@code StringWriter.getBuffer()}
	 * @return the arms
	 */
	static JvmStringStreamPositions mint(ConstantPool cp, boolean inputs, ClassConstant writerType,
			MethodrefConstant writerGetBuffer) {
		return new JvmStringStreamPositions(cp, inputs, writerType, writerGetBuffer);
	}

	/**
	 * Emits the arms over the table entry in {@code entrySlot}: each answers (the boxed
	 * position, {@code "T"}, or null) and returns, or falls through for any other entry.
	 * @param a the method being assembled
	 * @param entrySlot the local holding the table entry
	 * @param posSlot the local holding the position argument (null for the query)
	 * @param longSlot a free two-slot long local
	 * @param longClass {@code java/lang/Long}
	 * @param longValue {@code Long.longValue()}
	 * @param longValueOf {@code Long.valueOf(long)}
	 * @param tStr the {@code "T"} constant
	 */
	void emit(JvmAsm a, int entrySlot, int posSlot, int longSlot, ClassConstant longClass, MethodrefConstant longValue,
			MethodrefConstant longValueOf, StringConstant tStr) {
		int fail = a.label();
		if (this.inputType != null) {
			a.aload(entrySlot);
			a.instanceOf(this.inputType);
			int notInput = a.label();
			a.branch(Opcode.IFEQ, notInput);
			a.aload(posSlot);
			int inputSet = a.label();
			a.branch(Opcode.IFNONNULL, inputSet);
			a.aload(entrySlot);
			a.checkcast(this.inputType);
			a.invokevirtual(java.util.Objects.requireNonNull(this.inputPosition));
			a.invokestatic(longValueOf);
			a.areturn();
			a.bind(inputSet);
			a.aload(entrySlot);
			a.checkcast(this.inputType);
			a.aload(posSlot);
			a.checkcast(longClass);
			a.invokevirtual(longValue);
			a.invokevirtual(java.util.Objects.requireNonNull(this.inputSeek));
			a.branch(Opcode.IFEQ, fail);
			a.ldcString(tStr);
			a.areturn();
			a.bind(notInput);
		}
		a.aload(entrySlot);
		a.instanceOf(this.writerType);
		int notOutput = a.label();
		a.branch(Opcode.IFEQ, notOutput);
		// n = buffer.codePointCount(0, buffer.length())
		a.aload(entrySlot);
		a.checkcast(this.writerType);
		a.invokevirtual(this.writerGetBuffer);
		a.dup();
		a.invokevirtual(this.bufferLength);
		a.iconst(0);
		a.swap();
		a.invokevirtual(this.bufferCodePointCount);
		a.i2l();
		a.lstore(longSlot);
		a.aload(posSlot);
		int outputSet = a.label();
		a.branch(Opcode.IFNONNULL, outputSet);
		a.lload(longSlot);
		a.invokestatic(longValueOf);
		a.areturn();
		// The set succeeds only where the stream already is: at n, or at -1 (:end).
		a.bind(outputSet);
		a.aload(posSlot);
		a.checkcast(longClass);
		a.invokevirtual(longValue);
		a.lload(longSlot);
		a.lcmp();
		int atEnd = a.label();
		a.branch(Opcode.IFEQ, atEnd);
		a.aload(posSlot);
		a.checkcast(longClass);
		a.invokevirtual(longValue);
		a.ldc2Long(this.minusOne);
		a.lcmp();
		a.branch(Opcode.IFNE, fail);
		a.bind(atEnd);
		a.ldcString(tStr);
		a.areturn();
		a.bind(fail);
		a.aconstNull();
		a.areturn();
		a.bind(notOutput);
	}

}
