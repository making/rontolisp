package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.AccessFlag;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

import org.jspecify.annotations.Nullable;

/**
 * Builds the JVM bytecode for the file-stream runtime used by the {@code open},
 * {@code close}, {@code write-line} and stream-taking {@code read-line} built-ins (and
 * therefore by the {@code with-open-file} macro).
 *
 * <p>
 * A stream value is a {@code Long} handle indexing the static {@code _streams} table
 * (mirroring the WASM backend, where the handle is the WASI file descriptor). An entry is
 * a {@code BufferedReader} for {@code :input} or a {@code BufferedWriter} for
 * {@code :output}; a binary stream ({@code :element-type '(unsigned-byte 8)}) is a
 * {@code BufferedInputStream} or {@code BufferedOutputStream} served by
 * {@code _readByte}/{@code _writeByte}. {@code close} nulls the entry out.
 * {@code _writeLine} and {@code _readLineStream} treat a {@code null} stream as standard
 * output / standard input.
 */
final class JvmIoRuntimeBuilder {

	/**
	 * A stream-runtime method body ready to be emitted into the generated class.
	 * {@code extraFlags} is OR-ed into the emitted access flags --
	 * {@code ACC_SYNCHRONIZED} for the two methods that mutate the stream table (see
	 * {@link #ADD_STREAM_METHOD}).
	 */
	record IoMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code,
			int extraFlags) {

		IoMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
			this(name, desc, maxStack, maxLocals, code, 0);
		}
	}

	static final String STREAMS_FIELD = "_streams";

	static final String STREAMS_DESC = "[Ljava/lang/Object;";

	static final String STREAM_COUNT_FIELD = "_streamCount";

	static final String STREAM_COUNT_DESC = "I";

	static final String OPEN_METHOD = "_open";

	static final String OPEN_DESC = "(Ljava/lang/Object;I)Ljava/lang/Object;";

	/**
	 * The ONE allocator of the stream table: it appends an already-constructed entry and
	 * returns its handle. Every producer -- {@code _open}, the string-stream makers and
	 * the socket constructors in {@code JvmSocketRuntimeBuilder} -- goes through it, and
	 * it is emitted {@code synchronized}, because {@code http-handler} serves one virtual
	 * thread per request: a non-atomic "reserve a slot, then store" handed two concurrent
	 * requests the same handle, dropping one stream and crossing the two conversations on
	 * the survivor (.todo/193).
	 */
	static final String ADD_STREAM_METHOD = "_addStream";

	static final String ADD_STREAM_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String CLOSE_METHOD = "_closeStream";

	static final String CLOSE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String PROBE_FILE_METHOD = "_probeFile";

	static final String PROBE_FILE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String WRITE_LINE_METHOD = "_writeLine";

	static final String WRITE_LINE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String READ_LINE_STREAM_METHOD = "_readLineStream";

	static final String READ_LINE_STREAM_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String READ_BYTE_METHOD = "_readByte";

	static final String READ_BYTE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String READ_CHAR_METHOD = "_readChar";

	static final String READ_CHAR_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String PEEK_CHAR_METHOD = "_peekChar";

	static final String PEEK_CHAR_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String WRITE_BYTE_METHOD = "_writeByte";

	static final String WRITE_BYTE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String WRITE_STR_METHOD = "_writeStr";

	static final String WRITE_STR_DESC = "(Ljava/lang/String;Ljava/lang/Object;)V";

	static final String WRITE_STRING_METHOD = "_writeString";

	static final String WRITE_STRING_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String MAKE_STRING_OUTPUT_STREAM_METHOD = "_makeStringOutputStream";

	static final String MAKE_STRING_OUTPUT_STREAM_DESC = "()Ljava/lang/Object;";

	static final String MAKE_STRING_INPUT_STREAM_METHOD = "_makeStringInputStream";

	static final String MAKE_STRING_INPUT_STREAM_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String STRING_STREAM_CONTENTS_METHOD = "_stringStreamContents";

	static final String STRING_STREAM_CONTENTS_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String FRESH_LINE_METHOD = "_freshLine";

	static final String FRESH_LINE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String FORCE_OUTPUT_METHOD = "_forceOutput";

	static final String FORCE_OUTPUT_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String OPEN_STREAM_P_METHOD = "_openStreamP";

	static final String OPEN_STREAM_P_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String LISTEN_METHOD = "_listen";

	static final String LISTEN_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

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

	private final ClassConstant bufferedInputStreamClass;

	private final ClassConstant bufferedOutputStreamClass;

	private final ClassConstant fileInputStreamClass;

	private final ClassConstant fileOutputStreamClass;

	private final ClassConstant inputStreamClass;

	private final ClassConstant outputStreamClass;

	private final ClassConstant runtimeExceptionClass;

	private final MethodrefConstant arraysCopyOf;

	private final MethodrefConstant addStreamRef;

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

	private final MethodrefConstant bufferedInputStreamInit;

	private final MethodrefConstant bufferedOutputStreamInit;

	private final MethodrefConstant fileInputStreamInit;

	private final MethodrefConstant fileOutputStreamInit;

	private final MethodrefConstant inputStreamRead;

	private final MethodrefConstant inputStreamClose;

	private final MethodrefConstant outputStreamWrite;

	private final MethodrefConstant outputStreamClose;

	private final MethodrefConstant runtimeExceptionInit;

	private final FieldrefConstant systemOut;

	private final MethodrefConstant printlnStr;

	private final MethodrefConstant readLineHelper;

	private final MethodrefConstant printStr;

	private final MethodrefConstant stringCharAt;

	private final FieldrefConstant colField;

	private final ClassConstant stringWriterClass;

	private final MethodrefConstant stringWriterInit;

	private final MethodrefConstant stringWriterToString;

	private final MethodrefConstant stringWriterGetBuffer;

	private final MethodrefConstant stringBufferSetLength;

	private final ClassConstant stringReaderClass;

	private final MethodrefConstant stringReaderInit;

	private final MethodrefConstant writeStrMethod;

	private final ConstantPool.StringConstant tStr;

	private final ConstantPool.StringConstant quoteStr;

	private final ConstantPool.StringConstant newlineStr;

	private final ConstantPool.StringConstant eofStr;

	private final ConstantPool.StringConstant charEofStr;

	private final FieldrefConstant stdinReaderField;

	private final FieldrefConstant systemIn;

	private final ClassConstant inputStreamReaderClass;

	private final MethodrefConstant inputStreamReaderInit;

	private final MethodrefConstant bufferedReaderRead;

	private final MethodrefConstant bufferedReaderMark;

	private final MethodrefConstant bufferedReaderReset;

	private final MethodrefConstant characterIsHighSurrogate;

	private final MethodrefConstant characterIsLowSurrogate;

	private final MethodrefConstant characterToCodePoint;

	private final MethodrefConstant printStreamFlush;

	private final MethodrefConstant writerFlush;

	private final MethodrefConstant outputStreamFlush;

	private final MethodrefConstant bufferedReaderReady;

	private final MethodrefConstant inputStreamAvailable;

	private final MethodrefConstant socketIsClosed;

	private final ClassConstant fileClass;

	private final MethodrefConstant fileInit;

	private final MethodrefConstant fileExists;

	/**
	 * Socket-runtime constants, non-null only when the program uses a tcp built-in; the
	 * stream built-ins then grow socket branches (a socket entry is a raw
	 * {@code java.net.Socket}/{@code ServerSocket}, not a reader/writer). Non-socket
	 * programs keep their original bytes.
	 */
	private final JvmSocketRuntimeBuilder.@Nullable SocketRuntime sockets;

	private JvmIoRuntimeBuilder(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			ClassConstant stringClass, ClassConstant longClass, MethodrefConstant longValueOf,
			MethodrefConstant longValue, MethodrefConstant stringLength, MethodrefConstant stringSubstring,
			MethodrefConstant stringConcat, FieldrefConstant systemOut, MethodrefConstant printlnStr,
			MethodrefConstant readLineHelper, JvmSocketRuntimeBuilder.@Nullable SocketRuntime sockets) {
		this.sockets = sockets;
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
		this.addStreamRef = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(ADD_STREAM_METHOD), cp.addUtf8(ADD_STREAM_DESC)));
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
		this.bufferedInputStreamClass = cp.addClass(cp.addUtf8("java/io/BufferedInputStream"));
		this.bufferedOutputStreamClass = cp.addClass(cp.addUtf8("java/io/BufferedOutputStream"));
		this.fileInputStreamClass = cp.addClass(cp.addUtf8("java/io/FileInputStream"));
		this.fileOutputStreamClass = cp.addClass(cp.addUtf8("java/io/FileOutputStream"));
		this.inputStreamClass = cp.addClass(cp.addUtf8("java/io/InputStream"));
		this.outputStreamClass = cp.addClass(cp.addUtf8("java/io/OutputStream"));
		this.runtimeExceptionClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		this.bufferedInputStreamInit = cp.addMethodref(this.bufferedInputStreamClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/io/InputStream;)V")));
		this.bufferedOutputStreamInit = cp.addMethodref(this.bufferedOutputStreamClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/io/OutputStream;)V")));
		this.fileInputStreamInit = cp.addMethodref(this.fileInputStreamClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		this.fileOutputStreamInit = cp.addMethodref(this.fileOutputStreamClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		this.inputStreamRead = cp.addMethodref(this.inputStreamClass,
				cp.addNameAndType(cp.addUtf8("read"), cp.addUtf8("()I")));
		this.inputStreamClose = cp.addMethodref(this.inputStreamClass,
				cp.addNameAndType(cp.addUtf8("close"), cp.addUtf8("()V")));
		this.outputStreamWrite = cp.addMethodref(this.outputStreamClass,
				cp.addNameAndType(cp.addUtf8("write"), cp.addUtf8("(I)V")));
		this.outputStreamClose = cp.addMethodref(this.outputStreamClass,
				cp.addNameAndType(cp.addUtf8("close"), cp.addUtf8("()V")));
		this.runtimeExceptionInit = cp.addMethodref(this.runtimeExceptionClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		this.tStr = cp.addString("T");
		this.quoteStr = cp.addString("\"");
		this.newlineStr = cp.addString("\n");
		this.eofStr = cp.addString("read-byte: end of file");
		ClassConstant printStreamClass = cp.addClass(cp.addUtf8("java/io/PrintStream"));
		this.printStr = cp.addMethodref(printStreamClass,
				cp.addNameAndType(cp.addUtf8("print"), cp.addUtf8("(Ljava/lang/String;)V")));
		this.stringCharAt = cp.addMethodref(this.stringClass,
				cp.addNameAndType(cp.addUtf8("charAt"), cp.addUtf8("(I)C")));
		this.colField = cp.addFieldref(thisClass, cp.addNameAndType(cp.addUtf8(JvmFreshLineCompiler.COL_FIELD),
				cp.addUtf8(JvmFreshLineCompiler.COL_DESC)));
		this.stringWriterClass = cp.addClass(cp.addUtf8("java/io/StringWriter"));
		this.stringWriterInit = cp.addMethodref(this.stringWriterClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		this.stringWriterToString = cp.addMethodref(this.stringWriterClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		// get-output-stream-string CLEARS the stream as it answers (CL 21.2), which on
		// this backend is StringWriter.getBuffer().setLength(0).
		this.stringWriterGetBuffer = cp.addMethodref(this.stringWriterClass,
				cp.addNameAndType(cp.addUtf8("getBuffer"), cp.addUtf8("()Ljava/lang/StringBuffer;")));
		this.stringBufferSetLength = cp.addMethodref(cp.addClass(cp.addUtf8("java/lang/StringBuffer")),
				cp.addNameAndType(cp.addUtf8("setLength"), cp.addUtf8("(I)V")));
		this.stringReaderClass = cp.addClass(cp.addUtf8("java/io/StringReader"));
		this.stringReaderInit = cp.addMethodref(this.stringReaderClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		this.writeStrMethod = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(WRITE_STR_METHOD), cp.addUtf8(WRITE_STR_DESC)));
		// read-char support: the lazily initialized _stdinReader field (shared with the
		// _readLine helper), BufferedReader.read() and the boxed Character result.
		this.charEofStr = cp.addString(am.ik.rontolisp.ClosRegistry.END_OF_FILE_MESSAGE);
		this.stdinReaderField = cp.addFieldref(thisClass,
				cp.addNameAndType(cp.addUtf8("_stdinReader"), cp.addUtf8("Ljava/io/BufferedReader;")));
		this.systemIn = cp.addFieldref(cp.addClass(cp.addUtf8("java/lang/System")),
				cp.addNameAndType(cp.addUtf8("in"), cp.addUtf8("Ljava/io/InputStream;")));
		this.inputStreamReaderClass = cp.addClass(cp.addUtf8("java/io/InputStreamReader"));
		this.inputStreamReaderInit = cp.addMethodref(this.inputStreamReaderClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/io/InputStream;)V")));
		this.bufferedReaderRead = cp.addMethodref(this.bufferedReaderClass,
				cp.addNameAndType(cp.addUtf8("read"), cp.addUtf8("()I")));
		this.bufferedReaderMark = cp.addMethodref(this.bufferedReaderClass,
				cp.addNameAndType(cp.addUtf8("mark"), cp.addUtf8("(I)V")));
		this.bufferedReaderReset = cp.addMethodref(this.bufferedReaderClass,
				cp.addNameAndType(cp.addUtf8("reset"), cp.addUtf8("()V")));
		ClassConstant characterClass = cp.addClass(cp.addUtf8("java/lang/Character"));
		this.characterIsHighSurrogate = cp.addMethodref(characterClass,
				cp.addNameAndType(cp.addUtf8("isHighSurrogate"), cp.addUtf8("(C)Z")));
		this.characterIsLowSurrogate = cp.addMethodref(characterClass,
				cp.addNameAndType(cp.addUtf8("isLowSurrogate"), cp.addUtf8("(C)Z")));
		this.characterToCodePoint = cp.addMethodref(characterClass,
				cp.addNameAndType(cp.addUtf8("toCodePoint"), cp.addUtf8("(CC)I")));
		// force-output / listen support: flush on the three writer shapes, readiness
		// probes on the two reader shapes.
		this.printStreamFlush = cp.addMethodref(printStreamClass,
				cp.addNameAndType(cp.addUtf8("flush"), cp.addUtf8("()V")));
		this.writerFlush = cp.addMethodref(this.writerClass, cp.addNameAndType(cp.addUtf8("flush"), cp.addUtf8("()V")));
		this.outputStreamFlush = cp.addMethodref(this.outputStreamClass,
				cp.addNameAndType(cp.addUtf8("flush"), cp.addUtf8("()V")));
		this.bufferedReaderReady = cp.addMethodref(this.bufferedReaderClass,
				cp.addNameAndType(cp.addUtf8("ready"), cp.addUtf8("()Z")));
		this.inputStreamAvailable = cp.addMethodref(this.inputStreamClass,
				cp.addNameAndType(cp.addUtf8("available"), cp.addUtf8("()I")));
		this.socketIsClosed = cp.addMethodref(cp.addClass(cp.addUtf8("java/net/Socket")),
				cp.addNameAndType(cp.addUtf8("isClosed"), cp.addUtf8("()Z")));
		// probe-file support: java.io.File.exists() -- the one file question that must
		// not open (and therefore must not signal) on a missing path.
		this.fileClass = cp.addClass(cp.addUtf8("java/io/File"));
		this.fileInit = cp.addMethodref(this.fileClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		this.fileExists = cp.addMethodref(this.fileClass, cp.addNameAndType(cp.addUtf8("exists"), cp.addUtf8("()Z")));
	}

	static JvmIoRuntimeBuilder create(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			ClassConstant stringClass, ClassConstant longClass, MethodrefConstant longValueOf,
			MethodrefConstant longValue, MethodrefConstant stringLength, MethodrefConstant stringSubstring,
			MethodrefConstant stringConcat, FieldrefConstant systemOut, MethodrefConstant printlnStr,
			MethodrefConstant readLineHelper, JvmSocketRuntimeBuilder.@Nullable SocketRuntime sockets) {
		return new JvmIoRuntimeBuilder(cp, thisClass, objectClass, stringClass, longClass, longValueOf, longValue,
				stringLength, stringSubstring, stringConcat, systemOut, printlnStr, readLineHelper, sockets);
	}

	/** Returns all stream-runtime method bodies to emit. */
	List<IoMethod> methods() {
		List<IoMethod> ms = new ArrayList<>();
		ms.add(new IoMethod(this.cp.addUtf8(ADD_STREAM_METHOD), this.cp.addUtf8(ADD_STREAM_DESC), 3, 3,
				buildAddStream(), AccessFlag.ACC_SYNCHRONIZED));
		ms.add(new IoMethod(this.cp.addUtf8(OPEN_METHOD), this.cp.addUtf8(OPEN_DESC), 6, 4, buildOpen()));
		// synchronized with _addStream: the entry is nulled out on the CURRENT table, so
		// a close racing a table growth must not write into the array being replaced.
		ms.add(new IoMethod(this.cp.addUtf8(CLOSE_METHOD), this.cp.addUtf8(CLOSE_DESC), 4, 3, buildClose(),
				AccessFlag.ACC_SYNCHRONIZED));
		ms.add(new IoMethod(this.cp.addUtf8(PROBE_FILE_METHOD), this.cp.addUtf8(PROBE_FILE_DESC), 4, 2,
				buildProbeFile()));
		ms.add(new IoMethod(this.cp.addUtf8(WRITE_LINE_METHOD), this.cp.addUtf8(WRITE_LINE_DESC), 5, 4,
				buildWriteLine()));
		ms.add(new IoMethod(this.cp.addUtf8(READ_LINE_STREAM_METHOD), this.cp.addUtf8(READ_LINE_STREAM_DESC), 4, 2,
				buildReadLineStream()));
		ms.add(new IoMethod(this.cp.addUtf8(READ_BYTE_METHOD), this.cp.addUtf8(READ_BYTE_DESC), 4, 5, buildReadByte()));
		ms.add(new IoMethod(this.cp.addUtf8(READ_CHAR_METHOD), this.cp.addUtf8(READ_CHAR_DESC), 5, 6, buildReadChar()));
		ms.add(new IoMethod(this.cp.addUtf8(PEEK_CHAR_METHOD), this.cp.addUtf8(PEEK_CHAR_DESC), 5, 6, buildPeekChar()));
		ms.add(new IoMethod(this.cp.addUtf8(WRITE_BYTE_METHOD), this.cp.addUtf8(WRITE_BYTE_DESC), 4, 3,
				buildWriteByte()));
		ms.add(new IoMethod(this.cp.addUtf8(WRITE_STR_METHOD), this.cp.addUtf8(WRITE_STR_DESC), 4, 3, buildWriteStr()));
		ms.add(new IoMethod(this.cp.addUtf8(WRITE_STRING_METHOD), this.cp.addUtf8(WRITE_STRING_DESC), 4, 3,
				buildWriteString()));
		ms.add(new IoMethod(this.cp.addUtf8(MAKE_STRING_OUTPUT_STREAM_METHOD),
				this.cp.addUtf8(MAKE_STRING_OUTPUT_STREAM_DESC), 3, 1, buildMakeStringOutputStream()));
		ms.add(new IoMethod(this.cp.addUtf8(MAKE_STRING_INPUT_STREAM_METHOD),
				this.cp.addUtf8(MAKE_STRING_INPUT_STREAM_DESC), 5, 2, buildMakeStringInputStream()));
		ms.add(new IoMethod(this.cp.addUtf8(STRING_STREAM_CONTENTS_METHOD),
				this.cp.addUtf8(STRING_STREAM_CONTENTS_DESC), 3, 3, buildStringStreamContents()));
		ms.add(new IoMethod(this.cp.addUtf8(FRESH_LINE_METHOD), this.cp.addUtf8(FRESH_LINE_DESC), 4, 3,
				buildFreshLine()));
		ms.add(new IoMethod(this.cp.addUtf8(FORCE_OUTPUT_METHOD), this.cp.addUtf8(FORCE_OUTPUT_DESC), 4, 2,
				buildForceOutput()));
		ms.add(new IoMethod(this.cp.addUtf8(LISTEN_METHOD), this.cp.addUtf8(LISTEN_DESC), 4, 2, buildListen()));
		ms.add(new IoMethod(this.cp.addUtf8(OPEN_STREAM_P_METHOD), this.cp.addUtf8(OPEN_STREAM_P_DESC), 4, 2,
				buildOpenStreamP()));
		return ms;
	}

	/**
	 * {@code _freshLine(Object dest) -> null}. Emits a newline unless the destination is
	 * already at the start of a line: a non-handle designator ({@code null}/T) is
	 * standard output (the {@code _col} tracking); a string-stream entry exposes its
	 * contents, so the check is exact; any other writer's column is unknown, so a newline
	 * is always written (the same rule on every backend).
	 */
	private List<Integer> buildFreshLine() {
		// Slots: 0=dest, 1=entry, 2=contents (String)
		List<Integer> code = new ArrayList<>();
		// if (!(dest instanceof Long)) { if (_col != 0) { print "\n"; _col = 0; } }
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.longClass.index());
		int ifHandlePos = code.size();
		code.add(Opcode.IFNE);
		emitU2(code, 0);
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.colField.index());
		int ifAtStartPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.systemOut.index());
		code.add(Opcode.LDC_W);
		emitU2(code, this.newlineStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.printStr.index());
		code.add(Opcode.ICONST_0);
		code.add(Opcode.PUTSTATIC);
		emitU2(code, this.colField.index());
		patchBranch(code, ifAtStartPos, code.size());
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		patchBranch(code, ifHandlePos, code.size());
		// entry = _streams[(int) ((Long) dest).longValue()];
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamsField.index());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.longClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.longValue.index());
		code.add(Opcode.L2I);
		code.add(Opcode.AALOAD);
		code.add(Opcode.ASTORE_1);
		// if (entry instanceof StringWriter) { newline only when mid-line }
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.stringWriterClass.index());
		int ifNotStringWriterPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.stringWriterClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringWriterToString.index());
		code.add(Opcode.ASTORE_2);
		// if (contents.length() == 0 || contents.charAt(len - 1) == '\n') return null;
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringLength.index());
		int ifEmptyPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringLength.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ISUB);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringCharAt.index());
		code.add(Opcode.BIPUSH);
		code.add(10);
		int ifNewlinePos = code.size();
		code.add(Opcode.IF_ICMPEQ);
		emitU2(code, 0);
		int gotoWritePos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifEmptyPos, code.size());
		patchBranch(code, ifNewlinePos, code.size());
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		patchBranch(code, gotoWritePos, code.size());
		patchBranch(code, ifNotStringWriterPos, code.size());
		// _writeStr("\n", dest); return null;
		code.add(Opcode.LDC_W);
		emitU2(code, this.newlineStr.index());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.writeStrMethod.index());
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _forceOutput(Object handle) -> null}. Flushes the designated output stream's
	 * buffered bytes: a non-handle designator ({@code null}/T) flushes standard output, a
	 * socket entry its output stream, a writer/output-stream entry itself. Anything else
	 * (an input stream, a closed slot) is a no-op -- flushing never signals here.
	 */
	private List<Integer> buildForceOutput() {
		// Slots: 0=handle, 1=entry
		List<Integer> code = new ArrayList<>();
		// if (!(handle instanceof Long)) { System.out.flush(); return null; }
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.longClass.index());
		int ifHandlePos = code.size();
		code.add(Opcode.IFNE);
		emitU2(code, 0);
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.systemOut.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.printStreamFlush.index());
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		patchBranch(code, ifHandlePos, code.size());
		// entry = _streams[(int) ((Long) handle).longValue()];
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamsField.index());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.longClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.longValue.index());
		code.add(Opcode.L2I);
		code.add(Opcode.AALOAD);
		code.add(Opcode.ASTORE_1);
		List<Integer> gotoDones = new ArrayList<>();
		if (this.sockets != null) {
			// if (entry instanceof Socket) { entry.getOutputStream().flush(); }
			code.add(Opcode.ALOAD_1);
			code.add(Opcode.INSTANCEOF);
			emitU2(code, this.sockets.socketClass().index());
			int ifNotSocketPos = code.size();
			code.add(Opcode.IFEQ);
			emitU2(code, 0);
			code.add(Opcode.ALOAD_1);
			code.add(Opcode.CHECKCAST);
			emitU2(code, this.sockets.socketClass().index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, this.sockets.socketGetOutputStream().index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, this.outputStreamFlush.index());
			gotoDones.add(code.size());
			code.add(Opcode.GOTO);
			emitU2(code, 0);
			patchBranch(code, ifNotSocketPos, code.size());
		}
		// if (entry instanceof Writer) ((Writer) entry).flush();
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.writerClass.index());
		int ifNotWriterPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.writerClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.writerFlush.index());
		gotoDones.add(code.size());
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifNotWriterPos, code.size());
		// else if (entry instanceof OutputStream) ((OutputStream) entry).flush();
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.outputStreamClass.index());
		int ifNotOutputPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.outputStreamClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.outputStreamFlush.index());
		patchBranch(code, ifNotOutputPos, code.size());
		for (int gotoDone : gotoDones) {
			patchBranch(code, gotoDone, code.size());
		}
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _openStreamP(Object handle) -> "T" | null}. Whether the handle still names
	 * an open stream: its {@code _streams} slot is non-null (a {@code close} nulls it)
	 * and, for a socket entry, the socket has not been closed from the other side. A
	 * non-handle designator (the {@code t} standard-output designator) answers T; any
	 * other value answers nil.
	 */
	private List<Integer> buildOpenStreamP() {
		// Slots: 0=handle, 1=idx (int)
		List<Integer> code = new ArrayList<>();
		List<Integer> gotoNils = new ArrayList<>();
		// A non-Long designator: T for the t stream designator, nil for anything else.
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.longClass.index());
		int ifHandlePos = code.size();
		code.add(Opcode.IFNE);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		gotoNils.add(code.size());
		code.add(Opcode.IFNULL);
		emitU2(code, 0);
		emitLdc(code, this.tStr.index());
		code.add(Opcode.ARETURN);
		patchBranch(code, ifHandlePos, code.size());
		// _streams == null -> nil
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamsField.index());
		gotoNils.add(code.size());
		code.add(Opcode.IFNULL);
		emitU2(code, 0);
		// idx = (int) handle; idx < 0 || idx >= _streams.length -> nil
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.longClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.longValue.index());
		code.add(Opcode.L2I);
		code.add(Opcode.ISTORE_1);
		code.add(Opcode.ILOAD_1);
		gotoNils.add(code.size());
		code.add(Opcode.IFLT);
		emitU2(code, 0);
		code.add(Opcode.ILOAD_1);
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamsField.index());
		code.add(Opcode.ARRAYLENGTH);
		gotoNils.add(code.size());
		code.add(Opcode.IF_ICMPGE);
		emitU2(code, 0);
		// entry == null -> nil
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamsField.index());
		code.add(Opcode.ILOAD_1);
		code.add(Opcode.AALOAD);
		gotoNils.add(code.size());
		code.add(Opcode.IFNULL);
		emitU2(code, 0);
		if (this.sockets != null) {
			// A socket the peer (or a foreign close) shut: isClosed() -> nil.
			code.add(Opcode.GETSTATIC);
			emitU2(code, this.streamsField.index());
			code.add(Opcode.ILOAD_1);
			code.add(Opcode.AALOAD);
			code.add(Opcode.INSTANCEOF);
			emitU2(code, this.sockets.socketClass().index());
			int ifNotSocketPos = code.size();
			code.add(Opcode.IFEQ);
			emitU2(code, 0);
			code.add(Opcode.GETSTATIC);
			emitU2(code, this.streamsField.index());
			code.add(Opcode.ILOAD_1);
			code.add(Opcode.AALOAD);
			code.add(Opcode.CHECKCAST);
			emitU2(code, this.sockets.socketClass().index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, this.socketIsClosed.index());
			gotoNils.add(code.size());
			code.add(Opcode.IFNE);
			emitU2(code, 0);
			patchBranch(code, ifNotSocketPos, code.size());
		}
		emitLdc(code, this.tStr.index());
		code.add(Opcode.ARETURN);
		for (int gotoNil : gotoNils) {
			patchBranch(code, gotoNil, code.size());
		}
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _listen(Object handle) -> "T" | null}. Whether a character/byte is
	 * immediately available without blocking: a non-handle designator probes standard
	 * input ({@code _stdinReader.ready()} when the shared reader exists, otherwise
	 * {@code System.in.available()}), a socket entry asks the kernel receive buffer, a
	 * reader entry {@code ready()}, a byte-stream entry {@code available()}. A non-input
	 * entry answers nil.
	 */
	private List<Integer> buildListen() {
		// Slots: 0=handle, 1=entry
		List<Integer> code = new ArrayList<>();
		List<Integer> gotoNils = new ArrayList<>();
		List<Integer> gotoTs = new ArrayList<>();
		// if (!(handle instanceof Long)) { stdin probe }
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.longClass.index());
		int ifHandlePos = code.size();
		code.add(Opcode.IFNE);
		emitU2(code, 0);
		// if (_stdinReader != null) return _stdinReader.ready() ? "T" : null;
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.stdinReaderField.index());
		int ifNoReaderPos = code.size();
		code.add(Opcode.IFNULL);
		emitU2(code, 0);
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.stdinReaderField.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.bufferedReaderReady.index());
		gotoNils.add(code.size());
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		gotoTs.add(code.size());
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifNoReaderPos, code.size());
		// return System.in.available() > 0 ? "T" : null;
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.systemIn.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.inputStreamAvailable.index());
		gotoNils.add(code.size());
		code.add(Opcode.IFLE);
		emitU2(code, 0);
		gotoTs.add(code.size());
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifHandlePos, code.size());
		// entry = _streams[(int) ((Long) handle).longValue()];
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamsField.index());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.longClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.longValue.index());
		code.add(Opcode.L2I);
		code.add(Opcode.AALOAD);
		code.add(Opcode.ASTORE_1);
		if (this.sockets != null) {
			// if (entry instanceof Socket) return in.available() > 0 ? "T" : null;
			code.add(Opcode.ALOAD_1);
			code.add(Opcode.INSTANCEOF);
			emitU2(code, this.sockets.socketClass().index());
			int ifNotSocketPos = code.size();
			code.add(Opcode.IFEQ);
			emitU2(code, 0);
			code.add(Opcode.ALOAD_1);
			code.add(Opcode.CHECKCAST);
			emitU2(code, this.sockets.socketClass().index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, this.sockets.socketGetInputStream().index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, this.inputStreamAvailable.index());
			gotoNils.add(code.size());
			code.add(Opcode.IFLE);
			emitU2(code, 0);
			gotoTs.add(code.size());
			code.add(Opcode.GOTO);
			emitU2(code, 0);
			patchBranch(code, ifNotSocketPos, code.size());
		}
		// if (entry instanceof BufferedReader) return ready() ? "T" : null;
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.bufferedReaderClass.index());
		int ifNotReaderPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.bufferedReaderClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.bufferedReaderReady.index());
		gotoNils.add(code.size());
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		gotoTs.add(code.size());
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifNotReaderPos, code.size());
		// if (entry instanceof InputStream) return available() > 0 ? "T" : null;
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.inputStreamClass.index());
		gotoNils.add(code.size());
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.inputStreamClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.inputStreamAvailable.index());
		gotoNils.add(code.size());
		code.add(Opcode.IFLE);
		emitU2(code, 0);
		for (int gotoT : gotoTs) {
			patchBranch(code, gotoT, code.size());
		}
		emitLdc(code, this.tStr.index());
		code.add(Opcode.ARETURN);
		for (int gotoNil : gotoNils) {
			patchBranch(code, gotoNil, code.size());
		}
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _open(Object path, int mode) -> Long handle}. Strips the surrounding quotes
	 * from the path string, opens a {@code BufferedReader} (mode 0), a
	 * {@code BufferedWriter} (mode 1), a {@code BufferedInputStream} (mode 2, binary) or
	 * a {@code BufferedOutputStream} (mode 3, binary) and hands it to
	 * {@link #ADD_STREAM_METHOD}, which returns the handle. The file is opened BEFORE the
	 * table is touched, so the slow part stays outside the allocator's lock.
	 */
	private List<Integer> buildOpen() {
		// Slots: 0=path (Object), 1=mode (int), 2=p (String), 3=stream
		List<Integer> code = new ArrayList<>();
		// p = ((String) path).substring(1, length - 1);
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
		// stream = switch (mode) { case 0 -> new BufferedReader(new FileReader(p));
		// case 1 -> new BufferedWriter(new FileWriter(p));
		// case 2 -> new BufferedInputStream(new FileInputStream(p));
		// default -> new BufferedOutputStream(new FileOutputStream(p)); };
		code.add(Opcode.ILOAD_1);
		int ifModePos = code.size();
		code.add(Opcode.IFNE);
		emitU2(code, 0);
		emitOpenStream(code, this.bufferedReaderClass, this.fileReaderClass, this.fileReaderInit,
				this.bufferedReaderInit);
		int gotoStorePos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifModePos, code.size());
		code.add(Opcode.ILOAD_1);
		code.add(Opcode.ICONST_1);
		int ifMode1Pos = code.size();
		code.add(Opcode.IF_ICMPNE);
		emitU2(code, 0);
		emitOpenStream(code, this.bufferedWriterClass, this.fileWriterClass, this.fileWriterInit,
				this.bufferedWriterInit);
		int gotoStorePos1 = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifMode1Pos, code.size());
		code.add(Opcode.ILOAD_1);
		code.add(Opcode.ICONST_2);
		int ifMode2Pos = code.size();
		code.add(Opcode.IF_ICMPNE);
		emitU2(code, 0);
		emitOpenStream(code, this.bufferedInputStreamClass, this.fileInputStreamClass, this.fileInputStreamInit,
				this.bufferedInputStreamInit);
		int gotoStorePos2 = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifMode2Pos, code.size());
		emitOpenStream(code, this.bufferedOutputStreamClass, this.fileOutputStreamClass, this.fileOutputStreamInit,
				this.bufferedOutputStreamInit);
		patchBranch(code, gotoStorePos, code.size());
		patchBranch(code, gotoStorePos1, code.size());
		patchBranch(code, gotoStorePos2, code.size());
		// return _addStream(stream);
		code.add(Opcode.ALOAD_3);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.addStreamRef.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _probeFile(Object path) -> path | null}. Whether the path names an existing
	 * file: the quotes are stripped the way {@code _open} does, and the answer is the
	 * ORIGINAL (still quoted) path value so the caller gets a Lisp string back --
	 * rontolisp's namestring stands in for the truename. Unlike {@code _open} this never
	 * throws on a missing path; that is the whole point of the primitive.
	 */
	private List<Integer> buildProbeFile() {
		// Slots: 0=path (Object), 1=p (String)
		List<Integer> code = new ArrayList<>();
		// p = ((String) path).substring(1, length - 1);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.stringClass.index());
		code.add(Opcode.ASTORE_1);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringLength.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ISUB);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringSubstring.index());
		code.add(Opcode.ASTORE_1);
		// if (!new File(p).exists()) return null;
		code.add(Opcode.NEW);
		emitU2(code, this.fileClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.fileInit.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.fileExists.index());
		int ifMissingPos = code.size();
		code.add(Opcode.IFNE);
		emitU2(code, 0);
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		patchBranch(code, ifMissingPos, code.size());
		// return path;
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * Emits {@code slot3 = new <buffered>(new <file>(p))} where {@code p} is the path in
	 * slot 2 -- one arm of the {@code _open} mode branch.
	 */
	private void emitOpenStream(List<Integer> code, ClassConstant bufferedClass, ClassConstant fileClass,
			MethodrefConstant fileInit, MethodrefConstant bufferedInit) {
		code.add(Opcode.NEW);
		emitU2(code, bufferedClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.NEW);
		emitU2(code, fileClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, fileInit.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, bufferedInit.index());
		code.add(Opcode.ASTORE_3);
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
		// Socket entries first (only when the program uses tcp built-ins): a Socket /
		// ServerSocket is neither a reader/writer nor a raw byte stream, so the chain
		// below would fail on it.
		List<Integer> socketGotoDones = new ArrayList<>();
		if (this.sockets != null) {
			code.add(Opcode.ALOAD_2);
			code.add(Opcode.INSTANCEOF);
			emitU2(code, this.sockets.socketClass().index());
			int ifNotSocketPos = code.size();
			code.add(Opcode.IFEQ);
			emitU2(code, 0);
			code.add(Opcode.ALOAD_2);
			code.add(Opcode.CHECKCAST);
			emitU2(code, this.sockets.socketClass().index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, this.sockets.socketClose().index());
			socketGotoDones.add(code.size());
			code.add(Opcode.GOTO);
			emitU2(code, 0);
			patchBranch(code, ifNotSocketPos, code.size());
			code.add(Opcode.ALOAD_2);
			code.add(Opcode.INSTANCEOF);
			emitU2(code, this.sockets.serverSocketClass().index());
			int ifNotListenerPos = code.size();
			code.add(Opcode.IFEQ);
			emitU2(code, 0);
			code.add(Opcode.ALOAD_2);
			code.add(Opcode.CHECKCAST);
			emitU2(code, this.sockets.serverSocketClass().index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, this.sockets.serverSocketClose().index());
			socketGotoDones.add(code.size());
			code.add(Opcode.GOTO);
			emitU2(code, 0);
			patchBranch(code, ifNotListenerPos, code.size());
		}
		// if (stream instanceof BufferedReader) ((BufferedReader) stream).close();
		// else if (stream instanceof InputStream) ((InputStream) stream).close();
		// else if (stream instanceof OutputStream) ((OutputStream) stream).close();
		// else ((Writer) stream).close();
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.bufferedReaderClass.index());
		int ifNotReaderPos = code.size();
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
		patchBranch(code, ifNotReaderPos, code.size());
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.inputStreamClass.index());
		int ifNotInputPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.inputStreamClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.inputStreamClose.index());
		int gotoDonePos1 = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifNotInputPos, code.size());
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.outputStreamClass.index());
		int ifNotOutputPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.outputStreamClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.outputStreamClose.index());
		int gotoDonePos2 = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifNotOutputPos, code.size());
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.writerClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.writerClose.index());
		patchBranch(code, gotoDonePos, code.size());
		patchBranch(code, gotoDonePos1, code.size());
		patchBranch(code, gotoDonePos2, code.size());
		for (int socketGotoDone : socketGotoDones) {
			patchBranch(code, socketGotoDone, code.size());
		}
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
		// if (!(handle instanceof Long)) { System.out.println(content); _col = 0;
		// return str; } -- null and the designator t both mean standard output.
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.longClass.index());
		int ifStreamPos = code.size();
		code.add(Opcode.IFNE);
		emitU2(code, 0);
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.systemOut.index());
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.printlnStr.index());
		code.add(Opcode.ICONST_0);
		code.add(Opcode.PUTSTATIC);
		emitU2(code, this.colField.index());
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
		if (this.sockets != null) {
			// if (entry instanceof Socket) return _sockWriteLine(str, entry);
			code.add(Opcode.ASTORE_3);
			code.add(Opcode.ALOAD_3);
			code.add(Opcode.INSTANCEOF);
			emitU2(code, this.sockets.socketClass().index());
			int ifNotSocketPos = code.size();
			code.add(Opcode.IFEQ);
			emitU2(code, 0);
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.ALOAD_3);
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, this.sockets.sockWriteLine().index());
			code.add(Opcode.ARETURN);
			patchBranch(code, ifNotSocketPos, code.size());
			code.add(Opcode.ALOAD_3);
		}
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
	 * (standard input for a non-handle designator -- {@code null} = nil and {@code "T"} =
	 * t, what {@code *standard-input*} holds by default) and wraps it with the internal
	 * {@code '"'} prefix/suffix string format; returns {@code null} (nil) on EOF.
	 */
	private List<Integer> buildReadLineStream() {
		// Slots: 0=handle, 1=line (String)
		List<Integer> code = new ArrayList<>();
		// if (!(handle instanceof Long)) return _readLine();
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.longClass.index());
		int ifStreamPos = code.size();
		code.add(Opcode.IFNE);
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
		if (this.sockets != null) {
			// if (entry instanceof Socket) return _sockReadLine(entry);
			code.add(Opcode.ASTORE_1);
			code.add(Opcode.ALOAD_1);
			code.add(Opcode.INSTANCEOF);
			emitU2(code, this.sockets.socketClass().index());
			int ifNotSocketPos = code.size();
			code.add(Opcode.IFEQ);
			emitU2(code, 0);
			code.add(Opcode.ALOAD_1);
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, this.sockets.sockReadLine().index());
			code.add(Opcode.ARETURN);
			patchBranch(code, ifNotSocketPos, code.size());
			code.add(Opcode.ALOAD_1);
		}
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

	/**
	 * {@code _readByte(Object handle, Object eofErrorP, Object eofValue) -> Object}.
	 * Reads one byte from the binary input stream in the table; on EOF returns
	 * {@code eofValue} when {@code eofErrorP} is nil, otherwise throws. A byte is a boxed
	 * {@code Long} 0-255.
	 */
	private List<Integer> buildReadByte() {
		// Slots: 0=handle, 1=eofErrorP, 2=eofValue, 3=in (InputStream), 4=b (int)
		List<Integer> code = new ArrayList<>();
		// in = (InputStream) _streams[(int) ((Long) handle).longValue()];
		// (a Socket entry contributes its input stream instead)
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamsField.index());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.longClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.longValue.index());
		code.add(Opcode.L2I);
		code.add(Opcode.AALOAD);
		if (this.sockets != null) {
			code.add(Opcode.ASTORE_3);
			code.add(Opcode.ALOAD_3);
			code.add(Opcode.INSTANCEOF);
			emitU2(code, this.sockets.socketClass().index());
			int ifNotSocketPos = code.size();
			code.add(Opcode.IFEQ);
			emitU2(code, 0);
			code.add(Opcode.ALOAD_3);
			code.add(Opcode.CHECKCAST);
			emitU2(code, this.sockets.socketClass().index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, this.sockets.socketGetInputStream().index());
			code.add(Opcode.ASTORE_3);
			int gotoReadPos = code.size();
			code.add(Opcode.GOTO);
			emitU2(code, 0);
			patchBranch(code, ifNotSocketPos, code.size());
			code.add(Opcode.ALOAD_3);
			code.add(Opcode.CHECKCAST);
			emitU2(code, this.inputStreamClass.index());
			code.add(Opcode.ASTORE_3);
			patchBranch(code, gotoReadPos, code.size());
		}
		else {
			code.add(Opcode.CHECKCAST);
			emitU2(code, this.inputStreamClass.index());
			code.add(Opcode.ASTORE_3);
		}
		// b = in.read();
		code.add(Opcode.ALOAD_3);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.inputStreamRead.index());
		code.add(Opcode.ISTORE);
		code.add(4);
		// if (b >= 0) return Long.valueOf((long) b);
		code.add(Opcode.ILOAD);
		code.add(4);
		int ifEofPos = code.size();
		code.add(Opcode.IFLT);
		emitU2(code, 0);
		code.add(Opcode.ILOAD);
		code.add(4);
		code.add(Opcode.I2L);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.longValueOf.index());
		code.add(Opcode.ARETURN);
		patchBranch(code, ifEofPos, code.size());
		// if (eofErrorP == null) return eofValue;
		code.add(Opcode.ALOAD_1);
		int ifThrowPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ARETURN);
		patchBranch(code, ifThrowPos, code.size());
		// throw new RuntimeException("read-byte: end of file");
		code.add(Opcode.NEW);
		emitU2(code, this.runtimeExceptionClass.index());
		code.add(Opcode.DUP);
		emitLdc(code, this.eofStr.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.runtimeExceptionInit.index());
		code.add(Opcode.ATHROW);
		return code;
	}

	/**
	 * {@code _readChar(Object handle, Object eofErrorP, Object eofValue) -> Object}.
	 * Reads one character (a Unicode CODE POINT) from the text stream in the table, or
	 * from standard input when the handle is {@code null} (lazily initializing the
	 * {@code _stdinReader} field the {@code _readLine} helper shares). On EOF returns
	 * {@code eofValue} when {@code eofErrorP} is nil, otherwise throws.
	 *
	 * <p>
	 * When the underlying {@code BufferedReader.read()} yields a high-surrogate UTF-16
	 * code unit, peek/consume the following unit and combine into a single supplementary
	 * code point before boxing as CHARACTER ({@code int[1]{cp}}). {@code mark(1)} +
	 * conditional {@code reset()} on a non-matching low half keeps the stream position
	 * aligned. Matches {@code Environment.READ_CHAR} on the interpreter and the
	 * code-point walk on the WASM binary stream.
	 */
	private List<Integer> buildReadChar() {
		// Slots: 0=handle, 1=eofErrorP, 2=eofValue, 3=r (BufferedReader), 4=c (int),
		// 5=low (int)
		List<Integer> code = new ArrayList<>();
		emitResolveReader(code);
		// READ: c = r.read();
		code.add(Opcode.ALOAD_3);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.bufferedReaderRead.index());
		code.add(Opcode.ISTORE);
		code.add(4);
		// if (c < 0) goto EOF;
		code.add(Opcode.ILOAD);
		code.add(4);
		int ifEofPos = code.size();
		code.add(Opcode.IFLT);
		emitU2(code, 0);
		// if (!Character.isHighSurrogate((char) c)) goto BOX;
		code.add(Opcode.ILOAD);
		code.add(4);
		code.add(Opcode.I2C);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.characterIsHighSurrogate.index());
		int ifNotHighPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		// r.mark(1);
		code.add(Opcode.ALOAD_3);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.bufferedReaderMark.index());
		// low = r.read();
		code.add(Opcode.ALOAD_3);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.bufferedReaderRead.index());
		code.add(Opcode.ISTORE);
		code.add(5);
		// if (low < 0) goto BOX; -- EOF on the low half, keep the raw surrogate.
		code.add(Opcode.ILOAD);
		code.add(5);
		int ifLowEofPos = code.size();
		code.add(Opcode.IFLT);
		emitU2(code, 0);
		// if (!Character.isLowSurrogate((char) low)) goto RESET;
		code.add(Opcode.ILOAD);
		code.add(5);
		code.add(Opcode.I2C);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.characterIsLowSurrogate.index());
		int ifNotLowPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		// c = Character.toCodePoint((char) c, (char) low);
		code.add(Opcode.ILOAD);
		code.add(4);
		code.add(Opcode.I2C);
		code.add(Opcode.ILOAD);
		code.add(5);
		code.add(Opcode.I2C);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.characterToCodePoint.index());
		code.add(Opcode.ISTORE);
		code.add(4);
		int gotoBoxAfterCombinePos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		// RESET: r.reset(); goto BOX (the raw high surrogate stays in c).
		patchBranch(code, ifNotLowPos, code.size());
		code.add(Opcode.ALOAD_3);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.bufferedReaderReset.index());
		// BOX: return int[1]{c} -- the runtime CHARACTER representation.
		patchBranch(code, ifNotHighPos, code.size());
		patchBranch(code, ifLowEofPos, code.size());
		patchBranch(code, gotoBoxAfterCombinePos, code.size());
		emitBoxCodePoint(code, 4);
		// EOF: if (eofErrorP == null) return eofValue;
		patchBranch(code, ifEofPos, code.size());
		emitCharEof(code);
		return code;
	}

	/**
	 * {@code _peekChar(Object handle, Object eofErrorP, Object eofValue) -> Object}. The
	 * next character of the stream, LEFT IN PLACE: a {@code mark(2)} before the read and
	 * a {@code reset()} after it put the position back, and the budget of 2 covers a
	 * surrogate pair, so a supplementary code point peeks whole. Matches
	 * {@code Environment}'s {@code %peek-char} on the interpreter and
	 * {@code _peek_char}'s pushback cell on WASM.
	 */
	private List<Integer> buildPeekChar() {
		// Slots: 0=handle, 1=eofErrorP, 2=eofValue, 3=r (BufferedReader), 4=c (int),
		// 5=low (int)
		List<Integer> code = new ArrayList<>();
		emitResolveReader(code);
		// r.mark(2);
		code.add(Opcode.ALOAD_3);
		code.add(Opcode.ICONST_2);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.bufferedReaderMark.index());
		// c = r.read();
		code.add(Opcode.ALOAD_3);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.bufferedReaderRead.index());
		code.add(Opcode.ISTORE);
		code.add(4);
		// if (c < 0) goto EOF;
		code.add(Opcode.ILOAD);
		code.add(4);
		int ifEofPos = code.size();
		code.add(Opcode.IFLT);
		emitU2(code, 0);
		// if (!Character.isHighSurrogate((char) c)) goto BOX;
		code.add(Opcode.ILOAD);
		code.add(4);
		code.add(Opcode.I2C);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.characterIsHighSurrogate.index());
		int ifNotHighPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		// low = r.read();
		code.add(Opcode.ALOAD_3);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.bufferedReaderRead.index());
		code.add(Opcode.ISTORE);
		code.add(5);
		// if (low < 0) goto BOX;
		code.add(Opcode.ILOAD);
		code.add(5);
		int ifLowEofPos = code.size();
		code.add(Opcode.IFLT);
		emitU2(code, 0);
		// if (!Character.isLowSurrogate((char) low)) goto BOX;
		code.add(Opcode.ILOAD);
		code.add(5);
		code.add(Opcode.I2C);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.characterIsLowSurrogate.index());
		int ifNotLowPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		// c = Character.toCodePoint((char) c, (char) low);
		code.add(Opcode.ILOAD);
		code.add(4);
		code.add(Opcode.I2C);
		code.add(Opcode.ILOAD);
		code.add(5);
		code.add(Opcode.I2C);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.characterToCodePoint.index());
		code.add(Opcode.ISTORE);
		code.add(4);
		// BOX: r.reset(); return int[1]{c}.
		patchBranch(code, ifNotHighPos, code.size());
		patchBranch(code, ifLowEofPos, code.size());
		patchBranch(code, ifNotLowPos, code.size());
		code.add(Opcode.ALOAD_3);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.bufferedReaderReset.index());
		emitBoxCodePoint(code, 4);
		// EOF: rewind (the mark is still live) and take the eof branch.
		patchBranch(code, ifEofPos, code.size());
		code.add(Opcode.ALOAD_3);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.bufferedReaderReset.index());
		emitCharEof(code);
		return code;
	}

	/** {@code return int[1]{slot}} -- the runtime CHARACTER representation. */
	private static void emitBoxCodePoint(List<Integer> code, int slot) {
		code.add(Opcode.ICONST_1);
		code.add(Opcode.NEWARRAY);
		code.add(10); // T_INT
		code.add(Opcode.DUP);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.ILOAD);
		code.add(slot);
		code.add(Opcode.IASTORE);
		code.add(Opcode.ARETURN);
	}

	/**
	 * The shared end-of-file tail of {@code _readChar}/{@code _peekChar}: return
	 * {@code eofValue} when {@code eofErrorP} is nil, otherwise throw. The throw is a
	 * BACKSTOP -- every compiled call site whose eof-error-p can be true is rewritten to
	 * the typed {@code end-of-file} signal by
	 * {@code LispMacroExpander.expandReadEofSignal}, which reaches the helper with a nil
	 * eof-error-p and decides in Lisp.
	 */
	private void emitCharEof(List<Integer> code) {
		code.add(Opcode.ALOAD_1);
		int ifThrowPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ARETURN);
		patchBranch(code, ifThrowPos, code.size());
		code.add(Opcode.NEW);
		emitU2(code, this.runtimeExceptionClass.index());
		code.add(Opcode.DUP);
		emitLdc(code, this.charEofStr.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.runtimeExceptionInit.index());
		code.add(Opcode.ATHROW);
	}

	/**
	 * Resolves the stream argument in slot 0 into a {@code BufferedReader} in slot 3: a
	 * non-handle designator ({@code null} = nil, {@code "T"} = t) is standard input
	 * (lazily initializing the {@code _stdinReader} field the {@code _readLine} helper
	 * shares), otherwise the table entry.
	 */
	private void emitResolveReader(List<Integer> code) {
		// if (handle instanceof Long) goto STREAM;
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.longClass.index());
		int ifStreamPos = code.size();
		code.add(Opcode.IFNE);
		emitU2(code, 0);
		// if (_stdinReader == null) _stdinReader = new BufferedReader(new
		// InputStreamReader(System.in));
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.stdinReaderField.index());
		int ifHavePos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		code.add(Opcode.NEW);
		emitU2(code, this.bufferedReaderClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.NEW);
		emitU2(code, this.inputStreamReaderClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.systemIn.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.inputStreamReaderInit.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.bufferedReaderInit.index());
		code.add(Opcode.PUTSTATIC);
		emitU2(code, this.stdinReaderField.index());
		patchBranch(code, ifHavePos, code.size());
		// r = _stdinReader; goto READ;
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.stdinReaderField.index());
		code.add(Opcode.ASTORE_3);
		int gotoReadPos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifStreamPos, code.size());
		// STREAM: r = (BufferedReader) _streams[(int) ((Long) handle).longValue()];
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
		code.add(Opcode.ASTORE_3);
		patchBranch(code, gotoReadPos, code.size());
	}

	/**
	 * {@code _writeByte(Object byteObj, Object handle) -> byteObj}. Writes one raw byte
	 * to the binary output stream in the table.
	 */
	private List<Integer> buildWriteByte() {
		// Slots: 0=byteObj, 1=handle, 2=out (OutputStream)
		List<Integer> code = new ArrayList<>();
		// out = (OutputStream) _streams[(int) ((Long) handle).longValue()];
		// (a Socket entry contributes its output stream instead)
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamsField.index());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.longClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.longValue.index());
		code.add(Opcode.L2I);
		code.add(Opcode.AALOAD);
		if (this.sockets != null) {
			code.add(Opcode.ASTORE_2);
			code.add(Opcode.ALOAD_2);
			code.add(Opcode.INSTANCEOF);
			emitU2(code, this.sockets.socketClass().index());
			int ifNotSocketPos = code.size();
			code.add(Opcode.IFEQ);
			emitU2(code, 0);
			code.add(Opcode.ALOAD_2);
			code.add(Opcode.CHECKCAST);
			emitU2(code, this.sockets.socketClass().index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, this.sockets.socketGetOutputStream().index());
			code.add(Opcode.ASTORE_2);
			int gotoWritePos = code.size();
			code.add(Opcode.GOTO);
			emitU2(code, 0);
			patchBranch(code, ifNotSocketPos, code.size());
			code.add(Opcode.ALOAD_2);
			code.add(Opcode.CHECKCAST);
			emitU2(code, this.outputStreamClass.index());
			code.add(Opcode.ASTORE_2);
			patchBranch(code, gotoWritePos, code.size());
		}
		else {
			code.add(Opcode.CHECKCAST);
			emitU2(code, this.outputStreamClass.index());
			code.add(Opcode.ASTORE_2);
		}
		// out.write((int) ((Long) byteObj).longValue()); return byteObj;
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.longClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.longValue.index());
		code.add(Opcode.L2I);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.outputStreamWrite.index());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _writeStr(String content, Object handle) -> void}. Writes an
	 * already-rendered content string to the stream (a {@code Writer} table entry), or to
	 * standard output (updating the {@code _col} fresh-line tracking) when the handle is
	 * not a stream handle ({@code null} = nil, {@code "t"} = t). The routing sink of the
	 * print-family optional stream argument and the write-string built-in.
	 */
	private List<Integer> buildWriteStr() {
		// Slots: 0=content (String), 1=handle
		List<Integer> code = new ArrayList<>();
		// if (handle instanceof Long) { ((Writer) _streams[idx]).write(content); return;
		// }
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.longClass.index());
		int ifStdoutPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
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
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.writerWrite.index());
		code.add(Opcode.RETURN);
		patchBranch(code, ifStdoutPos, code.size());
		// System.out.print(content);
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.systemOut.index());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.printStr.index());
		// if (content.length() != 0) _col = content.charAt(len - 1) == '\n' ? 0 : 1;
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringLength.index());
		int ifEmptyPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringLength.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ISUB);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringCharAt.index());
		code.add(Opcode.BIPUSH);
		code.add(10);
		int ifNotNewlinePos = code.size();
		code.add(Opcode.IF_ICMPNE);
		emitU2(code, 0);
		code.add(Opcode.ICONST_0);
		int gotoStorePos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifNotNewlinePos, code.size());
		code.add(Opcode.ICONST_1);
		patchBranch(code, gotoStorePos, code.size());
		code.add(Opcode.PUTSTATIC);
		emitU2(code, this.colField.index());
		patchBranch(code, ifEmptyPos, code.size());
		code.add(Opcode.RETURN);
		return code;
	}

	/**
	 * {@code _writeString(Object str, Object handle) -> str}. Writes the string content
	 * (without the surrounding quotes) to the stream via {@code _writeStr} -- write-line
	 * minus the newline.
	 */
	private List<Integer> buildWriteString() {
		// Slots: 0=str, 1=handle, 2=content (String)
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
		// _writeStr(content, handle); return str;
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.writeStrMethod.index());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _addStream(Object stream) -> Long handle}. The ONE allocator of the stream
	 * table (lazily created, growable): it appends the entry and returns its index. It is
	 * emitted {@code synchronized} -- reserving a slot and filling it must be one atomic
	 * step, or two concurrent request threads take the same index and one stream is lost
	 * (.todo/193). The {@code _streams} field is written back on EVERY call, not just on
	 * a growth, and is volatile: that store is what publishes the new element to a reader
	 * thread, which reaches the table through the same volatile field.
	 */
	private List<Integer> buildAddStream() {
		// Slots: 0=stream, 1=arr, 2=count
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
		code.add(Opcode.ASTORE_1);
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamCountField.index());
		code.add(Opcode.ISTORE_2);
		// if (count >= arr.length) arr = Arrays.copyOf(arr, count * 2);
		code.add(Opcode.ILOAD_2);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ARRAYLENGTH);
		int ifGrowPos = code.size();
		code.add(Opcode.IF_ICMPLT);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ILOAD_2);
		code.add(Opcode.ICONST_2);
		code.add(Opcode.IMUL);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.arraysCopyOf.index());
		code.add(Opcode.ASTORE_1);
		patchBranch(code, ifGrowPos, code.size());
		// arr[count] = stream; _streamCount = count + 1; _streams = arr;
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ILOAD_2);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.AASTORE);
		code.add(Opcode.ILOAD_2);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.IADD);
		code.add(Opcode.PUTSTATIC);
		emitU2(code, this.streamCountField.index());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.PUTSTATIC);
		emitU2(code, this.streamsField.index());
		// return Long.valueOf((long) count);
		code.add(Opcode.ILOAD_2);
		code.add(Opcode.I2L);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.longValueOf.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _makeStringOutputStream() -> Long handle}. Stores a fresh
	 * {@code StringWriter} in the stream table -- the string-builder stream behind
	 * with-output-to-string.
	 */
	private List<Integer> buildMakeStringOutputStream() {
		// No slots: the entry is built on the stack and handed to _addStream.
		List<Integer> code = new ArrayList<>();
		// return _addStream(new StringWriter());
		code.add(Opcode.NEW);
		emitU2(code, this.stringWriterClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.stringWriterInit.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.addStreamRef.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _makeStringInputStream(Object str) -> Long handle}. Stores a
	 * {@code BufferedReader} over the string content (without the surrounding quotes) in
	 * the stream table, so read-line/read consume it like any input stream -- the
	 * string-backed stream behind with-input-from-string.
	 */
	private List<Integer> buildMakeStringInputStream() {
		// Slots: 0=str, 1=content (String)
		List<Integer> code = new ArrayList<>();
		// content = ((String) str).substring(1, length - 1);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.stringClass.index());
		code.add(Opcode.ASTORE_1);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringLength.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ISUB);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringSubstring.index());
		code.add(Opcode.ASTORE_1);
		// return _addStream(new BufferedReader(new StringReader(content)));
		code.add(Opcode.NEW);
		emitU2(code, this.bufferedReaderClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.NEW);
		emitU2(code, this.stringReaderClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.stringReaderInit.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.bufferedReaderInit.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.addStreamRef.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _stringStreamContents(Object handle) -> String}. Returns the string
	 * accumulated by a {@code StringWriter} table entry, wrapped in the internal
	 * {@code '"'} prefix/suffix string format, and CLEARS the entry -- CL's
	 * {@code get-output-stream-string} contract, which {@code with-output-to-string}
	 * cannot tell apart because it fetches once and then closes.
	 */
	private List<Integer> buildStringStreamContents() {
		// Slots: 0=handle, 1=content (String), 2=writer (StringWriter)
		List<Integer> code = new ArrayList<>();
		// writer = (StringWriter) _streams[idx];
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
		emitU2(code, this.stringWriterClass.index());
		code.add(Opcode.ASTORE_2);
		// content = writer.toString();
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringWriterToString.index());
		code.add(Opcode.ASTORE_1);
		// writer.getBuffer().setLength(0);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringWriterGetBuffer.index());
		code.add(Opcode.ICONST_0);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringBufferSetLength.index());
		// return "\"".concat(content).concat("\"");
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
