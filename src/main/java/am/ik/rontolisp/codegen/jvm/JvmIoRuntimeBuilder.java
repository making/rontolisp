package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.jvm.AccessFlag;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.compiler.OpenModes;
import am.ik.rontolisp.compiler.StreamDesignators;

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
	 * {@code ACC_SYNCHRONIZED} for the methods that mutate the stream table (see
	 * {@link #ADD_STREAM_METHOD}).
	 */
	record IoMethod(Utf8Entry name, Utf8Entry desc, MethodCode code, int extraFlags) {

		IoMethod(Utf8Entry name, Utf8Entry desc, MethodCode code) {
			this(name, desc, code, 0);
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
	 * the survivor.
	 */
	static final String ADD_STREAM_METHOD = "_addStream";

	static final String ADD_STREAM_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String CLOSE_METHOD = "_closeStream";

	static final String CLOSE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String PROBE_FILE_METHOD = "_probeFile";

	static final String PROBE_FILE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * The directory-LISTING primitive behind {@code directory} and the {@code uiop:}
	 * spellings. Emitted only for a program that calls it (the {@code listDirectory}
	 * gate), so every artifact compiled before it stays byte-identical.
	 */
	static final String LIST_DIRECTORY_METHOD = "_listDirectory";

	static final String LIST_DIRECTORY_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/** {@code file-write-date}: the universal time, or null when it cannot be told. */
	static final String FILE_WRITE_DATE_METHOD = "_fileWriteDate";

	static final String FILE_WRITE_DATE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/** {@code %make-directories}: the write-side sibling of {@code _listDirectory}. */
	static final String MAKE_DIRECTORIES_METHOD = "_makeDirectories";

	static final String MAKE_DIRECTORIES_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * {@code %delete-file}: the other write-side sibling; null when nothing was removed.
	 */
	static final String DELETE_FILE_METHOD = "_deleteFile";

	static final String DELETE_FILE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * {@code %rename-file}: the third write-side sibling; null when nothing was renamed.
	 */
	static final String RENAME_FILE_METHOD = "_renameFile";

	static final String RENAME_FILE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * {@code file-length}: the byte length of the file behind a FILE stream, read off the
	 * {@link #STREAM_PATHS_FIELD} table; null for every other stream kind.
	 */
	static final String FILE_LENGTH_METHOD = "_fileLength";

	static final String FILE_LENGTH_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * Records the namestring a handle was opened on, mirroring {@code _streams}. Returns
	 * the handle so {@code _open} can wrap its {@code _addStream} call in it.
	 */
	static final String SET_STREAM_PATH_METHOD = "_setStreamPath";

	static final String SET_STREAM_PATH_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * The namestring of each FILE stream, indexed by handle exactly like
	 * {@code _streams}. A Reader/Writer does not remember where it came from, so
	 * {@code file-length} needs this side table; only {@code _open} fills it, which is
	 * why every other stream kind answers nil.
	 */
	static final String STREAM_PATHS_FIELD = "_streamPaths";

	static final String STREAM_PATHS_DESC = "[Ljava/lang/Object;";

	/**
	 * The byte position of each BINARY file stream, indexed by handle exactly like
	 * {@code _streams}, mirroring the interpreter's {@code streamPositions} map. Only
	 * {@code _bumpStreamPosition}/{@code _filePosition} touch it, and only for a handle
	 * whose {@code _streamPaths} entry names a real file and is a binary stream; a
	 * character or bidirectional file stream answers from its own channel instead, and a
	 * non-file stream (a socket, a standard stream) answers nil.
	 */
	static final String STREAM_POSITIONS_FIELD = "_streamPositions";

	static final String STREAM_POSITIONS_DESC = "[Ljava/lang/Object;";

	/**
	 * {@code file-position}: the query (one argument) returns the boxed byte position of
	 * the binary file stream, or null where it cannot be told; the set (two arguments)
	 * re-opens the file at the offset and answers "T", or null where it cannot. Mirrors
	 * {@code Environment}'s {@code binaryFileStreamSet} and {@code streamPositions}.
	 */
	static final String FILE_POSITION_METHOD = "_filePosition";

	static final String FILE_POSITION_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * Records the boxed position of a handle, growing {@code _streamPositions} the way
	 * {@code _setStreamPath} grows {@code _streamPaths}. Returns the handle so
	 * {@code _filePosition} can call it from its set half.
	 */
	static final String STORE_STREAM_POSITION_METHOD = "_storeStreamPosition";

	static final String STORE_STREAM_POSITION_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * Advances the boxed position of a binary file stream by {@code delta} bytes. A
	 * self-contained guard (the handle must resolve to a real file) so the byte
	 * primitives can call it unconditionally after a transfer.
	 */
	static final String BUMP_STREAM_POSITION_METHOD = "_bumpStreamPosition";

	static final String BUMP_STREAM_POSITION_DESC = "(Ljava/lang/Object;I)Ljava/lang/Object;";

	static final String WRITE_LINE_METHOD = "_writeLine";

	static final String WRITE_LINE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String READ_LINE_STREAM_METHOD = "_readLineStream";

	static final String READ_LINE_STREAM_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * {@code %read-line-pair}'s helper: the line and missing-newline-p as a cons, emitted
	 * only for a program that can lower a {@code read-line} producer
	 * ({@link #buildReadLinePair}).
	 */
	static final String READ_LINE_PAIR_METHOD = "_readLinePair";

	static final String READ_LINE_PAIR_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String READ_BYTE_METHOD = "_readByte";

	static final String READ_BYTE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String READ_CHAR_METHOD = "_readChar";

	static final String READ_CHAR_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String PEEK_CHAR_METHOD = "_peekChar";

	static final String PEEK_CHAR_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String READ_SEQ_PACKED_METHOD = "_readSeqPacked";

	static final String READ_SEQ_PACKED_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String WRITE_SEQ_PACKED_METHOD = "_writeSeqPacked";

	static final String WRITE_SEQ_PACKED_DESC = READ_SEQ_PACKED_DESC;

	static final String READ_SEQ_CHARS_METHOD = "_readSeqChars";

	static final String READ_SEQ_CHARS_DESC = READ_SEQ_PACKED_DESC;

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

	private final FieldRefEntry streamsField;

	private final FieldRefEntry streamCountField;

	private final ClassEntry objectClass;

	private final ClassEntry stringClass;

	private final ClassEntry longClass;

	private final ClassEntry bufferedReaderClass;

	private final ClassEntry bufferedWriterClass;

	private final ClassEntry fileReaderClass;

	private final ClassEntry fileWriterClass;

	private final ClassEntry writerClass;

	private final ClassEntry bufferedInputStreamClass;

	private final ClassEntry bufferedOutputStreamClass;

	private final ClassEntry fileInputStreamClass;

	private final ClassEntry fileOutputStreamClass;

	private final ClassEntry inputStreamClass;

	private final ClassEntry outputStreamClass;

	private final ClassEntry runtimeExceptionClass;

	private final MethodRefEntry arraysCopyOf;

	private final MethodRefEntry addStreamRef;

	private final MethodRefEntry stringLength;

	private final MethodRefEntry stringSubstring;

	private final MethodRefEntry stringConcat;

	private final MethodRefEntry longValueOf;

	private final MethodRefEntry longValue;

	private final MethodRefEntry fileReaderInit;

	private final MethodRefEntry fileWriterInit;

	/**
	 * {@code FileWriter(String, boolean append)} -- the append arm of the {@code _open}
	 * mode branch ({@code :if-exists :append}, mode 5).
	 */
	private final MethodRefEntry fileWriterAppendInit;

	private final MethodRefEntry bufferedReaderInit;

	private final MethodRefEntry bufferedWriterInit;

	/**
	 * {@code _lfLine}: a line off a {@code BufferedReader}, ended by {@code \n} alone.
	 */
	private final MethodRefEntry lfLine;

	private final MethodRefEntry bufferedReaderClose;

	private final MethodRefEntry writerWrite;

	private final MethodRefEntry writerClose;

	private final MethodRefEntry bufferedInputStreamInit;

	private final MethodRefEntry bufferedOutputStreamInit;

	private final MethodRefEntry fileInputStreamInit;

	private final MethodRefEntry fileOutputStreamInit;

	/**
	 * {@code FileOutputStream(String, boolean append)} -- the binary append arm of the
	 * {@code _open} mode branch (mode 7).
	 */
	private final MethodRefEntry fileOutputStreamAppendInit;

	private final MethodRefEntry inputStreamRead;

	private final MethodRefEntry inputStreamClose;

	private final MethodRefEntry outputStreamWrite;

	private final MethodRefEntry outputStreamClose;

	private final MethodRefEntry runtimeExceptionInit;

	private final FieldRefEntry systemOut;

	private final MethodRefEntry printlnStr;

	private final MethodRefEntry readLineHelper;

	private final MethodRefEntry printStr;

	private final MethodRefEntry stringCharAt;

	private final FieldRefEntry colField;

	private final ClassEntry stringWriterClass;

	private final MethodRefEntry stringWriterInit;

	private final MethodRefEntry stringWriterToString;

	private final MethodRefEntry stringWriterGetBuffer;

	private final MethodRefEntry stringBufferSetLength;

	private final ClassEntry stringReaderClass;

	private final MethodRefEntry stringReaderInit;

	private final MethodRefEntry writeStrMethod;

	private final StringEntry tStr;

	private final StringEntry quoteStr;

	private final StringEntry newlineStr;

	private final StringEntry eofStr;

	private final StringEntry charEofStr;

	private final FieldRefEntry stdinReaderField;

	private final FieldRefEntry systemIn;

	private final ClassEntry inputStreamReaderClass;

	private final MethodRefEntry inputStreamReaderInit;

	private final MethodRefEntry bufferedReaderRead;

	private final MethodRefEntry bufferedReaderMark;

	private final MethodRefEntry bufferedReaderReset;

	private final MethodRefEntry characterIsHighSurrogate;

	private final MethodRefEntry characterIsLowSurrogate;

	private final MethodRefEntry characterToCodePoint;

	private final MethodRefEntry printStreamFlush;

	private final MethodRefEntry writerFlush;

	private final MethodRefEntry outputStreamFlush;

	private final MethodRefEntry bufferedReaderReady;

	private final MethodRefEntry inputStreamAvailable;

	private final MethodRefEntry socketIsClosed;

	private final ClassEntry fileClass;

	private final MethodRefEntry fileInit;

	private final MethodRefEntry fileExists;

	/**
	 * {@code %list-directory} support, minted only when the program calls it:
	 * {@code File.list()} (null for anything that is not a readable directory, which is
	 * exactly the primitive's "not there" answer), {@code File.isDirectory()} for the
	 * per-entry kind, and the {@code File(File, String)} constructor that joins the two.
	 */
	@Nullable private final MethodRefEntry fileList;

	@Nullable private final MethodRefEntry fileIsDirectory;

	@Nullable private final MethodRefEntry fileInitChild;

	@Nullable private final StringEntry slashQuoteStr;

	/**
	 * Whether the program calls {@code %list-directory}; see
	 * {@link #LIST_DIRECTORY_METHOD}.
	 */
	private final boolean listDirectory;

	/** Which file-metadata helpers to emit; see {@link FileMeta}. */
	private final FileMeta fileMeta;

	/**
	 * The constant-pool entries of {@code _readLinePair}, minted only for a program that
	 * can lower a {@code read-line} producer, so every other artifact keeps its bytes.
	 */
	@Nullable private final LinePairs linePairs;

	private final @Nullable PackedSequenceIo packedSequenceIo;

	/**
	 * The BIDIRECTIONAL file-stream entries, minted only for a program whose {@code open}
	 * can ask for one ({@code :direction :io} / {@code :if-exists :overwrite}). Null
	 * everywhere else, so every other artifact keeps its exact bytes AND its single class
	 * file -- the stream is a travelling runtime class, not emitted bytecode.
	 */
	private final @Nullable IoStreams ioStreams;

	/** The string-stream arms of {@code _filePosition}, minted with it. */
	private final @Nullable JvmStringStreamPositions stringPositions;

	/**
	 * {@code RontoStringInputStream(String)}: every string input stream is built
	 * positioned, so {@code listen} answers uniformly.
	 */
	private final @Nullable MethodRefEntry stringInputStreamInit;

	/** {@code RontoStringInputStream}, beside {@link #stringInputStreamInit}. */
	private final @Nullable ClassEntry stringInputStreamClass;

	/**
	 * {@code RontoStringInputStream.hasRemaining()}, beside
	 * {@link #stringInputStreamInit}.
	 */
	private final @Nullable MethodRefEntry stringInputHasRemaining;

	/**
	 * The positioned CHARACTER file streams ({@code runtime/RontoCharFileReader} /
	 * {@code RontoCharFileWriter}), minted only for a program that names
	 * {@code file-position} and can open a character file stream
	 * ({@link FileMeta#characterPosition}). Null everywhere else, so a character stream
	 * stays a {@code BufferedReader} over a {@code FileReader} and the artifact a single
	 * class file.
	 */
	private final @Nullable CharFileStreams charFileStreams;

	/**
	 * Whether a quantized matrix -- a {@code byte[]} of ggml blocks behind an int header
	 * ({@link JvmQuantizedMatrixRuntimeBuilder}) -- can exist in the program, so the bulk
	 * transfer takes it as a buffer of bytes ({@code .kb/quantized-matrix.md}).
	 */
	private final boolean quantizedBuffer;

	@Nullable private final MethodRefEntry fileLastModified;

	@Nullable private final MethodRefEntry fileMkdirs;

	@Nullable private final MethodRefEntry fileDelete;

	@Nullable private final MethodRefEntry fileRenameTo;

	@Nullable private final MethodRefEntry fileLengthRef;

	@Nullable private final FieldRefEntry streamPathsField;

	@Nullable private final MethodRefEntry setStreamPathRef;

	@Nullable private final MethodRefEntry forceOutputRef;

	/**
	 * The {@code _streamPositions} side table and the position helpers, non-null only for
	 * a program that calls {@code file-position} at all, so every other artifact keeps
	 * its bytes.
	 */
	@Nullable private final FieldRefEntry streamPositionsField;

	@Nullable private final MethodRefEntry storeStreamPositionRef;

	@Nullable private final MethodRefEntry bumpStreamPositionRef;

	/**
	 * The file re-open refs {@code _filePosition}'s set half needs, minted only with the
	 * {@link #position} gate: {@code FileInputStream.getChannel()}, {@code Path.of},
	 * {@code FileChannel.open} over {@code StandardOpenOption.WRITE},
	 * {@code FileChannel.position(long)} and {@code Channels.newOutputStream}.
	 */
	@Nullable private final MethodRefEntry fileInputStreamGetChannel;

	@Nullable private final MethodRefEntry pathOf;

	@Nullable private final MethodRefEntry fileChannelOpen;

	@Nullable private final MethodRefEntry fileChannelPosition;

	@Nullable private final MethodRefEntry channelsNewOutputStream;

	@Nullable private final ClassEntry openOptionClass;

	@Nullable private final FieldRefEntry standardOpenOptionWrite;

	/** The {@code file-position} set-half's negative-position error message. */
	@Nullable private final StringEntry negPositionMsg;

	/**
	 * Socket-runtime constants, non-null only when the program uses a tcp built-in; the
	 * stream built-ins then grow socket branches (a socket entry is a raw
	 * {@code java.net.Socket}/{@code ServerSocket}, not a reader/writer). Non-socket
	 * programs keep their original bytes.
	 */
	private final JvmSocketRuntimeBuilder.@Nullable SocketRuntime sockets;

	/**
	 * Whether the program can name {@code *error-output*}, whose value is the reserved
	 * handle {@link StreamDesignators#STANDARD_ERROR_HANDLE}. Only then do the stream
	 * built-ins grow their stderr branch and does the table reserve the standard-stream
	 * handles; a program that never mentions the variable keeps its original bytes.
	 */
	private final boolean errorOutput;

	/**
	 * {@code System.err}, the sink of the {@link #errorOutput} branches -- minted only
	 * with them, so a program that never names {@code *error-output*} does not even carry
	 * the constant.
	 */
	@Nullable private final FieldRefEntry systemErr;

	/**
	 * {@code _strv}, minted only when the array runtime exists: a string reaching a
	 * runtime body here can then be a MUTABLE CHARACTER VECTOR (every subseq/copy-seq
	 * result is one), and the body's {@code (String)} cast needs the rendered form.
	 * Without the array runtime no character vector can exist and the reference must not
	 * be minted -- the method it names is not emitted.
	 */
	@Nullable private final MethodRefEntry strvRef;

	/**
	 * The constant-pool entries of {@code _readSeqChars}, minted only for a program that
	 * calls {@code read-sequence} at all, so every other artifact keeps its bytes.
	 */
	@Nullable private final CharSequenceIo charSequenceIo;

	private JvmIoRuntimeBuilder(ConstantPool cp, ClassEntry thisClass, ClassEntry objectClass, ClassEntry stringClass,
			ClassEntry longClass, MethodRefEntry longValueOf, MethodRefEntry longValue, MethodRefEntry stringLength,
			MethodRefEntry stringSubstring, MethodRefEntry stringConcat, FieldRefEntry systemOut,
			MethodRefEntry printlnStr, MethodRefEntry readLineHelper,
			JvmSocketRuntimeBuilder.@Nullable SocketRuntime sockets, boolean errorOutput, boolean listDirectory,
			FileMeta fileMeta, boolean packedSequenceIo, boolean charSequenceIo, boolean arrayRuntime,
			boolean quantizedBuffer, boolean bidirectionalStreams, boolean readLinePairs) {
		this.sockets = sockets;
		this.ioStreams = bidirectionalStreams ? IoStreams.mint(cp) : null;
		this.linePairs = readLinePairs ? LinePairs.mint(cp, bidirectionalStreams) : null;
		this.charFileStreams = fileMeta.characterPosition() ? CharFileStreams.mint(cp) : null;
		this.quantizedBuffer = quantizedBuffer;
		this.errorOutput = errorOutput;
		this.listDirectory = listDirectory;
		this.fileMeta = fileMeta;
		this.packedSequenceIo = packedSequenceIo ? PackedSequenceIo.mint(cp, thisClass) : null;
		this.charSequenceIo = charSequenceIo ? CharSequenceIo.mint(cp) : null;
		this.strvRef = arrayRuntime
				? cp.methodRef(thisClass, JvmArrayRuntimeBuilder.STRV, JvmArrayRuntimeBuilder.STRV_DESC) : null;
		this.systemErr = errorOutput ? cp.fieldRef(cp.classEntry("java/lang/System"), "err", "Ljava/io/PrintStream;")
				: null;
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
		this.streamsField = cp.fieldRef(thisClass, STREAMS_FIELD, STREAMS_DESC);
		this.streamCountField = cp.fieldRef(thisClass, STREAM_COUNT_FIELD, STREAM_COUNT_DESC);
		ClassEntry arraysClass = cp.classEntry("java/util/Arrays");
		this.arraysCopyOf = cp.methodRef(arraysClass, "copyOf", "([Ljava/lang/Object;I)[Ljava/lang/Object;");
		this.addStreamRef = cp.methodRef(thisClass, ADD_STREAM_METHOD, ADD_STREAM_DESC);
		this.bufferedReaderClass = cp.classEntry("java/io/BufferedReader");
		this.bufferedWriterClass = cp.classEntry("java/io/BufferedWriter");
		this.fileReaderClass = cp.classEntry("java/io/FileReader");
		this.fileWriterClass = cp.classEntry("java/io/FileWriter");
		this.writerClass = cp.classEntry("java/io/Writer");
		this.fileReaderInit = cp.methodRef(this.fileReaderClass, "<init>", "(Ljava/lang/String;)V");
		this.fileWriterInit = cp.methodRef(this.fileWriterClass, "<init>", "(Ljava/lang/String;)V");
		this.fileWriterAppendInit = cp.methodRef(this.fileWriterClass, "<init>", "(Ljava/lang/String;Z)V");
		this.bufferedReaderInit = cp.methodRef(this.bufferedReaderClass, "<init>", "(Ljava/io/Reader;)V");
		this.bufferedWriterInit = cp.methodRef(this.bufferedWriterClass, "<init>", "(Ljava/io/Writer;)V");
		this.lfLine = cp.methodRef(thisClass, JvmRuntimeBuilder.LF_LINE_METHOD, JvmRuntimeBuilder.LF_LINE_DESC);
		this.bufferedReaderClose = cp.methodRef(this.bufferedReaderClass, "close", "()V");
		this.writerWrite = cp.methodRef(this.writerClass, "write", "(Ljava/lang/String;)V");
		this.writerClose = cp.methodRef(this.writerClass, "close", "()V");
		this.bufferedInputStreamClass = cp.classEntry("java/io/BufferedInputStream");
		this.bufferedOutputStreamClass = cp.classEntry("java/io/BufferedOutputStream");
		this.fileInputStreamClass = cp.classEntry("java/io/FileInputStream");
		this.fileOutputStreamClass = cp.classEntry("java/io/FileOutputStream");
		this.inputStreamClass = cp.classEntry("java/io/InputStream");
		this.outputStreamClass = cp.classEntry("java/io/OutputStream");
		this.runtimeExceptionClass = cp.classEntry("java/lang/RuntimeException");
		this.bufferedInputStreamInit = cp.methodRef(this.bufferedInputStreamClass, "<init>",
				"(Ljava/io/InputStream;)V");
		this.bufferedOutputStreamInit = cp.methodRef(this.bufferedOutputStreamClass, "<init>",
				"(Ljava/io/OutputStream;)V");
		this.fileInputStreamInit = cp.methodRef(this.fileInputStreamClass, "<init>", "(Ljava/lang/String;)V");
		this.fileOutputStreamInit = cp.methodRef(this.fileOutputStreamClass, "<init>", "(Ljava/lang/String;)V");
		this.fileOutputStreamAppendInit = cp.methodRef(this.fileOutputStreamClass, "<init>", "(Ljava/lang/String;Z)V");
		this.inputStreamRead = cp.methodRef(this.inputStreamClass, "read", "()I");
		this.inputStreamClose = cp.methodRef(this.inputStreamClass, "close", "()V");
		this.outputStreamWrite = cp.methodRef(this.outputStreamClass, "write", "(I)V");
		this.outputStreamClose = cp.methodRef(this.outputStreamClass, "close", "()V");
		this.runtimeExceptionInit = cp.methodRef(this.runtimeExceptionClass, "<init>", "(Ljava/lang/String;)V");
		this.tStr = cp.stringEntry("T");
		this.quoteStr = cp.stringEntry("\"");
		this.newlineStr = cp.stringEntry("\n");
		this.eofStr = cp.stringEntry("read-byte: end of file");
		ClassEntry printStreamClass = cp.classEntry("java/io/PrintStream");
		this.printStr = cp.methodRef(printStreamClass, "print", "(Ljava/lang/String;)V");
		this.stringCharAt = cp.methodRef(this.stringClass, "charAt", "(I)C");
		this.colField = cp.fieldRef(thisClass, JvmFreshLineCompiler.COL_FIELD, JvmFreshLineCompiler.COL_DESC);
		this.stringWriterClass = cp.classEntry("java/io/StringWriter");
		this.stringWriterInit = cp.methodRef(this.stringWriterClass, "<init>", "()V");
		this.stringWriterToString = cp.methodRef(this.stringWriterClass, "toString", "()Ljava/lang/String;");
		// get-output-stream-string CLEARS the stream as it answers (CL 21.2), which on
		// this backend is StringWriter.getBuffer().setLength(0).
		this.stringWriterGetBuffer = cp.methodRef(this.stringWriterClass, "getBuffer", "()Ljava/lang/StringBuffer;");
		this.stringBufferSetLength = cp.methodRef(cp.classEntry("java/lang/StringBuffer"), "setLength", "(I)V");
		this.stringPositions = fileMeta.position() ? JvmStringStreamPositions.mint(cp, fileMeta.stringInputPositions(),
				this.stringWriterClass, this.stringWriterGetBuffer) : null;
		this.stringInputStreamClass = fileMeta.stringInputs()
				? cp.classEntry(JvmStringStreamPositions.STRING_INPUT_STREAM_CLASS) : null;
		this.stringInputStreamInit = this.stringInputStreamClass != null
				? cp.methodRef(this.stringInputStreamClass, "<init>", "(Ljava/lang/String;)V") : null;
		this.stringInputHasRemaining = this.stringInputStreamClass != null
				? cp.methodRef(this.stringInputStreamClass, "hasRemaining", "()Z") : null;
		this.stringReaderClass = cp.classEntry("java/io/StringReader");
		this.stringReaderInit = cp.methodRef(this.stringReaderClass, "<init>", "(Ljava/lang/String;)V");
		this.writeStrMethod = cp.methodRef(thisClass, WRITE_STR_METHOD, WRITE_STR_DESC);
		// read-char support: the lazily initialized _stdinReader field (shared with the
		// _readLine helper), BufferedReader.read() and the boxed Character result.
		this.charEofStr = cp.stringEntry(am.ik.rontolisp.ClosRegistry.END_OF_FILE_MESSAGE);
		this.stdinReaderField = cp.fieldRef(thisClass, "_stdinReader", "Ljava/io/BufferedReader;");
		this.systemIn = cp.fieldRef(cp.classEntry("java/lang/System"), "in", "Ljava/io/InputStream;");
		this.inputStreamReaderClass = cp.classEntry("java/io/InputStreamReader");
		this.inputStreamReaderInit = cp.methodRef(this.inputStreamReaderClass, "<init>", "(Ljava/io/InputStream;)V");
		this.bufferedReaderRead = cp.methodRef(this.bufferedReaderClass, "read", "()I");
		this.bufferedReaderMark = cp.methodRef(this.bufferedReaderClass, "mark", "(I)V");
		this.bufferedReaderReset = cp.methodRef(this.bufferedReaderClass, "reset", "()V");
		ClassEntry characterClass = cp.classEntry("java/lang/Character");
		this.characterIsHighSurrogate = cp.methodRef(characterClass, "isHighSurrogate", "(C)Z");
		this.characterIsLowSurrogate = cp.methodRef(characterClass, "isLowSurrogate", "(C)Z");
		this.characterToCodePoint = cp.methodRef(characterClass, "toCodePoint", "(CC)I");
		// force-output / listen support: flush on the three writer shapes, readiness
		// probes on the two reader shapes.
		this.printStreamFlush = cp.methodRef(printStreamClass, "flush", "()V");
		this.writerFlush = cp.methodRef(this.writerClass, "flush", "()V");
		this.outputStreamFlush = cp.methodRef(this.outputStreamClass, "flush", "()V");
		this.bufferedReaderReady = cp.methodRef(this.bufferedReaderClass, "ready", "()Z");
		this.inputStreamAvailable = cp.methodRef(this.inputStreamClass, "available", "()I");
		this.socketIsClosed = cp.methodRef(cp.classEntry("java/net/Socket"), "isClosed", "()Z");
		// probe-file support: java.io.File.exists() -- the one file question that must
		// not open (and therefore must not signal) on a missing path.
		this.fileClass = cp.classEntry("java/io/File");
		this.fileInit = cp.methodRef(this.fileClass, "<init>", "(Ljava/lang/String;)V");
		this.fileExists = cp.methodRef(this.fileClass, "exists", "()Z");
		// %list-directory support, minted only for a program that calls it so every
		// other artifact keeps its original bytes.
		this.fileList = listDirectory ? cp.methodRef(this.fileClass, "list", "()[Ljava/lang/String;") : null;
		// Also minted for %make-directories: mkdirs' boolean answers false both for a
		// directory that already exists (success) and for one the host refused (failure),
		// so isDirectory() re-checks afterwards to tell them apart -- the WASM
		// verify-by-opening precedent (.kb/read-load-streams.md).
		this.fileIsDirectory = (listDirectory || fileMeta.makeDirectories())
				? cp.methodRef(this.fileClass, "isDirectory", "()Z") : null;
		this.fileInitChild = listDirectory
				? cp.methodRef(this.fileClass, "<init>", "(Ljava/io/File;Ljava/lang/String;)V") : null;
		this.slashQuoteStr = listDirectory ? cp.stringEntry("/\"") : null;
		// The file-metadata trio, each minted only for a program that calls it so every
		// other artifact keeps its original bytes (the %list-directory rule above).
		this.fileLastModified = fileMeta.writeDate() ? cp.methodRef(this.fileClass, "lastModified", "()J") : null;
		this.fileMkdirs = fileMeta.makeDirectories() ? cp.methodRef(this.fileClass, "mkdirs", "()Z") : null;
		this.fileDelete = fileMeta.deleteFile() ? cp.methodRef(this.fileClass, "delete", "()Z") : null;
		this.fileRenameTo = fileMeta.renameFile() ? cp.methodRef(this.fileClass, "renameTo", "(Ljava/io/File;)Z")
				: null;
		this.fileLengthRef = (fileMeta.fileLength() || fileMeta.position())
				? cp.methodRef(this.fileClass, "length", "()J") : null;
		// file-position needs the _streamPaths side table (to know a handle is a file
		// stream and to re-open at an offset) exactly as file-length does, so the path
		// refs are shared: minted for either operator.
		final boolean streamPathsWanted = fileMeta.fileLength() || fileMeta.position();
		this.streamPathsField = streamPathsWanted ? cp.fieldRef(thisClass, STREAM_PATHS_FIELD, STREAM_PATHS_DESC)
				: null;
		this.setStreamPathRef = streamPathsWanted
				? cp.methodRef(thisClass, SET_STREAM_PATH_METHOD, SET_STREAM_PATH_DESC) : null;
		this.forceOutputRef = streamPathsWanted ? cp.methodRef(thisClass, FORCE_OUTPUT_METHOD, FORCE_OUTPUT_DESC)
				: null;
		// The file-position machinery is gated on its own operator so a program that only
		// wants file-length pays nothing beyond what it already did.
		this.streamPositionsField = fileMeta.position()
				? cp.fieldRef(thisClass, STREAM_POSITIONS_FIELD, STREAM_POSITIONS_DESC) : null;
		this.storeStreamPositionRef = fileMeta.position()
				? cp.methodRef(thisClass, STORE_STREAM_POSITION_METHOD, STORE_STREAM_POSITION_DESC) : null;
		this.bumpStreamPositionRef = fileMeta.position()
				? cp.methodRef(thisClass, BUMP_STREAM_POSITION_METHOD, BUMP_STREAM_POSITION_DESC) : null;
		this.fileInputStreamGetChannel = fileMeta.position()
				? cp.methodRef(this.fileInputStreamClass, "getChannel", "()Ljava/nio/channels/FileChannel;") : null;
		this.pathOf = fileMeta.position()
				? cp.methodRef(cp.classEntry("java/nio/file/Path"), "of", "(Ljava/lang/String;)Ljava/nio/file/Path;")
				: null;
		this.fileChannelOpen = fileMeta.position() ? cp.methodRef(cp.classEntry("java/nio/channels/FileChannel"),
				"open", "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/nio/channels/FileChannel;") : null;
		this.fileChannelPosition = fileMeta.position() ? cp.methodRef(cp.classEntry("java/nio/channels/FileChannel"),
				"position", "(J)Ljava/nio/channels/FileChannel;") : null;
		this.channelsNewOutputStream = fileMeta.position() ? cp.methodRef(cp.classEntry("java/nio/channels/Channels"),
				"newOutputStream", "(Ljava/nio/channels/WritableByteChannel;)Ljava/io/OutputStream;") : null;
		this.openOptionClass = fileMeta.position() ? cp.classEntry("java/nio/file/OpenOption") : null;
		this.standardOpenOptionWrite = fileMeta.position() ? cp
			.fieldRef(cp.classEntry("java/nio/file/StandardOpenOption"), "WRITE", "Ljava/nio/file/StandardOpenOption;")
				: null;
		this.negPositionMsg = fileMeta.position() ? cp.stringEntry("file-position: position must be non-negative")
				: null;
	}

	/**
	 * Which of the file-metadata helpers the program actually calls. They are gated one
	 * by one rather than as a group so a program using only {@code file-write-date} does
	 * not carry the {@code file-length} stream-path table, and every artifact compiled
	 * before any of them existed keeps its exact bytes.
	 *
	 * @param writeDate whether {@code file-write-date} is called
	 * @param makeDirectories whether {@code %make-directories} is called
	 * @param fileLength whether {@code file-length} is called
	 * @param deleteFile whether {@code %delete-file} is called
	 * @param renameFile whether {@code %rename-file} is called
	 * @param position whether {@code file-position} is called
	 * @param characterPosition whether {@code file-position} is called AND a character
	 * file stream can be opened, so character streams open positioned
	 * @param stringInputPositions whether {@code file-position} can be asked of a string
	 * INPUT stream -- it is called and the program can make one -- so the position
	 * machinery knows the travelling {@code RontoStringInputStream}
	 * @param stringInputs whether the program can make a string INPUT stream, which is
	 * therefore always built as the travelling {@code RontoStringInputStream} (so
	 * {@code listen} answers whether a character remains rather than
	 * {@code BufferedReader.ready()}, uniformly whether or not the program names
	 * {@code file-position})
	 */
	record FileMeta(boolean writeDate, boolean makeDirectories, boolean fileLength, boolean deleteFile,
			boolean renameFile, boolean position, boolean characterPosition, boolean stringInputPositions,
			boolean stringInputs) {

		static final FileMeta NONE = new FileMeta(false, false, false, false, false, false, false, false, false);

		/**
		 * Whether the {@code _streamPaths} side table must be present: both
		 * {@code file-length} and {@code file-position} read it, so either operator
		 * forces it.
		 */
		boolean streamPaths() {
			return this.fileLength || this.position;
		}

	}

	static JvmIoRuntimeBuilder create(ConstantPool cp, ClassEntry thisClass, ClassEntry objectClass,
			ClassEntry stringClass, ClassEntry longClass, MethodRefEntry longValueOf, MethodRefEntry longValue,
			MethodRefEntry stringLength, MethodRefEntry stringSubstring, MethodRefEntry stringConcat,
			FieldRefEntry systemOut, MethodRefEntry printlnStr, MethodRefEntry readLineHelper,
			JvmSocketRuntimeBuilder.@Nullable SocketRuntime sockets, boolean errorOutput, boolean listDirectory,
			FileMeta fileMeta, boolean packedSequenceIo, boolean charSequenceIo, boolean arrayRuntime,
			boolean quantizedBuffer, boolean bidirectionalStreams, boolean readLinePairs) {
		return new JvmIoRuntimeBuilder(cp, thisClass, objectClass, stringClass, longClass, longValueOf, longValue,
				stringLength, stringSubstring, stringConcat, systemOut, printlnStr, readLineHelper, sockets,
				errorOutput, listDirectory, fileMeta, packedSequenceIo, charSequenceIo, arrayRuntime, quantizedBuffer,
				bidirectionalStreams, readLinePairs);
	}

	/**
	 * The constant-pool entries of {@code _readLinePair}: the character-at-a-time line
	 * buffer, and the bidirectional stream's own terminator report where that stream kind
	 * exists.
	 */
	private record LinePairs(ClassEntry stringBuilderClass, MethodRefEntry stringBuilderInit, MethodRefEntry appendChar,
			MethodRefEntry builderToString, @Nullable MethodRefEntry ioMissingNewline) {

		static LinePairs mint(ConstantPool cp, boolean ioStreams) {
			ClassEntry builder = cp.classEntry("java/lang/StringBuilder");
			return new LinePairs(builder, cp.methodRef(builder, "<init>", "()V"),
					cp.methodRef(builder, "append", "(C)Ljava/lang/StringBuilder;"),
					cp.methodRef(builder, "toString", "()Ljava/lang/String;"),
					ioStreams ? cp.methodRef(cp.classEntry(IO_FILE_STREAM_CLASS), "lastLineMissingNewline", "()Z")
							: null);
		}

	}

	/**
	 * The constant-pool entries of the bulk character-I/O helper {@code _readSeqChars}
	 * ({@code .kb/character-sequence-io.md}): the character-vector representation (an
	 * {@code ArrayList} whose slot 0 is the length-4 header and whose elements follow),
	 * and the block read the transfer is made of.
	 */
	private record CharSequenceIo(ClassEntry arrayListClass, ClassEntry objectArrayClass, MethodRefEntry listGet,
			MethodRefEntry listSet, MethodRefEntry readBlock) {

		static CharSequenceIo mint(ConstantPool cp) {
			ClassEntry arrayList = cp.classEntry("java/util/ArrayList");
			return new CharSequenceIo(arrayList, cp.classEntry("[Ljava/lang/Object;"),
					cp.methodRef(arrayList, "get", "(I)Ljava/lang/Object;"),
					cp.methodRef(arrayList, "set", "(ILjava/lang/Object;)Ljava/lang/Object;"),
					cp.methodRef(cp.classEntry("java/io/BufferedReader"), "read", "([CII)I"));
		}

	}

	/**
	 * The constant-pool entries of the bulk binary-I/O helpers {@code _readSeqPacked} /
	 * {@code _writeSeqPacked} ({@code .kb/binary-sequence-io.md}), minted only for a
	 * program that calls {@code read-sequence} / {@code write-sequence} over a packed
	 * buffer, so every other artifact keeps its original bytes.
	 */
	/**
	 * The constant-pool entries of {@code am.ik.rontolisp.runtime.RontoIoFileStream}, the
	 * ONE bidirectional file stream the interpreter and a compiled program share. The JVM
	 * backend calls it rather than transcribing the UTF-8 walk into bytecode, so the two
	 * readings of {@code :direction :io} cannot drift; the class TRAVELS beside the
	 * output ({@link #RUNTIME_CLASS_FILES}).
	 * @param type the class itself
	 * @param init {@code (String path, int mode)}
	 * @param readByte {@code ()I}, -1 at end of file
	 * @param writeByte {@code (I)V}
	 * @param readCodePoint {@code ()I}, -1 at end of file
	 * @param peekCodePoint {@code ()I}, -1 at end of file
	 * @param readLine {@code ()Ljava/lang/String;}, "" once {@code ready} answers false
	 * @param position {@code ()J}
	 * @param seek {@code (J)V}
	 * @param length {@code ()J}
	 * @param ready {@code ()Z} -- the end-of-file test, since the class cannot say
	 * {@code @Nullable}
	 */
	/** The travelling class the bidirectional file stream is written in. */
	static final String IO_FILE_STREAM_CLASS = "am/ik/rontolisp/runtime/RontoIoFileStream";

	/**
	 * The travelling class list of {@code :direction :io} /
	 * {@code :if-exists :overwrite}: plain Java over a {@code RandomAccessFile}, which a
	 * bytecode transcription of the UTF-8 walk would only make harder to keep in step
	 * with the interpreter's -- the {@code RontoHashTable} precedent
	 * ({@code .kb/jvm-export.md}, "What travels"). It goes beside a compiled program that
	 * can open one; every other program still compiles to exactly one file.
	 */
	static final List<String> RUNTIME_CLASS_FILES = List.of(IO_FILE_STREAM_CLASS + ".class");

	/**
	 * The travelling class list of a string input stream whose position can be asked
	 * ({@link FileMeta#stringInputPositions}): {@code runtime.RontoStringInputStream}.
	 */
	static final List<String> STRING_INPUT_RUNTIME_CLASS_FILES = List
		.of(JvmStringStreamPositions.STRING_INPUT_STREAM_CLASS + ".class");

	/** The travelling class a positioned character INPUT file stream is written in. */
	static final String CHAR_FILE_READER_CLASS = "am/ik/rontolisp/runtime/RontoCharFileReader";

	/** The travelling class a positioned character OUTPUT file stream is written in. */
	static final String CHAR_FILE_WRITER_CLASS = "am/ik/rontolisp/runtime/RontoCharFileWriter";

	/**
	 * The travelling class list of the positioned character file streams: a
	 * {@code BufferedReader} / {@code BufferedWriter} that decodes / encodes UTF-8 over
	 * its own byte buffer, so {@code file-position} is the channel offset corrected by
	 * what is buffered. It goes beside a compiled program that names
	 * {@code file-position} and can open a character file stream.
	 */
	static final List<String> CHAR_FILE_RUNTIME_CLASS_FILES = List.of(CHAR_FILE_READER_CLASS + ".class",
			CHAR_FILE_WRITER_CLASS + ".class");

	/**
	 * The constant-pool entries of the positioned character file streams.
	 *
	 * @param reader the input class
	 * @param readerInit {@code (String path)}
	 * @param readerPosition {@code ()J}
	 * @param readerSeek {@code (J)V}
	 * @param writer the output class
	 * @param writerInit {@code (String path, boolean append)}
	 * @param writerPosition {@code ()J}
	 * @param writerSeek {@code (J)V}
	 */
	private record CharFileStreams(ClassEntry reader, MethodRefEntry readerInit, MethodRefEntry readerPosition,
			MethodRefEntry readerSeek, ClassEntry writer, MethodRefEntry writerInit, MethodRefEntry writerPosition,
			MethodRefEntry writerSeek) {

		static CharFileStreams mint(ConstantPool cp) {
			ClassEntry reader = cp.classEntry(CHAR_FILE_READER_CLASS);
			ClassEntry writer = cp.classEntry(CHAR_FILE_WRITER_CLASS);
			return new CharFileStreams(reader, cp.methodRef(reader, "<init>", "(Ljava/lang/String;)V"),
					cp.methodRef(reader, "position", "()J"), cp.methodRef(reader, "position", "(J)V"), writer,
					cp.methodRef(writer, "<init>", "(Ljava/lang/String;Z)V"), cp.methodRef(writer, "position", "()J"),
					cp.methodRef(writer, "position", "(J)V"));
		}

	}

	private record IoStreams(ClassEntry type, MethodRefEntry init, MethodRefEntry readByte, MethodRefEntry writeByte,
			MethodRefEntry readCodePoint, MethodRefEntry peekCodePoint, MethodRefEntry readLine,
			MethodRefEntry position, MethodRefEntry seek, MethodRefEntry length, MethodRefEntry ready) {

		static IoStreams mint(ConstantPool cp) {
			ClassEntry type = cp.classEntry(IO_FILE_STREAM_CLASS);
			return new IoStreams(type, cp.methodRef(type, "<init>", "(Ljava/lang/String;I)V"),
					cp.methodRef(type, "readByte", "()I"), cp.methodRef(type, "writeByte", "(I)V"),
					cp.methodRef(type, "readCodePoint", "()I"), cp.methodRef(type, "peekCodePoint", "()I"),
					cp.methodRef(type, "readLine", "()Ljava/lang/String;"), cp.methodRef(type, "position", "()J"),
					cp.methodRef(type, "position", "(J)V"), cp.methodRef(type, "length", "()J"),
					cp.methodRef(type, "ready", "()Z"));
		}

	}

	private record PackedSequenceIo(ClassEntry floatArrayClass, ClassEntry doubleArrayClass, ClassEntry shortArrayClass,
			ClassEntry longArrayClass, ClassEntry byteBufferClass, MethodRefEntry readNBytes,
			MethodRefEntry byteBufferWrap, MethodRefEntry byteBufferOrder, FieldRefEntry littleEndian,
			MethodRefEntry asFloatBuffer, MethodRefEntry asDoubleBuffer, MethodRefEntry asShortBuffer,
			MethodRefEntry floatBufferGet, MethodRefEntry doubleBufferGet, MethodRefEntry shortBufferGet,
			MethodRefEntry floatBufferPut, MethodRefEntry doubleBufferPut, MethodRefEntry shortBufferPut,
			MethodRefEntry bbGet, MethodRefEntry bbGetShort, MethodRefEntry bbGetInt, MethodRefEntry bbPut,
			MethodRefEntry bbPutShort, MethodRefEntry bbPutInt, MethodRefEntry outputStreamWriteBytes,
			StringEntry boundsMessage, ClassEntry byteArrayClass, MethodRefEntry qmInt, MethodRefEntry bbGetBytes,
			MethodRefEntry bbPutBytes) {

		static PackedSequenceIo mint(ConstantPool cp, ClassEntry thisClass) {
			ClassEntry byteBuffer = cp.classEntry("java/nio/ByteBuffer");
			ClassEntry floatBuffer = cp.classEntry("java/nio/FloatBuffer");
			ClassEntry doubleBuffer = cp.classEntry("java/nio/DoubleBuffer");
			ClassEntry shortBuffer = cp.classEntry("java/nio/ShortBuffer");
			ClassEntry byteOrder = cp.classEntry("java/nio/ByteOrder");
			return new PackedSequenceIo(cp.classEntry("[F"), cp.classEntry("[D"), cp.classEntry("[S"),
					cp.classEntry("[J"), byteBuffer,
					cp.methodRef(cp.classEntry("java/io/InputStream"), "readNBytes", "(I)[B"),
					cp.methodRef(byteBuffer, "wrap", "([B)Ljava/nio/ByteBuffer;"),
					cp.methodRef(byteBuffer, "order", "(Ljava/nio/ByteOrder;)Ljava/nio/ByteBuffer;"),
					cp.fieldRef(byteOrder, "LITTLE_ENDIAN", "Ljava/nio/ByteOrder;"),
					cp.methodRef(byteBuffer, "asFloatBuffer", "()Ljava/nio/FloatBuffer;"),
					cp.methodRef(byteBuffer, "asDoubleBuffer", "()Ljava/nio/DoubleBuffer;"),
					cp.methodRef(byteBuffer, "asShortBuffer", "()Ljava/nio/ShortBuffer;"),
					cp.methodRef(floatBuffer, "get", "([FII)Ljava/nio/FloatBuffer;"),
					cp.methodRef(doubleBuffer, "get", "([DII)Ljava/nio/DoubleBuffer;"),
					cp.methodRef(shortBuffer, "get", "([SII)Ljava/nio/ShortBuffer;"),
					cp.methodRef(floatBuffer, "put", "([FII)Ljava/nio/FloatBuffer;"),
					cp.methodRef(doubleBuffer, "put", "([DII)Ljava/nio/DoubleBuffer;"),
					cp.methodRef(shortBuffer, "put", "([SII)Ljava/nio/ShortBuffer;"),
					cp.methodRef(byteBuffer, "get", "()B"), cp.methodRef(byteBuffer, "getShort", "()S"),
					cp.methodRef(byteBuffer, "getInt", "()I"),
					cp.methodRef(byteBuffer, "put", "(B)Ljava/nio/ByteBuffer;"),
					cp.methodRef(byteBuffer, "putShort", "(S)Ljava/nio/ByteBuffer;"),
					cp.methodRef(byteBuffer, "putInt", "(I)Ljava/nio/ByteBuffer;"),
					cp.methodRef(cp.classEntry("java/io/OutputStream"), "write", "([B)V"),
					cp.stringEntry("read-sequence/write-sequence: :start/:end exceed the buffer size"),
					cp.classEntry("[B"),
					cp.methodRef(thisClass, JvmQuantizedMatrixRuntimeBuilder.INT,
							JvmQuantizedMatrixRuntimeBuilder.INT_DESC),
					cp.methodRef(byteBuffer, "get", "([BII)Ljava/nio/ByteBuffer;"),
					cp.methodRef(byteBuffer, "put", "([BII)Ljava/nio/ByteBuffer;"));
		}
	}

	/**
	 * Emits {@code if (handle instanceof Long && (int) handle == 2) { <stderr> }} in
	 * front of a stream built-in's table lookup, so the {@code *error-output*} designator
	 * reaches the process standard error instead of a table slot. Emitted only when the
	 * program can name {@code *error-output*} ({@link #errorOutput}), and the branch body
	 * must return -- both tests fall through to the original code.
	 * @param code the method body under construction
	 * @param handleSlot the local holding the handle argument
	 * @param stderrBody appends the branch body (which must end in a return)
	 */
	private void emitStderrBranch(MethodCode code, int handleSlot, Runnable stderrBody) {
		if (!this.errorOutput) {
			return;
		}
		code.aload(handleSlot);
		code.instanceOf(this.longClass);
		MethodCode.Label ifNotHandle = code.newLabel();
		code.ifeq(ifNotHandle);
		code.aload(handleSlot);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.iconst_2();
		MethodCode.Label ifNotStderr = code.newLabel();
		code.if_icmpne(ifNotStderr);
		stderrBody.run();
		code.labelBinding(ifNotHandle);
		code.labelBinding(ifNotStderr);
	}

	/** Returns all stream-runtime method bodies to emit. */
	List<IoMethod> methods() {
		List<IoMethod> ms = new ArrayList<>();
		ms.add(new IoMethod(this.cp.utf8Entry(ADD_STREAM_METHOD), this.cp.utf8Entry(ADD_STREAM_DESC), buildAddStream(),
				AccessFlag.ACC_SYNCHRONIZED));
		ms.add(new IoMethod(this.cp.utf8Entry(OPEN_METHOD), this.cp.utf8Entry(OPEN_DESC), buildOpen()));
		// synchronized with _addStream: the entry is nulled out on the CURRENT table, so
		// a close racing a table growth must not write into the array being replaced.
		ms.add(new IoMethod(this.cp.utf8Entry(CLOSE_METHOD), this.cp.utf8Entry(CLOSE_DESC), buildClose(),
				AccessFlag.ACC_SYNCHRONIZED));
		ms.add(new IoMethod(this.cp.utf8Entry(PROBE_FILE_METHOD), this.cp.utf8Entry(PROBE_FILE_DESC),
				buildProbeFile()));
		if (this.listDirectory) {
			ms.add(new IoMethod(this.cp.utf8Entry(LIST_DIRECTORY_METHOD), this.cp.utf8Entry(LIST_DIRECTORY_DESC),
					buildListDirectory()));
		}
		if (this.fileMeta.writeDate()) {
			ms.add(new IoMethod(this.cp.utf8Entry(FILE_WRITE_DATE_METHOD), this.cp.utf8Entry(FILE_WRITE_DATE_DESC),
					buildFileWriteDate()));
		}
		if (this.fileMeta.makeDirectories()) {
			ms.add(new IoMethod(this.cp.utf8Entry(MAKE_DIRECTORIES_METHOD), this.cp.utf8Entry(MAKE_DIRECTORIES_DESC),
					buildMakeDirectories()));
		}
		if (this.fileMeta.deleteFile()) {
			ms.add(new IoMethod(this.cp.utf8Entry(DELETE_FILE_METHOD), this.cp.utf8Entry(DELETE_FILE_DESC),
					buildDeleteFile()));
		}
		if (this.fileMeta.renameFile()) {
			ms.add(new IoMethod(this.cp.utf8Entry(RENAME_FILE_METHOD), this.cp.utf8Entry(RENAME_FILE_DESC),
					buildRenameFile()));
		}
		if (this.fileMeta.streamPaths()) {
			ms.add(new IoMethod(this.cp.utf8Entry(SET_STREAM_PATH_METHOD), this.cp.utf8Entry(SET_STREAM_PATH_DESC),
					buildSetStreamPath(), AccessFlag.ACC_SYNCHRONIZED));
		}
		if (this.fileMeta.fileLength()) {
			ms.add(new IoMethod(this.cp.utf8Entry(FILE_LENGTH_METHOD), this.cp.utf8Entry(FILE_LENGTH_DESC),
					buildFileLength()));
		}
		if (this.fileMeta.position()) {
			ms.add(new IoMethod(this.cp.utf8Entry(STORE_STREAM_POSITION_METHOD),
					this.cp.utf8Entry(STORE_STREAM_POSITION_DESC), buildStoreStreamPosition(),
					AccessFlag.ACC_SYNCHRONIZED));
			ms.add(new IoMethod(this.cp.utf8Entry(BUMP_STREAM_POSITION_METHOD),
					this.cp.utf8Entry(BUMP_STREAM_POSITION_DESC), buildBumpStreamPosition(),
					AccessFlag.ACC_SYNCHRONIZED));
			ms.add(new IoMethod(this.cp.utf8Entry(FILE_POSITION_METHOD), this.cp.utf8Entry(FILE_POSITION_DESC),
					buildFilePosition()));
		}
		ms.add(new IoMethod(this.cp.utf8Entry(WRITE_LINE_METHOD), this.cp.utf8Entry(WRITE_LINE_DESC),
				buildWriteLine()));
		ms.add(new IoMethod(this.cp.utf8Entry(READ_LINE_STREAM_METHOD), this.cp.utf8Entry(READ_LINE_STREAM_DESC),
				buildReadLineStream()));
		if (this.linePairs != null) {
			ms.add(new IoMethod(this.cp.utf8Entry(READ_LINE_PAIR_METHOD), this.cp.utf8Entry(READ_LINE_PAIR_DESC),
					buildReadLinePair(this.linePairs)));
		}
		ms.add(new IoMethod(this.cp.utf8Entry(READ_BYTE_METHOD), this.cp.utf8Entry(READ_BYTE_DESC), buildReadByte()));
		ms.add(new IoMethod(this.cp.utf8Entry(READ_CHAR_METHOD), this.cp.utf8Entry(READ_CHAR_DESC), buildReadChar()));
		ms.add(new IoMethod(this.cp.utf8Entry(PEEK_CHAR_METHOD), this.cp.utf8Entry(PEEK_CHAR_DESC), buildPeekChar()));
		ms.add(new IoMethod(this.cp.utf8Entry(WRITE_BYTE_METHOD), this.cp.utf8Entry(WRITE_BYTE_DESC),
				buildWriteByte()));
		if (this.packedSequenceIo != null) {
			ms.add(new IoMethod(this.cp.utf8Entry(READ_SEQ_PACKED_METHOD), this.cp.utf8Entry(READ_SEQ_PACKED_DESC),
					buildSeqPacked(true)));
			ms.add(new IoMethod(this.cp.utf8Entry(WRITE_SEQ_PACKED_METHOD), this.cp.utf8Entry(WRITE_SEQ_PACKED_DESC),
					buildSeqPacked(false)));
		}
		if (this.charSequenceIo != null) {
			ms.add(new IoMethod(this.cp.utf8Entry(READ_SEQ_CHARS_METHOD), this.cp.utf8Entry(READ_SEQ_CHARS_DESC),
					buildReadSeqChars()));
		}
		ms.add(new IoMethod(this.cp.utf8Entry(WRITE_STR_METHOD), this.cp.utf8Entry(WRITE_STR_DESC), buildWriteStr()));
		ms.add(new IoMethod(this.cp.utf8Entry(WRITE_STRING_METHOD), this.cp.utf8Entry(WRITE_STRING_DESC),
				buildWriteString()));
		ms.add(new IoMethod(this.cp.utf8Entry(MAKE_STRING_OUTPUT_STREAM_METHOD),
				this.cp.utf8Entry(MAKE_STRING_OUTPUT_STREAM_DESC), buildMakeStringOutputStream()));
		ms.add(new IoMethod(this.cp.utf8Entry(MAKE_STRING_INPUT_STREAM_METHOD),
				this.cp.utf8Entry(MAKE_STRING_INPUT_STREAM_DESC), buildMakeStringInputStream()));
		ms.add(new IoMethod(this.cp.utf8Entry(STRING_STREAM_CONTENTS_METHOD),
				this.cp.utf8Entry(STRING_STREAM_CONTENTS_DESC), buildStringStreamContents()));
		ms.add(new IoMethod(this.cp.utf8Entry(FRESH_LINE_METHOD), this.cp.utf8Entry(FRESH_LINE_DESC),
				buildFreshLine()));
		ms.add(new IoMethod(this.cp.utf8Entry(FORCE_OUTPUT_METHOD), this.cp.utf8Entry(FORCE_OUTPUT_DESC),
				buildForceOutput()));
		ms.add(new IoMethod(this.cp.utf8Entry(LISTEN_METHOD), this.cp.utf8Entry(LISTEN_DESC), buildListen()));
		ms.add(new IoMethod(this.cp.utf8Entry(OPEN_STREAM_P_METHOD), this.cp.utf8Entry(OPEN_STREAM_P_DESC),
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
	private MethodCode buildFreshLine() {
		// Slots: 0=dest, 1=entry, 2=contents (String)
		MethodCode code = new MethodCode();
		// The *error-output* designator has no table entry (and stderr's column is
		// unknown), so it takes the always-write rule through _writeStr.
		emitStderrBranch(code, 0, () -> {
			code.ldc(this.newlineStr);
			code.aload(0);
			code.invokestatic(this.writeStrMethod);
			code.aconst_null();
			code.areturn();
		});
		// if (!(dest instanceof Long)) { if (_col != 0) { print "\n"; _col = 0; } }
		code.aload(0);
		code.instanceOf(this.longClass);
		MethodCode.Label ifHandle = code.newLabel();
		code.ifne(ifHandle);
		code.getstatic(this.colField);
		MethodCode.Label ifAtStart = code.newLabel();
		code.ifeq(ifAtStart);
		code.getstatic(this.systemOut);
		code.ldc(this.newlineStr);
		code.invokevirtual(this.printStr);
		code.iconst_0();
		code.putstatic(this.colField);
		code.labelBinding(ifAtStart);
		code.aconst_null();
		code.areturn();
		code.labelBinding(ifHandle);
		// entry = _streams[(int) ((Long) dest).longValue()];
		code.getstatic(this.streamsField);
		code.aload(0);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.aaload();
		code.astore(1);
		// if (entry instanceof StringWriter) { newline only when mid-line }
		code.aload(1);
		code.instanceOf(this.stringWriterClass);
		MethodCode.Label ifNotStringWriter = code.newLabel();
		code.ifeq(ifNotStringWriter);
		code.aload(1);
		code.checkcast(this.stringWriterClass);
		code.invokevirtual(this.stringWriterToString);
		code.astore(2);
		// if (contents.length() == 0 || contents.charAt(len - 1) == '\n') return null;
		code.aload(2);
		code.invokevirtual(this.stringLength);
		MethodCode.Label ifEmpty = code.newLabel();
		code.ifeq(ifEmpty);
		code.aload(2);
		code.aload(2);
		code.invokevirtual(this.stringLength);
		code.iconst_1();
		code.isub();
		code.invokevirtual(this.stringCharAt);
		code.loadConstant(10);
		MethodCode.Label ifNewline = code.newLabel();
		code.if_icmpeq(ifNewline);
		MethodCode.Label gotoWrite = code.newLabel();
		code.goto_(gotoWrite);
		code.labelBinding(ifEmpty);
		code.labelBinding(ifNewline);
		code.aconst_null();
		code.areturn();
		code.labelBinding(gotoWrite);
		code.labelBinding(ifNotStringWriter);
		// _writeStr("\n", dest); return null;
		code.ldc(this.newlineStr);
		code.aload(0);
		code.invokestatic(this.writeStrMethod);
		code.aconst_null();
		code.areturn();
		return code;
	}

	/**
	 * {@code _forceOutput(Object handle) -> null}. Flushes the designated output stream's
	 * buffered bytes: a non-handle designator ({@code null}/T) flushes standard output, a
	 * socket entry its output stream, a writer/output-stream entry itself. Anything else
	 * (an input stream, a closed slot) is a no-op -- flushing never signals here.
	 */
	private MethodCode buildForceOutput() {
		// Slots: 0=handle, 1=entry
		MethodCode code = new MethodCode();
		// The *error-output* designator: System.err.flush(); return null;
		emitStderrBranch(code, 0, () -> {
			code.getstatic(java.util.Objects.requireNonNull(this.systemErr));
			code.invokevirtual(this.printStreamFlush);
			code.aconst_null();
			code.areturn();
		});
		// if (!(handle instanceof Long)) { System.out.flush(); return null; }
		code.aload(0);
		code.instanceOf(this.longClass);
		MethodCode.Label ifHandle = code.newLabel();
		code.ifne(ifHandle);
		code.getstatic(this.systemOut);
		code.invokevirtual(this.printStreamFlush);
		code.aconst_null();
		code.areturn();
		code.labelBinding(ifHandle);
		// entry = _streams[(int) ((Long) handle).longValue()];
		code.getstatic(this.streamsField);
		code.aload(0);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.aaload();
		code.astore(1);
		MethodCode.Label done = code.newLabel();
		if (this.sockets != null) {
			// if (entry instanceof Socket) { entry.getOutputStream().flush(); }
			code.aload(1);
			code.instanceOf(this.sockets.socketClass());
			MethodCode.Label ifNotSocket = code.newLabel();
			code.ifeq(ifNotSocket);
			code.aload(1);
			code.checkcast(this.sockets.socketClass());
			code.invokevirtual(this.sockets.socketGetOutputStream());
			code.invokevirtual(this.outputStreamFlush);
			code.goto_(done);
			code.labelBinding(ifNotSocket);
		}
		// if (entry instanceof Writer) ((Writer) entry).flush();
		code.aload(1);
		code.instanceOf(this.writerClass);
		MethodCode.Label ifNotWriter = code.newLabel();
		code.ifeq(ifNotWriter);
		code.aload(1);
		code.checkcast(this.writerClass);
		code.invokevirtual(this.writerFlush);
		code.goto_(done);
		code.labelBinding(ifNotWriter);
		// else if (entry instanceof OutputStream) ((OutputStream) entry).flush();
		code.aload(1);
		code.instanceOf(this.outputStreamClass);
		MethodCode.Label ifNotOutput = code.newLabel();
		code.ifeq(ifNotOutput);
		code.aload(1);
		code.checkcast(this.outputStreamClass);
		code.invokevirtual(this.outputStreamFlush);
		code.labelBinding(ifNotOutput);
		code.labelBinding(done);
		code.aconst_null();
		code.areturn();
		return code;
	}

	/**
	 * {@code _openStreamP(Object handle) -> "T" | null}. Whether the handle still names
	 * an open stream: its {@code _streams} slot is non-null (a {@code close} nulls it)
	 * and, for a socket entry, the socket has not been closed from the other side. A
	 * non-handle designator (the {@code t} standard-output designator) answers T; any
	 * other value answers nil.
	 */
	private MethodCode buildOpenStreamP() {
		// Slots: 0=handle, 1=idx (int)
		MethodCode code = new MethodCode();
		// The *error-output* designator names no table slot but is always open.
		emitStderrBranch(code, 0, () -> {
			code.ldc(this.tStr);
			code.areturn();
		});
		MethodCode.Label returnNil = code.newLabel();
		// A non-Long designator: T for the t stream designator, nil for anything else.
		code.aload(0);
		code.instanceOf(this.longClass);
		MethodCode.Label ifHandle = code.newLabel();
		code.ifne(ifHandle);
		code.aload(0);
		code.ifnull(returnNil);
		code.ldc(this.tStr);
		code.areturn();
		code.labelBinding(ifHandle);
		// _streams == null -> nil
		code.getstatic(this.streamsField);
		code.ifnull(returnNil);
		// idx = (int) handle; idx < 0 || idx >= _streams.length -> nil
		code.aload(0);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.istore(1);
		code.iload(1);
		code.iflt(returnNil);
		code.iload(1);
		code.getstatic(this.streamsField);
		code.arraylength();
		code.if_icmpge(returnNil);
		// entry == null -> nil
		code.getstatic(this.streamsField);
		code.iload(1);
		code.aaload();
		code.ifnull(returnNil);
		if (this.sockets != null) {
			// A socket the peer (or a foreign close) shut: isClosed() -> nil.
			code.getstatic(this.streamsField);
			code.iload(1);
			code.aaload();
			code.instanceOf(this.sockets.socketClass());
			MethodCode.Label ifNotSocket = code.newLabel();
			code.ifeq(ifNotSocket);
			code.getstatic(this.streamsField);
			code.iload(1);
			code.aaload();
			code.checkcast(this.sockets.socketClass());
			code.invokevirtual(this.socketIsClosed);
			code.ifne(returnNil);
			code.labelBinding(ifNotSocket);
		}
		code.ldc(this.tStr);
		code.areturn();
		code.labelBinding(returnNil);
		code.aconst_null();
		code.areturn();
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
	private MethodCode buildListen() {
		// Slots: 0=handle, 1=entry
		MethodCode code = new MethodCode();
		MethodCode.Label returnNil = code.newLabel();
		MethodCode.Label returnT = code.newLabel();
		// if (!(handle instanceof Long)) { stdin probe }
		code.aload(0);
		code.instanceOf(this.longClass);
		MethodCode.Label ifHandle = code.newLabel();
		code.ifne(ifHandle);
		// if (_stdinReader != null) return _stdinReader.ready() ? "T" : null;
		code.getstatic(this.stdinReaderField);
		MethodCode.Label ifNoReader = code.newLabel();
		code.ifnull(ifNoReader);
		code.getstatic(this.stdinReaderField);
		code.invokevirtual(this.bufferedReaderReady);
		code.ifeq(returnNil);
		code.goto_(returnT);
		code.labelBinding(ifNoReader);
		// return System.in.available() > 0 ? "T" : null;
		code.getstatic(this.systemIn);
		code.invokevirtual(this.inputStreamAvailable);
		code.ifle(returnNil);
		code.goto_(returnT);
		code.labelBinding(ifHandle);
		// entry = _streams[(int) ((Long) handle).longValue()];
		code.getstatic(this.streamsField);
		code.aload(0);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.aaload();
		code.astore(1);
		if (this.sockets != null) {
			// if (entry instanceof Socket) return in.available() > 0 ? "T" : null;
			code.aload(1);
			code.instanceOf(this.sockets.socketClass());
			MethodCode.Label ifNotSocket = code.newLabel();
			code.ifeq(ifNotSocket);
			code.aload(1);
			code.checkcast(this.sockets.socketClass());
			code.invokevirtual(this.sockets.socketGetInputStream());
			code.invokevirtual(this.inputStreamAvailable);
			code.ifle(returnNil);
			code.goto_(returnT);
			code.labelBinding(ifNotSocket);
		}
		if (this.ioStreams != null) {
			// A bidirectional stream is ready while its cursor is before the end.
			code.aload(1);
			code.instanceOf(this.ioStreams.type());
			MethodCode.Label ifNotIo = code.newLabel();
			code.ifeq(ifNotIo);
			code.aload(1);
			code.checkcast(this.ioStreams.type());
			code.invokevirtual(this.ioStreams.ready());
			code.ifeq(returnNil);
			code.goto_(returnT);
			code.labelBinding(ifNotIo);
		}
		// if (entry instanceof RontoStringInputStream) return hasRemaining() ? "T" :
		// null. A string input stream answers whether a character remains, not
		// Reader.ready() (true until close): at the end, with nothing parked, there
		// is nothing to listen to. Every string input is built positioned, so this
		// never depends on whether the program names file-position; the
		// unread-char cell rides ahead of this helper (the %unread-listen rewrite).
		if (this.stringInputStreamClass != null && this.stringInputHasRemaining != null) {
			code.aload(1);
			code.instanceOf(this.stringInputStreamClass);
			MethodCode.Label ifNotStringInput = code.newLabel();
			code.ifeq(ifNotStringInput);
			code.aload(1);
			code.checkcast(this.stringInputStreamClass);
			code.invokevirtual(this.stringInputHasRemaining);
			code.ifeq(returnNil);
			code.goto_(returnT);
			code.labelBinding(ifNotStringInput);
		}
		// if (entry instanceof BufferedReader) return ready() ? "T" : null;
		code.aload(1);
		code.instanceOf(this.bufferedReaderClass);
		MethodCode.Label ifNotReader = code.newLabel();
		code.ifeq(ifNotReader);
		code.aload(1);
		code.checkcast(this.bufferedReaderClass);
		code.invokevirtual(this.bufferedReaderReady);
		code.ifeq(returnNil);
		code.goto_(returnT);
		code.labelBinding(ifNotReader);
		// if (entry instanceof InputStream) return available() > 0 ? "T" : null;
		code.aload(1);
		code.instanceOf(this.inputStreamClass);
		code.ifeq(returnNil);
		code.aload(1);
		code.checkcast(this.inputStreamClass);
		code.invokevirtual(this.inputStreamAvailable);
		code.ifle(returnNil);
		code.labelBinding(returnT);
		code.ldc(this.tStr);
		code.areturn();
		code.labelBinding(returnNil);
		code.aconst_null();
		code.areturn();
		return code;
	}

	/**
	 * {@code _open(Object path, int mode) -> Long handle | null}. Strips the surrounding
	 * quotes from the path string, opens a {@code BufferedReader} (mode 0), a
	 * {@code BufferedWriter} (mode 1), a {@code BufferedInputStream} (mode 2, binary) or
	 * a {@code BufferedOutputStream} (mode 3, binary) and hands it to
	 * {@link #ADD_STREAM_METHOD}, which returns the handle. The file is opened BEFORE the
	 * table is touched, so the slow part stays outside the allocator's lock. An
	 * {@code IOException} from the open answers null -- the WASM {@code _open}'s contract
	 * -- and the shared {@code open} lowering signals the {@code file-error}
	 * ({@code LispMacroExpander.expandOpenFileErrorSignal}).
	 */
	private MethodCode buildOpen() {
		// Slots: 0=path (Object), 1=mode (int), 2=p (String), 3=stream
		MethodCode code = new MethodCode();
		// p = ((String) path).substring(1, length - 1);
		code.aload(0);
		emitStrvOnStack(code);
		code.checkcast(this.stringClass);
		code.astore(2);
		code.aload(2);
		code.iconst_1();
		code.aload(2);
		code.invokevirtual(this.stringLength);
		code.iconst_1();
		code.isub();
		code.invokevirtual(this.stringSubstring);
		code.astore(2);
		// stream = switch (mode) { case 0 -> new BufferedReader(new FileReader(p));
		// case 1 -> new BufferedWriter(new FileWriter(p));
		// case 2 -> new BufferedInputStream(new FileInputStream(p));
		// case 3 -> new BufferedOutputStream(new FileOutputStream(p));
		// case 5 -> new BufferedWriter(new FileWriter(p, true));
		// default -> new BufferedOutputStream(new FileOutputStream(p, true)); };
		// (5 and 7 are the OpenModes.APPEND_BIT arms -- :if-exists :append.)
		int[] modes = { 0, 1, 2, 3, 5 };
		MethodCode.Label tryStart = code.newBoundLabel();
		MethodCode.Label store = code.newLabel();
		MethodCode.@Nullable Label nextTest = null;
		if (this.ioStreams != null) {
			// The BIDIRECTIONAL arm comes FIRST and tests the two bits rather than the
			// whole mode, because :io and :overwrite multiply out with the element type
			// and the disposition into eight of them and all eight are the same object:
			// stream = new RontoIoFileStream(p, mode).
			code.iload(1);
			code.loadConstant(OpenModes.IO_BIT | OpenModes.OVERWRITE_BIT);
			code.iand();
			MethodCode.Label notBidirectional = code.newLabel();
			code.ifeq(notBidirectional);
			code.new_(this.ioStreams.type());
			code.dup();
			code.aload(2);
			code.iload(1);
			code.invokespecial(this.ioStreams.init());
			code.astore(3);
			code.goto_(store);
			code.labelBinding(notBidirectional);
		}
		for (int mode : modes) {
			if (nextTest != null) {
				code.labelBinding(nextTest);
			}
			code.iload(1);
			code.loadConstant(mode);
			nextTest = code.newLabel();
			code.if_icmpne(nextTest);
			CharFileStreams chars = this.charFileStreams;
			switch (mode) {
				case 0 -> {
					if (chars != null) {
						// stream = new RontoCharFileReader(p)
						code.new_(chars.reader());
						code.dup();
						code.aload(2);
						code.invokespecial(chars.readerInit());
						code.astore(3);
					}
					else {
						emitOpenStream(code, this.bufferedReaderClass, this.fileReaderClass, this.fileReaderInit,
								this.bufferedReaderInit, false);
					}
				}
				case 1, 5 -> {
					if (chars != null) {
						// stream = new RontoCharFileWriter(p, append)
						code.new_(chars.writer());
						code.dup();
						code.aload(2);
						code.loadConstant(mode == 5 ? 1 : 0);
						code.invokespecial(chars.writerInit());
						code.astore(3);
					}
					else if (mode == 1) {
						emitOpenStream(code, this.bufferedWriterClass, this.fileWriterClass, this.fileWriterInit,
								this.bufferedWriterInit, false);
					}
					else {
						emitOpenStream(code, this.bufferedWriterClass, this.fileWriterClass, this.fileWriterAppendInit,
								this.bufferedWriterInit, true);
					}
				}
				case 2 -> emitOpenStream(code, this.bufferedInputStreamClass, this.fileInputStreamClass,
						this.fileInputStreamInit, this.bufferedInputStreamInit, false);
				case 3 -> emitOpenStream(code, this.bufferedOutputStreamClass, this.fileOutputStreamClass,
						this.fileOutputStreamInit, this.bufferedOutputStreamInit, false);
				default -> throw new IllegalStateException("unreachable open mode " + mode);
			}
			code.goto_(store);
		}
		code.labelBinding(Objects.requireNonNull(nextTest));
		emitOpenStream(code, this.bufferedOutputStreamClass, this.fileOutputStreamClass,
				this.fileOutputStreamAppendInit, this.bufferedOutputStreamInit, true);
		MethodCode.Label tryEnd = code.newBoundLabel();
		code.labelBinding(store);
		// return _addStream(stream); -- or, for a program that asks for file-length,
		// return _setStreamPath(_addStream(stream), p), which records the namestring the
		// Reader/Writer itself does not remember.
		code.aload(3);
		code.invokestatic(this.addStreamRef);
		if (this.fileMeta.streamPaths()) {
			code.aload(2);
			code.invokestatic(Objects.requireNonNull(this.setStreamPathRef));
		}
		if (this.fileMeta.position()) {
			// An appending BINARY stream starts at the end of the file (sbcl), so its
			// counter starts there: if (mode == 7) _storeStreamPosition(handle,
			// Long.valueOf(new File(p).length())). A character one asks its channel.
			code.iload(1);
			code.loadConstant(OpenModes.OUTPUT_BIT | OpenModes.BINARY_BIT | OpenModes.APPEND_BIT);
			MethodCode.Label notBinaryAppend = code.newLabel();
			code.if_icmpne(notBinaryAppend);
			code.dup();
			code.new_(this.fileClass);
			code.dup();
			code.aload(2);
			code.invokespecial(this.fileInit);
			code.invokevirtual(Objects.requireNonNull(this.fileLengthRef));
			code.invokestatic(this.longValueOf);
			code.invokestatic(Objects.requireNonNull(this.storeStreamPositionRef));
			code.pop();
			code.labelBinding(notBinaryAppend);
		}
		code.areturn();
		// catch (IOException e) { return null; }
		MethodCode.Label handler = code.newBoundLabel();
		code.pop();
		code.aconst_null();
		code.areturn();
		code.exceptionCatch(tryStart, tryEnd, handler, this.cp.classEntry("java/io/IOException"));
		return code;
	}

	/**
	 * {@code _probeFile(Object path) -> path | null}. Whether the path names an existing
	 * file: the quotes are stripped the way {@code _open} does, and the answer is the
	 * ORIGINAL (still quoted) path value so the caller gets a Lisp string back --
	 * rontolisp's namestring stands in for the truename. Unlike {@code _open} this never
	 * throws on a missing path; that is the whole point of the primitive.
	 */
	private MethodCode buildProbeFile() {
		// Slots: 0=path (Object), 1=p (String)
		MethodCode code = new MethodCode();
		// p = ((String) path).substring(1, length - 1);
		code.aload(0);
		emitStrvOnStack(code);
		code.checkcast(this.stringClass);
		code.astore(1);
		code.aload(1);
		code.iconst_1();
		code.aload(1);
		code.invokevirtual(this.stringLength);
		code.iconst_1();
		code.isub();
		code.invokevirtual(this.stringSubstring);
		code.astore(1);
		// if (!new File(p).exists()) return null;
		code.new_(this.fileClass);
		code.dup();
		code.aload(1);
		code.invokespecial(this.fileInit);
		code.invokevirtual(this.fileExists);
		MethodCode.Label ifMissing = code.newLabel();
		code.ifne(ifMissing);
		code.aconst_null();
		code.areturn();
		code.labelBinding(ifMissing);
		// return path;
		code.aload(0);
		code.areturn();
		return code;
	}

	/**
	 * {@code _fileWriteDate(Object path) -> Long | null}. The file's modification time as
	 * a universal time (seconds since 1900). Quotes are stripped like {@code _probeFile}
	 * does, and a missing or unreadable file answers null -- {@code File.lastModified}
	 * reports 0 for both, which is also the answer Common Lisp prescribes for "the time
	 * cannot be determined". (A file genuinely stamped at the Unix epoch therefore reads
	 * as unknown; no other JDK call distinguishes them without throwing.)
	 */
	private MethodCode buildFileWriteDate() {
		// Slots: 0=path (Object), 1=p (String), 2/3=millis (long)
		MethodCode code = new MethodCode();
		emitStripQuotes(code, 0, 1);
		// millis = new File(p).lastModified();
		code.new_(this.fileClass);
		code.dup();
		code.aload(1);
		code.invokespecial(this.fileInit);
		code.invokevirtual(Objects.requireNonNull(this.fileLastModified));
		code.dup2();
		code.lstore(2);
		// if (millis == 0L) return null;
		code.lconst_0();
		code.lcmp();
		MethodCode.Label ifKnown = code.newLabel();
		code.ifne(ifKnown);
		code.aconst_null();
		code.areturn();
		code.labelBinding(ifKnown);
		// return Long.valueOf(millis / 1000 + 2208988800L);
		code.lload(2);
		emitLongConst(code, 1000L);
		code.ldiv();
		emitLongConst(code, 2208988800L);
		code.ladd();
		code.invokestatic(this.longValueOf);
		code.areturn();
		return code;
	}

	/**
	 * {@code _makeDirectories(Object path) -> "T" | null}. Creates the directory and
	 * every missing parent, answering null when it does not exist AFTERWARDS -- {@code
	 * mkdirs} answers false both for a directory that already exists (success) and for
	 * one the host refused (failure), so the boolean alone cannot tell them apart. The
	 * re-check via {@code isDirectory()} is the WASM verify-by-opening precedent
	 * (.kb/read-load-streams.md): "a refused directory is a file-error" is the Lisp
	 * {@code ensure-directories-exist} above it, not here.
	 */
	private MethodCode buildMakeDirectories() {
		// Slots: 0=path (Object), 1=p (String)
		MethodCode code = new MethodCode();
		emitStripQuotes(code, 0, 1);
		code.new_(this.fileClass);
		code.dup();
		code.aload(1);
		code.invokespecial(this.fileInit);
		code.invokevirtual(Objects.requireNonNull(this.fileMkdirs));
		code.pop();
		code.new_(this.fileClass);
		code.dup();
		code.aload(1);
		code.invokespecial(this.fileInit);
		code.invokevirtual(Objects.requireNonNull(this.fileIsDirectory));
		MethodCode.Label ifDir = code.newLabel();
		code.ifne(ifDir);
		code.aconst_null();
		code.areturn();
		code.labelBinding(ifDir);
		code.ldc(this.tStr);
		code.areturn();
		return code;
	}

	/**
	 * {@code _deleteFile(Object path) -> "T" | null}. Removes the named file, answering
	 * null when there was nothing to remove or the host refused -- the "a missing file is
	 * a file-error" decision belongs to the Lisp {@code delete-file} above it, not here.
	 */
	private MethodCode buildDeleteFile() {
		// Slots: 0=path (Object), 1=p (String)
		MethodCode code = new MethodCode();
		emitStripQuotes(code, 0, 1);
		code.new_(this.fileClass);
		code.dup();
		code.aload(1);
		code.invokespecial(this.fileInit);
		code.invokevirtual(Objects.requireNonNull(this.fileDelete));
		MethodCode.Label ifDeleted = code.newLabel();
		code.ifne(ifDeleted);
		code.aconst_null();
		code.areturn();
		code.labelBinding(ifDeleted);
		code.ldc(this.tStr);
		code.areturn();
		return code;
	}

	/**
	 * {@code _renameFile(Object from, Object to) -> "T" | null}. Renames the file,
	 * answering null when there was nothing to rename or the host refused -- the "a
	 * missing file is a file-error" decision belongs to the Lisp {@code rename-file}
	 * above it, not here. {@code File.renameTo} is the one JDK call that needs no
	 * exception table, which is what keeps this a plain emitted body.
	 */
	private MethodCode buildRenameFile() {
		// Slots: 0=from (Object), 1=to (Object), 2=f (String), 3=t (String)
		MethodCode code = new MethodCode();
		emitStripQuotes(code, 0, 2);
		emitStripQuotes(code, 1, 3);
		// new File(f).renameTo(new File(t))
		code.new_(this.fileClass);
		code.dup();
		code.aload(2);
		code.invokespecial(this.fileInit);
		code.new_(this.fileClass);
		code.dup();
		code.aload(3);
		code.invokespecial(this.fileInit);
		code.invokevirtual(Objects.requireNonNull(this.fileRenameTo));
		MethodCode.Label ifRenamed = code.newLabel();
		code.ifne(ifRenamed);
		code.aconst_null();
		code.areturn();
		code.labelBinding(ifRenamed);
		code.ldc(this.tStr);
		code.areturn();
		return code;
	}

	/**
	 * {@code _setStreamPath(Object handle, Object path) -> handle}. Records the
	 * namestring a handle was opened on, growing the side table to fit. Emitted
	 * {@code synchronized} for the same reason {@code _addStream} is: served requests
	 * open files concurrently, and the growth swaps the array.
	 */
	private MethodCode buildSetStreamPath() {
		// Slots: 0=handle, 1=path, 2=arr, 3=idx (int)
		MethodCode code = new MethodCode();
		FieldRefEntry paths = Objects.requireNonNull(this.streamPathsField);
		// idx = (int) ((Long) handle).longValue();
		code.aload(0);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.istore(3);
		// arr = _streamPaths == null ? new Object[16] : _streamPaths;
		code.getstatic(paths);
		code.astore(2);
		code.aload(2);
		MethodCode.Label ifHave = code.newLabel();
		code.ifnonnull(ifHave);
		code.loadConstant(16);
		code.anewarray(this.objectClass);
		code.astore(2);
		code.labelBinding(ifHave);
		// if (idx >= arr.length) arr = Arrays.copyOf(arr, (idx + 1) * 2);
		code.iload(3);
		code.aload(2);
		code.arraylength();
		MethodCode.Label ifFits = code.newLabel();
		code.if_icmplt(ifFits);
		code.aload(2);
		code.iload(3);
		code.iconst_1();
		code.iadd();
		code.iconst_2();
		code.imul();
		code.invokestatic(this.arraysCopyOf);
		code.astore(2);
		code.labelBinding(ifFits);
		// arr[idx] = path; _streamPaths = arr; return handle;
		code.aload(2);
		code.iload(3);
		code.aload(1);
		code.aastore();
		code.aload(2);
		code.putstatic(paths);
		code.aload(0);
		code.areturn();
		return code;
	}

	/**
	 * {@code _fileLength(Object handle) -> Long | null}. The length of the file the
	 * handle was opened on. Anything the side table does not know -- a string stream, a
	 * socket, a standard stream, a closed handle -- answers null, Common Lisp's "cannot
	 * be determined". An output stream is flushed first (through {@code _forceOutput},
	 * which already knows every entry kind), so the answer counts what has been WRITTEN
	 * rather than what happens to have reached the disk.
	 */
	private MethodCode buildFileLength() {
		// Slots: 0=handle, 1=arr, 2=idx (int), 3=p
		MethodCode code = new MethodCode();
		FieldRefEntry paths = Objects.requireNonNull(this.streamPathsField);
		MethodCode.Label returnNil = code.newLabel();
		// arr = _streamPaths; if (arr == null) return null;
		code.getstatic(paths);
		code.astore(1);
		code.aload(1);
		code.ifnull(returnNil);
		// if (!(handle instanceof Long)) return null;
		code.aload(0);
		code.instanceOf(this.longClass);
		code.ifeq(returnNil);
		// idx = (int) handle;
		code.aload(0);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.istore(2);
		// if (idx < 0 || idx >= arr.length) return null;
		code.iload(2);
		code.iflt(returnNil);
		code.iload(2);
		code.aload(1);
		code.arraylength();
		code.if_icmpge(returnNil);
		// p = arr[idx]; if (p == null) return null;
		code.aload(1);
		code.iload(2);
		code.aaload();
		code.astore(3);
		code.aload(3);
		code.ifnull(returnNil);
		// _forceOutput(handle);
		code.aload(0);
		code.invokestatic(Objects.requireNonNull(this.forceOutputRef));
		code.pop();
		// return Long.valueOf(new File((String) p).length());
		code.new_(this.fileClass);
		code.dup();
		code.aload(3);
		code.checkcast(this.stringClass);
		code.invokespecial(this.fileInit);
		code.invokevirtual(Objects.requireNonNull(this.fileLengthRef));
		code.invokestatic(this.longValueOf);
		code.areturn();
		code.labelBinding(returnNil);
		code.aconst_null();
		code.areturn();
		return code;
	}

	/**
	 * {@code _storeStreamPosition(Object handle, Object pos) -> handle}. Records the
	 * boxed {@code Long} position of a handle in {@code _streamPositions}, growing the
	 * table the way {@code _setStreamPath} grows {@code _streamPaths}. Used by
	 * {@code _filePosition}'s set half (an exact position) and by
	 * {@code _bumpStreamPosition} (after it reads the current one).
	 */
	private MethodCode buildStoreStreamPosition() {
		MethodCode a = new MethodCode();
		// Slots: 0=handle, 1=pos, 2=arr, 3=idx (int)
		a.aload(0);
		a.checkcast(this.longClass);
		a.invokevirtual(this.longValue);
		a.l2i();
		a.istore(3);
		a.getstatic(Objects.requireNonNull(this.streamPositionsField));
		a.astore(2);
		a.aload(2);
		MethodCode.Label have = a.newLabel();
		a.ifnonnull(have);
		a.loadConstant(16);
		a.anewarray(this.objectClass);
		a.astore(2);
		a.labelBinding(have);
		a.iload(3);
		a.aload(2);
		a.arraylength();
		MethodCode.Label fits = a.newLabel();
		a.if_icmplt(fits);
		a.aload(2);
		a.iload(3);
		a.loadConstant(1);
		a.iadd();
		a.loadConstant(2);
		a.imul();
		a.invokestatic(this.arraysCopyOf);
		a.astore(2);
		a.labelBinding(fits);
		a.aload(2);
		a.iload(3);
		a.aload(1);
		a.aastore();
		a.aload(2);
		a.putstatic(Objects.requireNonNull(this.streamPositionsField));
		a.aload(0);
		a.areturn();
		return a;
	}

	/**
	 * {@code _bumpStreamPosition(Object handle, int delta) -> null}. Advances the boxed
	 * position of a binary file stream by {@code delta} bytes, self-contained enough for
	 * the byte primitives to call it unconditionally after a transfer: a non-file handle
	 * (a socket, a character file stream, a standard stream, a closed slot) is a no-op.
	 */
	private MethodCode buildBumpStreamPosition() {
		MethodCode a = new MethodCode();
		// Slots: 0=handle, 1=delta (int), 2=idx (int), 3=arr, 4=paths, 5=val, 6=cur
		// (long)
		a.aload(0);
		a.instanceOf(this.longClass);
		MethodCode.Label notHandle = a.newLabel();
		a.ifeq(notHandle);
		a.aload(0);
		a.checkcast(this.longClass);
		a.invokevirtual(this.longValue);
		a.l2i();
		a.istore(2);
		a.getstatic(Objects.requireNonNull(this.streamPathsField));
		a.astore(4);
		a.aload(4);
		MethodCode.Label noPaths = a.newLabel();
		a.ifnull(noPaths);
		a.iload(2);
		MethodCode.Label idxNeg = a.newLabel();
		a.iflt(idxNeg);
		a.iload(2);
		a.aload(4);
		a.arraylength();
		MethodCode.Label idxOob = a.newLabel();
		a.if_icmpge(idxOob);
		a.aload(4);
		a.iload(2);
		a.aaload();
		MethodCode.Label notFile = a.newLabel();
		a.ifnull(notFile);
		// cur = (arr = _streamPositions) != null && idx < arr.length
		// && (val = arr[idx]) != null ? ((Long) val).longValue() : 0L
		a.getstatic(Objects.requireNonNull(this.streamPositionsField));
		a.astore(3);
		a.lconst_0();
		a.lstore(6);
		a.aload(3);
		MethodCode.Label noArr = a.newLabel();
		a.ifnull(noArr);
		a.iload(2);
		a.aload(3);
		a.arraylength();
		MethodCode.Label idxOob2 = a.newLabel();
		a.if_icmpge(idxOob2);
		a.aload(3);
		a.iload(2);
		a.aaload();
		a.astore(5);
		a.aload(5);
		MethodCode.Label curNull2 = a.newLabel();
		a.ifnull(curNull2);
		a.aload(5);
		a.checkcast(this.longClass);
		a.invokevirtual(this.longValue);
		a.lstore(6);
		a.labelBinding(noArr);
		a.labelBinding(idxOob2);
		a.labelBinding(curNull2);
		// cur += delta
		a.lload(6);
		a.iload(1);
		a.i2l();
		a.ladd();
		a.invokestatic(this.longValueOf);
		// _storeStreamPosition(handle, Long.valueOf(cur)); return null;
		a.aload(0);
		a.swap();
		a.invokestatic(Objects.requireNonNull(this.storeStreamPositionRef));
		a.pop();
		MethodCode.Label done = a.newLabel();
		a.goto_(done);
		a.labelBinding(notHandle);
		a.labelBinding(noPaths);
		a.labelBinding(idxNeg);
		a.labelBinding(idxOob);
		a.labelBinding(notFile);
		a.labelBinding(done);
		a.aconst_null();
		a.areturn();
		return a;
	}

	/**
	 * {@code _filePosition(Object handle, Object pos) -> Object}. {@code pos == null} is
	 * the one-argument query: the boxed byte position of the file stream, or null where
	 * it cannot be told (a socket, a string stream, a closed handle -- Common Lisp's
	 * "cannot be determined"). A boxed {@code Long} is the set: a bidirectional or
	 * positioned character entry moves its own cursor; a binary one re-opens the file at
	 * that offset (flushing an output stream first, keeping everything before the
	 * offset). Either answers "T" on success, null where it cannot.
	 */
	private MethodCode buildFilePosition() {
		MethodCode a = new MethodCode();
		// Slots: 0=handle, 1=pos, 2=idx (int), 3=paths, 4=p, 5=entry, 6=arr, 7=n (long,
		// slots 7-8), 9=stream/val
		a.aload(0);
		a.instanceOf(this.longClass);
		MethodCode.Label notHandle = a.newLabel();
		a.ifeq(notHandle);
		a.aload(0);
		a.checkcast(this.longClass);
		a.invokevirtual(this.longValue);
		a.l2i();
		a.istore(2);
		if (this.stringPositions != null) {
			// A STRING stream answers first (JvmStringStreamPositions); it has no path.
			a.iload(2);
			MethodCode.Label notInTable = a.newLabel();
			a.iflt(notInTable);
			a.iload(2);
			a.getstatic(Objects.requireNonNull(this.streamsField));
			a.arraylength();
			a.if_icmpge(notInTable);
			a.getstatic(Objects.requireNonNull(this.streamsField));
			a.iload(2);
			a.aaload();
			a.astore(5);
			this.stringPositions.emit(a, 5, 1, 7, this.longClass, this.longValue, this.longValueOf, this.tStr);
			a.labelBinding(notInTable);
		}
		a.getstatic(Objects.requireNonNull(this.streamPathsField));
		a.astore(3);
		a.aload(3);
		MethodCode.Label noPaths = a.newLabel();
		a.ifnull(noPaths);
		a.iload(2);
		MethodCode.Label idxNeg = a.newLabel();
		a.iflt(idxNeg);
		a.iload(2);
		a.aload(3);
		a.arraylength();
		MethodCode.Label idxOob = a.newLabel();
		a.if_icmpge(idxOob);
		a.aload(3);
		a.iload(2);
		a.aaload();
		a.astore(4);
		a.aload(4);
		MethodCode.Label noPathVal = a.newLabel();
		a.ifnull(noPathVal);
		// entry = _streams[idx]
		a.getstatic(Objects.requireNonNull(this.streamsField));
		a.iload(2);
		a.aaload();
		a.astore(5);
		MethodCode.@Nullable Label ioNeg = null;
		if (this.ioStreams != null) {
			// A BIDIRECTIONAL entry owns its cursor: both halves are the
			// RandomAccessFile's own, with no side table and no re-open. This is what
			// makes file-position real for a CHARACTER :io stream, which the
			// Reader/Writer arms below cannot answer for.
			a.aload(5);
			a.instanceOf(this.ioStreams.type());
			MethodCode.Label notIo = a.newLabel();
			a.ifeq(notIo);
			a.aload(1);
			MethodCode.Label ioSet = a.newLabel();
			a.ifnonnull(ioSet);
			a.aload(5);
			a.checkcast(this.ioStreams.type());
			a.invokevirtual(this.ioStreams.position());
			a.invokestatic(this.longValueOf);
			a.areturn();
			a.labelBinding(ioSet);
			a.aload(1);
			a.checkcast(this.longClass);
			a.invokevirtual(this.longValue);
			a.lstore(7);
			a.lload(7);
			a.lconst_0();
			a.lcmp();
			ioNeg = a.newLabel();
			a.iflt(ioNeg);
			a.aload(5);
			a.checkcast(this.ioStreams.type());
			a.lload(7);
			a.invokevirtual(this.ioStreams.seek());
			a.ldc(this.tStr);
			a.areturn();
			a.labelBinding(notIo);
		}
		List<MethodCode.Label> charNegs = new ArrayList<>();
		if (this.charFileStreams != null) {
			// A positioned CHARACTER entry answers its own byte offset, and a set moves
			// it (dropping what it had buffered): no side table, no re-open.
			emitOwnCursorArm(a, this.charFileStreams.reader(), this.charFileStreams.readerPosition(),
					this.charFileStreams.readerSeek(), charNegs);
			emitOwnCursorArm(a, this.charFileStreams.writer(), this.charFileStreams.writerPosition(),
					this.charFileStreams.writerSeek(), charNegs);
		}
		// Only a BINARY entry (an InputStream/OutputStream) has a byte position.
		a.aload(5);
		a.instanceOf(this.inputStreamClass);
		MethodCode.Label notBinaryIn = a.newLabel();
		a.ifeq(notBinaryIn);
		MethodCode.Label isBinary = a.newLabel();
		a.goto_(isBinary);
		a.labelBinding(notBinaryIn);
		a.aload(5);
		a.instanceOf(this.outputStreamClass);
		MethodCode.Label notBinaryOut = a.newLabel();
		a.ifeq(notBinaryOut);
		a.labelBinding(isBinary);
		// pos == null -> query
		a.aload(1);
		MethodCode.Label setPos = a.newLabel();
		a.ifnonnull(setPos);
		a.getstatic(Objects.requireNonNull(this.streamPositionsField));
		a.astore(6);
		// return arr != null && idx < arr.length && arr[idx] != null ? arr[idx]
		// : Long.valueOf(0L)
		a.aload(6);
		MethodCode.Label noPosArr = a.newLabel();
		a.ifnull(noPosArr);
		a.iload(2);
		a.aload(6);
		a.arraylength();
		MethodCode.Label posIdxOob = a.newLabel();
		a.if_icmpge(posIdxOob);
		a.aload(6);
		a.iload(2);
		a.aaload();
		a.astore(9);
		a.aload(9);
		MethodCode.Label posNull = a.newLabel();
		a.ifnull(posNull);
		a.aload(9);
		a.areturn();
		a.labelBinding(noPosArr);
		a.labelBinding(posIdxOob);
		a.labelBinding(posNull);
		a.lconst_0();
		a.invokestatic(this.longValueOf);
		a.areturn();
		// --- the set half -----------------------------------------------------
		a.labelBinding(setPos);
		a.aload(1);
		a.checkcast(this.longClass);
		a.invokevirtual(this.longValue);
		a.lstore(7);
		a.lload(7);
		a.lconst_0();
		a.lcmp();
		MethodCode.Label posNeg = a.newLabel();
		a.iflt(posNeg);
		a.aload(0);
		a.invokestatic(Objects.requireNonNull(this.forceOutputRef));
		a.pop();
		// input arm
		a.aload(5);
		a.instanceOf(this.inputStreamClass);
		MethodCode.Label notInput = a.newLabel();
		a.ifeq(notInput);
		// fis = new FileInputStream(p); fis.getChannel().position(n)
		a.new_(this.fileInputStreamClass);
		a.dup();
		a.aload(4);
		a.checkcast(this.stringClass);
		a.invokespecial(this.fileInputStreamInit);
		a.astore(9);
		a.aload(9);
		a.invokevirtual(Objects.requireNonNull(this.fileInputStreamGetChannel));
		a.lload(7);
		a.invokevirtual(Objects.requireNonNull(this.fileChannelPosition));
		a.pop();
		// _streams[idx] = new BufferedInputStream(fis); close the old entry
		a.getstatic(Objects.requireNonNull(this.streamsField));
		a.iload(2);
		a.new_(this.bufferedInputStreamClass);
		a.dup();
		a.aload(9);
		a.invokespecial(this.bufferedInputStreamInit);
		a.aastore();
		a.aload(5);
		a.checkcast(this.inputStreamClass);
		a.invokevirtual(this.inputStreamClose);
		MethodCode.Label setDone = a.newLabel();
		a.goto_(setDone);
		a.labelBinding(notInput);
		// output arm: FileChannel.open(Path.of(p), WRITE).position(n)
		a.aload(4);
		a.checkcast(this.stringClass);
		a.invokestatic(Objects.requireNonNull(this.pathOf));
		a.loadConstant(1);
		a.anewarray(Objects.requireNonNull(this.openOptionClass));
		a.dup();
		a.loadConstant(0);
		a.getstatic(Objects.requireNonNull(this.standardOpenOptionWrite));
		a.aastore();
		a.invokestatic(Objects.requireNonNull(this.fileChannelOpen));
		a.astore(9);
		a.aload(9);
		a.lload(7);
		a.invokevirtual(Objects.requireNonNull(this.fileChannelPosition));
		a.pop();
		// _streams[idx] = new BufferedOutputStream(Channels.newOutputStream(ch))
		a.getstatic(Objects.requireNonNull(this.streamsField));
		a.iload(2);
		a.new_(this.bufferedOutputStreamClass);
		a.dup();
		a.aload(9);
		a.invokestatic(Objects.requireNonNull(this.channelsNewOutputStream));
		a.invokespecial(this.bufferedOutputStreamInit);
		a.aastore();
		a.aload(5);
		a.checkcast(this.outputStreamClass);
		a.invokevirtual(this.outputStreamClose);
		a.labelBinding(setDone);
		// _storeStreamPosition(handle, Long.valueOf(n)); return "T";
		a.lload(7);
		a.invokestatic(this.longValueOf);
		a.aload(0);
		a.swap();
		a.invokestatic(Objects.requireNonNull(this.storeStreamPositionRef));
		a.pop();
		a.ldc(this.tStr);
		a.areturn();
		// --- the nil exits ----------------------------------------------------
		a.labelBinding(notHandle);
		a.labelBinding(noPaths);
		a.labelBinding(idxNeg);
		a.labelBinding(idxOob);
		a.labelBinding(noPathVal);
		a.labelBinding(notBinaryOut);
		a.aconst_null();
		a.areturn();
		// the negative-position error
		a.labelBinding(posNeg);
		if (ioNeg != null) {
			a.labelBinding(ioNeg);
		}
		for (MethodCode.Label charNeg : charNegs) {
			a.labelBinding(charNeg);
		}
		a.new_(this.runtimeExceptionClass);
		a.dup();
		a.ldc(java.util.Objects.requireNonNull(this.negPositionMsg));
		a.invokespecial(this.runtimeExceptionInit);
		a.athrow();
		return a;
	}

	/**
	 * One {@code _filePosition} arm for an entry that owns its cursor: {@code entry}
	 * (slot 5) of the given type answers {@code position()} for the query ({@code pos},
	 * slot 1, null) and {@code position(n)} then "T" for the set. A negative position
	 * branches to the label added to {@code negs}, bound by the caller at its error.
	 */
	private void emitOwnCursorArm(MethodCode a, ClassEntry type, MethodRefEntry position, MethodRefEntry seek,
			List<MethodCode.Label> negs) {
		a.aload(5);
		a.instanceOf(type);
		MethodCode.Label notThis = a.newLabel();
		a.ifeq(notThis);
		a.aload(1);
		MethodCode.Label set = a.newLabel();
		a.ifnonnull(set);
		a.aload(5);
		a.checkcast(type);
		a.invokevirtual(position);
		a.invokestatic(this.longValueOf);
		a.areturn();
		a.labelBinding(set);
		a.aload(1);
		a.checkcast(this.longClass);
		a.invokevirtual(this.longValue);
		a.lstore(7);
		a.lload(7);
		a.lconst_0();
		a.lcmp();
		MethodCode.Label neg = a.newLabel();
		a.iflt(neg);
		negs.add(neg);
		a.aload(5);
		a.checkcast(type);
		a.lload(7);
		a.invokevirtual(seek);
		a.ldc(this.tStr);
		a.areturn();
		a.labelBinding(notThis);
	}

	// Renders a mutable character vector on the stack into the runtime string when the
	// array runtime exists (a no-op call for any other value); without it no character
	// vector can exist and nothing is emitted.
	private void emitStrvOnStack(MethodCode code) {
		if (this.strvRef == null) {
			return;
		}
		code.invokestatic(this.strvRef);
	}

	/**
	 * Emits {@code <target> = ((String) <arg>).substring(1, length - 1)} -- the quote
	 * stripping every path-taking helper starts with (a rontolisp string value carries
	 * its quotes).
	 */
	private void emitStripQuotes(MethodCode code, int argSlot, int targetSlot) {
		code.aload(argSlot);
		emitStrvOnStack(code);
		code.checkcast(this.stringClass);
		code.astore(targetSlot);
		code.aload(targetSlot);
		code.iconst_1();
		code.aload(targetSlot);
		code.invokevirtual(this.stringLength);
		code.iconst_1();
		code.isub();
		code.invokevirtual(this.stringSubstring);
		code.astore(targetSlot);
	}

	/** Pushes a raw {@code long} constant. */
	private void emitLongConst(MethodCode code, long value) {
		code.ldc(this.cp.entries().longEntry(value));
	}

	/**
	 * {@code _listDirectory(Object path) -> (t . names) | null}. The one
	 * directory-LISTING primitive: the quotes are stripped like {@code _probeFile} does,
	 * {@code File.list()} answers {@code null} for anything that is not a readable
	 * directory (which is exactly the primitive's "not there"), and each surviving name
	 * comes back as a quoted Lisp string with a trailing {@code /} when it is itself a
	 * directory. The result is {@code (t . names)} rather than a bare list because an
	 * EMPTY directory and a missing one would otherwise both be nil. The host's order is
	 * kept -- the caller ({@code directory}, in the prelude) sorts, so every backend
	 * answers alike.
	 */
	private MethodCode buildListDirectory() {
		// Slots: 0=path, 1=p (String), 2=dir (File), 3=names (String[]), 4=i (int),
		// 5=acc (Object), 6=entry (String)
		MethodCode a = new MethodCode();
		MethodCode.Label notADirectory = a.newLabel();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		MethodCode.Label notSubdir = a.newLabel();
		MethodCode.Label joined = a.newLabel();
		// p = ((String) path).substring(1, length - 1);
		a.aload(0);
		if (this.strvRef != null) {
			a.invokestatic(this.strvRef);
		}
		a.checkcast(this.stringClass);
		a.astore(1);
		a.aload(1);
		a.loadConstant(1);
		a.aload(1);
		a.invokevirtual(this.stringLength);
		a.loadConstant(1);
		a.isub();
		a.invokevirtual(this.stringSubstring);
		a.astore(1);
		// dir = new File(p); names = dir.list(); if (names == null) return null;
		a.new_(this.fileClass);
		a.dup();
		a.aload(1);
		a.invokespecial(this.fileInit);
		a.astore(2);
		a.aload(2);
		a.invokevirtual(java.util.Objects.requireNonNull(this.fileList));
		a.astore(3);
		a.aload(3);
		a.ifnull(notADirectory);
		// acc = null; for (i = names.length - 1; i >= 0; i--)
		a.aconst_null();
		a.astore(5);
		a.aload(3);
		a.arraylength();
		a.loadConstant(1);
		a.isub();
		a.istore(4);
		a.labelBinding(loop);
		a.iload(4);
		a.iflt(done);
		// entry = "\"" + names[i]; then + "/\"" for a subdirectory, + "\"" otherwise
		a.ldc(this.quoteStr);
		a.aload(3);
		a.iload(4);
		a.aaload();
		a.invokevirtual(this.stringConcat);
		a.astore(6);
		a.new_(this.fileClass);
		a.dup();
		a.aload(2);
		a.aload(3);
		a.iload(4);
		a.aaload();
		a.invokespecial(java.util.Objects.requireNonNull(this.fileInitChild));
		a.invokevirtual(java.util.Objects.requireNonNull(this.fileIsDirectory));
		a.ifeq(notSubdir);
		a.aload(6);
		a.ldc(java.util.Objects.requireNonNull(this.slashQuoteStr));
		a.invokevirtual(this.stringConcat);
		a.astore(6);
		a.goto_(joined);
		a.labelBinding(notSubdir);
		a.aload(6);
		a.ldc(this.quoteStr);
		a.invokevirtual(this.stringConcat);
		a.astore(6);
		a.labelBinding(joined);
		// acc = new Object[] { entry, acc };
		a.loadConstant(2);
		a.anewarray(this.objectClass);
		a.dup();
		a.loadConstant(0);
		a.aload(6);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aload(5);
		a.aastore();
		a.astore(5);
		a.iinc(4, -1);
		a.goto_(loop);
		a.labelBinding(done);
		// return new Object[] { "T", acc };
		a.loadConstant(2);
		a.anewarray(this.objectClass);
		a.dup();
		a.loadConstant(0);
		a.ldc(this.tStr);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aload(5);
		a.aastore();
		a.areturn();
		a.labelBinding(notADirectory);
		a.aconst_null();
		a.areturn();
		return a;
	}

	/**
	 * Emits {@code slot3 = new <buffered>(new <file>(p))} where {@code p} is the path in
	 * slot 2 -- one arm of the {@code _open} mode branch.
	 */
	private void emitOpenStream(MethodCode code, ClassEntry bufferedClass, ClassEntry fileClass,
			MethodRefEntry fileInit, MethodRefEntry bufferedInit, boolean append) {
		code.new_(bufferedClass);
		code.dup();
		code.new_(fileClass);
		code.dup();
		code.aload(2);
		if (append) {
			// FileWriter(String, boolean) / FileOutputStream(String, boolean): the
			// append flag keeps an existing file's content and writes past its end.
			code.iconst_1();
		}
		code.invokespecial(fileInit);
		code.invokespecial(bufferedInit);
		code.astore(3);
	}

	/**
	 * {@code _closeStream(Object handle) -> t}. Closes the table entry (reader or writer)
	 * and nulls it out.
	 */
	private MethodCode buildClose() {
		// Slots: 0=handle, 1=idx (int), 2=stream
		MethodCode code = new MethodCode();
		// The process standard error outlives a close of it (CL lets a program try), so
		// a later warn still reaches stderr.
		emitStderrBranch(code, 0, () -> {
			code.ldc(this.tStr);
			code.areturn();
		});
		// idx = (int) ((Long) handle).longValue();
		code.aload(0);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.istore(1);
		// stream = _streams[idx];
		code.getstatic(this.streamsField);
		code.iload(1);
		code.aaload();
		code.astore(2);
		// if (stream == null) return "t"; -- closing an ALREADY-CLOSED stream is not an
		// error in CL, it answers true and does nothing (the interpreter's close says the
		// same). Without the guard the chain below reached the Writer arm with null and
		// the close threw a NullPointerException.
		code.aload(2);
		MethodCode.Label ifOpen = code.newLabel();
		code.ifnonnull(ifOpen);
		code.ldc(this.tStr);
		code.areturn();
		code.labelBinding(ifOpen);
		// Socket entries first (only when the program uses tcp built-ins): a Socket /
		// ServerSocket is neither a reader/writer nor a raw byte stream, so the chain
		// below would fail on it.
		MethodCode.Label closed = code.newLabel();
		if (this.sockets != null) {
			code.aload(2);
			code.instanceOf(this.sockets.socketClass());
			MethodCode.Label ifNotSocket = code.newLabel();
			code.ifeq(ifNotSocket);
			code.aload(2);
			code.checkcast(this.sockets.socketClass());
			code.invokevirtual(this.sockets.socketClose());
			code.goto_(closed);
			code.labelBinding(ifNotSocket);
			code.aload(2);
			code.instanceOf(this.sockets.serverSocketClass());
			MethodCode.Label ifNotListener = code.newLabel();
			code.ifeq(ifNotListener);
			code.aload(2);
			code.checkcast(this.sockets.serverSocketClass());
			code.invokevirtual(this.sockets.serverSocketClose());
			code.goto_(closed);
			code.labelBinding(ifNotListener);
		}
		// if (stream instanceof BufferedReader) ((BufferedReader) stream).close();
		// else if (stream instanceof InputStream) ((InputStream) stream).close();
		// else if (stream instanceof OutputStream) ((OutputStream) stream).close();
		// else ((Writer) stream).close();
		code.aload(2);
		code.instanceOf(this.bufferedReaderClass);
		MethodCode.Label ifNotReader = code.newLabel();
		code.ifeq(ifNotReader);
		code.aload(2);
		code.checkcast(this.bufferedReaderClass);
		code.invokevirtual(this.bufferedReaderClose);
		code.goto_(closed);
		code.labelBinding(ifNotReader);
		code.aload(2);
		code.instanceOf(this.inputStreamClass);
		MethodCode.Label ifNotInput = code.newLabel();
		code.ifeq(ifNotInput);
		code.aload(2);
		code.checkcast(this.inputStreamClass);
		code.invokevirtual(this.inputStreamClose);
		code.goto_(closed);
		code.labelBinding(ifNotInput);
		code.aload(2);
		code.instanceOf(this.outputStreamClass);
		MethodCode.Label ifNotOutput = code.newLabel();
		code.ifeq(ifNotOutput);
		code.aload(2);
		code.checkcast(this.outputStreamClass);
		code.invokevirtual(this.outputStreamClose);
		code.goto_(closed);
		code.labelBinding(ifNotOutput);
		code.aload(2);
		code.checkcast(this.writerClass);
		code.invokevirtual(this.writerClose);
		code.labelBinding(closed);
		// _streams[idx] = null; return "t";
		code.getstatic(this.streamsField);
		code.iload(1);
		code.aconst_null();
		code.aastore();
		// The path side table is released with the entry, so file-length on a CLOSED
		// handle answers nil rather than the length the file happens to have now -- the
		// interpreter (which removes the map entry in close) says the same. file-position
		// runs the same rule, so its position table is cleared too.
		if (this.fileMeta.streamPaths()) {
			code.getstatic(Objects.requireNonNull(this.streamPathsField));
			MethodCode.Label ifNoPaths = code.newLabel();
			code.ifnull(ifNoPaths);
			code.iload(1);
			code.getstatic(this.streamPathsField);
			code.arraylength();
			MethodCode.Label ifOutOfRange = code.newLabel();
			code.if_icmpge(ifOutOfRange);
			code.getstatic(this.streamPathsField);
			code.iload(1);
			code.aconst_null();
			code.aastore();
			code.labelBinding(ifNoPaths);
			code.labelBinding(ifOutOfRange);
		}
		if (this.fileMeta.position()) {
			code.getstatic(Objects.requireNonNull(this.streamPositionsField));
			MethodCode.Label ifNoPositions = code.newLabel();
			code.ifnull(ifNoPositions);
			code.iload(1);
			code.getstatic(this.streamPositionsField);
			code.arraylength();
			MethodCode.Label ifOutOfRangePos = code.newLabel();
			code.if_icmpge(ifOutOfRangePos);
			code.getstatic(this.streamPositionsField);
			code.iload(1);
			code.aconst_null();
			code.aastore();
			code.labelBinding(ifNoPositions);
			code.labelBinding(ifOutOfRangePos);
		}
		code.ldc(this.tStr);
		code.areturn();
		return code;
	}

	/**
	 * {@code _writeLine(Object str, Object handle) -> str}. Writes the string content
	 * (without the surrounding quotes) plus a newline to the stream, or to standard
	 * output when the handle is {@code null}.
	 */
	private MethodCode buildWriteLine() {
		// Slots: 0=str, 1=handle, 2=content (String), 3=writer
		MethodCode code = new MethodCode();
		// content = ((String) str).substring(1, length - 1);
		code.aload(0);
		emitStrvOnStack(code);
		code.checkcast(this.stringClass);
		code.astore(2);
		code.aload(2);
		code.iconst_1();
		code.aload(2);
		code.invokevirtual(this.stringLength);
		code.iconst_1();
		code.isub();
		code.invokevirtual(this.stringSubstring);
		code.astore(2);
		// The *error-output* designator: System.err.println(content); return str;
		emitStderrBranch(code, 1, () -> {
			code.getstatic(java.util.Objects.requireNonNull(this.systemErr));
			code.aload(2);
			code.invokevirtual(this.printlnStr);
			code.aload(0);
			code.areturn();
		});
		// if (!(handle instanceof Long)) { System.out.println(content); _col = 0;
		// return str; } -- null and the designator t both mean standard output.
		code.aload(1);
		code.instanceOf(this.longClass);
		MethodCode.Label ifStream = code.newLabel();
		code.ifne(ifStream);
		code.getstatic(this.systemOut);
		code.aload(2);
		code.invokevirtual(this.printlnStr);
		code.iconst_0();
		code.putstatic(this.colField);
		code.aload(0);
		code.areturn();
		code.labelBinding(ifStream);
		// writer = (Writer) _streams[(int) ((Long) handle).longValue()];
		code.getstatic(this.streamsField);
		code.aload(1);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.aaload();
		if (this.sockets != null) {
			// if (entry instanceof Socket) return _sockWriteLine(str, entry);
			code.astore(3);
			code.aload(3);
			code.instanceOf(this.sockets.socketClass());
			MethodCode.Label ifNotSocket = code.newLabel();
			code.ifeq(ifNotSocket);
			code.aload(0);
			code.aload(3);
			code.invokestatic(this.sockets.sockWriteLine());
			code.areturn();
			code.labelBinding(ifNotSocket);
			code.aload(3);
		}
		code.checkcast(this.writerClass);
		code.astore(3);
		// writer.write(content); writer.write("\n"); return str;
		code.aload(3);
		code.aload(2);
		code.invokevirtual(this.writerWrite);
		code.aload(3);
		code.ldc(this.newlineStr);
		code.invokevirtual(this.writerWrite);
		code.aload(0);
		code.areturn();
		return code;
	}

	/**
	 * {@code _readLineStream(Object handle) -> Object}. Reads one line from the stream
	 * (standard input for a non-handle designator -- {@code null} = nil and {@code "T"} =
	 * t, what {@code *standard-input*} holds by default) and wraps it with the internal
	 * {@code '"'} prefix/suffix string format; returns {@code null} (nil) on EOF.
	 */
	private MethodCode buildReadLineStream() {
		// Slots: 0=handle, 1=line (String)
		MethodCode code = new MethodCode();
		// if (!(handle instanceof Long)) return _readLine();
		code.aload(0);
		code.instanceOf(this.longClass);
		MethodCode.Label ifStream = code.newLabel();
		code.ifne(ifStream);
		code.invokestatic(this.readLineHelper);
		code.areturn();
		code.labelBinding(ifStream);
		// line = ((BufferedReader) _streams[(int) handle]).readLine();
		code.getstatic(this.streamsField);
		code.aload(0);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.aaload();
		if (this.sockets != null) {
			// if (entry instanceof Socket) return _sockReadLine(entry);
			code.astore(1);
			code.aload(1);
			code.instanceOf(this.sockets.socketClass());
			MethodCode.Label ifNotSocket = code.newLabel();
			code.ifeq(ifNotSocket);
			code.aload(1);
			code.invokestatic(this.sockets.sockReadLine());
			code.areturn();
			code.labelBinding(ifNotSocket);
			code.aload(1);
		}
		MethodCode.@Nullable Label afterIoLine = null;
		if (this.ioStreams != null) {
			// A bidirectional stream reads its line off the shared cursor. End of file
			// is ready()'s answer, not a null line: the travelling class cannot spell
			// @Nullable (.kb/jvm-export.md).
			code.astore(2);
			code.aload(2);
			code.instanceOf(this.ioStreams.type());
			MethodCode.Label notIo = code.newLabel();
			code.ifeq(notIo);
			code.aload(2);
			code.checkcast(this.ioStreams.type());
			code.invokevirtual(this.ioStreams.ready());
			MethodCode.Label haveLine = code.newLabel();
			code.ifne(haveLine);
			code.aconst_null();
			code.areturn();
			code.labelBinding(haveLine);
			code.aload(2);
			code.checkcast(this.ioStreams.type());
			code.invokevirtual(this.ioStreams.readLine());
			code.astore(1);
			afterIoLine = code.newLabel();
			code.goto_(afterIoLine);
			code.labelBinding(notIo);
			code.aload(2);
		}
		code.checkcast(this.bufferedReaderClass);
		code.invokestatic(this.lfLine);
		code.astore(1);
		if (afterIoLine != null) {
			code.labelBinding(afterIoLine);
		}
		// if (line == null) return null;
		code.aload(1);
		MethodCode.Label ifLine = code.newLabel();
		code.ifnonnull(ifLine);
		code.aconst_null();
		code.areturn();
		code.labelBinding(ifLine);
		// return "\"".concat(line).concat("\"");
		code.ldc(this.quoteStr);
		code.aload(1);
		code.invokevirtual(this.stringConcat);
		code.ldc(this.quoteStr);
		code.invokevirtual(this.stringConcat);
		code.areturn();
		return code;
	}

	/**
	 * {@code _readLinePair(Object handle) -> Object}: the line {@code _readLineStream}
	 * reads, as the cons {@code (line . missing-newline-p)} -- {@code "T"} when end of
	 * file ended the line -- or {@code null} (nil) at end of file. What
	 * {@code %read-line-pair}, the read under a {@code read-line} producer's
	 * multiple-value lowering, compiles to. A {@code BufferedReader} is read a character
	 * at a time so the terminator is seen ({@code readLine} drops it); only {@code \n}
	 * ends a line and one {@code \r} before it or the end is dropped, as in
	 * {@code _lfLine} (the WASM backends' rule). A bidirectional stream reports its own
	 * terminator, a socket reads through {@code _sockReadLinePair}.
	 */
	private MethodCode buildReadLinePair(LinePairs lp) {
		// Slots: 0=handle, 1=sb (StringBuilder), 2=missing (int), 3=r (BufferedReader),
		// 4=c (int), 5=next (int), 6=entry, 7=io line (String)
		MethodCode code = new MethodCode();
		if (this.sockets != null || this.ioStreams != null) {
			code.aload(0);
			code.instanceOf(this.longClass);
			MethodCode.Label notHandle = code.newLabel();
			code.ifeq(notHandle);
			code.getstatic(this.streamsField);
			code.aload(0);
			code.checkcast(this.longClass);
			code.invokevirtual(this.longValue);
			code.l2i();
			code.aaload();
			code.astore(6);
			JvmSocketRuntimeBuilder.SocketRuntime socketRuntime = this.sockets;
			if (socketRuntime != null) {
				// if (entry instanceof Socket) return _sockReadLinePair(entry);
				code.aload(6);
				code.instanceOf(socketRuntime.socketClass());
				MethodCode.Label notSocket = code.newLabel();
				code.ifeq(notSocket);
				code.aload(6);
				code.invokestatic(Objects.requireNonNull(socketRuntime.sockReadLinePair()));
				code.areturn();
				code.labelBinding(notSocket);
			}
			IoStreams io = this.ioStreams;
			if (io != null) {
				// if (entry instanceof RontoIoFileStream) { if (!ready()) return null;
				// line = readLine(); return {line, lastLineMissingNewline() ? T : nil}; }
				code.aload(6);
				code.instanceOf(io.type());
				MethodCode.Label notIo = code.newLabel();
				code.ifeq(notIo);
				code.aload(6);
				code.checkcast(io.type());
				code.invokevirtual(io.ready());
				MethodCode.Label ioReady = code.newLabel();
				code.ifne(ioReady);
				code.aconst_null();
				code.areturn();
				code.labelBinding(ioReady);
				code.aload(6);
				code.checkcast(io.type());
				code.invokevirtual(io.readLine());
				code.astore(7);
				code.aload(6);
				code.checkcast(io.type());
				code.invokevirtual(Objects.requireNonNull(lp.ioMissingNewline()));
				code.istore(2);
				emitLinePair(code, () -> code.aload(7));
				code.labelBinding(notIo);
			}
			code.labelBinding(notHandle);
		}
		emitResolveReader(code);
		// c = r.read(); if (c < 0) return null;
		code.aload(3);
		code.invokevirtual(this.bufferedReaderRead);
		code.istore(4);
		code.iload(4);
		MethodCode.Label haveChar = code.newLabel();
		code.ifge(haveChar);
		code.aconst_null();
		code.areturn();
		code.labelBinding(haveChar);
		// sb = new StringBuilder();
		code.new_(lp.stringBuilderClass());
		code.dup();
		code.invokespecial(lp.stringBuilderInit());
		code.astore(1);
		// while (c >= 0 && c != '\n') { sb.append((char) c); c = r.read(); }
		MethodCode.Label loop = code.newBoundLabel();
		MethodCode.Label ended = code.newLabel();
		code.iload(4);
		code.iflt(ended);
		code.iload(4);
		code.loadConstant('\n');
		code.if_icmpeq(ended);
		code.aload(1);
		code.iload(4);
		code.i2c();
		code.invokevirtual(lp.appendChar());
		code.pop();
		code.aload(3);
		code.invokevirtual(this.bufferedReaderRead);
		code.istore(4);
		code.goto_(loop);
		code.labelBinding(ended);
		// missing = c < 0; the line drops one trailing '\r' (a '\n' alone ends a line)
		code.iload(4);
		MethodCode.Label terminated = code.newLabel();
		code.ifge(terminated);
		code.iconst_1();
		code.istore(2);
		MethodCode.Label flagged = code.newLabel();
		code.goto_(flagged);
		code.labelBinding(terminated);
		code.iconst_0();
		code.istore(2);
		code.labelBinding(flagged);
		JvmRuntimeBuilder.emitStripTrailingCr(code, this.cp, 1, 5);
		emitLinePair(code, () -> {
			code.aload(1);
			code.invokevirtual(lp.builderToString());
		});
		return code;
	}

	/**
	 * Emits {@code return new Object[] {"\"" + line + "\"", missing != 0 ? "T" : null};}
	 * with the raw line pushed by {@code line} and the flag in slot 2.
	 */
	private void emitLinePair(MethodCode code, Runnable line) {
		code.iconst_2();
		code.anewarray(this.objectClass);
		code.dup();
		code.iconst_0();
		code.ldc(this.quoteStr);
		line.run();
		code.invokevirtual(this.stringConcat);
		code.ldc(this.quoteStr);
		code.invokevirtual(this.stringConcat);
		code.aastore();
		code.dup();
		code.iconst_1();
		code.iload(2);
		MethodCode.Label endedByEof = code.newLabel();
		code.ifne(endedByEof);
		code.aconst_null();
		MethodCode.Label stored = code.newLabel();
		code.goto_(stored);
		code.labelBinding(endedByEof);
		code.ldc(this.tStr);
		code.labelBinding(stored);
		code.aastore();
		code.areturn();
	}

	/**
	 * {@code _readByte(Object handle, Object eofErrorP, Object eofValue) -> Object}.
	 * Reads one byte from the binary input stream in the table; on EOF returns
	 * {@code eofValue} when {@code eofErrorP} is nil, otherwise throws. A byte is a boxed
	 * {@code Long} 0-255.
	 *
	 * <p>
	 * A non-handle designator -- nil, or the {@code t} an unbound
	 * {@code *standard-input*} reads as -- reads the process standard input, so
	 * {@code (read-byte *standard-input*)} moves raw octets through stdin. That is
	 * {@code System.in} directly, NOT the {@code _stdinReader} the character reads share:
	 * mixing text and byte reads on one stream is out of contract on every backend, and a
	 * shared reader would buffer ahead and swallow bytes.
	 *
	 * <p>
	 * A numeric handle is ALWAYS a table index here, never a WASI-style file descriptor:
	 * the table reserves 0/1/2 only when the program names {@code *error-output*}
	 * ({@code JvmLispCompiler}'s {@code <clinit>} seeds {@code _streamCount = 3} under
	 * that same gate), so in every other program the first three streams a program opens
	 * ARE handles 0, 1 and 2. Reading handle 0 as standard input therefore hijacked a
	 * real file/socket -- which is what it did to the cl-postgres handshake.
	 */
	private MethodCode buildReadByte() {
		// Slots: 0=handle, 1=eofErrorP, 2=eofValue, 3=in (InputStream), 4=b (int)
		MethodCode code = new MethodCode();
		// if (!(handle instanceof Long)) in = System.in;
		code.aload(0);
		code.instanceOf(this.longClass);
		MethodCode.Label ifHandle = code.newLabel();
		code.ifeq(ifHandle);
		// in = (InputStream) _streams[(int) ((Long) handle).longValue()];
		// (a Socket entry contributes its input stream instead)
		code.getstatic(this.streamsField);
		code.aload(0);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.aaload();
		MethodCode.@Nullable Label afterIoByte = null;
		if (this.ioStreams != null) {
			// A bidirectional stream is neither an InputStream nor an OutputStream: the
			// octet comes off the one cursor its character reads and writes share.
			code.astore(3);
			code.aload(3);
			code.instanceOf(this.ioStreams.type());
			MethodCode.Label notIoByte = code.newLabel();
			code.ifeq(notIoByte);
			code.aload(3);
			code.checkcast(this.ioStreams.type());
			code.invokevirtual(this.ioStreams.readByte());
			code.istore(4);
			afterIoByte = code.newLabel();
			code.goto_(afterIoByte);
			code.labelBinding(notIoByte);
			code.aload(3);
		}
		if (this.sockets != null) {
			code.astore(3);
			code.aload(3);
			code.instanceOf(this.sockets.socketClass());
			MethodCode.Label ifNotSocket = code.newLabel();
			code.ifeq(ifNotSocket);
			code.aload(3);
			code.checkcast(this.sockets.socketClass());
			code.invokevirtual(this.sockets.socketGetInputStream());
			code.astore(3);
			MethodCode.Label gotoRead = code.newLabel();
			code.goto_(gotoRead);
			code.labelBinding(ifNotSocket);
			code.aload(3);
			code.checkcast(this.inputStreamClass);
			code.astore(3);
			code.labelBinding(gotoRead);
		}
		else {
			code.checkcast(this.inputStreamClass);
			code.astore(3);
		}
		MethodCode.Label gotoStdRead = code.newLabel();
		code.goto_(gotoStdRead);
		code.labelBinding(ifHandle);
		code.getstatic(this.systemIn);
		code.astore(3);
		code.labelBinding(gotoStdRead);
		// b = in.read();
		code.aload(3);
		code.invokevirtual(this.inputStreamRead);
		code.istore(4);
		if (afterIoByte != null) {
			code.labelBinding(afterIoByte);
		}
		// if (b >= 0) { advance the file stream's position; return Long.valueOf((long)
		// b); }
		code.iload(4);
		MethodCode.Label ifEof = code.newLabel();
		code.iflt(ifEof);
		emitBumpPosition(code, 0, () -> code.iconst_1());
		code.iload(4);
		code.i2l();
		code.invokestatic(this.longValueOf);
		code.areturn();
		code.labelBinding(ifEof);
		// if (eofErrorP == null) return eofValue;
		code.aload(1);
		MethodCode.Label ifThrow = code.newLabel();
		code.ifnonnull(ifThrow);
		code.aload(2);
		code.areturn();
		code.labelBinding(ifThrow);
		// throw new RuntimeException("read-byte: end of file");
		code.new_(this.runtimeExceptionClass);
		code.dup();
		code.ldc(this.eofStr);
		code.invokespecial(this.runtimeExceptionInit);
		code.athrow();
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
	private MethodCode buildReadChar() {
		// Slots: 0=handle, 1=eofErrorP, 2=eofValue, 3=r (BufferedReader), 4=c (int),
		// 5=low (int), 6=entry/char (socket programs only)
		MethodCode code = new MethodCode();
		MethodCode.@Nullable Label ifSocketEof = null;
		if (this.sockets != null) {
			// if (handle instanceof Long && _streams[idx] instanceof Socket) {
			// c = _sockReadChar(entry); if (c != null) return c; goto EOF; }
			// A socket entry is a raw Socket, never the BufferedReader the resolver
			// below casts to -- and it must stay one: a Reader would buffer ahead and
			// swallow bytes a following read-byte/read-line owes the caller.
			code.aload(0);
			code.instanceOf(this.longClass);
			MethodCode.Label ifNotHandle = code.newLabel();
			code.ifeq(ifNotHandle);
			code.getstatic(this.streamsField);
			code.aload(0);
			code.checkcast(this.longClass);
			code.invokevirtual(this.longValue);
			code.l2i();
			code.aaload();
			code.astore(6);
			code.aload(6);
			code.instanceOf(this.sockets.socketClass());
			MethodCode.Label ifNotSocket = code.newLabel();
			code.ifeq(ifNotSocket);
			code.aload(6);
			code.invokestatic(this.sockets.sockReadChar());
			code.astore(6);
			code.aload(6);
			ifSocketEof = code.newLabel();
			code.ifnull(ifSocketEof);
			code.aload(6);
			code.areturn();
			code.labelBinding(ifNotHandle);
			code.labelBinding(ifNotSocket);
		}
		emitIoCharArm(code, false, 4);
		emitResolveReader(code);
		// READ: c = r.read();
		code.aload(3);
		code.invokevirtual(this.bufferedReaderRead);
		code.istore(4);
		// if (c < 0) goto EOF;
		code.iload(4);
		MethodCode.Label ifEof = code.newLabel();
		code.iflt(ifEof);
		// if (!Character.isHighSurrogate((char) c)) goto BOX;
		code.iload(4);
		code.i2c();
		code.invokestatic(this.characterIsHighSurrogate);
		MethodCode.Label ifNotHigh = code.newLabel();
		code.ifeq(ifNotHigh);
		// r.mark(1);
		code.aload(3);
		code.iconst_1();
		code.invokevirtual(this.bufferedReaderMark);
		// low = r.read();
		code.aload(3);
		code.invokevirtual(this.bufferedReaderRead);
		code.istore(5);
		// if (low < 0) goto BOX; -- EOF on the low half, keep the raw surrogate.
		code.iload(5);
		MethodCode.Label ifLowEof = code.newLabel();
		code.iflt(ifLowEof);
		// if (!Character.isLowSurrogate((char) low)) goto RESET;
		code.iload(5);
		code.i2c();
		code.invokestatic(this.characterIsLowSurrogate);
		MethodCode.Label ifNotLow = code.newLabel();
		code.ifeq(ifNotLow);
		// c = Character.toCodePoint((char) c, (char) low);
		code.iload(4);
		code.i2c();
		code.iload(5);
		code.i2c();
		code.invokestatic(this.characterToCodePoint);
		code.istore(4);
		MethodCode.Label gotoBoxAfterCombine = code.newLabel();
		code.goto_(gotoBoxAfterCombine);
		// RESET: r.reset(); goto BOX (the raw high surrogate stays in c).
		code.labelBinding(ifNotLow);
		code.aload(3);
		code.invokevirtual(this.bufferedReaderReset);
		// BOX: return int[1]{c} -- the runtime CHARACTER representation.
		code.labelBinding(ifNotHigh);
		code.labelBinding(ifLowEof);
		code.labelBinding(gotoBoxAfterCombine);
		emitBoxCodePoint(code, 4);
		// EOF: if (eofErrorP == null) return eofValue; -- the socket arm's nil (peer
		// closed) lands here too, so a socket answers the same eof contract as a file.
		code.labelBinding(ifEof);
		if (ifSocketEof != null) {
			code.labelBinding(ifSocketEof);
		}
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
	private MethodCode buildPeekChar() {
		// Slots: 0=handle, 1=eofErrorP, 2=eofValue, 3=r (BufferedReader), 4=c (int),
		// 5=low (int)
		MethodCode code = new MethodCode();
		emitIoCharArm(code, true, 4);
		emitResolveReader(code);
		// r.mark(2);
		code.aload(3);
		code.iconst_2();
		code.invokevirtual(this.bufferedReaderMark);
		// c = r.read();
		code.aload(3);
		code.invokevirtual(this.bufferedReaderRead);
		code.istore(4);
		// if (c < 0) goto EOF;
		code.iload(4);
		MethodCode.Label ifEof = code.newLabel();
		code.iflt(ifEof);
		// if (!Character.isHighSurrogate((char) c)) goto BOX;
		code.iload(4);
		code.i2c();
		code.invokestatic(this.characterIsHighSurrogate);
		MethodCode.Label ifNotHigh = code.newLabel();
		code.ifeq(ifNotHigh);
		// low = r.read();
		code.aload(3);
		code.invokevirtual(this.bufferedReaderRead);
		code.istore(5);
		// if (low < 0) goto BOX;
		code.iload(5);
		MethodCode.Label ifLowEof = code.newLabel();
		code.iflt(ifLowEof);
		// if (!Character.isLowSurrogate((char) low)) goto BOX;
		code.iload(5);
		code.i2c();
		code.invokestatic(this.characterIsLowSurrogate);
		MethodCode.Label ifNotLow = code.newLabel();
		code.ifeq(ifNotLow);
		// c = Character.toCodePoint((char) c, (char) low);
		code.iload(4);
		code.i2c();
		code.iload(5);
		code.i2c();
		code.invokestatic(this.characterToCodePoint);
		code.istore(4);
		// BOX: r.reset(); return int[1]{c}.
		code.labelBinding(ifNotHigh);
		code.labelBinding(ifLowEof);
		code.labelBinding(ifNotLow);
		code.aload(3);
		code.invokevirtual(this.bufferedReaderReset);
		emitBoxCodePoint(code, 4);
		// EOF: rewind (the mark is still live) and take the eof branch.
		code.labelBinding(ifEof);
		code.aload(3);
		code.invokevirtual(this.bufferedReaderReset);
		emitCharEof(code);
		return code;
	}

	/** {@code return int[1]{slot}} -- the runtime CHARACTER representation. */
	private static void emitBoxCodePoint(MethodCode code, int slot) {
		code.iconst_1();
		code.newarray(TypeKind.INT);
		code.dup();
		code.iconst_0();
		code.iload(slot);
		code.iastore();
		code.areturn();
	}

	/**
	 * The shared end-of-file tail of {@code _readChar}/{@code _peekChar}: return
	 * {@code eofValue} when {@code eofErrorP} is nil, otherwise throw. The throw is a
	 * BACKSTOP -- every compiled call site whose eof-error-p can be true is rewritten to
	 * the typed {@code end-of-file} signal by
	 * {@code LispMacroExpander.expandReadEofSignal}, which reaches the helper with a nil
	 * eof-error-p and decides in Lisp.
	 */
	private void emitCharEof(MethodCode code) {
		code.aload(1);
		MethodCode.Label ifThrow = code.newLabel();
		code.ifnonnull(ifThrow);
		code.aload(2);
		code.areturn();
		code.labelBinding(ifThrow);
		code.new_(this.runtimeExceptionClass);
		code.dup();
		code.ldc(this.charEofStr);
		code.invokespecial(this.runtimeExceptionInit);
		code.athrow();
	}

	/**
	 * The BIDIRECTIONAL arm of {@code _readChar} / {@code _peekChar}, emitted ahead of
	 * {@link #emitResolveReader} because such a stream is not a {@code BufferedReader}:
	 * it decodes the UTF-8 sequence at the one cursor its writes and
	 * {@code file-position} share. Falls through with an empty stack when the handle is
	 * anything else.
	 * @param code the body being built
	 * @param peek whether the character is left in place
	 * @param cpSlot the local the code point goes in
	 */
	private void emitIoCharArm(MethodCode code, boolean peek, int cpSlot) {
		if (this.ioStreams == null) {
			return;
		}
		code.aload(0);
		code.instanceOf(this.longClass);
		MethodCode.Label notHandle = code.newLabel();
		code.ifeq(notHandle);
		code.getstatic(this.streamsField);
		code.aload(0);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.aaload();
		code.dup();
		code.instanceOf(this.ioStreams.type());
		MethodCode.Label notIo = code.newLabel();
		code.ifeq(notIo);
		code.checkcast(this.ioStreams.type());
		code.invokevirtual((peek ? this.ioStreams.peekCodePoint() : this.ioStreams.readCodePoint()));
		code.istore(cpSlot);
		code.iload(cpSlot);
		MethodCode.Label ioEof = code.newLabel();
		code.iflt(ioEof);
		emitBoxCodePoint(code, cpSlot);
		code.labelBinding(ioEof);
		emitCharEof(code);
		code.labelBinding(notIo);
		code.pop();
		code.labelBinding(notHandle);
	}

	/**
	 * Resolves the stream argument in slot 0 into a {@code BufferedReader} in slot 3: a
	 * non-handle designator ({@code null} = nil, {@code "T"} = t) is standard input
	 * (lazily initializing the {@code _stdinReader} field the {@code _readLine} helper
	 * shares), otherwise the table entry.
	 */
	private void emitResolveReader(MethodCode code) {
		// if (handle instanceof Long) goto STREAM;
		code.aload(0);
		code.instanceOf(this.longClass);
		MethodCode.Label ifStream = code.newLabel();
		code.ifne(ifStream);
		// if (_stdinReader == null) _stdinReader = new BufferedReader(new
		// InputStreamReader(System.in));
		code.getstatic(this.stdinReaderField);
		MethodCode.Label ifHave = code.newLabel();
		code.ifnonnull(ifHave);
		code.new_(this.bufferedReaderClass);
		code.dup();
		code.new_(this.inputStreamReaderClass);
		code.dup();
		code.getstatic(this.systemIn);
		code.invokespecial(this.inputStreamReaderInit);
		code.invokespecial(this.bufferedReaderInit);
		code.putstatic(this.stdinReaderField);
		code.labelBinding(ifHave);
		// r = _stdinReader; goto READ;
		code.getstatic(this.stdinReaderField);
		code.astore(3);
		MethodCode.Label gotoRead = code.newLabel();
		code.goto_(gotoRead);
		code.labelBinding(ifStream);
		// STREAM: r = (BufferedReader) _streams[(int) ((Long) handle).longValue()];
		code.getstatic(this.streamsField);
		code.aload(0);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.aaload();
		code.checkcast(this.bufferedReaderClass);
		code.astore(3);
		code.labelBinding(gotoRead);
	}

	/**
	 * {@code _writeByte(Object byteObj, Object handle) -> byteObj}. Writes one raw byte
	 * to the binary output stream in the table.
	 *
	 * <p>
	 * The designator mirror of {@link #buildReadByte}: a non-handle -- nil, or the
	 * {@code t} an unbound {@code *standard-output*} reads as -- writes to
	 * {@code System.out}, the very {@code PrintStream} the print family writes through,
	 * so binary output and {@code princ} cannot reorder. A numeric handle stays a table
	 * index, for the reason spelled out on {@link #buildReadByte}. The one handle that IS
	 * a standard stream is 2 ({@code *error-output*}) and only under the
	 * {@code emitStderrBranch} gate -- which is exactly the condition under which the
	 * table reserves 0/1/2 in the first place.
	 */
	private MethodCode buildWriteByte() {
		// Slots: 0=byteObj, 1=handle, 2=out (OutputStream)
		MethodCode code = new MethodCode();
		// The *error-output* designator: System.err.write(b); return byteObj;
		emitStderrBranch(code, 1, () -> {
			code.getstatic(java.util.Objects.requireNonNull(this.systemErr));
			emitByteValue(code);
			code.invokevirtual(this.outputStreamWrite);
			code.aload(0);
			code.areturn();
		});
		// if (!(handle instanceof Long)) out = System.out;
		code.aload(1);
		code.instanceOf(this.longClass);
		MethodCode.Label ifHandle = code.newLabel();
		code.ifeq(ifHandle);
		// out = (OutputStream) _streams[(int) ((Long) handle).longValue()];
		// (a Socket entry contributes its output stream instead)
		code.getstatic(this.streamsField);
		code.aload(1);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.aaload();
		if (this.ioStreams != null) {
			// A bidirectional stream writes the octet at its own cursor and answers.
			code.astore(2);
			code.aload(2);
			code.instanceOf(this.ioStreams.type());
			MethodCode.Label notIoByte = code.newLabel();
			code.ifeq(notIoByte);
			code.aload(2);
			code.checkcast(this.ioStreams.type());
			emitByteValue(code);
			code.invokevirtual(this.ioStreams.writeByte());
			code.aload(0);
			code.areturn();
			code.labelBinding(notIoByte);
			code.aload(2);
		}
		if (this.sockets != null) {
			code.astore(2);
			code.aload(2);
			code.instanceOf(this.sockets.socketClass());
			MethodCode.Label ifNotSocket = code.newLabel();
			code.ifeq(ifNotSocket);
			code.aload(2);
			code.checkcast(this.sockets.socketClass());
			code.invokevirtual(this.sockets.socketGetOutputStream());
			code.astore(2);
			MethodCode.Label gotoWrite = code.newLabel();
			code.goto_(gotoWrite);
			code.labelBinding(ifNotSocket);
			code.aload(2);
			code.checkcast(this.outputStreamClass);
			code.astore(2);
			code.labelBinding(gotoWrite);
		}
		else {
			code.checkcast(this.outputStreamClass);
			code.astore(2);
		}
		MethodCode.Label gotoWriteByte = code.newLabel();
		code.goto_(gotoWriteByte);
		code.labelBinding(ifHandle);
		code.getstatic(this.systemOut);
		// The cast is what makes the two paths MERGE as an OutputStream: without it the
		// frame joins PrintStream with OutputStream as Object and the verifier rejects
		// the write below.
		code.checkcast(this.outputStreamClass);
		code.astore(2);
		// _col = b ^ '\n' -- zero exactly when the octet just written IS a newline,
		// which is the only thing the field means (fresh-line tests it against zero).
		// A raw byte moves the standard-output column like a character does, or a
		// (write-byte 10 t) followed by fresh-line would emit a second newline here
		// while the interpreter emits none. Branchless, so the frame stays flat.
		emitByteValue(code);
		code.loadConstant(10);
		code.ixor();
		code.putstatic(this.colField);
		code.labelBinding(gotoWriteByte);
		// out.write((int) ((Long) byteObj).longValue()); return byteObj;
		code.aload(2);
		emitByteValue(code);
		code.invokevirtual(this.outputStreamWrite);
		// advance a file stream's position after the byte lands on it (stdout and any
		// other non-file designator no-op inside _bumpStreamPosition)
		emitBumpPosition(code, 1, () -> code.iconst_1());
		code.aload(0);
		code.areturn();
		return code;
	}

	/**
	 * Pushes {@code (int) ((Long) byteObj).longValue()} -- slot 0 of {@code _writeByte}.
	 */
	private void emitByteValue(MethodCode code) {
		code.aload(0);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
	}

	/**
	 * Emits a {@code _bumpStreamPosition(handle, delta)} call after a byte transfer, when
	 * the program has asked for {@code file-position} at all. {@code pushDelta} leaves
	 * the {@code int} byte count on the stack; the helper itself no-ops for any stream
	 * that is not a binary file stream, so the call sites need no guard.
	 */
	private void emitBumpPosition(MethodCode code, int handleSlot, Runnable pushDelta) {
		if (!this.fileMeta.position()) {
			return;
		}
		code.aload(handleSlot);
		pushDelta.run();
		code.invokestatic(java.util.Objects.requireNonNull(this.bumpStreamPositionRef));
		code.pop();
	}

	/**
	 * {@code _readSeqChars(Object seq, Object handle, Object start, Object end) -> Object}:
	 * the bulk CHARACTER transfer behind {@code read-sequence} over a character buffer
	 * ({@code .kb/character-sequence-io.md}). The buffer is a mutable character vector --
	 * an {@code ArrayList} whose slot 0 is the LENGTH-4 header and whose elements follow
	 * from slot 1 ({@code .kb/adjustable-arrays.md}) -- and the stream a
	 * {@code BufferedReader} table entry or the standard-stream designator; anything else
	 * answers {@code null}, "declined", and the expansion's per-character loop takes
	 * over.
	 *
	 * <p>
	 * Each round asks the reader for exactly as many UTF-16 units as there are code
	 * points still wanted, which can never overshoot: a code point is one unit or two, so
	 * N units hold at most N of them. A surrogate pair SPLIT by the end of a block is
	 * completed by one more read behind a {@code mark(1)}, and a high half followed by
	 * anything else is its own character with the unit after it put back -- the answer
	 * {@code _readChar} gives for the same input, element for element. {@code start} /
	 * {@code end} are boxed {@code Long}s or {@code null} (0 / the buffer's length); a
	 * range outside the buffer declines rather than throwing, so the loop signals exactly
	 * as it did.
	 */
	private MethodCode buildReadSeqChars() {
		CharSequenceIo io = java.util.Objects.requireNonNull(this.charSequenceIo);
		MethodCode a = new MethodCode();
		// Slots: 0=seq, 1=handle, 2=start, 3=end, 4=list, 5=header, 6=len, 7=s, 8=e,
		// 9=r (BufferedReader), 10=block (char[]), 11=at, 12=n, 13=k, 14=c, 15=low,
		// 16=entry
		final int SEQ = 0, HANDLE = 1, START = 2, END = 3, LIST = 4, HEADER = 5, LEN = 6, S = 7, E = 8, R = 9,
				BLOCK = 10, AT = 11, N = 12, K = 13, C = 14, LOW = 15, ENTRY = 16;
		final int BLOCK_UNITS = 8192;
		MethodCode.Label declined = a.newLabel();
		// --- the buffer: the length-4 header is the character-vector marker ----------
		a.aload(SEQ);
		a.instanceOf(io.arrayListClass());
		a.ifeq(declined);
		a.aload(SEQ);
		a.checkcast(io.arrayListClass());
		a.astore(LIST);
		a.aload(LIST);
		a.loadConstant(0);
		a.invokevirtual(io.listGet());
		a.instanceOf(io.objectArrayClass());
		a.ifeq(declined);
		a.aload(LIST);
		a.loadConstant(0);
		a.invokevirtual(io.listGet());
		a.checkcast(io.objectArrayClass());
		a.astore(HEADER);
		a.aload(HEADER);
		a.arraylength();
		a.loadConstant(4);
		a.if_icmpne(declined);
		// len = the fill pointer when there is one, else dimension 0 -- what (length seq)
		// answers, which is the bound the loop this replaces reads.
		MethodCode.Label useDim = a.newLabel();
		MethodCode.Label haveLen = a.newLabel();
		a.aload(HEADER);
		a.loadConstant(1);
		a.aaload();
		a.ifnull(useDim);
		a.aload(HEADER);
		a.loadConstant(1);
		a.aaload();
		a.checkcast(this.longClass);
		a.invokevirtual(this.longValue);
		a.l2i();
		a.istore(LEN);
		a.goto_(haveLen);
		a.labelBinding(useDim);
		a.aload(HEADER);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(io.objectArrayClass());
		a.loadConstant(0);
		a.aaload();
		a.checkcast(this.longClass);
		a.invokevirtual(this.longValue);
		a.l2i();
		a.istore(LEN);
		a.labelBinding(haveLen);
		// --- the bounds -------------------------------------------------------------
		emitBoundArg(a, START, S, () -> a.loadConstant(0));
		emitBoundArg(a, END, E, () -> a.iload(LEN));
		a.iload(S);
		a.iflt(declined);
		a.iload(E);
		a.iload(LEN);
		a.if_icmpgt(declined);
		a.iload(S);
		a.iload(E);
		a.if_icmpgt(declined);
		// --- the stream: a text table entry, or standard input ----------------------
		MethodCode.Label stdin = a.newLabel();
		MethodCode.Label haveReader = a.newLabel();
		a.aload(HANDLE);
		a.instanceOf(this.longClass);
		a.ifeq(stdin);
		a.getstatic(this.streamsField);
		a.aload(HANDLE);
		a.checkcast(this.longClass);
		a.invokevirtual(this.longValue);
		a.l2i();
		a.aaload();
		a.astore(ENTRY);
		a.aload(ENTRY);
		a.instanceOf(this.bufferedReaderClass);
		a.ifeq(declined);
		a.aload(ENTRY);
		a.checkcast(this.bufferedReaderClass);
		a.astore(R);
		a.goto_(haveReader);
		a.labelBinding(stdin);
		MethodCode.Label haveStdin = a.newLabel();
		a.getstatic(this.stdinReaderField);
		a.ifnonnull(haveStdin);
		a.new_(this.bufferedReaderClass);
		a.dup();
		a.new_(this.inputStreamReaderClass);
		a.dup();
		a.getstatic(this.systemIn);
		a.invokespecial(this.inputStreamReaderInit);
		a.invokespecial(this.bufferedReaderInit);
		a.putstatic(this.stdinReaderField);
		a.labelBinding(haveStdin);
		a.getstatic(this.stdinReaderField);
		a.astore(R);
		a.labelBinding(haveReader);
		// --- the transfer: block by block, at walks the code points from s to e ------
		a.iload(E);
		a.iload(S);
		a.isub();
		a.istore(N);
		MethodCode.Label blockSized = a.newLabel();
		a.iload(N);
		a.loadConstant(BLOCK_UNITS);
		a.if_icmple(blockSized);
		a.loadConstant(BLOCK_UNITS);
		a.istore(N);
		a.labelBinding(blockSized);
		a.iload(N);
		a.newarray(TypeKind.CHAR);
		a.astore(BLOCK);
		a.iload(S);
		a.istore(AT);
		MethodCode.Label round = a.newLabel();
		MethodCode.Label done = a.newLabel();
		MethodCode.Label unit = a.newLabel();
		a.labelBinding(round);
		a.iload(AT);
		a.iload(E);
		a.if_icmpge(done);
		// n = r.read(block, 0, min(block.length, e - at))
		a.aload(BLOCK);
		a.arraylength();
		a.istore(N);
		MethodCode.Label wantSized = a.newLabel();
		a.iload(N);
		a.iload(E);
		a.iload(AT);
		a.isub();
		a.if_icmple(wantSized);
		a.iload(E);
		a.iload(AT);
		a.isub();
		a.istore(N);
		a.labelBinding(wantSized);
		a.aload(R);
		a.aload(BLOCK);
		a.loadConstant(0);
		a.iload(N);
		a.invokevirtual(io.readBlock());
		a.istore(N);
		a.iload(N);
		a.iflt(done);
		a.loadConstant(0);
		a.istore(K);
		a.labelBinding(unit);
		a.iload(K);
		a.iload(N);
		a.if_icmpge(round);
		a.iload(AT);
		a.iload(E);
		a.if_icmpge(round);
		a.aload(BLOCK);
		a.iload(K);
		a.caload();
		a.istore(C);
		a.iinc(K, 1);
		MethodCode.Label store = a.newLabel();
		a.iload(C);
		a.i2c();
		a.invokestatic(this.characterIsHighSurrogate);
		a.ifeq(store);
		MethodCode.Label fromBlock = a.newLabel();
		a.iload(K);
		a.iload(N);
		a.if_icmplt(fromBlock);
		// the block ended on the high half: read one more behind a mark
		a.aload(R);
		a.loadConstant(1);
		a.invokevirtual(this.bufferedReaderMark);
		a.aload(R);
		a.invokevirtual(this.bufferedReaderRead);
		a.istore(LOW);
		a.iload(LOW);
		a.iflt(store);
		a.iload(LOW);
		a.i2c();
		a.invokestatic(this.characterIsLowSurrogate);
		MethodCode.Label putBack = a.newLabel();
		a.ifeq(putBack);
		emitCombinePair(a, C, LOW);
		a.goto_(store);
		a.labelBinding(putBack);
		a.aload(R);
		a.invokevirtual(this.bufferedReaderReset);
		a.goto_(store);
		// the low half is the next unit of the block, when it is one
		a.labelBinding(fromBlock);
		a.aload(BLOCK);
		a.iload(K);
		a.caload();
		a.istore(LOW);
		a.iload(LOW);
		a.i2c();
		a.invokestatic(this.characterIsLowSurrogate);
		a.ifeq(store);
		a.iinc(K, 1);
		emitCombinePair(a, C, LOW);
		// list.set(1 + at, new int[]{c}) -- the runtime CHARACTER representation
		a.labelBinding(store);
		a.aload(LIST);
		a.loadConstant(1);
		a.iload(AT);
		a.iadd();
		a.loadConstant(1);
		a.newarray(TypeKind.INT);
		a.dup();
		a.loadConstant(0);
		a.iload(C);
		a.iastore();
		a.invokevirtual(io.listSet());
		a.pop();
		a.iinc(AT, 1);
		a.goto_(unit);
		a.labelBinding(done);
		a.iload(AT);
		a.i2l();
		a.invokestatic(this.longValueOf);
		a.areturn();
		a.labelBinding(declined);
		a.aconst_null();
		a.areturn();
		return a;
	}

	// c = Character.toCodePoint((char) c, (char) low)
	private void emitCombinePair(MethodCode a, int cSlot, int lowSlot) {
		a.iload(cSlot);
		a.i2c();
		a.iload(lowSlot);
		a.i2c();
		a.invokestatic(this.characterToCodePoint);
		a.istore(cSlot);
	}

	// target = arg is nil ? dflt : (int) ((Long) arg).longValue()
	private void emitBoundArg(MethodCode a, int argSlot, int targetSlot, Runnable dflt) {
		MethodCode.Label fromArg = a.newLabel();
		MethodCode.Label have = a.newLabel();
		a.aload(argSlot);
		a.ifnonnull(fromArg);
		dflt.run();
		a.istore(targetSlot);
		a.goto_(have);
		a.labelBinding(fromArg);
		a.aload(argSlot);
		a.checkcast(this.longClass);
		a.invokevirtual(this.longValue);
		a.l2i();
		a.istore(targetSlot);
		a.labelBinding(have);
	}

	/**
	 * {@code _readSeqPacked(Object seq, Object handle, Object start, Object end) -> Object}
	 * and its mirror {@code _writeSeqPacked}: the bulk binary transfer behind
	 * {@code read-sequence} / {@code write-sequence} over a PACKED buffer
	 * ({@code .kb/binary-sequence-io.md}). The buffer is a packed float array of any rank
	 * ({@code float[]} / {@code double[]} with the {@code [rank, dims..., data...]}
	 * header, 4 / 8 bytes per element) or a packed integer vector ({@code long[]} with
	 * its width header, width/8 bytes per element), moved as raw little-endian elements
	 * in ONE {@code readNBytes} / {@code write} through a {@code ByteBuffer} view. The
	 * stream is a binary table entry ({@code InputStream} / {@code OutputStream}) or the
	 * standard-stream designator (a non-handle: {@code System.in} / {@code System.out});
	 * a buffer or stream of any other shape answers {@code null} -- "declined" -- and the
	 * expansion's element loop takes over, so nothing here changes what those cases did.
	 * {@code start} / {@code end} are boxed {@code Long}s or {@code null} (0 / the total
	 * size); a range outside the buffer throws. Read answers the boxed fill position,
	 * write answers {@code seq}.
	 */
	private MethodCode buildSeqPacked(boolean read) {
		PackedSequenceIo io = java.util.Objects.requireNonNull(this.packedSequenceIo);
		// Slots: 0=seq, 1=handle, 2=start, 3=end, 4=width, 5=size, 6=base (the data
		// offset), 7=stream, 8=s, 9=e, 10=bytes, 11=n, 12=bb, 13=k
		final int WIDTH = 4, SIZE = 5, BASE = 6, STREAM = 7, S = 8, E = 9, BYTES = 10, N = 11, BB = 12, K = 13;
		MethodCode code = new MethodCode();
		// --- the buffer shape ---------------------------------------------------
		// if (seq instanceof float[]) { base = 1 + (int) seq[0]; width = 4; size = len -
		// base }
		code.aload(0);
		code.instanceOf(io.floatArrayClass());
		MethodCode.Label ifNotFloat = code.newLabel();
		code.ifeq(ifNotFloat);
		code.aload(0);
		code.checkcast(io.floatArrayClass());
		code.iconst_0();
		code.faload();
		code.f2i();
		code.iconst_1();
		code.iadd();
		code.istore(BASE);
		code.iconst_4();
		code.istore(WIDTH);
		code.aload(0);
		code.checkcast(io.floatArrayClass());
		code.arraylength();
		code.iload(BASE);
		code.isub();
		code.istore(SIZE);
		MethodCode.Label shaped = code.newLabel();
		code.goto_(shaped);
		code.labelBinding(ifNotFloat);
		// else if (seq instanceof double[]) { base = 1 + (int) seq[0]; width = 8; ... }
		code.aload(0);
		code.instanceOf(io.doubleArrayClass());
		MethodCode.Label ifNotDouble = code.newLabel();
		code.ifeq(ifNotDouble);
		code.aload(0);
		code.checkcast(io.doubleArrayClass());
		code.iconst_0();
		code.daload();
		code.d2i();
		code.iconst_1();
		code.iadd();
		code.istore(BASE);
		code.loadConstant(8);
		code.istore(WIDTH);
		code.aload(0);
		code.checkcast(io.doubleArrayClass());
		code.arraylength();
		code.iload(BASE);
		code.isub();
		code.istore(SIZE);
		code.goto_(shaped);
		code.labelBinding(ifNotDouble);
		// else if (seq instanceof short[]) { base = 1 + 2 * (int) seq[0]; width = 2; ...
		// }
		// The bfloat16 width's header takes TWO slots per dimension, because a dimension
		// is an int and a short caps at 32767 (JvmPackedFloatWidth.BFLOAT16) -- the one
		// place in this method where the data offset is not 1 + rank. An element on the
		// wire is the stored pattern itself, so a BF16 tensor reads in with no conversion
		// (.kb/bfloat16.md).
		//
		// UNGATED, unlike the quantized arm below: this arm and its transfer twin are
		// ~45 bytes together, they are emitted only for a program that already does
		// packed bulk I/O, and the scan that would gate them cannot see a bf16 array
		// arriving as a PARAMETER -- the hole the usesFloat16Bits comment in
		// JvmLispCompiler describes, where the author forced the gate on for the same
		// reason. A gate that guessed wrong would not signal: it would decline into the
		// element loop and read a whole tensor one boxed element at a time.
		code.aload(0);
		code.instanceOf(io.shortArrayClass());
		MethodCode.Label ifNotShort = code.newLabel();
		code.ifeq(ifNotShort);
		code.aload(0);
		code.checkcast(io.shortArrayClass());
		code.iconst_0();
		code.saload();
		code.iconst_2();
		code.imul();
		code.iconst_1();
		code.iadd();
		code.istore(BASE);
		code.iconst_2();
		code.istore(WIDTH);
		code.aload(0);
		code.checkcast(io.shortArrayClass());
		code.arraylength();
		code.iload(BASE);
		code.isub();
		code.istore(SIZE);
		code.goto_(shaped);
		code.labelBinding(ifNotShort);
		// else if (seq instanceof long[]) { base = 1; width = (int) seq[0] / 8; size =
		// len - 1 }
		code.aload(0);
		code.instanceOf(io.longArrayClass());
		MethodCode.Label ifNotLong = code.newLabel();
		code.ifeq(ifNotLong);
		code.iconst_1();
		code.istore(BASE);
		code.aload(0);
		code.checkcast(io.longArrayClass());
		code.iconst_0();
		code.laload();
		code.l2i();
		code.iconst_3();
		code.ishr();
		code.istore(WIDTH);
		code.aload(0);
		code.checkcast(io.longArrayClass());
		code.arraylength();
		code.iconst_1();
		code.isub();
		code.istore(SIZE);
		code.goto_(shaped);
		code.labelBinding(ifNotLong);
		// else if (seq instanceof byte[]) -- an (unsigned-byte 8) vector
		// byte[]{8, e0, ...}: { base = 1; width = 1; size = len - 1 }, or, where one can
		// exist, a quantized matrix (told apart by the tag in slot 0).
		code.aload(0);
		code.instanceOf(io.byteArrayClass());
		MethodCode.Label ifNotByte = code.newLabel();
		code.ifeq(ifNotByte);
		MethodCode.@Nullable Label ifQuantized = null;
		if (this.quantizedBuffer) {
			code.aload(0);
			code.checkcast(io.byteArrayClass());
			code.iconst_0();
			code.baload();
			code.loadConstant(JvmIntArrayRuntimeBuilder.OCTET_TAG);
			ifQuantized = code.newLabel();
			code.if_icmpne(ifQuantized);
		}
		code.iconst_1();
		code.istore(BASE);
		code.iconst_1();
		code.istore(WIDTH);
		code.aload(0);
		code.checkcast(io.byteArrayClass());
		code.arraylength();
		code.iconst_1();
		code.isub();
		code.istore(SIZE);
		code.goto_(shaped);
		if (this.quantizedBuffer) {
			// the quantized matrix: { base = 8 + 4 * _qmInt(seq, 4); width = 1; size =
			// len - base } -- its ggml blocks, one byte an element, so a GGUF tensor is
			// one transfer (.kb/quantized-matrix.md).
			code.labelBinding(Objects.requireNonNull(ifQuantized));
			code.aload(0);
			code.checkcast(io.byteArrayClass());
			code.iconst_4();
			code.invokestatic(io.qmInt());
			code.iconst_4();
			code.imul();
			code.loadConstant(8);
			code.iadd();
			code.istore(BASE);
			code.iconst_1();
			code.istore(WIDTH);
			code.aload(0);
			code.checkcast(io.byteArrayClass());
			code.arraylength();
			code.iload(BASE);
			code.isub();
			code.istore(SIZE);
			code.goto_(shaped);
		}
		code.labelBinding(ifNotByte);
		// else return null (not a packed buffer -- declined)
		code.aconst_null();
		code.areturn();
		code.labelBinding(shaped);
		// --- the stream --------------------------------------------------------
		// if (handle instanceof Long) { entry = _streams[idx]; if (!(entry instanceof
		// InputStream/OutputStream)) return null; stream = entry } else stream =
		// System.in/out
		ClassEntry streamClass = read ? this.inputStreamClass : this.outputStreamClass;
		code.aload(1);
		code.instanceOf(this.longClass);
		MethodCode.Label ifNotHandle = code.newLabel();
		code.ifeq(ifNotHandle);
		code.getstatic(this.streamsField);
		code.aload(1);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.aaload();
		code.astore(STREAM);
		code.aload(STREAM);
		code.instanceOf(streamClass);
		MethodCode.Label ifStreamOk = code.newLabel();
		code.ifne(ifStreamOk);
		code.aconst_null();
		code.areturn();
		code.labelBinding(ifStreamOk);
		code.aload(STREAM);
		code.checkcast(streamClass);
		code.astore(STREAM);
		MethodCode.Label gotoStreamed = code.newLabel();
		code.goto_(gotoStreamed);
		code.labelBinding(ifNotHandle);
		code.getstatic(read ? this.systemIn : this.systemOut);
		// The cast is what makes the two paths MERGE as the stream class (a PrintStream
		// joined with an OutputStream would meet at Object and fail the verifier).
		code.checkcast(streamClass);
		code.astore(STREAM);
		code.labelBinding(gotoStreamed);
		// --- the bounds ---------------------------------------------------------
		// s = start == null ? 0 : (int) start ; e = end == null ? size : (int) end
		emitBoundOrDefault(code, 2, () -> code.iconst_0(), S);
		emitBoundOrDefault(code, 3, () -> {
			code.iload(SIZE);
		}, E);
		// if (s < 0 || e > size || s > e) throw new RuntimeException(bounds message)
		code.iload(S);
		MethodCode.Label ifSNeg = code.newLabel();
		code.iflt(ifSNeg);
		code.iload(E);
		code.iload(SIZE);
		MethodCode.Label ifEBig = code.newLabel();
		code.if_icmpgt(ifEBig);
		code.iload(S);
		code.iload(E);
		MethodCode.Label ifBoundsOk = code.newLabel();
		code.if_icmple(ifBoundsOk);
		code.labelBinding(ifSNeg);
		code.labelBinding(ifEBig);
		code.new_(this.runtimeExceptionClass);
		code.dup();
		code.ldc(io.boundsMessage());
		code.invokespecial(this.runtimeExceptionInit);
		code.athrow();
		code.labelBinding(ifBoundsOk);
		// --- the byte buffer ----------------------------------------------------
		if (read) {
			// bytes = in.readNBytes((e - s) * width); n = bytes.length / width
			code.aload(STREAM);
			code.iload(E);
			code.iload(S);
			code.isub();
			code.iload(WIDTH);
			code.imul();
			code.invokevirtual(io.readNBytes());
			code.astore(BYTES);
			code.aload(BYTES);
			code.arraylength();
			code.iload(WIDTH);
			code.idiv();
			code.istore(N);
		}
		else {
			// n = e - s; bytes = new byte[n * width]
			code.iload(E);
			code.iload(S);
			code.isub();
			code.istore(N);
			code.iload(N);
			code.iload(WIDTH);
			code.imul();
			code.newarray(TypeKind.BYTE);
			code.astore(BYTES);
		}
		// bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
		code.aload(BYTES);
		code.invokestatic(io.byteBufferWrap());
		code.getstatic(io.littleEndian());
		code.invokevirtual(io.byteBufferOrder());
		code.astore(BB);
		// --- the transfer, by buffer shape --------------------------------------
		// if (seq instanceof float[]) bb.asFloatBuffer().get/put((float[]) seq, base + s,
		// n)
		code.aload(0);
		code.instanceOf(io.floatArrayClass());
		MethodCode.Label ifNotFloat2 = code.newLabel();
		code.ifeq(ifNotFloat2);
		code.aload(BB);
		code.invokevirtual(io.asFloatBuffer());
		code.aload(0);
		code.checkcast(io.floatArrayClass());
		emitBasePlusS(code, BASE, S);
		code.iload(N);
		code.invokevirtual((read ? io.floatBufferGet() : io.floatBufferPut()));
		code.pop();
		MethodCode.Label moved = code.newLabel();
		code.goto_(moved);
		code.labelBinding(ifNotFloat2);
		// else if (seq instanceof double[]) bb.asDoubleBuffer().get/put(...)
		code.aload(0);
		code.instanceOf(io.doubleArrayClass());
		MethodCode.Label ifNotDouble2 = code.newLabel();
		code.ifeq(ifNotDouble2);
		code.aload(BB);
		code.invokevirtual(io.asDoubleBuffer());
		code.aload(0);
		code.checkcast(io.doubleArrayClass());
		emitBasePlusS(code, BASE, S);
		code.iload(N);
		code.invokevirtual((read ? io.doubleBufferGet() : io.doubleBufferPut()));
		code.pop();
		code.goto_(moved);
		code.labelBinding(ifNotDouble2);
		// else if (seq instanceof byte[]) bb.get/put((byte[]) seq, base + s, n) -- an
		// octet vector or a quantized matrix, whose base the shape arm above already
		// told apart.
		code.aload(0);
		code.instanceOf(io.byteArrayClass());
		MethodCode.Label ifNotByte2 = code.newLabel();
		code.ifeq(ifNotByte2);
		code.aload(BB);
		code.aload(0);
		code.checkcast(io.byteArrayClass());
		emitBasePlusS(code, BASE, S);
		code.iload(N);
		code.invokevirtual((read ? io.bbGetBytes() : io.bbPutBytes()));
		code.pop();
		code.goto_(moved);
		code.labelBinding(ifNotByte2);
		// else if (seq instanceof short[]) bb.asShortBuffer().get/put((short[]) seq,
		// base + s, n) -- the bfloat16 width moves as its stored patterns, in one bulk
		// transfer like the two f32/f64 arms above and unlike the long[] element loop.
		code.aload(0);
		code.instanceOf(io.shortArrayClass());
		MethodCode.Label ifNotShort2 = code.newLabel();
		code.ifeq(ifNotShort2);
		code.aload(BB);
		code.invokevirtual(io.asShortBuffer());
		code.aload(0);
		code.checkcast(io.shortArrayClass());
		emitBasePlusS(code, BASE, S);
		code.iload(N);
		code.invokevirtual((read ? io.shortBufferGet() : io.shortBufferPut()));
		code.pop();
		code.goto_(moved);
		code.labelBinding(ifNotShort2);
		// else (long[]): for (k = 0; k < n; k++) one element of the width
		code.iconst_0();
		code.istore(K);
		MethodCode.Label loopTop = code.newBoundLabel();
		code.iload(K);
		code.iload(N);
		MethodCode.Label ifLoopDone = code.newLabel();
		code.if_icmpge(ifLoopDone);
		if (read) {
			// seq[base + s + k] = (width == 1 ? bb.get() & 0xFF : width == 2 ?
			// bb.getShort() &
			// 0xFFFF : bb.getInt() & 0xFFFFFFFFL)
			code.aload(0);
			code.checkcast(io.longArrayClass());
			emitBasePlusS(code, BASE, S);
			code.iload(K);
			code.iadd();
			emitWidthSwitch(code, WIDTH, () -> {
				code.aload(BB);
				code.invokevirtual(io.bbGet());
				code.loadConstant(0xFF);
				code.iand();
				code.i2l();
			}, () -> {
				code.aload(BB);
				code.invokevirtual(io.bbGetShort());
				code.ldc(this.cp.entries().intEntry(0xFFFF));
				code.iand();
				code.i2l();
			}, () -> {
				code.aload(BB);
				code.invokevirtual(io.bbGetInt());
				code.i2l();
				code.ldc(this.cp.entries().longEntry(0xFFFF_FFFFL));
				code.land();
			});
			code.lastore();
		}
		else {
			// e = seq[base + s + k]; width == 1 ? bb.put((byte) e) : width == 2 ?
			// bb.putShort((short) e) : bb.putInt((int) e)
			code.aload(0);
			code.checkcast(io.longArrayClass());
			emitBasePlusS(code, BASE, S);
			code.iload(K);
			code.iadd();
			code.laload();
			code.l2i();
			code.istore(SIZE); // scratch: size is not read past the bounds check
			emitWidthSwitch(code, WIDTH, () -> {
				code.aload(BB);
				code.iload(SIZE);
				code.i2b();
				code.invokevirtual(io.bbPut());
				code.pop();
			}, () -> {
				code.aload(BB);
				code.iload(SIZE);
				code.i2s();
				code.invokevirtual(io.bbPutShort());
				code.pop();
			}, () -> {
				code.aload(BB);
				code.iload(SIZE);
				code.invokevirtual(io.bbPutInt());
				code.pop();
			});
		}
		code.iinc(K, 1);
		code.goto_(loopTop);
		code.labelBinding(ifLoopDone);
		code.labelBinding(moved);
		if (read) {
			// advance a file stream's position by the bytes just read; return
			// Long.valueOf(s + n)
			emitBumpPosition(code, 1, () -> {
				code.aload(BYTES);
				code.arraylength();
			});
			code.iload(S);
			code.iload(N);
			code.iadd();
			code.i2l();
			code.invokestatic(this.longValueOf);
			code.areturn();
		}
		else {
			// out.write(bytes); standard output tracks the fresh-line column off the
			// last byte (_col = b ^ '\n', as _writeByte does); return seq
			code.aload(STREAM);
			code.aload(BYTES);
			code.invokevirtual(io.outputStreamWriteBytes());
			code.aload(1);
			code.instanceOf(this.longClass);
			MethodCode.Label ifHandleOut = code.newLabel();
			code.ifne(ifHandleOut);
			code.iload(N);
			MethodCode.Label ifEmpty = code.newLabel();
			code.ifle(ifEmpty);
			code.aload(BYTES);
			code.aload(BYTES);
			code.arraylength();
			code.iconst_1();
			code.isub();
			code.baload();
			code.loadConstant(10);
			code.ixor();
			code.putstatic(this.colField);
			code.labelBinding(ifHandleOut);
			code.labelBinding(ifEmpty);
			// advance a file stream's position; return seq
			emitBumpPosition(code, 1, () -> {
				code.aload(BYTES);
				code.arraylength();
			});
			code.aload(0);
			code.areturn();
		}
		return code;
	}

	// slot[target] = (arg == null ? <dflt> : (int) ((Long) arg).longValue())
	private void emitBoundOrDefault(MethodCode code, int argSlot, Runnable dflt, int target) {
		code.aload(argSlot);
		MethodCode.Label ifGiven = code.newLabel();
		code.ifnonnull(ifGiven);
		dflt.run();
		code.istore(target);
		MethodCode.Label gotoDone = code.newLabel();
		code.goto_(gotoDone);
		code.labelBinding(ifGiven);
		code.aload(argSlot);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.istore(target);
		code.labelBinding(gotoDone);
	}

	// Pushes base + s.
	private static void emitBasePlusS(MethodCode code, int base, int s) {
		code.iload(base);
		code.iload(s);
		code.iadd();
	}

	// if (width == 1) one() else if (width == 2) two() else four()
	private static void emitWidthSwitch(MethodCode code, int widthSlot, Runnable one, Runnable two, Runnable four) {
		code.iload(widthSlot);
		code.iconst_1();
		MethodCode.Label ifNotOne = code.newLabel();
		code.if_icmpne(ifNotOne);
		one.run();
		MethodCode.Label done = code.newLabel();
		code.goto_(done);
		code.labelBinding(ifNotOne);
		code.iload(widthSlot);
		code.iconst_2();
		MethodCode.Label ifNotTwo = code.newLabel();
		code.if_icmpne(ifNotTwo);
		two.run();
		code.goto_(done);
		code.labelBinding(ifNotTwo);
		four.run();
		code.labelBinding(done);
	}

	/**
	 * {@code _writeStr(String content, Object handle) -> void}. Writes an
	 * already-rendered content string to the stream (a {@code Writer} table entry), or to
	 * standard output (updating the {@code _col} fresh-line tracking) when the handle is
	 * not a stream handle ({@code null} = nil, {@code "t"} = t). The routing sink of the
	 * print-family optional stream argument and the write-string built-in.
	 */
	private MethodCode buildWriteStr() {
		// Slots: 0=content (String), 1=handle
		MethodCode code = new MethodCode();
		// The *error-output* designator: System.err.print(content); return;
		emitStderrBranch(code, 1, () -> {
			code.getstatic(java.util.Objects.requireNonNull(this.systemErr));
			code.aload(0);
			code.invokevirtual(this.printStr);
			code.return_();
		});
		// if (handle instanceof Long) { ((Writer) _streams[idx]).write(content); return;
		// }
		code.aload(1);
		code.instanceOf(this.longClass);
		MethodCode.Label ifStdout = code.newLabel();
		code.ifeq(ifStdout);
		code.getstatic(this.streamsField);
		code.aload(1);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.aaload();
		code.checkcast(this.writerClass);
		code.aload(0);
		code.invokevirtual(this.writerWrite);
		code.return_();
		code.labelBinding(ifStdout);
		// System.out.print(content);
		code.getstatic(this.systemOut);
		code.aload(0);
		code.invokevirtual(this.printStr);
		// if (content.length() != 0) _col = content.charAt(len - 1) == '\n' ? 0 : 1;
		code.aload(0);
		code.invokevirtual(this.stringLength);
		MethodCode.Label ifEmpty = code.newLabel();
		code.ifeq(ifEmpty);
		code.aload(0);
		code.aload(0);
		code.invokevirtual(this.stringLength);
		code.iconst_1();
		code.isub();
		code.invokevirtual(this.stringCharAt);
		code.loadConstant(10);
		MethodCode.Label ifNotNewline = code.newLabel();
		code.if_icmpne(ifNotNewline);
		code.iconst_0();
		MethodCode.Label gotoStore = code.newLabel();
		code.goto_(gotoStore);
		code.labelBinding(ifNotNewline);
		code.iconst_1();
		code.labelBinding(gotoStore);
		code.putstatic(this.colField);
		code.labelBinding(ifEmpty);
		code.return_();
		return code;
	}

	/**
	 * {@code _writeString(Object str, Object handle) -> str}. Writes the string content
	 * (without the surrounding quotes) to the stream via {@code _writeStr} -- write-line
	 * minus the newline.
	 */
	private MethodCode buildWriteString() {
		// Slots: 0=str, 1=handle, 2=content (String), 3=entry (socket programs only)
		MethodCode code = new MethodCode();
		if (this.sockets != null) {
			// if (handle instanceof Long && _streams[idx] instanceof Socket)
			// return _sockWriteString(str, entry);
			// The arm sits HERE and not in _writeStr, which the print family shares:
			// print/princ to a socket has no dispatch on the --component backend, so
			// widening it here only would ship a program that works on two backends and
			// traps on the third (see .kb/tcp-sockets.md).
			code.aload(1);
			code.instanceOf(this.longClass);
			MethodCode.Label ifNotHandle = code.newLabel();
			code.ifeq(ifNotHandle);
			code.getstatic(this.streamsField);
			code.aload(1);
			code.checkcast(this.longClass);
			code.invokevirtual(this.longValue);
			code.l2i();
			code.aaload();
			code.astore(3);
			code.aload(3);
			code.instanceOf(this.sockets.socketClass());
			MethodCode.Label ifNotSocket = code.newLabel();
			code.ifeq(ifNotSocket);
			code.aload(0);
			code.aload(3);
			code.invokestatic(this.sockets.sockWriteString());
			code.areturn();
			code.labelBinding(ifNotHandle);
			code.labelBinding(ifNotSocket);
		}
		// content = ((String) str).substring(1, length - 1);
		code.aload(0);
		emitStrvOnStack(code);
		code.checkcast(this.stringClass);
		code.astore(2);
		code.aload(2);
		code.iconst_1();
		code.aload(2);
		code.invokevirtual(this.stringLength);
		code.iconst_1();
		code.isub();
		code.invokevirtual(this.stringSubstring);
		// _writeStr(content, handle); return str;
		code.aload(1);
		code.invokestatic(this.writeStrMethod);
		code.aload(0);
		code.areturn();
		return code;
	}

	/**
	 * {@code _addStream(Object stream) -> Long handle}. The ONE allocator of the stream
	 * table (lazily created, growable): it appends the entry and returns its index. It is
	 * emitted {@code synchronized} -- reserving a slot and filling it must be one atomic
	 * step, or two concurrent request threads take the same index and one stream is lost.
	 * The {@code _streams} field is written back on EVERY call, not just on a growth, and
	 * is volatile: that store is what publishes the new element to a reader thread, which
	 * reaches the table through the same volatile field.
	 */
	private MethodCode buildAddStream() {
		// Slots: 0=stream, 1=arr, 2=count
		MethodCode code = new MethodCode();
		// if (_streams == null) _streams = new Object[16];
		code.getstatic(this.streamsField);
		MethodCode.Label ifInit = code.newLabel();
		code.ifnonnull(ifInit);
		code.loadConstant(16);
		code.anewarray(this.objectClass);
		code.putstatic(this.streamsField);
		if (this.errorOutput) {
			// Reserve the standard-stream handles (0/1/2, the WASI file descriptors the
			// wasm backends use), so no user stream can be handed the *error-output*
			// designator's handle 2 and be diverted to stderr by the branches above.
			code.iconst_3();
			code.putstatic(this.streamCountField);
		}
		code.labelBinding(ifInit);
		// arr = _streams; count = _streamCount;
		code.getstatic(this.streamsField);
		code.astore(1);
		code.getstatic(this.streamCountField);
		code.istore(2);
		// if (count >= arr.length) arr = Arrays.copyOf(arr, count * 2);
		code.iload(2);
		code.aload(1);
		code.arraylength();
		MethodCode.Label ifGrow = code.newLabel();
		code.if_icmplt(ifGrow);
		code.aload(1);
		code.iload(2);
		code.iconst_2();
		code.imul();
		code.invokestatic(this.arraysCopyOf);
		code.astore(1);
		code.labelBinding(ifGrow);
		// arr[count] = stream; _streamCount = count + 1; _streams = arr;
		code.aload(1);
		code.iload(2);
		code.aload(0);
		code.aastore();
		code.iload(2);
		code.iconst_1();
		code.iadd();
		code.putstatic(this.streamCountField);
		code.aload(1);
		code.putstatic(this.streamsField);
		// return Long.valueOf((long) count);
		code.iload(2);
		code.i2l();
		code.invokestatic(this.longValueOf);
		code.areturn();
		return code;
	}

	/**
	 * {@code _makeStringOutputStream() -> Long handle}. Stores a fresh
	 * {@code StringWriter} in the stream table -- the string-builder stream behind
	 * with-output-to-string.
	 */
	private MethodCode buildMakeStringOutputStream() {
		// No slots: the entry is built on the stack and handed to _addStream.
		MethodCode code = new MethodCode();
		// return _addStream(new StringWriter());
		code.new_(this.stringWriterClass);
		code.dup();
		code.invokespecial(this.stringWriterInit);
		code.invokestatic(this.addStreamRef);
		code.areturn();
		return code;
	}

	/**
	 * {@code _makeStringInputStream(Object str) -> Long handle}. Stores a
	 * {@code BufferedReader} over the string content (without the surrounding quotes) in
	 * the stream table, so read-line/read consume it like any input stream -- the
	 * string-backed stream behind with-input-from-string.
	 */
	private MethodCode buildMakeStringInputStream() {
		// Slots: 0=str, 1=content (String)
		MethodCode code = new MethodCode();
		// content = ((String) str).substring(1, length - 1);
		code.aload(0);
		emitStrvOnStack(code);
		code.checkcast(this.stringClass);
		code.astore(1);
		code.aload(1);
		code.iconst_1();
		code.aload(1);
		code.invokevirtual(this.stringLength);
		code.iconst_1();
		code.isub();
		code.invokevirtual(this.stringSubstring);
		code.astore(1);
		if (this.stringInputStreamInit != null) {
			// return _addStream(new RontoStringInputStream(content)) -- the reader
			// that knows its position and its remainder, which file-position and
			// listen ask for.
			code.new_(Objects.requireNonNull(this.stringInputStreamClass));
			code.dup();
			code.aload(1);
			code.invokespecial(this.stringInputStreamInit);
			code.invokestatic(this.addStreamRef);
			code.areturn();
			return code;
		}
		// return _addStream(new BufferedReader(new StringReader(content)));
		code.new_(this.bufferedReaderClass);
		code.dup();
		code.new_(this.stringReaderClass);
		code.dup();
		code.aload(1);
		code.invokespecial(this.stringReaderInit);
		code.invokespecial(this.bufferedReaderInit);
		code.invokestatic(this.addStreamRef);
		code.areturn();
		return code;
	}

	/**
	 * {@code _stringStreamContents(Object handle) -> String}. Returns the string
	 * accumulated by a {@code StringWriter} table entry, wrapped in the internal
	 * {@code '"'} prefix/suffix string format, and CLEARS the entry -- CL's
	 * {@code get-output-stream-string} contract, which {@code with-output-to-string}
	 * cannot tell apart because it fetches once and then closes.
	 */
	private MethodCode buildStringStreamContents() {
		// Slots: 0=handle, 1=content (String), 2=writer (StringWriter)
		MethodCode code = new MethodCode();
		// writer = (StringWriter) _streams[idx];
		code.getstatic(this.streamsField);
		code.aload(0);
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
		code.aaload();
		code.checkcast(this.stringWriterClass);
		code.astore(2);
		// content = writer.toString();
		code.aload(2);
		code.invokevirtual(this.stringWriterToString);
		code.astore(1);
		// writer.getBuffer().setLength(0);
		code.aload(2);
		code.invokevirtual(this.stringWriterGetBuffer);
		code.iconst_0();
		code.invokevirtual(this.stringBufferSetLength);
		// return "\"".concat(content).concat("\"");
		code.ldc(this.quoteStr);
		code.aload(1);
		code.invokevirtual(this.stringConcat);
		code.ldc(this.quoteStr);
		code.invokevirtual(this.stringConcat);
		code.areturn();
		return code;
	}

}
