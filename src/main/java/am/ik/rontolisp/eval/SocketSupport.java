package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.jspecify.annotations.Nullable;

/**
 * Performs blocking TCP socket operations for the interpreter backend, using the JDK
 * {@link Socket} / {@link ServerSocket}. A socket is stored directly in the interpreter's
 * stream table (both types implement {@link Closeable}, so {@code close} needs no special
 * case) and the stream built-ins ({@code read-line}, {@code write-line},
 * {@code write-string}, {@code read-char}, {@code read-byte}, {@code write-byte})
 * dispatch to the helpers here for socket entries. Reads are byte-at-a-time (no readahead
 * buffer is held between calls) and writes go out immediately, matching the compiled
 * backends. This is the seam the browser playground substitutes (see
 * {@code src/web/java/.../eval/Target_SocketSupport.java}), where every operation signals
 * an error: the browser sandbox provides no host TCP sockets.
 */
final class SocketSupport {

	private SocketSupport() {
	}

	/**
	 * Opens a blocking TCP connection to the given host and port.
	 * @param host a hostname or IP literal
	 * @param port the remote TCP port
	 * @return the connected socket
	 * @throws LispEvalException if the connection cannot be established
	 */
	static Socket connect(String host, int port) {
		try {
			return new Socket(host, port);
		}
		catch (IOException ex) {
			throw new LispEvalException("tcp-connect: cannot connect to " + host + ":" + port + ": " + ex.getMessage());
		}
	}

	/**
	 * Opens a blocking TCP connection and performs a TLS handshake. By default the server
	 * certificate is validated against the JDK default trust store and the hostname is
	 * verified (HTTPS-style endpoint identification); {@code insecure} disables both
	 * checks (a trust-all manager, no endpoint identification) for development against
	 * self-signed servers. A fresh {@link SSLContext} is initialized per call so the
	 * {@code javax.net.ssl.trustStore} system properties are re-read on every connection
	 * (unlike the process-wide cached {@code SSLSocketFactory.getDefault()}). An
	 * {@link SSLSocket} is a {@link Socket}, so the returned socket goes into the stream
	 * table unchanged and every stream built-in works on it as-is.
	 * @param host a hostname or IP literal
	 * @param port the remote TCP port
	 * @param insecure whether to skip certificate and hostname verification
	 * @return the connected socket with the handshake completed
	 * @throws LispEvalException if the connection or the handshake fails
	 */
	static Socket connectTls(String host, int port, boolean insecure) {
		try {
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, insecure ? new TrustManager[] { new TrustAllManager() } : null, null);
			SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(host, port);
			if (!insecure) {
				SSLParameters parameters = socket.getSSLParameters();
				parameters.setEndpointIdentificationAlgorithm("HTTPS");
				socket.setSSLParameters(parameters);
			}
			socket.startHandshake();
			return socket;
		}
		catch (IOException | GeneralSecurityException ex) {
			throw new LispEvalException("tls-connect: cannot connect to " + host + ":" + port + ": " + ex.getMessage());
		}
	}

	/**
	 * Wraps an ALREADY-CONNECTED socket in TLS as a client -- the
	 * {@code rontolisp:tls-upgrade} primitive behind the {@code cl+ssl} shim's
	 * {@code make-ssl-client-stream}. Identical trust policy to {@link #connectTls}
	 * (fresh {@link SSLContext} per call, JDK default trust store + HTTPS-style endpoint
	 * identification against {@code host}, both skipped by {@code insecure}); the
	 * difference is the transport: the handshake runs over the given socket's existing
	 * connection ({@code SSLSocketFactory.createSocket(socket, host, port, true)})
	 * instead of opening a new one, which is what an HTTP client that has already
	 * connected (and possibly issued a proxy CONNECT) needs. The returned
	 * {@link SSLSocket} closes the underlying socket when closed.
	 * @param socket the connected TCP socket to upgrade
	 * @param host the server name the certificate is verified against (and sent as SNI)
	 * @param insecure whether to skip certificate and hostname verification
	 * @return the wrapping socket with the handshake completed
	 * @throws LispEvalException if the handshake fails
	 */
	static Socket upgradeTls(Socket socket, String host, boolean insecure) {
		try {
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, insecure ? new TrustManager[] { new TrustAllManager() } : null, null);
			SSLSocket tls = (SSLSocket) ((javax.net.ssl.SSLSocketFactory) context.getSocketFactory())
				.createSocket(socket, host, socket.getPort(), true);
			if (!insecure) {
				SSLParameters parameters = tls.getSSLParameters();
				parameters.setEndpointIdentificationAlgorithm("HTTPS");
				tls.setSSLParameters(parameters);
			}
			tls.startHandshake();
			return tls;
		}
		catch (IOException | GeneralSecurityException ex) {
			throw new LispEvalException(
					"tls-upgrade: cannot upgrade the stream to TLS against " + host + ": " + ex.getMessage());
		}
	}

	/** Accepts any peer certificate chain; used only by the {@code :insecure} opt-out. */
	private static final class TrustAllManager implements X509TrustManager {

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) {
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) {
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}

	}

	/**
	 * Binds a listening TCP socket.
	 * @param port the local port (0 picks an ephemeral port)
	 * @param host the local address to bind, or {@code null} for all interfaces
	 * @return the listening server socket
	 * @throws LispEvalException if the address cannot be bound
	 */
	static ServerSocket listen(int port, @Nullable String host) {
		try {
			ServerSocket listener = new ServerSocket();
			listener.setReuseAddress(true);
			InetAddress address = (host == null) ? null : InetAddress.getByName(host);
			listener.bind(new InetSocketAddress(address, port));
			return listener;
		}
		catch (IOException ex) {
			throw new LispEvalException("tcp-listen: cannot listen on " + ((host == null) ? "*" : host) + ":" + port
					+ ": " + ex.getMessage());
		}
	}

	/**
	 * Binds a listening TLS socket serving the certificate from a PKCS12 keystore file.
	 * The listener is a {@code ServerSocket} subclass, so it lives in the stream table
	 * like a plain listener and {@code tcp-accept}/{@code tcp-local-port}/{@code close}
	 * work on it unchanged; an accepted {@code SSLSocket} performs its TLS handshake
	 * lazily on the first read/write.
	 * @param keyStorePath a PKCS12 keystore file holding the server key and certificate
	 * @param password the keystore (and key) password
	 * @param port the local port (0 picks an ephemeral port)
	 * @param host the local address to bind, or {@code null} for all interfaces
	 * @return the listening TLS server socket
	 * @throws LispEvalException if the keystore cannot be loaded or the address cannot be
	 * bound
	 */
	static ServerSocket listenTls(String keyStorePath, String password, int port, @Nullable String host) {
		try {
			KeyStore keyStore = KeyStore.getInstance("PKCS12");
			try (InputStream in = new FileInputStream(keyStorePath)) {
				keyStore.load(in, password.toCharArray());
			}
			KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			kmf.init(keyStore, password.toCharArray());
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(kmf.getKeyManagers(), null, null);
			InetAddress address = (host == null) ? null : InetAddress.getByName(host);
			return context.getServerSocketFactory().createServerSocket(port, 50, address);
		}
		catch (IOException | GeneralSecurityException ex) {
			throw new LispEvalException("tls-listen: cannot listen on " + ((host == null) ? "*" : host) + ":" + port
					+ ": " + ex.getMessage());
		}
	}

	/**
	 * The fixed password of the in-memory PKCS12 keystore built from PEM material
	 * (package-visible so {@link TlsPemSupport} can serialize the keystore for the
	 * compile-time inliner with the same password).
	 */
	static final char[] PEM_KEYSTORE_PASSWORD = "rontolisp-pem".toCharArray();

	/**
	 * Builds an in-memory PKCS12 {@link KeyStore} from a PEM certificate chain and an
	 * unencrypted PKCS#8 private key. Shared by the interpreter ({@link #listenTlsPem})
	 * and the JVM/WASM compile-time inliner, which serializes the result and embeds it,
	 * so both backends parse PEM through exactly this one code path.
	 * @param certPath a PEM file holding one or more {@code CERTIFICATE} blocks (leaf
	 * first)
	 * @param keyPath a PEM file holding one unencrypted PKCS#8 {@code PRIVATE KEY} block
	 * @return a single-entry PKCS12 keystore protected by {@link #PEM_KEYSTORE_PASSWORD}
	 * @throws IOException if a file cannot be read or the PEM is malformed
	 * @throws GeneralSecurityException if the certificate or key cannot be parsed
	 */
	static KeyStore pemToKeyStore(String certPath, String keyPath) throws IOException, GeneralSecurityException {
		java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
		java.util.List<java.security.cert.Certificate> chain = new java.util.ArrayList<>();
		try (InputStream in = new FileInputStream(certPath)) {
			for (java.security.cert.Certificate cert : cf.generateCertificates(in)) {
				chain.add(cert);
			}
		}
		if (chain.isEmpty()) {
			throw new java.security.cert.CertificateException("no certificate found in " + certPath);
		}
		String keyPem = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(keyPath)),
				StandardCharsets.UTF_8);
		java.security.PrivateKey privateKey = parsePkcs8PrivateKey(keyPem, keyPath);
		KeyStore keyStore = KeyStore.getInstance("PKCS12");
		keyStore.load(null, null);
		keyStore.setKeyEntry("key", privateKey, PEM_KEYSTORE_PASSWORD,
				chain.toArray(new java.security.cert.Certificate[0]));
		return keyStore;
	}

	/**
	 * Parses an unencrypted PKCS#8 {@code PRIVATE KEY} PEM block, detecting the key
	 * algorithm by trying each supported {@code KeyFactory} in turn (the PKCS#8 wrapper
	 * carries the algorithm, but the JDK offers no single algorithm-agnostic factory).
	 */
	private static java.security.PrivateKey parsePkcs8PrivateKey(String keyPem, String keyPath)
			throws GeneralSecurityException {
		StringBuilder base64 = new StringBuilder();
		boolean inBlock = false;
		for (String line : keyPem.split("\\R")) {
			String trimmed = line.trim();
			if (trimmed.startsWith("-----BEGIN") && trimmed.contains("PRIVATE KEY")) {
				inBlock = true;
			}
			else if (trimmed.startsWith("-----END") && trimmed.contains("PRIVATE KEY")) {
				break;
			}
			else if (inBlock) {
				base64.append(trimmed);
			}
		}
		if (base64.isEmpty()) {
			throw new java.security.spec.InvalidKeySpecException(
					"no unencrypted PKCS#8 PRIVATE KEY block found in " + keyPath);
		}
		byte[] der = java.util.Base64.getDecoder().decode(base64.toString());
		java.security.spec.PKCS8EncodedKeySpec spec = new java.security.spec.PKCS8EncodedKeySpec(der);
		for (String algorithm : new String[] { "RSA", "EC", "DSA", "EdDSA" }) {
			try {
				return java.security.KeyFactory.getInstance(algorithm).generatePrivate(spec);
			}
			catch (java.security.spec.InvalidKeySpecException ignored) {
				// try the next algorithm
			}
		}
		throw new java.security.spec.InvalidKeySpecException(
				"unsupported or encrypted private key in " + keyPath + " (expected unencrypted PKCS#8)");
	}

	/**
	 * Binds a listening TLS socket serving a certificate chain and private key read from
	 * two PEM files. Otherwise identical to {@link #listenTls}: the listener is a
	 * {@code ServerSocket} subclass, so {@code tcp-accept}/{@code tcp-local-port}/
	 * {@code close} work on it unchanged and an accepted socket handshakes lazily.
	 * @param certPath a PEM certificate-chain file (leaf certificate first)
	 * @param keyPath a PEM file holding the unencrypted PKCS#8 private key
	 * @param port the local port (0 picks an ephemeral port)
	 * @param host the local address to bind, or {@code null} for all interfaces
	 * @return the listening TLS server socket
	 * @throws LispEvalException if the PEM cannot be parsed or the address cannot be
	 * bound
	 */
	static ServerSocket listenTlsPem(String certPath, String keyPath, int port, @Nullable String host) {
		try {
			KeyStore keyStore = pemToKeyStore(certPath, keyPath);
			KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			kmf.init(keyStore, PEM_KEYSTORE_PASSWORD);
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(kmf.getKeyManagers(), null, null);
			InetAddress address = (host == null) ? null : InetAddress.getByName(host);
			return context.getServerSocketFactory().createServerSocket(port, 50, address);
		}
		catch (IOException | GeneralSecurityException ex) {
			throw new LispEvalException("tls-listen-pem: cannot listen on " + ((host == null) ? "*" : host) + ":" + port
					+ ": " + ex.getMessage());
		}
	}

	/**
	 * Binds a listening TLS socket from an in-memory PKCS12 keystore supplied as a Base64
	 * string (the shape the {@code tls-listen-pem} compile-time inliner produces: the PEM
	 * material is parsed and serialized to PKCS12 bytes at compile time, then embedded).
	 * @param base64KeyStore the Base64-encoded PKCS12 keystore bytes
	 * @param password the keystore (and key) password
	 * @param port the local port (0 picks an ephemeral port)
	 * @param host the local address to bind, or {@code null} for all interfaces
	 * @return the listening TLS server socket
	 * @throws LispEvalException if the keystore cannot be loaded or the address cannot be
	 * bound
	 */
	static ServerSocket listenTlsP12(String base64KeyStore, String password, int port, @Nullable String host) {
		try {
			byte[] bytes = java.util.Base64.getDecoder().decode(base64KeyStore);
			KeyStore keyStore = KeyStore.getInstance("PKCS12");
			try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
				keyStore.load(in, password.toCharArray());
			}
			KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			kmf.init(keyStore, password.toCharArray());
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(kmf.getKeyManagers(), null, null);
			InetAddress address = (host == null) ? null : InetAddress.getByName(host);
			return context.getServerSocketFactory().createServerSocket(port, 50, address);
		}
		catch (IOException | GeneralSecurityException ex) {
			throw new LispEvalException("tls-listen-pem: cannot listen on " + ((host == null) ? "*" : host) + ":" + port
					+ ": " + ex.getMessage());
		}
	}

	/**
	 * Blocks until a client connects to the given listener.
	 * @param listener a server socket returned by {@link #listen}
	 * @return the accepted connection socket
	 * @throws LispEvalException if accepting fails (e.g. the listener was closed)
	 */
	static Socket accept(ServerSocket listener) {
		try {
			return listener.accept();
		}
		catch (IOException ex) {
			throw new LispEvalException("tcp-accept: " + ex.getMessage());
		}
	}

	/**
	 * Sets the read deadline of a connected socket ({@code SO_TIMEOUT}): every subsequent
	 * blocking read signals after {@code milliseconds} without data. Zero clears the
	 * deadline (blocking reads wait indefinitely again). The deadline lives on the raw
	 * socket, so it keeps governing a connection later upgraded with {@code tls-upgrade}
	 * (the wrapping {@code SSLSocket} reads through the same transport).
	 * @param socket the connected socket
	 * @param milliseconds the deadline in milliseconds, or 0 to clear it
	 * @throws LispEvalException if the socket rejects the option (e.g. already closed)
	 */
	static void setTimeout(Socket socket, int milliseconds) {
		try {
			socket.setSoTimeout(milliseconds);
		}
		catch (java.net.SocketException ex) {
			throw new LispEvalException("tcp-set-timeout: " + ex.getMessage());
		}
	}

	/**
	 * Returns the local port bound to a socket or listener handle, or {@code -1} for any
	 * other stream entry.
	 * @param entry a stream-table entry
	 * @return the local port number, or -1 if the entry is not a TCP handle
	 */
	static long localPort(Closeable entry) {
		if (entry instanceof ServerSocket listener) {
			return listener.getLocalPort();
		}
		if (entry instanceof Socket socket) {
			return socket.getLocalPort();
		}
		return -1;
	}

	/**
	 * Returns the local/bound IP address of a socket or listener handle, or {@code null}
	 * for any other stream entry.
	 * @param entry a stream-table entry
	 * @return the local IP address string, or null if the entry is not a TCP handle
	 */
	static @Nullable String localAddress(Closeable entry) {
		if (entry instanceof ServerSocket listener) {
			InetAddress address = listener.getInetAddress();
			return (address == null) ? null : address.getHostAddress();
		}
		if (entry instanceof Socket socket) {
			return socket.getLocalAddress().getHostAddress();
		}
		return null;
	}

	/**
	 * Returns the remote IP address of a connected socket handle, or {@code null} for a
	 * listener or any other stream entry.
	 * @param entry a stream-table entry
	 * @return the peer IP address string, or null if the entry is not a connected socket
	 */
	static @Nullable String peerAddress(Closeable entry) {
		if (entry instanceof Socket socket) {
			InetAddress address = socket.getInetAddress();
			return (address == null) ? null : address.getHostAddress();
		}
		return null;
	}

	/**
	 * Returns the remote port of a connected socket handle, or {@code -1} for a listener
	 * or any other stream entry.
	 * @param entry a stream-table entry
	 * @return the peer port number, or -1 if the entry is not a connected socket
	 */
	static long peerPort(Closeable entry) {
		if (entry instanceof Socket socket) {
			return socket.getPort();
		}
		return -1;
	}

	/**
	 * Reads one line from a socket: bytes up to a {@code \n} (exclusive, with one
	 * trailing {@code \r} stripped for CRLF parity with {@code BufferedReader.readLine}),
	 * decoded as UTF-8. Reads byte-at-a-time so no readahead is lost between calls.
	 * @param socket the connected socket
	 * @return the line, or {@code null} when the peer closed before any byte arrived
	 */
	static @Nullable String readLine(Socket socket) {
		try {
			InputStream in = socket.getInputStream();
			ByteArrayOutputStream line = new ByteArrayOutputStream();
			int b = in.read();
			if (b < 0) {
				return null;
			}
			while (b >= 0 && b != '\n') {
				line.write(b);
				b = in.read();
			}
			byte[] bytes = line.toByteArray();
			int length = (bytes.length > 0 && bytes[bytes.length - 1] == '\r') ? bytes.length - 1 : bytes.length;
			return new String(bytes, 0, length, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new LispEvalException("read-line: " + ex.getMessage());
		}
	}

	/**
	 * Writes a line (the string plus {@code \n}, UTF-8) to a socket. Socket output
	 * streams are unbuffered, so the bytes go out immediately (unlike buffered file
	 * writers).
	 * @param socket the connected socket
	 * @param line the line to send (without the newline)
	 */
	static void writeLine(Socket socket, String line) {
		try {
			socket.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new LispEvalException("write-line: " + ex.getMessage());
		}
	}

	/**
	 * Writes a string (UTF-8) to a socket -- {@link #writeLine} without the newline. Like
	 * every socket write the bytes go out immediately.
	 * @param socket the connected socket
	 * @param text the string to send
	 */
	static void writeString(Socket socket, String text) {
		try {
			socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new LispEvalException("write-string: " + ex.getMessage());
		}
	}

	/**
	 * Reads ONE character -- a Unicode code point -- from a socket, assembling its UTF-8
	 * sequence byte-wise off the socket's input stream. The socket entry is a raw
	 * {@code Socket}, never a {@code Reader}, and it must stay that way: a Reader would
	 * buffer ahead and swallow bytes a following {@code read-byte} / {@code read-line}
	 * owes the caller. An invalid lead byte stands alone and decodes to whatever the
	 * UTF-8 decoder yields for it (U+FFFD), which is what the component's byte-wise
	 * {@code %sock-read-char-f} answers as well.
	 * @param socket the connected socket
	 * @return the code point, or -1 when the peer closed before any byte arrived
	 */
	static int readChar(Socket socket) {
		try {
			InputStream in = socket.getInputStream();
			int b0 = in.read();
			if (b0 < 0) {
				return -1;
			}
			if (b0 < 128) {
				return b0;
			}
			int continuations = (b0 < 192) ? 0 : (b0 < 224) ? 1 : (b0 < 240) ? 2 : 3;
			ByteArrayOutputStream sequence = new ByteArrayOutputStream();
			sequence.write(b0);
			for (int i = 0; i < continuations; i++) {
				int bn = in.read();
				if (bn < 0) {
					break;
				}
				sequence.write(bn);
			}
			return new String(sequence.toByteArray(), StandardCharsets.UTF_8).codePointAt(0);
		}
		catch (IOException ex) {
			throw new LispEvalException("read-char: " + ex.getMessage());
		}
	}

	/**
	 * Reads a single byte from a socket.
	 * @param socket the connected socket
	 * @return the byte value (0-255), or -1 at end of stream
	 */
	static int readByte(Socket socket) {
		try {
			return socket.getInputStream().read();
		}
		catch (IOException ex) {
			throw new LispEvalException("read-byte: " + ex.getMessage());
		}
	}

	/**
	 * Writes a single byte to a socket (sent immediately).
	 * @param socket the connected socket
	 * @param value the byte value (0-255)
	 */
	static void writeByte(Socket socket, int value) {
		try {
			socket.getOutputStream().write(value);
		}
		catch (IOException ex) {
			throw new LispEvalException("write-byte: " + ex.getMessage());
		}
	}

}
