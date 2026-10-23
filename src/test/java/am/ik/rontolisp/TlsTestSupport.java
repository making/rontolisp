package am.ik.rontolisp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.jspecify.annotations.Nullable;

/**
 * Shared TLS test fixture for the {@code rontolisp:tls-connect} tests (interpreter and
 * JVM backend). Generates one self-signed PKCS12 keystore per JVM with the JDK's
 * {@code keytool} (CN=localhost, SAN covering {@code 127.0.0.1} and {@code localhost}, so
 * the client's endpoint identification passes on loopback) and builds an in-test
 * {@link SSLServerSocket} from it. The same keystore doubles as the client trust store:
 * tests point {@code javax.net.ssl.trustStore} at it, which {@code tls-connect} re-reads
 * on every call because it initializes a fresh {@link SSLContext} per connection.
 */
public final class TlsTestSupport {

	public static final String STORE_PASSWORD = "changeit";

	/** How long a helper thread waits on a peer: an accept, a read, a reported port. */
	private static final int IO_TIMEOUT_MS = 120_000;

	private static volatile @Nullable Path keyStorePath;

	private TlsTestSupport() {
	}

	/**
	 * Returns the shared self-signed PKCS12 keystore, generating it on first use.
	 * @return the keystore path
	 */
	public static Path keyStore() {
		Path path = keyStorePath;
		if (path == null) {
			synchronized (TlsTestSupport.class) {
				path = keyStorePath;
				if (path == null) {
					path = generateKeyStore();
					keyStorePath = path;
				}
			}
		}
		return path;
	}

	private static volatile Path @Nullable [] pemFiles;

	/**
	 * Returns {@code [certPath, keyPath]} PEM files derived from the shared keystore
	 * (same certificate and key, so the existing {@link #startOneShotEchoClient} trusts a
	 * PEM-configured server unchanged). The certificate is written as a
	 * {@code CERTIFICATE} block and the private key as an unencrypted PKCS#8
	 * {@code PRIVATE KEY} block, both standard PEM (matching what certbot /
	 * {@code openssl
	 * genpkey} emit). Generated on first use.
	 * @return the two PEM file paths
	 */
	public static Path[] pemFiles() {
		Path[] files = pemFiles;
		if (files == null) {
			synchronized (TlsTestSupport.class) {
				files = pemFiles;
				if (files == null) {
					files = generatePemFiles();
					pemFiles = files;
				}
			}
		}
		return files;
	}

	private static Path[] generatePemFiles() {
		try {
			KeyStore ks = KeyStore.getInstance("PKCS12");
			try (var in = Files.newInputStream(keyStore())) {
				ks.load(in, STORE_PASSWORD.toCharArray());
			}
			String alias = "rontolisp-tls-test";
			java.security.cert.Certificate cert = ks.getCertificate(alias);
			java.security.Key key = ks.getKey(alias, STORE_PASSWORD.toCharArray());
			Path dir = Files.createTempDirectory("rontolisp-tls-pem");
			Path certPem = dir.resolve("cert.pem");
			Path keyPem = dir.resolve("key.pem");
			Files.writeString(certPem, pemBlock("CERTIFICATE", cert.getEncoded()));
			Files.writeString(keyPem, pemBlock("PRIVATE KEY", key.getEncoded()));
			return new Path[] { certPem, keyPem };
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (java.security.GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String pemBlock(String type, byte[] der) {
		String base64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
		return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
	}

	private static Path generateKeyStore() {
		try {
			Path dir = Files.createTempDirectory("rontolisp-tls-test");
			Path store = dir.resolve("tls-test.p12");
			String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
			Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "rontolisp-tls-test", "-keyalg",
					"EC", "-dname", "CN=localhost", "-validity", "3650", "-ext", "SAN=ip:127.0.0.1,dns:localhost",
					"-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", STORE_PASSWORD, "-keypass",
					STORE_PASSWORD)
				.redirectErrorStream(true)
				.start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			int exit = process.waitFor();
			if (exit != 0) {
				throw new IllegalStateException("keytool failed (" + exit + "): " + output);
			}
			return store;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("keytool interrupted", ex);
		}
	}

	/**
	 * Builds a TLS server socket bound to an ephemeral port on 127.0.0.1, serving the
	 * shared self-signed certificate.
	 * @return the bound server socket
	 */
	public static SSLServerSocket newServerSocket() {
		try {
			KeyStore ks = KeyStore.getInstance("PKCS12");
			try (var in = Files.newInputStream(keyStore())) {
				ks.load(in, STORE_PASSWORD.toCharArray());
			}
			KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			kmf.init(ks, STORE_PASSWORD.toCharArray());
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(kmf.getKeyManagers(), null, null);
			SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory()
				.createServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
			return server;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (java.security.GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * The Lisp form that reports a {@code tls-listen} server's bound port to the client
	 * of {@link #startOneShotEchoClient}: the server binds port 0 and writes the port it
	 * got, so no test picks a port by closing a probe listener -- another test may bind
	 * the freed port before the server does ({@code .kb/tcp-sockets.md}, "Socket tests").
	 * @param portFile the file the client waits on
	 * @param listener the Lisp variable holding the listener
	 * @return the form's source text
	 */
	public static String reportPortForm(Path portFile, String listener) {
		return "(with-open-file (o \"" + portFile.toString().replace("\\", "\\\\")
				+ "\" :direction :output :if-exists :supersede) (write-line (princ-to-string (rontolisp:tcp-local-port "
				+ listener + ")) o))";
	}

	/**
	 * Starts a daemon thread that waits for the Lisp {@code tls-listen} server under test
	 * to report its port ({@link #reportPortForm}), connects a TLS client (trusting the
	 * shared self-signed certificate directly, no system properties needed) to 127.0.0.1
	 * on it, sends one line and reads the reply. Every wait is bounded; failures end the
	 * thread quietly (the server side asserts the visible behavior).
	 * @param portFile the file the server writes its port to
	 * @param line the line to send (without the newline)
	 * @return the started thread (join it to make sure the exchange finished)
	 */
	public static Thread startOneShotEchoClient(Path portFile, String line) {
		Thread thread = new Thread(() -> {
			try {
				KeyStore ks = KeyStore.getInstance("PKCS12");
				try (var in = Files.newInputStream(keyStore())) {
					ks.load(in, STORE_PASSWORD.toCharArray());
				}
				javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory
					.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
				tmf.init(ks);
				SSLContext context = SSLContext.getInstance("TLS");
				context.init(null, tmf.getTrustManagers(), null);
				int port = awaitPort(portFile);
				if (port < 0) {
					return;
				}
				try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", port)) {
					socket.setSoTimeout(IO_TIMEOUT_MS);
					socket.startHandshake();
					socket.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
					socket.getOutputStream().flush();
					new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
						.readLine();
				}
			}
			catch (Exception ex) {
				// quiet: the Lisp server side asserts the visible behavior
			}
		}, "tls-test-echo-client");
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	// The port once the whole line is written (the trailing newline), -1 past the
	// deadline.
	private static int awaitPort(Path portFile) throws IOException, InterruptedException {
		long deadline = System.currentTimeMillis() + IO_TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			if (Files.exists(portFile)) {
				String text = Files.readString(portFile, StandardCharsets.UTF_8);
				if (text.endsWith("\n")) {
					return Integer.parseInt(text.strip());
				}
			}
			Thread.sleep(20);
		}
		return -1;
	}

	/**
	 * Starts a daemon thread that accepts one connection, echoes one line back and
	 * closes. Every wait is bounded; handshake or I/O failures end the thread quietly
	 * (the client side asserts the visible behavior).
	 * @param server the server socket to accept on
	 * @return the started thread (join it to make sure the exchange finished)
	 */
	public static Thread startOneShotEchoServer(SSLServerSocket server) {
		Thread thread = new Thread(() -> {
			try {
				server.setSoTimeout(IO_TIMEOUT_MS);
				try (SSLSocket socket = (SSLSocket) server.accept()) {
					socket.setSoTimeout(IO_TIMEOUT_MS);
					BufferedReader reader = new BufferedReader(
							new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
					String line = reader.readLine();
					if (line != null) {
						socket.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
						socket.getOutputStream().flush();
					}
				}
			}
			catch (IOException ex) {
				// expected for the negative tests (handshake rejected by the client)
			}
		}, "tls-test-echo-server");
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	/**
	 * Points the JDK default trust store at the shared self-signed keystore, runs the
	 * action, and restores the previous system properties.
	 * @param action the code to run while the self-signed certificate is trusted
	 * @throws Exception whatever the action throws
	 */
	public static void withTrustStore(ThrowingRunnable action) throws Exception {
		String oldStore = System.getProperty("javax.net.ssl.trustStore");
		String oldPassword = System.getProperty("javax.net.ssl.trustStorePassword");
		String oldType = System.getProperty("javax.net.ssl.trustStoreType");
		System.setProperty("javax.net.ssl.trustStore", keyStore().toString());
		System.setProperty("javax.net.ssl.trustStorePassword", STORE_PASSWORD);
		System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
		try {
			action.run();
		}
		finally {
			restore("javax.net.ssl.trustStore", oldStore);
			restore("javax.net.ssl.trustStorePassword", oldPassword);
			restore("javax.net.ssl.trustStoreType", oldType);
		}
	}

	private static void restore(String key, @Nullable String oldValue) {
		if (oldValue == null) {
			System.clearProperty(key);
		}
		else {
			System.setProperty(key, oldValue);
		}
	}

	/** A runnable that may throw. */
	public interface ThrowingRunnable {

		void run() throws Exception;

	}

}
