package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Base64;

/**
 * Compile-time PEM support, the bridge the {@code rontolisp:tls-listen-pem} inliner uses.
 * The interpreter reads PEM files at run time (see {@link SocketSupport#listenTlsPem});
 * on the compile path the same {@link SocketSupport#pemToKeyStore} parser runs at compile
 * time and the resulting PKCS12 keystore is serialized and Base64-embedded into the
 * program, which then binds the listener at run time from those bytes via
 * {@code rontolisp:%tls-listen-p12}. Both paths therefore parse PEM through exactly one
 * code path; only the moment of parsing differs.
 */
public final class TlsPemSupport {

	private TlsPemSupport() {
	}

	/**
	 * The keystore password shared by the embedded PKCS12 blob and the generated
	 * {@code %tls-listen-p12} call; internal, never chosen by the user.
	 */
	public static final String KEYSTORE_PASSWORD = new String(SocketSupport.PEM_KEYSTORE_PASSWORD);

	/**
	 * Parses a PEM certificate chain and unencrypted PKCS#8 private key at compile time
	 * and returns the equivalent PKCS12 keystore as a Base64 string, ready to embed as a
	 * literal argument to {@code rontolisp:%tls-listen-p12}.
	 * @param certPath a PEM certificate-chain file (leaf certificate first)
	 * @param keyPath a PEM file holding the unencrypted PKCS#8 private key
	 * @return the Base64-encoded PKCS12 keystore bytes
	 * @throws LispEvalException if a file cannot be read or the PEM cannot be parsed
	 */
	public static String pemToBase64Pkcs12(String certPath, String keyPath) {
		try {
			KeyStore keyStore = SocketSupport.pemToKeyStore(certPath, keyPath);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			keyStore.store(out, SocketSupport.PEM_KEYSTORE_PASSWORD);
			return Base64.getEncoder().encodeToString(out.toByteArray());
		}
		catch (IOException | GeneralSecurityException ex) {
			throw new LispEvalException(
					"tls-listen-pem: cannot read PEM (" + certPath + ", " + keyPath + "): " + ex.getMessage());
		}
	}

}
