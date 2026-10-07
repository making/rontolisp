package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import org.jspecify.annotations.Nullable;

/**
 * Builds the JVM bytecode for the TCP/TLS socket runtime used by the
 * {@code rontolisp:tcp-connect} / {@code tcp-listen} / {@code tcp-accept} /
 * {@code tcp-local-port} / {@code tls-connect} built-ins. A socket handle is a
 * {@code Long} indexing the same static {@code _streams} table as file streams
 * ({@link JvmIoRuntimeBuilder}); the entry is the raw {@code java.net.Socket} (or
 * {@code java.net.ServerSocket} for a listener; an {@code SSLSocket} for
 * {@code tls-connect} -- a plain {@code Socket} subclass, so no extra branches), and the
 * stream built-ins dispatch on it: {@code _writeLine}/{@code _readLineStream}/
 * {@code _writeString}/{@code _readChar} call the {@code _sockWriteLine}/
 * {@code _sockReadLine}/{@code _sockWriteString}/{@code _sockReadChar} helpers here
 * (byte-at-a-time reads, unbuffered writes), {@code _readByte}/{@code _writeByte} branch
 * to the socket's input/output stream, and {@code _closeStream} closes the socket
 * directly. All methods are emitted only when the program uses a tcp or tls built-in.
 */
final class JvmSocketRuntimeBuilder {

	/** A socket-runtime method body ready to be emitted into the generated class. */
	record SocketMethod(Utf8Entry name, Utf8Entry desc, MethodCode code) {
	}

	/**
	 * The emitted method bodies plus the constants {@link JvmIoRuntimeBuilder} needs to
	 * add the socket branches to the shared stream built-ins.
	 */
	record SocketRuntime(List<SocketMethod> methods, ClassEntry socketClass, ClassEntry serverSocketClass,
			MethodRefEntry socketGetInputStream, MethodRefEntry socketGetOutputStream, MethodRefEntry socketClose,
			MethodRefEntry serverSocketClose, MethodRefEntry sockReadLine, MethodRefEntry sockWriteLine,
			MethodRefEntry sockWriteString, MethodRefEntry sockReadChar, @Nullable MethodRefEntry sockReadLinePair) {
	}

	static final String TCP_CONNECT_METHOD = "_tcpConnect";

	static final String TCP_CONNECT_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String TLS_CONNECT_METHOD = "_tlsConnect";

	static final String TLS_CONNECT_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String TLS_UPGRADE_METHOD = "_tlsUpgrade";

	static final String TLS_UPGRADE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

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

	static final String TCP_SET_TIMEOUT_METHOD = "_tcpSetTimeout";

	static final String TCP_SET_TIMEOUT_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	private static final String SOCK_READ_LINE_METHOD = "_sockReadLine";

	private static final String SOCK_READ_LINE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	private static final String SOCK_READ_LINE_PAIR_METHOD = "_sockReadLinePair";

	private static final String SOCK_WRITE_LINE_METHOD = "_sockWriteLine";

	private static final String SOCK_WRITE_LINE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	private static final String SOCK_WRITE_STRING_METHOD = "_sockWriteString";

	private static final String SOCK_WRITE_STRING_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	private static final String SOCK_READ_CHAR_METHOD = "_sockReadChar";

	private static final String SOCK_READ_CHAR_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	private final ConstantPool cp;

	private final FieldRefEntry streamsField;

	private final ClassEntry stringClass;

	private final ClassEntry longClass;

	private final MethodRefEntry longValueOf;

	private final MethodRefEntry longValue;

	private final MethodRefEntry stringLength;

	private final MethodRefEntry stringSubstring;

	private final MethodRefEntry stringConcat;

	private final ClassEntry socketClass;

	private final ClassEntry serverSocketClass;

	private final MethodRefEntry socketInit;

	private final ClassEntry sslSocketClass;

	private final MethodRefEntry sslContextGetInstance;

	private final MethodRefEntry sslContextInit;

	private final MethodRefEntry sslContextGetSocketFactory;

	private final MethodRefEntry socketFactoryCreateSocket;

	private final ClassEntry sslSocketFactoryClass;

	private final MethodRefEntry sslSocketFactoryCreateOverSocket;

	private final MethodRefEntry sslSocketGetSSLParameters;

	private final MethodRefEntry sslSocketSetSSLParameters;

	private final MethodRefEntry sslSocketStartHandshake;

	private final MethodRefEntry sslParametersSetEndpointIdAlg;

	private final ClassEntry trustManagerClass;

	private final ClassEntry thisClassRef;

	private final MethodRefEntry thisClassInit;

	private final StringEntry tlsStr;

	private final StringEntry httpsStr;

	private final StringEntry pkcs12Str;

	private final MethodRefEntry base64GetDecoder;

	private final MethodRefEntry base64Decode;

	private final ClassEntry byteArrayInputStreamClass;

	private final MethodRefEntry byteArrayInputStreamInit;

	private final MethodRefEntry keyStoreGetInstance;

	private final MethodRefEntry keyStoreLoad;

	private final ClassEntry fileInputStreamClass;

	private final MethodRefEntry fileInputStreamInit;

	private final MethodRefEntry fileInputStreamClose;

	private final MethodRefEntry kmfGetDefaultAlgorithm;

	private final MethodRefEntry kmfGetInstance;

	private final MethodRefEntry kmfInit;

	private final MethodRefEntry kmfGetKeyManagers;

	private final MethodRefEntry sslContextGetServerSocketFactory;

	private final MethodRefEntry serverSocketFactoryCreate;

	private final MethodRefEntry serverSocketFactoryCreateHost;

	private final MethodRefEntry stringToCharArray;

	private final MethodRefEntry serverSocketInitPort;

	private final MethodRefEntry serverSocketInitHost;

	private final MethodRefEntry inetGetByName;

	private final MethodRefEntry socketGetInputStream;

	private final MethodRefEntry socketGetOutputStream;

	private final MethodRefEntry socketGetLocalPort;

	private final MethodRefEntry serverSocketGetLocalPort;

	private final MethodRefEntry socketGetLocalAddress;

	private final MethodRefEntry socketGetInetAddress;

	private final MethodRefEntry socketGetPort;

	private final MethodRefEntry socketSetSoTimeout;

	private final MethodRefEntry serverSocketGetInetAddress;

	private final MethodRefEntry inetGetHostAddress;

	private final MethodRefEntry serverSocketAccept;

	private final MethodRefEntry socketClose;

	private final MethodRefEntry serverSocketClose;

	private final MethodRefEntry inputStreamRead;

	private final MethodRefEntry outputStreamWriteBytes;

	private final ClassEntry baosClass;

	private final MethodRefEntry baosInit;

	private final MethodRefEntry baosWrite;

	private final MethodRefEntry baosToByteArray;

	private final ClassEntry stringClassRef;

	private final MethodRefEntry stringInitBytes;

	private final MethodRefEntry stringGetBytes;

	private final FieldRefEntry utf8Field;

	private final MethodRefEntry addStreamRef;

	private final MethodRefEntry sockReadLineRef;

	private final MethodRefEntry sockWriteLineRef;

	private final MethodRefEntry sockWriteStringRef;

	private final MethodRefEntry sockReadCharRef;

	private final MethodRefEntry stringCodePointAt;

	private final StringEntry quoteStr;

	private final StringEntry newlineStr;

	/**
	 * {@code _strv}, minted only when the array runtime exists: a string reaching a
	 * socket write here can be a MUTABLE CHARACTER VECTOR (a subseq/copy-seq or
	 * flipped-producer result), and {@link #emitStripQuotes}'s {@code (String)} cast
	 * needs the rendered form. Without the array runtime no character vector can exist
	 * and the reference must not be minted -- the method it names is not emitted.
	 */
	private final @Nullable MethodRefEntry strvRef;

	private JvmSocketRuntimeBuilder(ConstantPool cp, ClassEntry thisClass, ClassEntry stringClass, ClassEntry longClass,
			MethodRefEntry longValueOf, MethodRefEntry longValue, MethodRefEntry stringLength,
			MethodRefEntry stringSubstring, MethodRefEntry stringConcat, boolean arrayRuntime) {
		this.cp = cp;
		this.strvRef = arrayRuntime
				? cp.methodRef(thisClass, JvmArrayRuntimeBuilder.STRV, JvmArrayRuntimeBuilder.STRV_DESC) : null;
		this.stringClass = stringClass;
		this.longClass = longClass;
		this.longValueOf = longValueOf;
		this.longValue = longValue;
		this.stringLength = stringLength;
		this.stringSubstring = stringSubstring;
		this.stringConcat = stringConcat;
		this.streamsField = cp.fieldRef(thisClass, JvmIoRuntimeBuilder.STREAMS_FIELD, JvmIoRuntimeBuilder.STREAMS_DESC);
		this.socketClass = cp.classEntry("java/net/Socket");
		this.serverSocketClass = cp.classEntry("java/net/ServerSocket");
		this.socketInit = cp.methodRef(this.socketClass, "<init>", "(Ljava/lang/String;I)V");
		ClassEntry sslContextClass = cp.classEntry("javax/net/ssl/SSLContext");
		this.sslSocketClass = cp.classEntry("javax/net/ssl/SSLSocket");
		ClassEntry sslParametersClass = cp.classEntry("javax/net/ssl/SSLParameters");
		this.sslContextGetInstance = cp.methodRef(sslContextClass, "getInstance",
				"(Ljava/lang/String;)Ljavax/net/ssl/SSLContext;");
		this.sslContextInit = cp.methodRef(sslContextClass, "init",
				"([Ljavax/net/ssl/KeyManager;[Ljavax/net/ssl/TrustManager;Ljava/security/SecureRandom;)V");
		this.sslContextGetSocketFactory = cp.methodRef(sslContextClass, "getSocketFactory",
				"()Ljavax/net/ssl/SSLSocketFactory;");
		ClassEntry socketFactoryClass = cp.classEntry("javax/net/SocketFactory");
		this.socketFactoryCreateSocket = cp.methodRef(socketFactoryClass, "createSocket",
				"(Ljava/lang/String;I)Ljava/net/Socket;");
		// The layered createSocket overload (_tlsUpgrade's transport) is declared on
		// SSLSocketFactory, not on the javax.net.SocketFactory base.
		this.sslSocketFactoryClass = cp.classEntry("javax/net/ssl/SSLSocketFactory");
		this.sslSocketFactoryCreateOverSocket = cp.methodRef(this.sslSocketFactoryClass, "createSocket",
				"(Ljava/net/Socket;Ljava/lang/String;IZ)Ljava/net/Socket;");
		this.sslSocketGetSSLParameters = cp.methodRef(this.sslSocketClass, "getSSLParameters",
				"()Ljavax/net/ssl/SSLParameters;");
		this.sslSocketSetSSLParameters = cp.methodRef(this.sslSocketClass, "setSSLParameters",
				"(Ljavax/net/ssl/SSLParameters;)V");
		this.sslSocketStartHandshake = cp.methodRef(this.sslSocketClass, "startHandshake", "()V");
		this.sslParametersSetEndpointIdAlg = cp.methodRef(sslParametersClass, "setEndpointIdentificationAlgorithm",
				"(Ljava/lang/String;)V");
		this.trustManagerClass = cp.classEntry("javax/net/ssl/TrustManager");
		this.thisClassRef = thisClass;
		this.thisClassInit = cp.methodRef(thisClass, "<init>", "()V");
		this.tlsStr = cp.stringEntry("TLS");
		this.httpsStr = cp.stringEntry("HTTPS");
		this.pkcs12Str = cp.stringEntry("PKCS12");
		ClassEntry base64Class = cp.classEntry("java/util/Base64");
		ClassEntry base64DecoderClass = cp.classEntry("java/util/Base64$Decoder");
		this.base64GetDecoder = cp.methodRef(base64Class, "getDecoder", "()Ljava/util/Base64$Decoder;");
		this.base64Decode = cp.methodRef(base64DecoderClass, "decode", "(Ljava/lang/String;)[B");
		this.byteArrayInputStreamClass = cp.classEntry("java/io/ByteArrayInputStream");
		this.byteArrayInputStreamInit = cp.methodRef(this.byteArrayInputStreamClass, "<init>", "([B)V");
		ClassEntry keyStoreClass = cp.classEntry("java/security/KeyStore");
		this.keyStoreGetInstance = cp.methodRef(keyStoreClass, "getInstance",
				"(Ljava/lang/String;)Ljava/security/KeyStore;");
		this.keyStoreLoad = cp.methodRef(keyStoreClass, "load", "(Ljava/io/InputStream;[C)V");
		this.fileInputStreamClass = cp.classEntry("java/io/FileInputStream");
		this.fileInputStreamInit = cp.methodRef(this.fileInputStreamClass, "<init>", "(Ljava/lang/String;)V");
		this.fileInputStreamClose = cp.methodRef(this.fileInputStreamClass, "close", "()V");
		ClassEntry kmfClass = cp.classEntry("javax/net/ssl/KeyManagerFactory");
		this.kmfGetDefaultAlgorithm = cp.methodRef(kmfClass, "getDefaultAlgorithm", "()Ljava/lang/String;");
		this.kmfGetInstance = cp.methodRef(kmfClass, "getInstance",
				"(Ljava/lang/String;)Ljavax/net/ssl/KeyManagerFactory;");
		this.kmfInit = cp.methodRef(kmfClass, "init", "(Ljava/security/KeyStore;[C)V");
		this.kmfGetKeyManagers = cp.methodRef(kmfClass, "getKeyManagers", "()[Ljavax/net/ssl/KeyManager;");
		this.sslContextGetServerSocketFactory = cp.methodRef(sslContextClass, "getServerSocketFactory",
				"()Ljavax/net/ssl/SSLServerSocketFactory;");
		ClassEntry serverSocketFactoryClass = cp.classEntry("javax/net/ServerSocketFactory");
		this.serverSocketFactoryCreate = cp.methodRef(serverSocketFactoryClass, "createServerSocket",
				"(II)Ljava/net/ServerSocket;");
		this.serverSocketFactoryCreateHost = cp.methodRef(serverSocketFactoryClass, "createServerSocket",
				"(IILjava/net/InetAddress;)Ljava/net/ServerSocket;");
		this.stringToCharArray = cp.methodRef(stringClass, "toCharArray", "()[C");
		this.serverSocketInitPort = cp.methodRef(this.serverSocketClass, "<init>", "(I)V");
		this.serverSocketInitHost = cp.methodRef(this.serverSocketClass, "<init>", "(IILjava/net/InetAddress;)V");
		ClassEntry inetAddressClass = cp.classEntry("java/net/InetAddress");
		this.inetGetByName = cp.methodRef(inetAddressClass, "getByName", "(Ljava/lang/String;)Ljava/net/InetAddress;");
		this.socketGetInputStream = cp.methodRef(this.socketClass, "getInputStream", "()Ljava/io/InputStream;");
		this.socketGetOutputStream = cp.methodRef(this.socketClass, "getOutputStream", "()Ljava/io/OutputStream;");
		this.socketGetLocalPort = cp.methodRef(this.socketClass, "getLocalPort", "()I");
		this.serverSocketGetLocalPort = cp.methodRef(this.serverSocketClass, "getLocalPort", "()I");
		this.socketGetLocalAddress = cp.methodRef(this.socketClass, "getLocalAddress", "()Ljava/net/InetAddress;");
		this.socketGetInetAddress = cp.methodRef(this.socketClass, "getInetAddress", "()Ljava/net/InetAddress;");
		this.socketGetPort = cp.methodRef(this.socketClass, "getPort", "()I");
		this.socketSetSoTimeout = cp.methodRef(this.socketClass, "setSoTimeout", "(I)V");
		this.serverSocketGetInetAddress = cp.methodRef(this.serverSocketClass, "getInetAddress",
				"()Ljava/net/InetAddress;");
		this.inetGetHostAddress = cp.methodRef(inetAddressClass, "getHostAddress", "()Ljava/lang/String;");
		this.serverSocketAccept = cp.methodRef(this.serverSocketClass, "accept", "()Ljava/net/Socket;");
		this.socketClose = cp.methodRef(this.socketClass, "close", "()V");
		this.serverSocketClose = cp.methodRef(this.serverSocketClass, "close", "()V");
		ClassEntry inputStreamClass = cp.classEntry("java/io/InputStream");
		this.inputStreamRead = cp.methodRef(inputStreamClass, "read", "()I");
		ClassEntry outputStreamClass = cp.classEntry("java/io/OutputStream");
		this.outputStreamWriteBytes = cp.methodRef(outputStreamClass, "write", "([B)V");
		this.baosClass = cp.classEntry("java/io/ByteArrayOutputStream");
		this.baosInit = cp.methodRef(this.baosClass, "<init>", "()V");
		this.baosWrite = cp.methodRef(this.baosClass, "write", "(I)V");
		this.baosToByteArray = cp.methodRef(this.baosClass, "toByteArray", "()[B");
		this.stringClassRef = stringClass;
		this.stringInitBytes = cp.methodRef(stringClass, "<init>", "([BIILjava/nio/charset/Charset;)V");
		this.stringGetBytes = cp.methodRef(stringClass, "getBytes", "(Ljava/nio/charset/Charset;)[B");
		ClassEntry standardCharsetsClass = cp.classEntry("java/nio/charset/StandardCharsets");
		this.utf8Field = cp.fieldRef(standardCharsetsClass, "UTF_8", "Ljava/nio/charset/Charset;");
		// The stream table's ONE allocator, emitted by JvmIoRuntimeBuilder
		// (synchronized):
		// every socket constructor here registers its socket through it.
		this.addStreamRef = cp.methodRef(thisClass, JvmIoRuntimeBuilder.ADD_STREAM_METHOD,
				JvmIoRuntimeBuilder.ADD_STREAM_DESC);
		this.sockReadLineRef = cp.methodRef(thisClass, SOCK_READ_LINE_METHOD, SOCK_READ_LINE_DESC);
		this.sockWriteLineRef = cp.methodRef(thisClass, SOCK_WRITE_LINE_METHOD, SOCK_WRITE_LINE_DESC);
		this.sockWriteStringRef = cp.methodRef(thisClass, SOCK_WRITE_STRING_METHOD, SOCK_WRITE_STRING_DESC);
		this.sockReadCharRef = cp.methodRef(thisClass, SOCK_READ_CHAR_METHOD, SOCK_READ_CHAR_DESC);
		this.stringCodePointAt = cp.methodRef(stringClass, "codePointAt", "(I)I");
		this.quoteStr = cp.stringEntry("\"");
		this.newlineStr = cp.stringEntry("\n");
	}

	static SocketRuntime build(ConstantPool cp, ClassEntry thisClass, ClassEntry stringClass, ClassEntry longClass,
			MethodRefEntry longValueOf, MethodRefEntry longValue, MethodRefEntry stringLength,
			MethodRefEntry stringSubstring, MethodRefEntry stringConcat, boolean arrayRuntime, boolean readLinePairs) {
		JvmSocketRuntimeBuilder builder = new JvmSocketRuntimeBuilder(cp, thisClass, stringClass, longClass,
				longValueOf, longValue, stringLength, stringSubstring, stringConcat, arrayRuntime);
		List<SocketMethod> methods = new ArrayList<>();
		methods.add(new SocketMethod(cp.utf8Entry(TCP_CONNECT_METHOD), cp.utf8Entry(TCP_CONNECT_DESC),
				builder.buildTcpConnect()));
		methods.add(new SocketMethod(cp.utf8Entry(TLS_CONNECT_METHOD), cp.utf8Entry(TLS_CONNECT_DESC),
				builder.buildTlsConnect()));
		methods.add(new SocketMethod(cp.utf8Entry(TLS_UPGRADE_METHOD), cp.utf8Entry(TLS_UPGRADE_DESC),
				builder.buildTlsUpgrade()));
		methods.add(new SocketMethod(cp.utf8Entry(TLS_LISTEN_METHOD), cp.utf8Entry(TLS_LISTEN_DESC),
				builder.buildTlsListen()));
		methods.add(new SocketMethod(cp.utf8Entry(TLS_LISTEN_P12_METHOD), cp.utf8Entry(TLS_LISTEN_P12_DESC),
				builder.buildTlsListenP12()));
		methods.add(new SocketMethod(cp.utf8Entry(TCP_LISTEN_METHOD), cp.utf8Entry(TCP_LISTEN_DESC),
				builder.buildTcpListen()));
		methods.add(new SocketMethod(cp.utf8Entry(TCP_ACCEPT_METHOD), cp.utf8Entry(TCP_ACCEPT_DESC),
				builder.buildTcpAccept()));
		methods.add(new SocketMethod(cp.utf8Entry(TCP_LOCAL_PORT_METHOD), cp.utf8Entry(TCP_LOCAL_PORT_DESC),
				builder.buildTcpLocalPort()));
		methods.add(new SocketMethod(cp.utf8Entry(TCP_LOCAL_ADDRESS_METHOD), cp.utf8Entry(TCP_LOCAL_ADDRESS_DESC),
				builder.buildTcpLocalAddress()));
		methods.add(new SocketMethod(cp.utf8Entry(TCP_PEER_ADDRESS_METHOD), cp.utf8Entry(TCP_PEER_ADDRESS_DESC),
				builder.buildTcpPeerAddress()));
		methods.add(new SocketMethod(cp.utf8Entry(TCP_PEER_PORT_METHOD), cp.utf8Entry(TCP_PEER_PORT_DESC),
				builder.buildTcpPeerPort()));
		methods.add(new SocketMethod(cp.utf8Entry(TCP_SET_TIMEOUT_METHOD), cp.utf8Entry(TCP_SET_TIMEOUT_DESC),
				builder.buildTcpSetTimeout()));
		methods.add(new SocketMethod(cp.utf8Entry(SOCK_READ_LINE_METHOD), cp.utf8Entry(SOCK_READ_LINE_DESC),
				builder.buildSockReadLine(false)));
		methods.add(new SocketMethod(cp.utf8Entry(SOCK_WRITE_LINE_METHOD), cp.utf8Entry(SOCK_WRITE_LINE_DESC),
				builder.buildSockWriteLine()));
		methods.add(new SocketMethod(cp.utf8Entry(SOCK_WRITE_STRING_METHOD), cp.utf8Entry(SOCK_WRITE_STRING_DESC),
				builder.buildSockWriteString()));
		methods.add(new SocketMethod(cp.utf8Entry(SOCK_READ_CHAR_METHOD), cp.utf8Entry(SOCK_READ_CHAR_DESC),
				builder.buildSockReadChar()));
		// _readLinePair's socket arm, only where the stream runtime emits that helper.
		MethodRefEntry sockReadLinePair = null;
		if (readLinePairs) {
			methods.add(new SocketMethod(cp.utf8Entry(SOCK_READ_LINE_PAIR_METHOD), cp.utf8Entry(SOCK_READ_LINE_DESC),
					builder.buildSockReadLine(true)));
			sockReadLinePair = cp.methodRef(thisClass, SOCK_READ_LINE_PAIR_METHOD, SOCK_READ_LINE_DESC);
		}
		return new SocketRuntime(methods, builder.socketClass, builder.serverSocketClass, builder.socketGetInputStream,
				builder.socketGetOutputStream, builder.socketClose, builder.serverSocketClose, builder.sockReadLineRef,
				builder.sockWriteLineRef, builder.sockWriteStringRef, builder.sockReadCharRef, sockReadLinePair);
	}

	/**
	 * {@code _tcpConnect(Object host, Object port) -> Long handle}. Strips the
	 * surrounding quotes from the host string, opens a blocking {@code Socket} and stores
	 * it in the stream table.
	 */
	private MethodCode buildTcpConnect() {
		// Slots: 0=host, 1=port, 2=h (String)
		MethodCode code = new MethodCode();
		emitStripQuotes(code, 0, 2);
		// new Socket(h, (int) ((Long) port).longValue())
		code.new_(this.socketClass);
		code.dup();
		code.aload(2);
		code.aload(1);
		emitUnboxInt(code);
		code.invokespecial(this.socketInit);
		code.invokestatic(this.addStreamRef);
		code.areturn();
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
	private MethodCode buildTlsConnect() {
		// Slots: 0=host, 1=port, 2=insecure, 3=h (String), 4=socket (SSLSocket),
		// 5=params
		MethodCode code = new MethodCode();
		emitStripQuotes(code, 0, 3);
		// SSLContext ctx = SSLContext.getInstance("TLS");
		// ctx.init(null, insecure != null ? new TrustManager[]{new Prog()} : null,
		// null);
		code.ldc(this.tlsStr);
		code.invokestatic(this.sslContextGetInstance);
		code.dup();
		code.aconst_null();
		code.aload(2);
		MethodCode.Label ifSecure = code.newLabel();
		code.ifnull(ifSecure);
		code.iconst_1();
		code.anewarray(this.trustManagerClass);
		code.dup();
		code.iconst_0();
		code.new_(this.thisClassRef);
		code.dup();
		code.invokespecial(this.thisClassInit);
		code.aastore();
		MethodCode.Label gotoInit = code.newLabel();
		code.goto_(gotoInit);
		code.labelBinding(ifSecure);
		code.aconst_null();
		code.labelBinding(gotoInit);
		code.aconst_null();
		code.invokevirtual(this.sslContextInit);
		// socket = (SSLSocket) ctx.getSocketFactory().createSocket(h, (int) port)
		code.invokevirtual(this.sslContextGetSocketFactory);
		code.aload(3);
		code.aload(1);
		emitUnboxInt(code);
		code.invokevirtual(this.socketFactoryCreateSocket);
		code.checkcast(this.sslSocketClass);
		code.astore(4);
		// endpoint identification only on the verifying path:
		// if (insecure == null) { params = socket.getSSLParameters();
		// params.setEndpointIdentificationAlgorithm("HTTPS");
		// socket.setSSLParameters(params); }
		code.aload(2);
		MethodCode.Label ifInsecure = code.newLabel();
		code.ifnonnull(ifInsecure);
		code.aload(4);
		code.invokevirtual(this.sslSocketGetSSLParameters);
		code.astore(5);
		code.aload(5);
		code.ldc(this.httpsStr);
		code.invokevirtual(this.sslParametersSetEndpointIdAlg);
		code.aload(4);
		code.aload(5);
		code.invokevirtual(this.sslSocketSetSSLParameters);
		code.labelBinding(ifInsecure);
		// socket.startHandshake();
		code.aload(4);
		code.invokevirtual(this.sslSocketStartHandshake);
		code.aload(4);
		code.invokestatic(this.addStreamRef);
		code.areturn();
		return code;
	}

	/**
	 * {@code _tlsUpgrade(Object handle, Object host, Object insecure) -> Long handle}.
	 * Wraps an ALREADY-CONNECTED socket entry in TLS as a client (the substrate of the
	 * {@code cl+ssl} shim's {@code make-ssl-client-stream}): same per-call
	 * {@code SSLContext} and trust policy as {@link #buildTlsConnect} -- a {@code null}
	 * (nil) {@code insecure} verifies against the JDK trust store with HTTPS-style
	 * endpoint identification, non-nil installs the generated program class as the
	 * trust-all {@code X509TrustManager} -- but the handshake runs over the EXISTING
	 * connection via {@code SSLSocketFactory.createSocket(socket, host, port, true)}
	 * instead of opening a new one. The wrapping {@code SSLSocket} is stored as a NEW
	 * stream-table entry (a plain {@code Socket} subclass, so every socket branch of the
	 * stream built-ins works on it unchanged).
	 */
	private MethodCode buildTlsUpgrade() {
		// Slots: 0=handle, 1=host, 2=insecure, 3=h (String), 4=sock (Socket),
		// 5=tls (SSLSocket), 6=params
		MethodCode code = new MethodCode();
		emitStripQuotes(code, 1, 3);
		// sock = (Socket) _streams[(int) handle];
		emitLoadStreamEntry(code, 0);
		code.checkcast(this.socketClass);
		code.astore(4);
		// SSLContext ctx = SSLContext.getInstance("TLS");
		// ctx.init(null, insecure != null ? new TrustManager[]{new Prog()} : null,
		// null);
		code.ldc(this.tlsStr);
		code.invokestatic(this.sslContextGetInstance);
		code.dup();
		code.aconst_null();
		code.aload(2);
		MethodCode.Label ifSecure = code.newLabel();
		code.ifnull(ifSecure);
		code.iconst_1();
		code.anewarray(this.trustManagerClass);
		code.dup();
		code.iconst_0();
		code.new_(this.thisClassRef);
		code.dup();
		code.invokespecial(this.thisClassInit);
		code.aastore();
		MethodCode.Label gotoInit = code.newLabel();
		code.goto_(gotoInit);
		code.labelBinding(ifSecure);
		code.aconst_null();
		code.labelBinding(gotoInit);
		code.aconst_null();
		code.invokevirtual(this.sslContextInit);
		// tls = (SSLSocket) ((SSLSocketFactory) ctx.getSocketFactory())
		// .createSocket(sock, h, sock.getPort(), true)
		code.invokevirtual(this.sslContextGetSocketFactory);
		code.checkcast(this.sslSocketFactoryClass);
		code.aload(4);
		code.aload(3);
		code.aload(4);
		code.invokevirtual(this.socketGetPort);
		code.iconst_1();
		code.invokevirtual(this.sslSocketFactoryCreateOverSocket);
		code.checkcast(this.sslSocketClass);
		code.astore(5);
		// endpoint identification only on the verifying path (see buildTlsConnect)
		code.aload(2);
		MethodCode.Label ifInsecure = code.newLabel();
		code.ifnonnull(ifInsecure);
		code.aload(5);
		code.invokevirtual(this.sslSocketGetSSLParameters);
		code.astore(6);
		code.aload(6);
		code.ldc(this.httpsStr);
		code.invokevirtual(this.sslParametersSetEndpointIdAlg);
		code.aload(5);
		code.aload(6);
		code.invokevirtual(this.sslSocketSetSSLParameters);
		code.labelBinding(ifInsecure);
		// tls.startHandshake();
		code.aload(5);
		code.invokevirtual(this.sslSocketStartHandshake);
		code.aload(5);
		code.invokestatic(this.addStreamRef);
		code.areturn();
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
	private MethodCode buildTlsListen() {
		// Slots: 0=keystore, 1=password, 2=port, 3=host, 4=path (String),
		// 5=pw (String, then char[]), 6=store (KeyStore), 7=in (FileInputStream),
		// 8=kms (KeyManager[]), 9=ctx (SSLContext), 10=factory, 11=p (int),
		// 12=h (String)
		MethodCode code = new MethodCode();
		emitStripQuotes(code, 0, 4);
		emitStripQuotes(code, 1, 5);
		// pw = pw.toCharArray()
		code.aload(5);
		code.invokevirtual(this.stringToCharArray);
		code.astore(5);
		// store = KeyStore.getInstance("PKCS12");
		code.ldc(this.pkcs12Str);
		code.invokestatic(this.keyStoreGetInstance);
		code.astore(6);
		// in = new FileInputStream(path); store.load(in, pw); in.close();
		code.new_(this.fileInputStreamClass);
		code.dup();
		code.aload(4);
		code.invokespecial(this.fileInputStreamInit);
		code.astore(7);
		code.aload(6);
		code.aload(7);
		code.aload(5);
		code.invokevirtual(this.keyStoreLoad);
		code.aload(7);
		code.invokevirtual(this.fileInputStreamClose);
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
	private MethodCode buildTlsListenP12() {
		// Slots: 0=base64, 1=password, 2=port, 3=host, 4=b64 (String),
		// 5=pw (String, then char[]), 6=store (KeyStore), 7=in (ByteArrayInputStream),
		// 8=kms, 9=ctx, 10=factory, 11=p (int), 12=h (String)
		MethodCode code = new MethodCode();
		emitStripQuotes(code, 0, 4);
		emitStripQuotes(code, 1, 5);
		// pw = pw.toCharArray()
		code.aload(5);
		code.invokevirtual(this.stringToCharArray);
		code.astore(5);
		// store = KeyStore.getInstance("PKCS12");
		code.ldc(this.pkcs12Str);
		code.invokestatic(this.keyStoreGetInstance);
		code.astore(6);
		// in = new ByteArrayInputStream(Base64.getDecoder().decode(b64));
		code.new_(this.byteArrayInputStreamClass);
		code.dup();
		code.invokestatic(this.base64GetDecoder);
		code.aload(4);
		code.invokevirtual(this.base64Decode);
		code.invokespecial(this.byteArrayInputStreamInit);
		code.astore(7);
		// store.load(in, pw);
		code.aload(6);
		code.aload(7);
		code.aload(5);
		code.invokevirtual(this.keyStoreLoad);
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
	private void emitKmfToServerSocket(MethodCode code) {
		// kms = KeyManagerFactory.getInstance(getDefaultAlgorithm()) initialized with
		// (store, pw)
		code.invokestatic(this.kmfGetDefaultAlgorithm);
		code.invokestatic(this.kmfGetInstance);
		code.dup();
		code.aload(6);
		code.aload(5);
		code.invokevirtual(this.kmfInit);
		code.invokevirtual(this.kmfGetKeyManagers);
		code.astore(8);
		// ctx = SSLContext.getInstance("TLS"); ctx.init(kms, null, null);
		code.ldc(this.tlsStr);
		code.invokestatic(this.sslContextGetInstance);
		code.astore(9);
		code.aload(9);
		code.aload(8);
		code.aconst_null();
		code.aconst_null();
		code.invokevirtual(this.sslContextInit);
		// factory = ctx.getServerSocketFactory(); p = (int) port;
		code.aload(9);
		code.invokevirtual(this.sslContextGetServerSocketFactory);
		code.astore(10);
		code.aload(2);
		emitUnboxInt(code);
		code.istore(11);
		code.aload(3);
		MethodCode.Label ifHost = code.newLabel();
		code.ifnonnull(ifHost);
		// factory.createServerSocket(p, 50)
		code.aload(10);
		code.iload(11);
		code.loadConstant(50);
		code.invokevirtual(this.serverSocketFactoryCreate);
		code.invokestatic(this.addStreamRef);
		code.areturn();
		code.labelBinding(ifHost);
		// factory.createServerSocket(p, 50, InetAddress.getByName(h))
		emitStripQuotes(code, 3, 12);
		code.aload(10);
		code.iload(11);
		code.loadConstant(50);
		code.aload(12);
		code.invokestatic(this.inetGetByName);
		code.invokevirtual(this.serverSocketFactoryCreateHost);
		code.invokestatic(this.addStreamRef);
		code.areturn();
	}

	/**
	 * {@code _tcpListen(Object port, Object host) -> Long handle}. Binds a
	 * {@code ServerSocket} on the port (0 picks an ephemeral port); a {@code null} host
	 * (nil) binds all interfaces.
	 */
	private MethodCode buildTcpListen() {
		// Slots: 0=port, 1=host, 2=p (int), 3=h (String)
		MethodCode code = new MethodCode();
		code.aload(0);
		emitUnboxInt(code);
		code.istore(2);
		code.aload(1);
		MethodCode.Label ifHost = code.newLabel();
		code.ifnonnull(ifHost);
		// new ServerSocket(p)
		code.new_(this.serverSocketClass);
		code.dup();
		code.iload(2);
		code.invokespecial(this.serverSocketInitPort);
		code.invokestatic(this.addStreamRef);
		code.areturn();
		code.labelBinding(ifHost);
		// new ServerSocket(p, 50, InetAddress.getByName(h))
		emitStripQuotes(code, 1, 3);
		code.new_(this.serverSocketClass);
		code.dup();
		code.iload(2);
		code.loadConstant(50);
		code.aload(3);
		code.invokestatic(this.inetGetByName);
		code.invokespecial(this.serverSocketInitHost);
		code.invokestatic(this.addStreamRef);
		code.areturn();
		return code;
	}

	/**
	 * {@code _tcpAccept(Object handle) -> Long handle}. Blocks in
	 * {@code ServerSocket.accept()} and stores the accepted socket in the stream table.
	 */
	private MethodCode buildTcpAccept() {
		MethodCode code = new MethodCode();
		emitLoadStreamEntry(code, 0);
		code.checkcast(this.serverSocketClass);
		code.invokevirtual(this.serverSocketAccept);
		code.invokestatic(this.addStreamRef);
		code.areturn();
		return code;
	}

	/**
	 * {@code _tcpLocalPort(Object handle) -> Long}. Returns the local port of a listener
	 * or socket entry.
	 */
	private MethodCode buildTcpLocalPort() {
		// Slots: 0=handle, 1=entry
		MethodCode code = new MethodCode();
		emitLoadStreamEntry(code, 0);
		code.astore(1);
		code.aload(1);
		code.instanceOf(this.serverSocketClass);
		MethodCode.Label ifSock = code.newLabel();
		code.ifeq(ifSock);
		code.aload(1);
		code.checkcast(this.serverSocketClass);
		code.invokevirtual(this.serverSocketGetLocalPort);
		emitBoxLong(code);
		code.areturn();
		code.labelBinding(ifSock);
		code.aload(1);
		code.checkcast(this.socketClass);
		code.invokevirtual(this.socketGetLocalPort);
		emitBoxLong(code);
		code.areturn();
		return code;
	}

	/**
	 * {@code _tcpSetTimeout(Object handle, Object ms) -> Object}. Sets the socket entry's
	 * read deadline ({@code Socket.setSoTimeout}): a boxed {@code Long} millisecond
	 * count, or {@code null} (nil) to clear it. Returns the {@code ms} argument. A
	 * non-socket entry fails on the {@code CHECKCAST} (a {@code ClassCastException},
	 * catchable like the other runtime failures).
	 */
	private MethodCode buildTcpSetTimeout() {
		// Slots: 0=handle, 1=ms
		MethodCode code = new MethodCode();
		emitLoadStreamEntry(code, 0);
		code.checkcast(this.socketClass);
		code.aload(1);
		MethodCode.Label ifNil = code.newLabel();
		code.ifnull(ifNil);
		code.aload(1);
		emitUnboxInt(code);
		MethodCode.Label gotoSet = code.newLabel();
		code.goto_(gotoSet);
		code.labelBinding(ifNil);
		code.iconst_0();
		code.labelBinding(gotoSet);
		code.invokevirtual(this.socketSetSoTimeout);
		code.aload(1);
		code.areturn();
		return code;
	}

	/**
	 * {@code _tcpLocalAddress(Object handle) -> String}. Returns the local/bound IP
	 * address of a listener or socket entry, quote-framed like every runtime string.
	 */
	private MethodCode buildTcpLocalAddress() {
		// Slots: 0=handle, 1=entry
		MethodCode code = new MethodCode();
		emitLoadStreamEntry(code, 0);
		code.astore(1);
		code.aload(1);
		code.instanceOf(this.serverSocketClass);
		MethodCode.Label ifSock = code.newLabel();
		code.ifeq(ifSock);
		// "\"".concat(((ServerSocket) entry).getInetAddress().getHostAddress()) + "\""
		code.ldc(this.quoteStr);
		code.aload(1);
		code.checkcast(this.serverSocketClass);
		code.invokevirtual(this.serverSocketGetInetAddress);
		emitHostAddressReturn(code);
		code.labelBinding(ifSock);
		code.ldc(this.quoteStr);
		code.aload(1);
		code.checkcast(this.socketClass);
		code.invokevirtual(this.socketGetLocalAddress);
		emitHostAddressReturn(code);
		return code;
	}

	/**
	 * {@code _tcpPeerAddress(Object handle) -> String}. Returns the remote IP address of
	 * a connected socket entry, quote-framed like every runtime string.
	 */
	private MethodCode buildTcpPeerAddress() {
		// Slots: 0=handle, 1=entry
		MethodCode code = new MethodCode();
		emitLoadStreamEntry(code, 0);
		code.astore(1);
		code.ldc(this.quoteStr);
		code.aload(1);
		code.checkcast(this.socketClass);
		code.invokevirtual(this.socketGetInetAddress);
		emitHostAddressReturn(code);
		return code;
	}

	/**
	 * {@code _tcpPeerPort(Object handle) -> Long}. Returns the remote port of a connected
	 * socket entry.
	 */
	private MethodCode buildTcpPeerPort() {
		MethodCode code = new MethodCode();
		emitLoadStreamEntry(code, 0);
		code.checkcast(this.socketClass);
		code.invokevirtual(this.socketGetPort);
		emitBoxLong(code);
		code.areturn();
		return code;
	}

	/**
	 * Emits the shared tail of the address accessors: with the opening quote string and
	 * an {@code InetAddress} on the stack, appends
	 * {@code .getHostAddress()}-concat-closing-quote and returns.
	 */
	private void emitHostAddressReturn(MethodCode code) {
		code.invokevirtual(this.inetGetHostAddress);
		code.invokevirtual(this.stringConcat);
		code.ldc(this.quoteStr);
		code.invokevirtual(this.stringConcat);
		code.areturn();
	}

	/**
	 * {@code _sockReadLine(Object socket) -> Object}. Reads bytes up to a {@code \n}
	 * (exclusive, one trailing {@code \r} stripped), decodes UTF-8 and wraps the line
	 * with the internal {@code '"'} prefix/suffix; returns {@code null} (nil) when the
	 * peer closed before any byte arrived. With {@code pair} it is
	 * {@code _sockReadLinePair}, answering the cons {@code (line . missing-newline-p)} --
	 * {@code "T"} when the peer's close ended the line -- for
	 * {@code JvmIoRuntimeBuilder}'s {@code _readLinePair}.
	 */
	private MethodCode buildSockReadLine(boolean pair) {
		// Slots: 0=socket, 1=in (InputStream), 2=baos, 3=b (int), 4=bytes, 5=len (int)
		MethodCode code = new MethodCode();
		// in = ((Socket) socket).getInputStream();
		code.aload(0);
		code.checkcast(this.socketClass);
		code.invokevirtual(this.socketGetInputStream);
		code.astore(1);
		// baos = new ByteArrayOutputStream();
		code.new_(this.baosClass);
		code.dup();
		code.invokespecial(this.baosInit);
		code.astore(2);
		// b = in.read(); if (b < 0) return null;
		code.aload(1);
		code.invokevirtual(this.inputStreamRead);
		code.istore(3);
		code.iload(3);
		MethodCode.Label ifData = code.newLabel();
		code.ifge(ifData);
		code.aconst_null();
		code.areturn();
		code.labelBinding(ifData);
		// while (b >= 0 && b != '\n') { baos.write(b); b = in.read(); }
		MethodCode.Label loopStart = code.newBoundLabel();
		code.iload(3);
		MethodCode.Label ifEof = code.newLabel();
		code.iflt(ifEof);
		code.iload(3);
		code.loadConstant('\n');
		MethodCode.Label ifNl = code.newLabel();
		code.if_icmpeq(ifNl);
		code.aload(2);
		code.iload(3);
		code.invokevirtual(this.baosWrite);
		code.aload(1);
		code.invokevirtual(this.inputStreamRead);
		code.istore(3);
		code.goto_(loopStart);
		code.labelBinding(ifEof);
		code.labelBinding(ifNl);
		// bytes = baos.toByteArray(); len = bytes.length;
		code.aload(2);
		code.invokevirtual(this.baosToByteArray);
		code.astore(4);
		code.aload(4);
		code.arraylength();
		code.istore(5);
		// if (len > 0 && bytes[len - 1] == '\r') len--;
		code.iload(5);
		MethodCode.Label ifEmpty = code.newLabel();
		code.ifle(ifEmpty);
		code.aload(4);
		code.iload(5);
		code.iconst_1();
		code.isub();
		code.baload();
		code.loadConstant('\r');
		MethodCode.Label ifNoCr = code.newLabel();
		code.if_icmpne(ifNoCr);
		code.iload(5);
		code.iconst_1();
		code.isub();
		code.istore(5);
		code.labelBinding(ifEmpty);
		code.labelBinding(ifNoCr);
		if (pair) {
			// return new Object[] {line, b < 0 ? "T" : null};
			code.iconst_2();
			code.anewarray(this.cp.classEntry("java/lang/Object"));
			code.dup();
			code.iconst_0();
		}
		// return "\"".concat(new String(bytes, 0, len, UTF_8)).concat("\"");
		code.ldc(this.quoteStr);
		code.new_(this.stringClassRef);
		code.dup();
		code.aload(4);
		code.iconst_0();
		code.iload(5);
		code.getstatic(this.utf8Field);
		code.invokespecial(this.stringInitBytes);
		code.invokevirtual(this.stringConcat);
		code.ldc(this.quoteStr);
		code.invokevirtual(this.stringConcat);
		if (pair) {
			code.aastore();
			code.dup();
			code.iconst_1();
			code.iload(3);
			MethodCode.Label closed = code.newLabel();
			code.iflt(closed);
			code.aconst_null();
			MethodCode.Label stored = code.newLabel();
			code.goto_(stored);
			code.labelBinding(closed);
			code.ldc(this.cp.stringEntry("T"));
			code.labelBinding(stored);
			code.aastore();
		}
		code.areturn();
		return code;
	}

	/**
	 * {@code _sockWriteLine(Object str, Object socket) -> str}. Writes the string content
	 * (without the surrounding quotes) plus a newline to the socket, UTF-8, sent
	 * immediately (socket output streams are unbuffered).
	 */
	private MethodCode buildSockWriteLine() {
		// Slots: 0=str, 1=socket, 2=content (String)
		MethodCode code = new MethodCode();
		emitStripQuotes(code, 0, 2);
		// content = content.concat("\n");
		code.aload(2);
		code.ldc(this.newlineStr);
		code.invokevirtual(this.stringConcat);
		code.astore(2);
		// ((Socket) socket).getOutputStream().write(content.getBytes(UTF_8));
		code.aload(1);
		code.checkcast(this.socketClass);
		code.invokevirtual(this.socketGetOutputStream);
		code.aload(2);
		code.getstatic(this.utf8Field);
		code.invokevirtual(this.stringGetBytes);
		code.invokevirtual(this.outputStreamWriteBytes);
		code.aload(0);
		code.areturn();
		return code;
	}

	/**
	 * {@code _sockWriteString(Object str, Object socket) -> str}.
	 * {@link #buildSockWriteLine} without the newline: the string content (quotes
	 * stripped) UTF-8-encoded onto the socket, sent immediately. Reached from
	 * {@code _writeString} -- and therefore from {@code write-char}, which lowers to
	 * {@code write-string} on every backend.
	 */
	private MethodCode buildSockWriteString() {
		// Slots: 0=str, 1=socket, 2=content (String)
		MethodCode code = new MethodCode();
		emitStripQuotes(code, 0, 2);
		// ((Socket) socket).getOutputStream().write(content.getBytes(UTF_8));
		code.aload(1);
		code.checkcast(this.socketClass);
		code.invokevirtual(this.socketGetOutputStream);
		code.aload(2);
		code.getstatic(this.utf8Field);
		code.invokevirtual(this.stringGetBytes);
		code.invokevirtual(this.outputStreamWriteBytes);
		code.aload(0);
		code.areturn();
		return code;
	}

	/**
	 * {@code _sockReadChar(Object socket) -> Object}. One character -- a Unicode CODE
	 * POINT boxed as {@code int[1]}, the runtime CHARACTER representation -- assembled
	 * from its UTF-8 sequence BYTE-WISE off the socket's input stream; {@code null} (nil)
	 * when the peer closed before any byte arrived, which {@code _readChar} turns into
	 * its eof contract. Byte-wise is the point: the entry is a raw {@code Socket} and a
	 * {@code Reader} wrapped around it would read ahead and swallow bytes a following
	 * {@code read-byte}/{@code read-line} owes the caller. An invalid lead byte stands
	 * alone and decodes to whatever the UTF-8 decoder yields for it (U+FFFD) -- the same
	 * answer as the interpreter's {@code SocketSupport.readChar} and the component's
	 * {@code %sock-read-char-f}.
	 */
	private MethodCode buildSockReadChar() {
		// Slots: 0=socket, 1=in (InputStream), 2=b0 (int), 3=n (int), 4=baos, 5=b (int)
		MethodCode code = new MethodCode();
		// in = ((Socket) socket).getInputStream();
		code.aload(0);
		code.checkcast(this.socketClass);
		code.invokevirtual(this.socketGetInputStream);
		code.astore(1);
		// b0 = in.read(); if (b0 < 0) return null;
		code.aload(1);
		code.invokevirtual(this.inputStreamRead);
		code.istore(2);
		code.iload(2);
		MethodCode.Label ifData = code.newLabel();
		code.ifge(ifData);
		code.aconst_null();
		code.areturn();
		code.labelBinding(ifData);
		// if (b0 >= 128) goto MULTI; return int[1]{b0};
		code.iload(2);
		code.loadConstant(128);
		MethodCode.Label ifMulti = code.newLabel();
		code.if_icmpge(ifMulti);
		emitBoxCodePoint(code, 2);
		// MULTI: n = b0 < 192 ? 0 : b0 < 224 ? 1 : b0 < 240 ? 2 : 3;
		code.labelBinding(ifMulti);
		emitContinuationCount(code);
		code.istore(3);
		// baos = new ByteArrayOutputStream(); baos.write(b0);
		code.new_(this.baosClass);
		code.dup();
		code.invokespecial(this.baosInit);
		code.astore(4);
		code.aload(4);
		code.iload(2);
		code.invokevirtual(this.baosWrite);
		// while (n > 0) { b = in.read(); if (b < 0) break; baos.write(b); n--; }
		MethodCode.Label loopStart = code.newBoundLabel();
		code.iload(3);
		MethodCode.Label ifDone = code.newLabel();
		code.ifle(ifDone);
		code.aload(1);
		code.invokevirtual(this.inputStreamRead);
		code.istore(5);
		code.iload(5);
		MethodCode.Label ifEof = code.newLabel();
		code.iflt(ifEof);
		code.aload(4);
		code.iload(5);
		code.invokevirtual(this.baosWrite);
		code.iinc(3, -1); // -1
		code.goto_(loopStart);
		code.labelBinding(ifDone);
		code.labelBinding(ifEof);
		// b0 = new String(baos.toByteArray(), 0, length, UTF_8).codePointAt(0);
		code.new_(this.stringClassRef);
		code.dup();
		code.aload(4);
		code.invokevirtual(this.baosToByteArray);
		code.dup();
		code.astore(5);
		code.iconst_0();
		code.aload(5);
		code.arraylength();
		code.getstatic(this.utf8Field);
		code.invokespecial(this.stringInitBytes);
		code.iconst_0();
		code.invokevirtual(this.stringCodePointAt);
		code.istore(2);
		emitBoxCodePoint(code, 2);
		return code;
	}

	/**
	 * Emits the UTF-8 continuation count of the lead byte in slot 2 onto the stack:
	 * {@code b0 < 192 ? 0 : b0 < 224 ? 1 : b0 < 240 ? 2 : 3} (an invalid lead byte -- a
	 * stray continuation -- counts 0 and stands alone).
	 */
	private void emitContinuationCount(MethodCode code) {
		code.iload(2);
		code.loadConstant(192);
		MethodCode.Label ifNot0 = code.newLabel();
		code.if_icmpge(ifNot0);
		code.iconst_0();
		MethodCode.Label done = code.newLabel();
		code.goto_(done);
		code.labelBinding(ifNot0);
		code.iload(2);
		code.loadConstant(224);
		MethodCode.Label ifNot1 = code.newLabel();
		code.if_icmpge(ifNot1);
		code.iconst_1();
		code.goto_(done);
		code.labelBinding(ifNot1);
		code.iload(2);
		code.loadConstant(240);
		MethodCode.Label ifNot2 = code.newLabel();
		code.if_icmpge(ifNot2);
		code.iconst_2();
		code.goto_(done);
		code.labelBinding(ifNot2);
		code.iconst_3();
		code.labelBinding(done);
	}

	/** Emits {@code return int[1]{slot}} -- the runtime CHARACTER representation. */
	private static void emitBoxCodePoint(MethodCode code, int slot) {
		code.iconst_1();
		code.newarray(TypeKind.INT);
		code.dup();
		code.iconst_0();
		code.iload(slot);
		code.iastore();
		code.areturn();
	}

	/** Emits {@code slot<target> = ((String) slot<src>).substring(1, length() - 1)}. */
	private void emitStripQuotes(MethodCode code, int srcSlot, int targetSlot) {
		code.aload(srcSlot);
		// A mutable character vector renders to its quote-framed string first; any
		// other value passes through (null without the array runtime -- no character
		// vector can exist then).
		if (this.strvRef != null) {
			code.invokestatic(this.strvRef);
		}
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

	/**
	 * Emits {@code _streams[(int) ((Long) slot<handleSlot>).longValue()]} (an Object).
	 */
	private void emitLoadStreamEntry(MethodCode code, int handleSlot) {
		code.getstatic(this.streamsField);
		code.aload(handleSlot);
		emitUnboxInt(code);
		code.aaload();
	}

	/** Emits {@code (int) ((Long) <stack top>).longValue()}. */
	private void emitUnboxInt(MethodCode code) {
		code.checkcast(this.longClass);
		code.invokevirtual(this.longValue);
		code.l2i();
	}

	/** Emits {@code Long.valueOf((long) <stack top int>)}. */
	private void emitBoxLong(MethodCode code) {
		code.i2l();
		code.invokestatic(this.longValueOf);
	}

}
