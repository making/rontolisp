package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.LongEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
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

	private final @Nullable ClassEntry inputType;

	private final @Nullable MethodRefEntry inputPosition;

	private final @Nullable MethodRefEntry inputSeek;

	private final ClassEntry writerType;

	private final MethodRefEntry writerGetBuffer;

	private final MethodRefEntry bufferLength;

	private final MethodRefEntry bufferCodePointCount;

	private final LongEntry minusOne;

	private JvmStringStreamPositions(ConstantPool cp, boolean inputs, ClassEntry writerType,
			MethodRefEntry writerGetBuffer) {
		if (inputs) {
			this.inputType = cp.classEntry(STRING_INPUT_STREAM_CLASS);
			this.inputPosition = cp.methodRef(this.inputType, "position", "()J");
			this.inputSeek = cp.methodRef(this.inputType, "seek", "(J)Z");
		}
		else {
			this.inputType = null;
			this.inputPosition = null;
			this.inputSeek = null;
		}
		this.writerType = writerType;
		this.writerGetBuffer = writerGetBuffer;
		ClassEntry buffer = cp.classEntry("java/lang/StringBuffer");
		this.bufferLength = cp.methodRef(buffer, "length", "()I");
		this.bufferCodePointCount = cp.methodRef(buffer, "codePointCount", "(II)I");
		this.minusOne = cp.entries().longEntry(-1L);
	}

	/**
	 * Mints the constant-pool entries.
	 * @param cp the class's constant pool
	 * @param inputs whether string input streams are built as the positioned class
	 * @param writerType {@code java/io/StringWriter}
	 * @param writerGetBuffer {@code StringWriter.getBuffer()}
	 * @return the arms
	 */
	static JvmStringStreamPositions mint(ConstantPool cp, boolean inputs, ClassEntry writerType,
			MethodRefEntry writerGetBuffer) {
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
	void emit(MethodCode a, int entrySlot, int posSlot, int longSlot, ClassEntry longClass, MethodRefEntry longValue,
			MethodRefEntry longValueOf, StringEntry tStr) {
		MethodCode.Label fail = a.newLabel();
		if (this.inputType != null) {
			a.aload(entrySlot);
			a.instanceOf(this.inputType);
			MethodCode.Label notInput = a.newLabel();
			a.ifeq(notInput);
			a.aload(posSlot);
			MethodCode.Label inputSet = a.newLabel();
			a.ifnonnull(inputSet);
			a.aload(entrySlot);
			a.checkcast(this.inputType);
			a.invokevirtual(java.util.Objects.requireNonNull(this.inputPosition));
			a.invokestatic(longValueOf);
			a.areturn();
			a.labelBinding(inputSet);
			a.aload(entrySlot);
			a.checkcast(this.inputType);
			a.aload(posSlot);
			a.checkcast(longClass);
			a.invokevirtual(longValue);
			a.invokevirtual(java.util.Objects.requireNonNull(this.inputSeek));
			a.ifeq(fail);
			a.ldc(tStr);
			a.areturn();
			a.labelBinding(notInput);
		}
		a.aload(entrySlot);
		a.instanceOf(this.writerType);
		MethodCode.Label notOutput = a.newLabel();
		a.ifeq(notOutput);
		// n = buffer.codePointCount(0, buffer.length())
		a.aload(entrySlot);
		a.checkcast(this.writerType);
		a.invokevirtual(this.writerGetBuffer);
		a.dup();
		a.invokevirtual(this.bufferLength);
		a.loadConstant(0);
		a.swap();
		a.invokevirtual(this.bufferCodePointCount);
		a.i2l();
		a.lstore(longSlot);
		a.aload(posSlot);
		MethodCode.Label outputSet = a.newLabel();
		a.ifnonnull(outputSet);
		a.lload(longSlot);
		a.invokestatic(longValueOf);
		a.areturn();
		// The set succeeds only where the stream already is: at n, or at -1 (:end).
		a.labelBinding(outputSet);
		a.aload(posSlot);
		a.checkcast(longClass);
		a.invokevirtual(longValue);
		a.lload(longSlot);
		a.lcmp();
		MethodCode.Label atEnd = a.newLabel();
		a.ifeq(atEnd);
		a.aload(posSlot);
		a.checkcast(longClass);
		a.invokevirtual(longValue);
		a.ldc(this.minusOne);
		a.lcmp();
		a.ifne(fail);
		a.labelBinding(atEnd);
		a.ldc(tStr);
		a.areturn();
		a.labelBinding(fail);
		a.aconst_null();
		a.areturn();
		a.labelBinding(notOutput);
	}

}
