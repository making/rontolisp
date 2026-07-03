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
 * Builds the JVM bytecode for the TCP socket runtime used by the
 * {@code rontolisp:tcp-connect} / {@code tcp-listen} / {@code tcp-accept} /
 * {@code tcp-local-port} built-ins. A socket handle is a {@code Long} indexing the same
 * static {@code _streams} table as file streams ({@link JvmIoRuntimeBuilder}); the entry
 * is the raw {@code java.net.Socket} (or {@code java.net.ServerSocket} for a listener),
 * and the stream built-ins dispatch on it: {@code _writeLine}/{@code _readLineStream}
 * call the {@code _sockWriteLine}/{@code _sockReadLine} helpers here (byte-at-a-time
 * reads, unbuffered writes), {@code _readByte}/{@code _writeByte} branch to the socket's
 * input/output stream, and {@code _closeStream} closes the socket directly. All methods
 * are emitted only when the program uses a tcp built-in.
 */
final class JvmSocketRuntimeBuilder {

	/** A socket-runtime method body ready to be emitted into the generated class. */
	record SocketMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	/**
	 * The emitted method bodies plus the constants {@link JvmIoRuntimeBuilder} needs to
	 * add the socket branches to the shared stream built-ins.
	 */
	record SocketRuntime(List<SocketMethod> methods, ClassConstant socketClass, ClassConstant serverSocketClass,
			MethodrefConstant socketGetInputStream, MethodrefConstant socketGetOutputStream,
			MethodrefConstant socketClose, MethodrefConstant serverSocketClose, MethodrefConstant sockReadLine,
			MethodrefConstant sockWriteLine) {
	}

	static final String TCP_CONNECT_METHOD = "_tcpConnect";

	static final String TCP_CONNECT_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String TCP_LISTEN_METHOD = "_tcpListen";

	static final String TCP_LISTEN_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String TCP_ACCEPT_METHOD = "_tcpAccept";

	static final String TCP_ACCEPT_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String TCP_LOCAL_PORT_METHOD = "_tcpLocalPort";

	static final String TCP_LOCAL_PORT_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	private static final String ADD_STREAM_METHOD = "_addStream";

	private static final String ADD_STREAM_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	private static final String SOCK_READ_LINE_METHOD = "_sockReadLine";

	private static final String SOCK_READ_LINE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	private static final String SOCK_WRITE_LINE_METHOD = "_sockWriteLine";

	private static final String SOCK_WRITE_LINE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	private final ConstantPool cp;

	private final FieldrefConstant streamsField;

	private final FieldrefConstant streamCountField;

	private final ClassConstant objectClass;

	private final ClassConstant stringClass;

	private final ClassConstant longClass;

	private final MethodrefConstant longValueOf;

	private final MethodrefConstant longValue;

	private final MethodrefConstant stringLength;

	private final MethodrefConstant stringSubstring;

	private final MethodrefConstant stringConcat;

	private final MethodrefConstant arraysCopyOf;

	private final ClassConstant socketClass;

	private final ClassConstant serverSocketClass;

	private final MethodrefConstant socketInit;

	private final MethodrefConstant serverSocketInitPort;

	private final MethodrefConstant serverSocketInitHost;

	private final MethodrefConstant inetGetByName;

	private final MethodrefConstant socketGetInputStream;

	private final MethodrefConstant socketGetOutputStream;

	private final MethodrefConstant socketGetLocalPort;

	private final MethodrefConstant serverSocketGetLocalPort;

	private final MethodrefConstant serverSocketAccept;

	private final MethodrefConstant socketClose;

	private final MethodrefConstant serverSocketClose;

	private final MethodrefConstant inputStreamRead;

	private final MethodrefConstant outputStreamWriteBytes;

	private final ClassConstant baosClass;

	private final MethodrefConstant baosInit;

	private final MethodrefConstant baosWrite;

	private final MethodrefConstant baosToByteArray;

	private final ClassConstant stringClassRef;

	private final MethodrefConstant stringInitBytes;

	private final MethodrefConstant stringGetBytes;

	private final FieldrefConstant utf8Field;

	private final MethodrefConstant addStreamRef;

	private final MethodrefConstant sockReadLineRef;

	private final MethodrefConstant sockWriteLineRef;

	private final ConstantPool.StringConstant quoteStr;

	private final ConstantPool.StringConstant newlineStr;

	private JvmSocketRuntimeBuilder(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			ClassConstant stringClass, ClassConstant longClass, MethodrefConstant longValueOf,
			MethodrefConstant longValue, MethodrefConstant stringLength, MethodrefConstant stringSubstring,
			MethodrefConstant stringConcat) {
		this.cp = cp;
		this.objectClass = objectClass;
		this.stringClass = stringClass;
		this.longClass = longClass;
		this.longValueOf = longValueOf;
		this.longValue = longValue;
		this.stringLength = stringLength;
		this.stringSubstring = stringSubstring;
		this.stringConcat = stringConcat;
		this.streamsField = cp.addFieldref(thisClass, cp.addNameAndType(cp.addUtf8(JvmIoRuntimeBuilder.STREAMS_FIELD),
				cp.addUtf8(JvmIoRuntimeBuilder.STREAMS_DESC)));
		this.streamCountField = cp.addFieldref(thisClass, cp.addNameAndType(
				cp.addUtf8(JvmIoRuntimeBuilder.STREAM_COUNT_FIELD), cp.addUtf8(JvmIoRuntimeBuilder.STREAM_COUNT_DESC)));
		ClassConstant arraysClass = cp.addClass(cp.addUtf8("java/util/Arrays"));
		this.arraysCopyOf = cp.addMethodref(arraysClass,
				cp.addNameAndType(cp.addUtf8("copyOf"), cp.addUtf8("([Ljava/lang/Object;I)[Ljava/lang/Object;")));
		this.socketClass = cp.addClass(cp.addUtf8("java/net/Socket"));
		this.serverSocketClass = cp.addClass(cp.addUtf8("java/net/ServerSocket"));
		this.socketInit = cp.addMethodref(this.socketClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;I)V")));
		this.serverSocketInitPort = cp.addMethodref(this.serverSocketClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(I)V")));
		this.serverSocketInitHost = cp.addMethodref(this.serverSocketClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(IILjava/net/InetAddress;)V")));
		ClassConstant inetAddressClass = cp.addClass(cp.addUtf8("java/net/InetAddress"));
		this.inetGetByName = cp.addMethodref(inetAddressClass,
				cp.addNameAndType(cp.addUtf8("getByName"), cp.addUtf8("(Ljava/lang/String;)Ljava/net/InetAddress;")));
		this.socketGetInputStream = cp.addMethodref(this.socketClass,
				cp.addNameAndType(cp.addUtf8("getInputStream"), cp.addUtf8("()Ljava/io/InputStream;")));
		this.socketGetOutputStream = cp.addMethodref(this.socketClass,
				cp.addNameAndType(cp.addUtf8("getOutputStream"), cp.addUtf8("()Ljava/io/OutputStream;")));
		this.socketGetLocalPort = cp.addMethodref(this.socketClass,
				cp.addNameAndType(cp.addUtf8("getLocalPort"), cp.addUtf8("()I")));
		this.serverSocketGetLocalPort = cp.addMethodref(this.serverSocketClass,
				cp.addNameAndType(cp.addUtf8("getLocalPort"), cp.addUtf8("()I")));
		this.serverSocketAccept = cp.addMethodref(this.serverSocketClass,
				cp.addNameAndType(cp.addUtf8("accept"), cp.addUtf8("()Ljava/net/Socket;")));
		this.socketClose = cp.addMethodref(this.socketClass, cp.addNameAndType(cp.addUtf8("close"), cp.addUtf8("()V")));
		this.serverSocketClose = cp.addMethodref(this.serverSocketClass,
				cp.addNameAndType(cp.addUtf8("close"), cp.addUtf8("()V")));
		ClassConstant inputStreamClass = cp.addClass(cp.addUtf8("java/io/InputStream"));
		this.inputStreamRead = cp.addMethodref(inputStreamClass,
				cp.addNameAndType(cp.addUtf8("read"), cp.addUtf8("()I")));
		ClassConstant outputStreamClass = cp.addClass(cp.addUtf8("java/io/OutputStream"));
		this.outputStreamWriteBytes = cp.addMethodref(outputStreamClass,
				cp.addNameAndType(cp.addUtf8("write"), cp.addUtf8("([B)V")));
		this.baosClass = cp.addClass(cp.addUtf8("java/io/ByteArrayOutputStream"));
		this.baosInit = cp.addMethodref(this.baosClass, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		this.baosWrite = cp.addMethodref(this.baosClass, cp.addNameAndType(cp.addUtf8("write"), cp.addUtf8("(I)V")));
		this.baosToByteArray = cp.addMethodref(this.baosClass,
				cp.addNameAndType(cp.addUtf8("toByteArray"), cp.addUtf8("()[B")));
		this.stringClassRef = stringClass;
		this.stringInitBytes = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("([BIILjava/nio/charset/Charset;)V")));
		this.stringGetBytes = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("getBytes"), cp.addUtf8("(Ljava/nio/charset/Charset;)[B")));
		ClassConstant standardCharsetsClass = cp.addClass(cp.addUtf8("java/nio/charset/StandardCharsets"));
		this.utf8Field = cp.addFieldref(standardCharsetsClass,
				cp.addNameAndType(cp.addUtf8("UTF_8"), cp.addUtf8("Ljava/nio/charset/Charset;")));
		this.addStreamRef = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(ADD_STREAM_METHOD), cp.addUtf8(ADD_STREAM_DESC)));
		this.sockReadLineRef = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(SOCK_READ_LINE_METHOD), cp.addUtf8(SOCK_READ_LINE_DESC)));
		this.sockWriteLineRef = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(SOCK_WRITE_LINE_METHOD), cp.addUtf8(SOCK_WRITE_LINE_DESC)));
		this.quoteStr = cp.addString("\"");
		this.newlineStr = cp.addString("\n");
	}

	static SocketRuntime build(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			ClassConstant stringClass, ClassConstant longClass, MethodrefConstant longValueOf,
			MethodrefConstant longValue, MethodrefConstant stringLength, MethodrefConstant stringSubstring,
			MethodrefConstant stringConcat) {
		JvmSocketRuntimeBuilder builder = new JvmSocketRuntimeBuilder(cp, thisClass, objectClass, stringClass,
				longClass, longValueOf, longValue, stringLength, stringSubstring, stringConcat);
		List<SocketMethod> methods = new ArrayList<>();
		methods.add(new SocketMethod(cp.addUtf8(TCP_CONNECT_METHOD), cp.addUtf8(TCP_CONNECT_DESC), 5, 3,
				builder.buildTcpConnect()));
		methods.add(new SocketMethod(cp.addUtf8(TCP_LISTEN_METHOD), cp.addUtf8(TCP_LISTEN_DESC), 5, 4,
				builder.buildTcpListen()));
		methods.add(new SocketMethod(cp.addUtf8(TCP_ACCEPT_METHOD), cp.addUtf8(TCP_ACCEPT_DESC), 3, 1,
				builder.buildTcpAccept()));
		methods.add(new SocketMethod(cp.addUtf8(TCP_LOCAL_PORT_METHOD), cp.addUtf8(TCP_LOCAL_PORT_DESC), 3, 2,
				builder.buildTcpLocalPort()));
		methods.add(new SocketMethod(cp.addUtf8(ADD_STREAM_METHOD), cp.addUtf8(ADD_STREAM_DESC), 3, 3,
				builder.buildAddStream()));
		methods.add(new SocketMethod(cp.addUtf8(SOCK_READ_LINE_METHOD), cp.addUtf8(SOCK_READ_LINE_DESC), 7, 6,
				builder.buildSockReadLine()));
		methods.add(new SocketMethod(cp.addUtf8(SOCK_WRITE_LINE_METHOD), cp.addUtf8(SOCK_WRITE_LINE_DESC), 4, 3,
				builder.buildSockWriteLine()));
		return new SocketRuntime(methods, builder.socketClass, builder.serverSocketClass, builder.socketGetInputStream,
				builder.socketGetOutputStream, builder.socketClose, builder.serverSocketClose, builder.sockReadLineRef,
				builder.sockWriteLineRef);
	}

	/**
	 * {@code _tcpConnect(Object host, Object port) -> Long handle}. Strips the
	 * surrounding quotes from the host string, opens a blocking {@code Socket} and stores
	 * it in the stream table.
	 */
	private List<Integer> buildTcpConnect() {
		// Slots: 0=host, 1=port, 2=h (String)
		List<Integer> code = new ArrayList<>();
		emitStripQuotes(code, 0, 2);
		// new Socket(h, (int) ((Long) port).longValue())
		code.add(Opcode.NEW);
		emitU2(code, this.socketClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ALOAD_1);
		emitUnboxInt(code);
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.socketInit.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.addStreamRef.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _tcpListen(Object port, Object host) -> Long handle}. Binds a
	 * {@code ServerSocket} on the port (0 picks an ephemeral port); a {@code null} host
	 * (nil) binds all interfaces.
	 */
	private List<Integer> buildTcpListen() {
		// Slots: 0=port, 1=host, 2=p (int), 3=h (String)
		List<Integer> code = new ArrayList<>();
		code.add(Opcode.ALOAD_0);
		emitUnboxInt(code);
		code.add(Opcode.ISTORE_2);
		code.add(Opcode.ALOAD_1);
		int ifHostPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		// new ServerSocket(p)
		code.add(Opcode.NEW);
		emitU2(code, this.serverSocketClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.ILOAD_2);
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.serverSocketInitPort.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.addStreamRef.index());
		code.add(Opcode.ARETURN);
		patchBranch(code, ifHostPos, code.size());
		// new ServerSocket(p, 50, InetAddress.getByName(h))
		emitStripQuotes(code, 1, 3);
		code.add(Opcode.NEW);
		emitU2(code, this.serverSocketClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.ILOAD_2);
		code.add(Opcode.BIPUSH);
		code.add(50);
		code.add(Opcode.ALOAD_3);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.inetGetByName.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.serverSocketInitHost.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.addStreamRef.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _tcpAccept(Object handle) -> Long handle}. Blocks in
	 * {@code ServerSocket.accept()} and stores the accepted socket in the stream table.
	 */
	private List<Integer> buildTcpAccept() {
		List<Integer> code = new ArrayList<>();
		emitLoadStreamEntry(code, 0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.serverSocketClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.serverSocketAccept.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.addStreamRef.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _tcpLocalPort(Object handle) -> Long}. Returns the local port of a listener
	 * or socket entry.
	 */
	private List<Integer> buildTcpLocalPort() {
		// Slots: 0=handle, 1=entry
		List<Integer> code = new ArrayList<>();
		emitLoadStreamEntry(code, 0);
		code.add(Opcode.ASTORE_1);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, this.serverSocketClass.index());
		int ifSockPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.serverSocketClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.serverSocketGetLocalPort.index());
		emitBoxLong(code);
		code.add(Opcode.ARETURN);
		patchBranch(code, ifSockPos, code.size());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.socketClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.socketGetLocalPort.index());
		emitBoxLong(code);
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _addStream(Object stream) -> Long handle}. Stores an entry in the (lazily
	 * created, growable) {@code _streams} table and returns its index -- the same table
	 * logic as the tail of {@code _open}, factored here so the socket constructors share
	 * it without touching {@code _open}'s emitted bytes.
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
		// if (count >= arr.length) { arr = Arrays.copyOf(arr, count * 2); _streams = arr;
		// }
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
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.PUTSTATIC);
		emitU2(code, this.streamsField.index());
		patchBranch(code, ifGrowPos, code.size());
		// arr[count] = stream; _streamCount = count + 1; return Long.valueOf(count);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ILOAD_2);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.AASTORE);
		code.add(Opcode.ILOAD_2);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.IADD);
		code.add(Opcode.PUTSTATIC);
		emitU2(code, this.streamCountField.index());
		code.add(Opcode.ILOAD_2);
		emitBoxLong(code);
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _sockReadLine(Object socket) -> Object}. Reads bytes up to a {@code \n}
	 * (exclusive, one trailing {@code \r} stripped), decodes UTF-8 and wraps the line
	 * with the internal {@code '"'} prefix/suffix; returns {@code null} (nil) when the
	 * peer closed before any byte arrived.
	 */
	private List<Integer> buildSockReadLine() {
		// Slots: 0=socket, 1=in (InputStream), 2=baos, 3=b (int), 4=bytes, 5=len (int)
		List<Integer> code = new ArrayList<>();
		// in = ((Socket) socket).getInputStream();
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.socketClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.socketGetInputStream.index());
		code.add(Opcode.ASTORE_1);
		// baos = new ByteArrayOutputStream();
		code.add(Opcode.NEW);
		emitU2(code, this.baosClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.baosInit.index());
		code.add(Opcode.ASTORE_2);
		// b = in.read(); if (b < 0) return null;
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.inputStreamRead.index());
		code.add(Opcode.ISTORE_3);
		code.add(Opcode.ILOAD_3);
		int ifDataPos = code.size();
		code.add(Opcode.IFGE);
		emitU2(code, 0);
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		patchBranch(code, ifDataPos, code.size());
		// while (b >= 0 && b != '\n') { baos.write(b); b = in.read(); }
		int loopStart = code.size();
		code.add(Opcode.ILOAD_3);
		int ifEofPos = code.size();
		code.add(Opcode.IFLT);
		emitU2(code, 0);
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.BIPUSH);
		code.add((int) '\n');
		int ifNlPos = code.size();
		code.add(Opcode.IF_ICMPEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.baosWrite.index());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.inputStreamRead.index());
		code.add(Opcode.ISTORE_3);
		int gotoLoopPos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, gotoLoopPos, loopStart);
		patchBranch(code, ifEofPos, code.size());
		patchBranch(code, ifNlPos, code.size());
		// bytes = baos.toByteArray(); len = bytes.length;
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.baosToByteArray.index());
		code.add(Opcode.ASTORE);
		code.add(4);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.ARRAYLENGTH);
		code.add(Opcode.ISTORE);
		code.add(5);
		// if (len > 0 && bytes[len - 1] == '\r') len--;
		code.add(Opcode.ILOAD);
		code.add(5);
		int ifEmptyPos = code.size();
		code.add(Opcode.IFLE);
		emitU2(code, 0);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.ILOAD);
		code.add(5);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ISUB);
		code.add(Opcode.BALOAD);
		code.add(Opcode.BIPUSH);
		code.add((int) '\r');
		int ifNoCrPos = code.size();
		code.add(Opcode.IF_ICMPNE);
		emitU2(code, 0);
		code.add(Opcode.ILOAD);
		code.add(5);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ISUB);
		code.add(Opcode.ISTORE);
		code.add(5);
		patchBranch(code, ifEmptyPos, code.size());
		patchBranch(code, ifNoCrPos, code.size());
		// return "\"".concat(new String(bytes, 0, len, UTF_8)).concat("\"");
		emitLdc(code, this.quoteStr.index());
		code.add(Opcode.NEW);
		emitU2(code, this.stringClassRef.index());
		code.add(Opcode.DUP);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.ILOAD);
		code.add(5);
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.utf8Field.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.stringInitBytes.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringConcat.index());
		emitLdc(code, this.quoteStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringConcat.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _sockWriteLine(Object str, Object socket) -> str}. Writes the string content
	 * (without the surrounding quotes) plus a newline to the socket, UTF-8, sent
	 * immediately (socket output streams are unbuffered).
	 */
	private List<Integer> buildSockWriteLine() {
		// Slots: 0=str, 1=socket, 2=content (String)
		List<Integer> code = new ArrayList<>();
		emitStripQuotes(code, 0, 2);
		// content = content.concat("\n");
		code.add(Opcode.ALOAD_2);
		emitLdc(code, this.newlineStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringConcat.index());
		code.add(Opcode.ASTORE_2);
		// ((Socket) socket).getOutputStream().write(content.getBytes(UTF_8));
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.socketClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.socketGetOutputStream.index());
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.utf8Field.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringGetBytes.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.outputStreamWriteBytes.index());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ARETURN);
		return code;
	}

	/** Emits {@code slot<target> = ((String) slot<src>).substring(1, length() - 1)}. */
	private void emitStripQuotes(List<Integer> code, int srcSlot, int targetSlot) {
		emitAload(code, srcSlot);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.stringClass.index());
		emitAstore(code, targetSlot);
		emitAload(code, targetSlot);
		code.add(Opcode.ICONST_1);
		emitAload(code, targetSlot);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringLength.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ISUB);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringSubstring.index());
		emitAstore(code, targetSlot);
	}

	/**
	 * Emits {@code _streams[(int) ((Long) slot<handleSlot>).longValue()]} (an Object).
	 */
	private void emitLoadStreamEntry(List<Integer> code, int handleSlot) {
		code.add(Opcode.GETSTATIC);
		emitU2(code, this.streamsField.index());
		emitAload(code, handleSlot);
		emitUnboxInt(code);
		code.add(Opcode.AALOAD);
	}

	/** Emits {@code (int) ((Long) <stack top>).longValue()}. */
	private void emitUnboxInt(List<Integer> code) {
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.longClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.longValue.index());
		code.add(Opcode.L2I);
	}

	/** Emits {@code Long.valueOf((long) <stack top int>)}. */
	private void emitBoxLong(List<Integer> code) {
		code.add(Opcode.I2L);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.longValueOf.index());
	}

	private static void emitAload(List<Integer> code, int slot) {
		switch (slot) {
			case 0 -> code.add(Opcode.ALOAD_0);
			case 1 -> code.add(Opcode.ALOAD_1);
			case 2 -> code.add(Opcode.ALOAD_2);
			case 3 -> code.add(Opcode.ALOAD_3);
			default -> {
				code.add(Opcode.ALOAD);
				code.add(slot);
			}
		}
	}

	private static void emitAstore(List<Integer> code, int slot) {
		switch (slot) {
			case 0 -> code.add(Opcode.ASTORE_0);
			case 1 -> code.add(Opcode.ASTORE_1);
			case 2 -> code.add(Opcode.ASTORE_2);
			case 3 -> code.add(Opcode.ASTORE_3);
			default -> {
				code.add(Opcode.ASTORE);
				code.add(slot);
			}
		}
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
