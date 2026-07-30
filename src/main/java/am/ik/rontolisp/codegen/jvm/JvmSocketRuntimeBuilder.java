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
 * Builds the JVM bytecode for the TCP/TLS socket runtime used by the
 * {@code rontolisp:tcp-connect} / {@code tcp-listen} / {@code tcp-accept} /
 * {@code tcp-local-port} / {@code tls-connect} built-ins. A socket handle is a
 * {@code Long} indexing the same static {@code _streams} table as file streams
 * ({@link JvmIoRuntimeBuilder}); the entry is the raw {@code java.net.Socket} (or
 * {@code java.net.ServerSocket} for a listener; an {@code SSLSocket} for
 * {@code tls-connect} -- a plain {@code Socket} subclass, so no extra branches), and the
 * stream built-ins dispatch on it: {@code _writeLine}/{@code _readLineStream} call the
 * {@code _sockWriteLine}/{@code _sockReadLine} helpers here (byte-at-a-time reads,
 * unbuffered writes), {@code _readByte}/{@code _writeByte} branch to the socket's
 * input/output stream, and {@code _closeStream} closes the socket directly. All methods
 * are emitted only when the program uses a tcp or tls built-in.
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

	static final String TLS_CONNECT_METHOD = "_tlsConnect";

	static final String TLS_CONNECT_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String TLS_LISTEN_METHOD = "_tlsListen";

	static final String TLS_LISTEN_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String TLS_LISTEN_P12_METHOD = "_tlsListenP12";

	static final String TLS_LISTEN_P12_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String TCP_LISTEN_METHOD = "_tcpListen";

	static final String TCP_LISTEN_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String TCP_ACCEPT_METHOD = "_tcpAccept";

	static final String TCP_ACCEPT_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String TCP_LOCAL_PORT_METHOD = "_tcpLocalPort";

	static final String TCP_LOCAL_PORT_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String TCP_LOCAL_ADDRESS_METHOD = "_tcpLocalAddress";

	static final String TCP_LOCAL_ADDRESS_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String TCP_PEER_ADDRESS_METHOD = "_tcpPeerAddress";

	static final String TCP_PEER_ADDRESS_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String TCP_PEER_PORT_METHOD = "_tcpPeerPort";

	static final String TCP_PEER_PORT_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	private static final String SOCK_READ_LINE_METHOD = "_sockReadLine";

	private static final String SOCK_READ_LINE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	private static final String SOCK_WRITE_LINE_METHOD = "_sockWriteLine";

	private static final String SOCK_WRITE_LINE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	private final ConstantPool cp;

	private final FieldrefConstant streamsField;

	private final ClassConstant stringClass;

	private final ClassConstant longClass;

	private final MethodrefConstant longValueOf;

	private final MethodrefConstant longValue;

	private final MethodrefConstant stringLength;

	private final MethodrefConstant stringSubstring;

	private final MethodrefConstant stringConcat;

	private final ClassConstant socketClass;

	private final ClassConstant serverSocketClass;

	private final MethodrefConstant socketInit;

	private final ClassConstant sslSocketClass;

	private final MethodrefConstant sslContextGetInstance;

	private final MethodrefConstant sslContextInit;

	private final MethodrefConstant sslContextGetSocketFactory;

	private final MethodrefConstant socketFactoryCreateSocket;

	private final MethodrefConstant sslSocketGetSSLParameters;

	private final MethodrefConstant sslSocketSetSSLParameters;

	private final MethodrefConstant sslSocketStartHandshake;

	private final MethodrefConstant sslParametersSetEndpointIdAlg;

	private final ClassConstant trustManagerClass;

	private final ClassConstant thisClassRef;

	private final MethodrefConstant thisClassInit;

	private final ConstantPool.StringConstant tlsStr;

	private final ConstantPool.StringConstant httpsStr;

	private final ConstantPool.StringConstant pkcs12Str;

	private final MethodrefConstant base64GetDecoder;

	private final MethodrefConstant base64Decode;

	private final ClassConstant byteArrayInputStreamClass;

	private final MethodrefConstant byteArrayInputStreamInit;

	private final MethodrefConstant keyStoreGetInstance;

	private final MethodrefConstant keyStoreLoad;

	private final ClassConstant fileInputStreamClass;

	private final MethodrefConstant fileInputStreamInit;

	private final MethodrefConstant fileInputStreamClose;

	private final MethodrefConstant kmfGetDefaultAlgorithm;

	private final MethodrefConstant kmfGetInstance;

	private final MethodrefConstant kmfInit;

	private final MethodrefConstant kmfGetKeyManagers;

	private final MethodrefConstant sslContextGetServerSocketFactory;

	private final MethodrefConstant serverSocketFactoryCreate;

	private final MethodrefConstant serverSocketFactoryCreateHost;

	private final MethodrefConstant stringToCharArray;

	private final MethodrefConstant serverSocketInitPort;

	private final MethodrefConstant serverSocketInitHost;

	private final MethodrefConstant inetGetByName;

	private final MethodrefConstant socketGetInputStream;

	private final MethodrefConstant socketGetOutputStream;

	private final MethodrefConstant socketGetLocalPort;

	private final MethodrefConstant serverSocketGetLocalPort;

	private final MethodrefConstant socketGetLocalAddress;

	private final MethodrefConstant socketGetInetAddress;

	private final MethodrefConstant socketGetPort;

	private final MethodrefConstant serverSocketGetInetAddress;

	private final MethodrefConstant inetGetHostAddress;

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

	private JvmSocketRuntimeBuilder(ConstantPool cp, ClassConstant thisClass, ClassConstant stringClass,
			ClassConstant longClass, MethodrefConstant longValueOf, MethodrefConstant longValue,
			MethodrefConstant stringLength, MethodrefConstant stringSubstring, MethodrefConstant stringConcat) {
		this.cp = cp;
		this.stringClass = stringClass;
		this.longClass = longClass;
		this.longValueOf = longValueOf;
		this.longValue = longValue;
		this.stringLength = stringLength;
		this.stringSubstring = stringSubstring;
		this.stringConcat = stringConcat;
		this.streamsField = cp.addFieldref(thisClass, cp.addNameAndType(cp.addUtf8(JvmIoRuntimeBuilder.STREAMS_FIELD),
				cp.addUtf8(JvmIoRuntimeBuilder.STREAMS_DESC)));
		this.socketClass = cp.addClass(cp.addUtf8("java/net/Socket"));
		this.serverSocketClass = cp.addClass(cp.addUtf8("java/net/ServerSocket"));
		this.socketInit = cp.addMethodref(this.socketClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;I)V")));
		ClassConstant sslContextClass = cp.addClass(cp.addUtf8("javax/net/ssl/SSLContext"));
		this.sslSocketClass = cp.addClass(cp.addUtf8("javax/net/ssl/SSLSocket"));
		ClassConstant sslParametersClass = cp.addClass(cp.addUtf8("javax/net/ssl/SSLParameters"));
		this.sslContextGetInstance = cp.addMethodref(sslContextClass, cp.addNameAndType(cp.addUtf8("getInstance"),
				cp.addUtf8("(Ljava/lang/String;)Ljavax/net/ssl/SSLContext;")));
		this.sslContextInit = cp.addMethodref(sslContextClass, cp.addNameAndType(cp.addUtf8("init"),
				cp.addUtf8("([Ljavax/net/ssl/KeyManager;[Ljavax/net/ssl/TrustManager;Ljava/security/SecureRandom;)V")));
		this.sslContextGetSocketFactory = cp.addMethodref(sslContextClass,
				cp.addNameAndType(cp.addUtf8("getSocketFactory"), cp.addUtf8("()Ljavax/net/ssl/SSLSocketFactory;")));
		ClassConstant socketFactoryClass = cp.addClass(cp.addUtf8("javax/net/SocketFactory"));
		this.socketFactoryCreateSocket = cp.addMethodref(socketFactoryClass,
				cp.addNameAndType(cp.addUtf8("createSocket"), cp.addUtf8("(Ljava/lang/String;I)Ljava/net/Socket;")));
		this.sslSocketGetSSLParameters = cp.addMethodref(this.sslSocketClass,
				cp.addNameAndType(cp.addUtf8("getSSLParameters"), cp.addUtf8("()Ljavax/net/ssl/SSLParameters;")));
		this.sslSocketSetSSLParameters = cp.addMethodref(this.sslSocketClass,
				cp.addNameAndType(cp.addUtf8("setSSLParameters"), cp.addUtf8("(Ljavax/net/ssl/SSLParameters;)V")));
		this.sslSocketStartHandshake = cp.addMethodref(this.sslSocketClass,
				cp.addNameAndType(cp.addUtf8("startHandshake"), cp.addUtf8("()V")));
		this.sslParametersSetEndpointIdAlg = cp.addMethodref(sslParametersClass, cp
			.addNameAndType(cp.addUtf8("setEndpointIdentificationAlgorithm"), cp.addUtf8("(Ljava/lang/String;)V")));
		this.trustManagerClass = cp.addClass(cp.addUtf8("javax/net/ssl/TrustManager"));
		this.thisClassRef = thisClass;
		this.thisClassInit = cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		this.tlsStr = cp.addString("TLS");
		this.httpsStr = cp.addString("HTTPS");
		this.pkcs12Str = cp.addString("PKCS12");
		ClassConstant base64Class = cp.addClass(cp.addUtf8("java/util/Base64"));
		ClassConstant base64DecoderClass = cp.addClass(cp.addUtf8("java/util/Base64$Decoder"));
		this.base64GetDecoder = cp.addMethodref(base64Class,
				cp.addNameAndType(cp.addUtf8("getDecoder"), cp.addUtf8("()Ljava/util/Base64$Decoder;")));
		this.base64Decode = cp.addMethodref(base64DecoderClass,
				cp.addNameAndType(cp.addUtf8("decode"), cp.addUtf8("(Ljava/lang/String;)[B")));
		this.byteArrayInputStreamClass = cp.addClass(cp.addUtf8("java/io/ByteArrayInputStream"));
		this.byteArrayInputStreamInit = cp.addMethodref(this.byteArrayInputStreamClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("([B)V")));
		ClassConstant keyStoreClass = cp.addClass(cp.addUtf8("java/security/KeyStore"));
		this.keyStoreGetInstance = cp.addMethodref(keyStoreClass, cp.addNameAndType(cp.addUtf8("getInstance"),
				cp.addUtf8("(Ljava/lang/String;)Ljava/security/KeyStore;")));
		this.keyStoreLoad = cp.addMethodref(keyStoreClass,
				cp.addNameAndType(cp.addUtf8("load"), cp.addUtf8("(Ljava/io/InputStream;[C)V")));
		this.fileInputStreamClass = cp.addClass(cp.addUtf8("java/io/FileInputStream"));
		this.fileInputStreamInit = cp.addMethodref(this.fileInputStreamClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		this.fileInputStreamClose = cp.addMethodref(this.fileInputStreamClass,
				cp.addNameAndType(cp.addUtf8("close"), cp.addUtf8("()V")));
		ClassConstant kmfClass = cp.addClass(cp.addUtf8("javax/net/ssl/KeyManagerFactory"));
		this.kmfGetDefaultAlgorithm = cp.addMethodref(kmfClass,
				cp.addNameAndType(cp.addUtf8("getDefaultAlgorithm"), cp.addUtf8("()Ljava/lang/String;")));
		this.kmfGetInstance = cp.addMethodref(kmfClass, cp.addNameAndType(cp.addUtf8("getInstance"),
				cp.addUtf8("(Ljava/lang/String;)Ljavax/net/ssl/KeyManagerFactory;")));
		this.kmfInit = cp.addMethodref(kmfClass,
				cp.addNameAndType(cp.addUtf8("init"), cp.addUtf8("(Ljava/security/KeyStore;[C)V")));
		this.kmfGetKeyManagers = cp.addMethodref(kmfClass,
				cp.addNameAndType(cp.addUtf8("getKeyManagers"), cp.addUtf8("()[Ljavax/net/ssl/KeyManager;")));
		this.sslContextGetServerSocketFactory = cp.addMethodref(sslContextClass, cp.addNameAndType(
				cp.addUtf8("getServerSocketFactory"), cp.addUtf8("()Ljavax/net/ssl/SSLServerSocketFactory;")));
		ClassConstant serverSocketFactoryClass = cp.addClass(cp.addUtf8("javax/net/ServerSocketFactory"));
		this.serverSocketFactoryCreate = cp.addMethodref(serverSocketFactoryClass,
				cp.addNameAndType(cp.addUtf8("createServerSocket"), cp.addUtf8("(II)Ljava/net/ServerSocket;")));
		this.serverSocketFactoryCreateHost = cp.addMethodref(serverSocketFactoryClass, cp.addNameAndType(
				cp.addUtf8("createServerSocket"), cp.addUtf8("(IILjava/net/InetAddress;)Ljava/net/ServerSocket;")));
		this.stringToCharArray = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("toCharArray"), cp.addUtf8("()[C")));
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
		this.socketGetLocalAddress = cp.addMethodref(this.socketClass,
				cp.addNameAndType(cp.addUtf8("getLocalAddress"), cp.addUtf8("()Ljava/net/InetAddress;")));
		this.socketGetInetAddress = cp.addMethodref(this.socketClass,
				cp.addNameAndType(cp.addUtf8("getInetAddress"), cp.addUtf8("()Ljava/net/InetAddress;")));
		this.socketGetPort = cp.addMethodref(this.socketClass,
				cp.addNameAndType(cp.addUtf8("getPort"), cp.addUtf8("()I")));
		this.serverSocketGetInetAddress = cp.addMethodref(this.serverSocketClass,
				cp.addNameAndType(cp.addUtf8("getInetAddress"), cp.addUtf8("()Ljava/net/InetAddress;")));
		this.inetGetHostAddress = cp.addMethodref(inetAddressClass,
				cp.addNameAndType(cp.addUtf8("getHostAddress"), cp.addUtf8("()Ljava/lang/String;")));
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
		// The stream table's ONE allocator, emitted by JvmIoRuntimeBuilder
		// (synchronized):
		// every socket constructor here registers its socket through it.
		this.addStreamRef = cp.addMethodref(thisClass, cp.addNameAndType(
				cp.addUtf8(JvmIoRuntimeBuilder.ADD_STREAM_METHOD), cp.addUtf8(JvmIoRuntimeBuilder.ADD_STREAM_DESC)));
		this.sockReadLineRef = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(SOCK_READ_LINE_METHOD), cp.addUtf8(SOCK_READ_LINE_DESC)));
		this.sockWriteLineRef = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(SOCK_WRITE_LINE_METHOD), cp.addUtf8(SOCK_WRITE_LINE_DESC)));
		this.quoteStr = cp.addString("\"");
		this.newlineStr = cp.addString("\n");
	}

	static SocketRuntime build(ConstantPool cp, ClassConstant thisClass, ClassConstant stringClass,
			ClassConstant longClass, MethodrefConstant longValueOf, MethodrefConstant longValue,
			MethodrefConstant stringLength, MethodrefConstant stringSubstring, MethodrefConstant stringConcat) {
		JvmSocketRuntimeBuilder builder = new JvmSocketRuntimeBuilder(cp, thisClass, stringClass, longClass,
				longValueOf, longValue, stringLength, stringSubstring, stringConcat);
		List<SocketMethod> methods = new ArrayList<>();
		methods.add(new SocketMethod(cp.addUtf8(TCP_CONNECT_METHOD), cp.addUtf8(TCP_CONNECT_DESC), 5, 3,
				builder.buildTcpConnect()));
		methods.add(new SocketMethod(cp.addUtf8(TLS_CONNECT_METHOD), cp.addUtf8(TLS_CONNECT_DESC), 8, 6,
				builder.buildTlsConnect()));
		methods.add(new SocketMethod(cp.addUtf8(TLS_LISTEN_METHOD), cp.addUtf8(TLS_LISTEN_DESC), 5, 13,
				builder.buildTlsListen()));
		methods.add(new SocketMethod(cp.addUtf8(TLS_LISTEN_P12_METHOD), cp.addUtf8(TLS_LISTEN_P12_DESC), 5, 13,
				builder.buildTlsListenP12()));
		methods.add(new SocketMethod(cp.addUtf8(TCP_LISTEN_METHOD), cp.addUtf8(TCP_LISTEN_DESC), 5, 4,
				builder.buildTcpListen()));
		methods.add(new SocketMethod(cp.addUtf8(TCP_ACCEPT_METHOD), cp.addUtf8(TCP_ACCEPT_DESC), 3, 1,
				builder.buildTcpAccept()));
		methods.add(new SocketMethod(cp.addUtf8(TCP_LOCAL_PORT_METHOD), cp.addUtf8(TCP_LOCAL_PORT_DESC), 3, 2,
				builder.buildTcpLocalPort()));
		methods.add(new SocketMethod(cp.addUtf8(TCP_LOCAL_ADDRESS_METHOD), cp.addUtf8(TCP_LOCAL_ADDRESS_DESC), 3, 2,
				builder.buildTcpLocalAddress()));
		methods.add(new SocketMethod(cp.addUtf8(TCP_PEER_ADDRESS_METHOD), cp.addUtf8(TCP_PEER_ADDRESS_DESC), 3, 2,
				builder.buildTcpPeerAddress()));
		methods.add(new SocketMethod(cp.addUtf8(TCP_PEER_PORT_METHOD), cp.addUtf8(TCP_PEER_PORT_DESC), 3, 2,
				builder.buildTcpPeerPort()));
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
	 * {@code _tlsConnect(Object host, Object port, Object insecure) -> Long handle}.
	 * Strips the surrounding quotes from the host string, opens a blocking TLS connection
	 * and stores the handshaken {@code SSLSocket} in the stream table. A fresh
	 * {@code SSLContext} is initialized per call (so the {@code javax.net.ssl.trustStore}
	 * system properties are re-read on every connection). A {@code null} (nil)
	 * {@code insecure} verifies: the JDK default trust store plus HTTPS-style endpoint
	 * identification; non-nil skips both by installing the generated program class itself
	 * as a trust-all {@code X509TrustManager} (see the interface/methods emitted by
	 * {@code JvmLispCompiler} when {@code tls-connect} is used). An {@code SSLSocket} is
	 * a {@code Socket}, so every socket branch of the stream built-ins works on the entry
	 * unchanged.
	 */
	private List<Integer> buildTlsConnect() {
		// Slots: 0=host, 1=port, 2=insecure, 3=h (String), 4=socket (SSLSocket),
		// 5=params
		List<Integer> code = new ArrayList<>();
		emitStripQuotes(code, 0, 3);
		// SSLContext ctx = SSLContext.getInstance("TLS");
		// ctx.init(null, insecure != null ? new TrustManager[]{new Prog()} : null,
		// null);
		emitLdc(code, this.tlsStr.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.sslContextGetInstance.index());
		code.add(Opcode.DUP);
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ALOAD_2);
		int ifSecurePos = code.size();
		code.add(Opcode.IFNULL);
		emitU2(code, 0);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ANEWARRAY);
		emitU2(code, this.trustManagerClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.NEW);
		emitU2(code, this.thisClassRef.index());
		code.add(Opcode.DUP);
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.thisClassInit.index());
		code.add(Opcode.AASTORE);
		int gotoInitPos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifSecurePos, code.size());
		code.add(Opcode.ACONST_NULL);
		patchBranch(code, gotoInitPos, code.size());
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.sslContextInit.index());
		// socket = (SSLSocket) ctx.getSocketFactory().createSocket(h, (int) port)
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.sslContextGetSocketFactory.index());
		code.add(Opcode.ALOAD_3);
		code.add(Opcode.ALOAD_1);
		emitUnboxInt(code);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.socketFactoryCreateSocket.index());
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.sslSocketClass.index());
		emitAstore(code, 4);
		// endpoint identification only on the verifying path:
		// if (insecure == null) { params = socket.getSSLParameters();
		// params.setEndpointIdentificationAlgorithm("HTTPS");
		// socket.setSSLParameters(params); }
		code.add(Opcode.ALOAD_2);
		int ifInsecurePos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		emitAload(code, 4);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.sslSocketGetSSLParameters.index());
		emitAstore(code, 5);
		emitAload(code, 5);
		emitLdc(code, this.httpsStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.sslParametersSetEndpointIdAlg.index());
		emitAload(code, 4);
		emitAload(code, 5);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.sslSocketSetSSLParameters.index());
		patchBranch(code, ifInsecurePos, code.size());
		// socket.startHandshake();
		emitAload(code, 4);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.sslSocketStartHandshake.index());
		emitAload(code, 4);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.addStreamRef.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * {@code _tlsListen(Object keystore, Object password, Object port, Object host) ->
	 * Long handle}. Loads the PKCS12 keystore, builds an {@code SSLContext} over its key
	 * managers and binds a TLS server socket (a {@code ServerSocket} subclass, so
	 * {@code _tcpAccept}/{@code _tcpLocalPort}/{@code _closeStream} work on the entry
	 * unchanged); a {@code null} host (nil) binds all interfaces. An accepted socket
	 * performs its TLS handshake lazily on the first read/write.
	 */
	private List<Integer> buildTlsListen() {
		// Slots: 0=keystore, 1=password, 2=port, 3=host, 4=path (String),
		// 5=pw (String, then char[]), 6=store (KeyStore), 7=in (FileInputStream),
		// 8=kms (KeyManager[]), 9=ctx (SSLContext), 10=factory, 11=p (int),
		// 12=h (String)
		List<Integer> code = new ArrayList<>();
		emitStripQuotes(code, 0, 4);
		emitStripQuotes(code, 1, 5);
		// pw = pw.toCharArray()
		emitAload(code, 5);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringToCharArray.index());
		emitAstore(code, 5);
		// store = KeyStore.getInstance("PKCS12");
		emitLdc(code, this.pkcs12Str.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.keyStoreGetInstance.index());
		emitAstore(code, 6);
		// in = new FileInputStream(path); store.load(in, pw); in.close();
		code.add(Opcode.NEW);
		emitU2(code, this.fileInputStreamClass.index());
		code.add(Opcode.DUP);
		emitAload(code, 4);
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.fileInputStreamInit.index());
		emitAstore(code, 7);
		emitAload(code, 6);
		emitAload(code, 7);
		emitAload(code, 5);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.keyStoreLoad.index());
		emitAload(code, 7);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.fileInputStreamClose.index());
		emitKmfToServerSocket(code);
		return code;
	}

	/**
	 * {@code _tlsListenP12(Object base64, Object password, Object port, Object host) ->
	 * Long handle}. The shape the {@code tls-listen-pem} compile-time inliner rewrites
	 * to: Base64-decodes the embedded PKCS12 keystore, loads it from a {@code
	 * ByteArrayInputStream} and otherwise behaves exactly like {@link #buildTlsListen}
	 * (same {@code SSLContext}/server-socket tail).
	 */
	private List<Integer> buildTlsListenP12() {
		// Slots: 0=base64, 1=password, 2=port, 3=host, 4=b64 (String),
		// 5=pw (String, then char[]), 6=store (KeyStore), 7=in (ByteArrayInputStream),
		// 8=kms, 9=ctx, 10=factory, 11=p (int), 12=h (String)
		List<Integer> code = new ArrayList<>();
		emitStripQuotes(code, 0, 4);
		emitStripQuotes(code, 1, 5);
		// pw = pw.toCharArray()
		emitAload(code, 5);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringToCharArray.index());
		emitAstore(code, 5);
		// store = KeyStore.getInstance("PKCS12");
		emitLdc(code, this.pkcs12Str.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.keyStoreGetInstance.index());
		emitAstore(code, 6);
		// in = new ByteArrayInputStream(Base64.getDecoder().decode(b64));
		code.add(Opcode.NEW);
		emitU2(code, this.byteArrayInputStreamClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.base64GetDecoder.index());
		emitAload(code, 4);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.base64Decode.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, this.byteArrayInputStreamInit.index());
		emitAstore(code, 7);
		// store.load(in, pw);
		emitAload(code, 6);
		emitAload(code, 7);
		emitAload(code, 5);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.keyStoreLoad.index());
		emitKmfToServerSocket(code);
		return code;
	}

	/**
	 * Emits the shared tail of {@link #buildTlsListen} / {@link #buildTlsListenP12}:
	 * given slot 5 = the password {@code char[]}, slot 6 = the loaded {@code KeyStore},
	 * slot 2 = the port {@code Long} and slot 3 = the host {@code String} (nil = all
	 * interfaces), builds the {@code SSLContext} over the keystore's key managers and
	 * binds the TLS server socket, storing it in the stream table.
	 */
	private void emitKmfToServerSocket(List<Integer> code) {
		// kms = KeyManagerFactory.getInstance(getDefaultAlgorithm()) initialized with
		// (store, pw)
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.kmfGetDefaultAlgorithm.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.kmfGetInstance.index());
		code.add(Opcode.DUP);
		emitAload(code, 6);
		emitAload(code, 5);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.kmfInit.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.kmfGetKeyManagers.index());
		emitAstore(code, 8);
		// ctx = SSLContext.getInstance("TLS"); ctx.init(kms, null, null);
		emitLdc(code, this.tlsStr.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.sslContextGetInstance.index());
		emitAstore(code, 9);
		emitAload(code, 9);
		emitAload(code, 8);
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.sslContextInit.index());
		// factory = ctx.getServerSocketFactory(); p = (int) port;
		emitAload(code, 9);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.sslContextGetServerSocketFactory.index());
		emitAstore(code, 10);
		code.add(Opcode.ALOAD_2);
		emitUnboxInt(code);
		code.add(Opcode.ISTORE);
		code.add(11);
		code.add(Opcode.ALOAD_3);
		int ifHostPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		// factory.createServerSocket(p, 50)
		emitAload(code, 10);
		code.add(Opcode.ILOAD);
		code.add(11);
		code.add(Opcode.BIPUSH);
		code.add(50);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.serverSocketFactoryCreate.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.addStreamRef.index());
		code.add(Opcode.ARETURN);
		patchBranch(code, ifHostPos, code.size());
		// factory.createServerSocket(p, 50, InetAddress.getByName(h))
		emitStripQuotes(code, 3, 12);
		emitAload(code, 10);
		code.add(Opcode.ILOAD);
		code.add(11);
		code.add(Opcode.BIPUSH);
		code.add(50);
		emitAload(code, 12);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.inetGetByName.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.serverSocketFactoryCreateHost.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, this.addStreamRef.index());
		code.add(Opcode.ARETURN);
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
	 * {@code _tcpLocalAddress(Object handle) -> String}. Returns the local/bound IP
	 * address of a listener or socket entry, quote-framed like every runtime string.
	 */
	private List<Integer> buildTcpLocalAddress() {
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
		// "\"".concat(((ServerSocket) entry).getInetAddress().getHostAddress()) + "\""
		emitLdc(code, this.quoteStr.index());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.serverSocketClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.serverSocketGetInetAddress.index());
		emitHostAddressReturn(code);
		patchBranch(code, ifSockPos, code.size());
		emitLdc(code, this.quoteStr.index());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.socketClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.socketGetLocalAddress.index());
		emitHostAddressReturn(code);
		return code;
	}

	/**
	 * {@code _tcpPeerAddress(Object handle) -> String}. Returns the remote IP address of
	 * a connected socket entry, quote-framed like every runtime string.
	 */
	private List<Integer> buildTcpPeerAddress() {
		// Slots: 0=handle, 1=entry
		List<Integer> code = new ArrayList<>();
		emitLoadStreamEntry(code, 0);
		code.add(Opcode.ASTORE_1);
		emitLdc(code, this.quoteStr.index());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.socketClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.socketGetInetAddress.index());
		emitHostAddressReturn(code);
		return code;
	}

	/**
	 * {@code _tcpPeerPort(Object handle) -> Long}. Returns the remote port of a connected
	 * socket entry.
	 */
	private List<Integer> buildTcpPeerPort() {
		List<Integer> code = new ArrayList<>();
		emitLoadStreamEntry(code, 0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, this.socketClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.socketGetPort.index());
		emitBoxLong(code);
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * Emits the shared tail of the address accessors: with the opening quote string and
	 * an {@code InetAddress} on the stack, appends
	 * {@code .getHostAddress()}-concat-closing-quote and returns.
	 */
	private void emitHostAddressReturn(List<Integer> code) {
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.inetGetHostAddress.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringConcat.index());
		emitLdc(code, this.quoteStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, this.stringConcat.index());
		code.add(Opcode.ARETURN);
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
