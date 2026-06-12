package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Builds the JVM bytecode for the file-stream runtime used by the {@code open},
 * {@code close}, {@code write-line} and stream-taking {@code read-line} built-ins (and
 * therefore by the {@code with-open-file} macro).
 *
 * <p>
 * A stream value is a {@code Long} handle indexing the static {@code _streams} table
 * (mirroring the WASM backend, where the handle is the WASI file descriptor). An entry is
 * a {@code BufferedReader} for {@code :input} or a {@code BufferedWriter} for
 * {@code :output}; {@code close} nulls the entry out. {@code _writeLine} and
 * {@code _readLineStream} treat a {@code null} stream as standard output / standard
 * input.
 */
final class JvmIoRuntimeBuilder {

	/** A stream-runtime method body ready to be emitted into the generated class. */
	record IoMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	static final String STREAMS_FIELD = "_streams";

	static final String STREAMS_DESC = "[Ljava/lang/Object;";

	static final String STREAM_COUNT_FIELD = "_streamCount";

	static final String STREAM_COUNT_DESC = "I";

	static final String OPEN_METHOD = "_open";

	static final String OPEN_DESC = "(Ljava/lang/Object;I)Ljava/lang/Object;";

	static final String CLOSE_METHOD = "_closeStream";

	static final String CLOSE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String WRITE_LINE_METHOD = "_writeLine";

	static final String WRITE_LINE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String READ_LINE_STREAM_METHOD = "_readLineStream";

	static final String READ_LINE_STREAM_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	private final ConstantPool cp;

	private final FieldrefConstant streamsField;

	private final FieldrefConstant streamCountField;

	private final ClassConstant objectClass;

	private final ClassConstant stringClass;

	private final ClassConstant longClass;

	private final ClassConstant bufferedReaderClass;

	private final ClassConstant bufferedWriterClass;

	private final ClassConstant fileReaderClass;

	private final ClassConstant fileWriterClass;

	private final ClassConstant writerClass;

	private final MethodrefConstant arraysCopyOf;

	private final MethodrefConstant stringLength;

	private final MethodrefConstant stringSubstring;

	private final MethodrefConstant stringConcat;

	private final MethodrefConstant longValueOf;

	private final MethodrefConstant longValue;

	private final MethodrefConstant fileReaderInit;

	private final MethodrefConstant fileWriterInit;

	private final MethodrefConstant bufferedReaderInit;

	private final MethodrefConstant bufferedWriterInit;

	private final MethodrefConstant bufferedReaderReadLine;

	private final MethodrefConstant bufferedReaderClose;

	private final MethodrefConstant writerWrite;

	private final MethodrefConstant writerClose;

	private final FieldrefConstant systemOut;

	private final MethodrefConstant printlnStr;

	private final MethodrefConstant readLineHelper;

	private final ConstantPool.StringConstant tStr;

	private final ConstantPool.StringConstant quoteStr;

	private final ConstantPool.StringConstant newlineStr;

	private JvmIoRuntimeBuilder(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			ClassConstant stringClass, ClassConstant longClass, MethodrefConstant longValueOf,
			MethodrefConstant longValue, MethodrefConstant stringLength, MethodrefConstant stringSubstring,
			MethodrefConstant stringConcat, FieldrefConstant systemOut, MethodrefConstant printlnStr,
			MethodrefConstant readLineHelper) {
		this.cp = cp;
		this.objectClass = objectClass;
		this.stringClass = stringClass;
		this.longClass = longClass;
		this.longValueOf = longValueOf;
		this.longValue = longValue;
		this.stringLength = stringLength;
		this.stringSubstring = stringSubstring;
		this.stringConcat = stringConcat;
		this.systemOut = systemOut;
		this.printlnStr = printlnStr;
		this.readLineHelper = readLineHelper;
		this.streamsField = cp.addFieldref(thisClass,
				cp.addNameAndType(cp.addUtf8(STREAMS_FIELD), cp.addUtf8(STREAMS_DESC)));
		this.streamCountField = cp.addFieldref(thisClass,
				cp.addNameAndType(cp.addUtf8(STREAM_COUNT_FIELD), cp.addUtf8(STREAM_COUNT_DESC)));
		ClassConstant arraysClass = cp.addClass(cp.addUtf8("java/util/Arrays"));
		this.arraysCopyOf = cp.addMethodref(arraysClass,
				cp.addNameAndType(cp.addUtf8("copyOf"), cp.addUtf8("([Ljava/lang/Object;I)[Ljava/lang/Object;")));
		this.bufferedReaderClass = cp.addClass(cp.addUtf8("java/io/BufferedReader"));
		this.bufferedWriterClass = cp.addClass(cp.addUtf8("java/io/BufferedWriter"));
		this.fileReaderClass = cp.addClass(cp.addUtf8("java/io/FileReader"));
		this.fileWriterClass = cp.addClass(cp.addUtf8("java/io/FileWriter"));
		this.writerClass = cp.addClass(cp.addUtf8("java/io/Writer"));
		this.fileReaderInit = cp.addMethodref(this.fileReaderClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		this.fileWriterInit = cp.addMethodref(this.fileWriterClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		this.bufferedReaderInit = cp.addMethodref(this.bufferedReaderClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/io/Reader;)V")));
		this.bufferedWriterInit = cp.addMethodref(this.bufferedWriterClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/io/Writer;)V")));
		this.bufferedReaderReadLine = cp.addMethodref(this.bufferedReaderClass,
				cp.addNameAndType(cp.addUtf8("readLine"), cp.addUtf8("()Ljava/lang/String;")));
		this.bufferedReaderClose = cp.addMethodref(this.bufferedReaderClass,
				cp.addNameAndType(cp.addUtf8("close"), cp.addUtf8("()V")));
		this.writerWrite = cp.addMethodref(this.writerClass,
				cp.addNameAndType(cp.addUtf8("write"), cp.addUtf8("(Ljava/lang/String;)V")));
		this.writerClose = cp.addMethodref(this.writerClass, cp.addNameAndType(cp.addUtf8("close"), cp.addUtf8("()V")));
		this.tStr = cp.addString("t");
		this.quoteStr = cp.addString("\"");
		this.newlineStr = cp.addString("\n");
	}

	static JvmIoRuntimeBuilder create(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			ClassConstant stringClass, ClassConstant longClass, MethodrefConstant longValueOf,
			MethodrefConstant longValue, MethodrefConstant stringLength, MethodrefConstant stringSubstring,
			MethodrefConstant stringConcat, FieldrefConstant systemOut, MethodrefConstant printlnStr,
			MethodrefConstant readLineHelper) {
		return new JvmIoRuntimeBuilder(cp, thisClass, objectClass, stringClass, longClass, longValueOf, longValue,
				stringLength, stringSubstring, stringConcat, systemOut, printlnStr, readLineHelper);
	}

	/** Returns all stream-runtime method bodies to emit. */
	List<IoMethod> methods() {
		List<IoMethod> ms = new ArrayList<>();
		ms.add(new IoMethod(this.cp.addUtf8(OPEN_METHOD), this.cp.addUtf8(OPEN_DESC), 6, 6, buildOpen()));
		ms.add(new IoMethod(this.cp.addUtf8(CLOSE_METHOD), this.cp.addUtf8(CLOSE_DESC), 4, 3, buildClose()));
		ms.add(new IoMethod(this.cp.addUtf8(WRITE_LINE_METHOD), this.cp.addUtf8(WRITE_LINE_DESC), 5, 4,
				buildWriteLine()));
		ms.add(new IoMethod(this.cp.addUtf8(READ_LINE_STREAM_METHOD), this.cp.addUtf8(READ_LINE_STREAM_DESC), 4, 2,
				buildReadLineStream()));
		return ms;
	}

	/**
	 * {@code _open(Object path, int mode) -> Long handle}. Strips the surrounding quotes
	 * from the path string, opens a {@code BufferedReader} (mode 0) or
	 * {@code BufferedWriter} (mode 1), stores it in the (lazily created, growable)
	 * {@code _streams} table, and returns the index as the stream handle.
	 */
	private List<Integer> buildOpen() {
		// Slots: 0=path (Object), 1=mode (int), 2=arr, 3=count, 4=p (String), 5=stream
		List<Integer> code = new ArrayList<>();
		// if (_streams == null) _streams = new Object[16];
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamsField.index());
		int ifInitPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		code.add(Opcode.BIPUSH);
		code.add(16);
		code.add(Opcode.ANEWARRAY);
		emitU2(code, this.objectClass.index());
		code.add(Opcode.PUTSTATIC);
		emitU2(code, this.streamsField.index());
		patchBranch(code, ifInitPos, code.size());
		// arr = _streams; count = _streamCount;
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamsField.index());
		code.add(Opcode.ASTORE_2);
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamCountField.index());
		code.add(Opcode.ISTORE_3);
		// if (count >= arr.length) { arr = Arrays.copyOf(arr, count * 2); _streams = arr;
		// }
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ARRAYLENGTH);
		int ifGrowPos = code.size();
		code.add(Opcode.IF_ICMPLT);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.ICONST_2);
		code.add(Opcode.IMUL);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.arraysCopyOf.index());
		code.add(Opcode.ASTORE_2);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.PUTSTATIC);
		emitU2(code, this.streamsField.index());
		patchBranch(code, ifGrowPos, code.size());
		// p = ((String) path).substring(1, length - 1);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.stringClass.index());
		code.add(Opcode.ASTORE);
		code.add(4);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringLength.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ISUB);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringSubstring.index());
		code.add(Opcode.ASTORE);
		code.add(4);
		// stream = mode == 0 ? new BufferedReader(new FileReader(p))
		// : new BufferedWriter(new FileWriter(p));
		code.add(Opcode.ILOAD_1);
		int ifModePos = code.size();
		code.add(Opcode.IFNE);
		emitU2(code, 0);
		code.add(Opcode.NEW);
		emitU2(code, this.bufferedReaderClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.NEW);
		emitU2(code, this.fileReaderClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.fileReaderInit.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.bufferedReaderInit.index());
		code.add(Opcode.ASTORE);
		code.add(5);
		int gotoStorePos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifModePos, code.size());
		code.add(Opcode.NEW);
		emitU2(code, this.bufferedWriterClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.NEW);
		emitU2(code, this.fileWriterClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.fileWriterInit.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.bufferedWriterInit.index());
		code.add(Opcode.ASTORE);
		code.add(5);
		patchBranch(code, gotoStorePos, code.size());
		// arr[count] = stream; _streamCount = count + 1;
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.ALOAD);
		code.add(5);
		code.add(Opcode.AASTORE);
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.IADD);
		code.add(Opcode.PUTSTATIC);
		emitU2(code, this.streamCountField.index());
		// return Long.valueOf((long) count);
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.I2L);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.longValueOf.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _closeStream(Object handle) -> t}. Closes the table entry (reader or writer)
	 * and nulls it out.
	 */
	private List<Integer> buildClose() {
		// Slots: 0=handle, 1=idx (int), 2=stream
		List<Integer> code = new ArrayList<>();
		// idx = (int) ((Long) handle).longValue();
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.longClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.longValue.index());
		code.add(Opcode.L2I);
		code.add(Opcode.ISTORE_1);
		// stream = _streams[idx];
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamsField.index());
		code.add(Opcode.ILOAD_1);
		code.add(Opcode.AALOAD);
		code.add(Opcode.ASTORE_2);
		// if (stream instanceof BufferedReader) ((BufferedReader) stream).close();
		// else ((Writer) stream).close();
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.bufferedReaderClass.index());
		int ifWriterPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.bufferedReaderClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.bufferedReaderClose.index());
		int gotoDonePos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifWriterPos, code.size());
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.writerClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.writerClose.index());
		patchBranch(code, gotoDonePos, code.size());
		// _streams[idx] = null; return "t";
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamsField.index());
		code.add(Opcode.ILOAD_1);
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.AASTORE);
		emitLdc(code, this.tStr.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _writeLine(Object str, Object handle) -> str}. Writes the string content
	 * (without the surrounding quotes) plus a newline to the stream, or to standard
	 * output when the handle is {@code null}.
	 */
	private List<Integer> buildWriteLine() {
		// Slots: 0=str, 1=handle, 2=content (String), 3=writer
		List<Integer> code = new ArrayList<>();
		// content = ((String) str).substring(1, length - 1);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.stringClass.index());
		code.add(Opcode.ASTORE_2);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringLength.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ISUB);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringSubstring.index());
		code.add(Opcode.ASTORE_2);
		// if (handle == null) { System.out.println(content); return str; }
		code.add(Opcode.ALOAD_1);
		int ifStreamPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.systemOut.index());
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.printlnStr.index());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ARETURN);
		patchBranch(code, ifStreamPos, code.size());
		// writer = (Writer) _streams[(int) ((Long) handle).longValue()];
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamsField.index());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.longClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.longValue.index());
		code.add(Opcode.L2I);
		code.add(Opcode.AALOAD);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.writerClass.index());
		code.add(Opcode.ASTORE_3);
		// writer.write(content); writer.write("\n"); return str;
		code.add(Opcode.ALOAD_3);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.writerWrite.index());
		code.add(Opcode.ALOAD_3);
		emitLdc(code, this.newlineStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.writerWrite.index());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _readLineStream(Object handle) -> Object}. Reads one line from the stream
	 * (standard input when the handle is {@code null}) and wraps it with the internal
	 * {@code '"'} prefix/suffix string format; returns {@code null} (nil) on EOF.
	 */
	private List<Integer> buildReadLineStream() {
		// Slots: 0=handle, 1=line (String)
		List<Integer> code = new ArrayList<>();
		// if (handle == null) return _readLine();
		code.add(Opcode.ALOAD_0);
		int ifStreamPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.readLineHelper.index());
		code.add(Opcode.ARETURN);
		patchBranch(code, ifStreamPos, code.size());
		// line = ((BufferedReader) _streams[(int) handle]).readLine();
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamsField.index());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.longClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.longValue.index());
		code.add(Opcode.L2I);
		code.add(Opcode.AALOAD);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.bufferedReaderClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.bufferedReaderReadLine.index());
		code.add(Opcode.ASTORE_1);
		// if (line == null) return null;
		code.add(Opcode.ALOAD_1);
		int ifLinePos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		patchBranch(code, ifLinePos, code.size());
		// return "\"".concat(line).concat("\"");
		emitLdc(code, this.quoteStr.index());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringConcat.index());
		emitLdc(code, this.quoteStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringConcat.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	private static void emitU2(List<Integer> code, int value) {
		JvmRuntimeBuilder.emitU2(code, value);
	}

	private static void emitLdc(List<Integer> code, int cpIndex) {
		JvmRuntimeBuilder.emitLdc(code, cpIndex);
	}

	private static void patchBranch(List<Integer> code, int branchPos, int targetPos) {
		JvmRuntimeBuilder.patchBranch(code, branchPos, targetPos);
	}

}
