package am.ik.jdbc.postgresql;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;

/**
 * The client's side of one SCRAM-SHA-256 exchange (RFC 5802, RFC 7677), without channel
 * binding: the three messages a client sends and checks.
 *
 * <p>
 * The server takes the user from the start-up message and ignores the one of the exchange
 * (PostgreSQL documentation, "SASL Authentication"), so the name sent is empty.
 */
final class Scram {

	// "n,," in base64: no channel binding, no authorization identity.
	private static final String BINDING = "biws";

	private final String name;

	private final String password;

	private final String nonce;

	private byte @Nullable [] serverSignature;

	Scram(String name, String password, String nonce) {
		this.name = name;
		this.password = SaslPrep.prepare(password);
		this.nonce = nonce;
	}

	/** A nonce of 18 octets of the host's entropy, in base64: no comma in it. */
	static String nonce() {
		byte[] octets = new byte[18];
		new SecureRandom().nextBytes(octets);
		return Base64.getEncoder().encodeToString(octets);
	}

	String clientFirst() {
		return "n,," + clientFirstBare();
	}

	private String clientFirstBare() {
		return "n=" + this.name + ",r=" + this.nonce;
	}

	/**
	 * Answers the server's first message with the proof that the client holds the
	 * password, and keeps the signature the server has to answer with.
	 */
	String clientFinal(String serverFirst) throws SQLException {
		String serverNonce = null;
		String salt = null;
		int iterations = 0;
		for (String attribute : serverFirst.split(",")) {
			if (attribute.startsWith("r=")) {
				serverNonce = attribute.substring(2);
			}
			else if (attribute.startsWith("s=")) {
				salt = attribute.substring(2);
			}
			else if (attribute.startsWith("i=")) {
				try {
					iterations = Integer.parseInt(attribute.substring(2));
				}
				catch (NumberFormatException ex) {
					iterations = 0;
				}
			}
			else if (attribute.startsWith("m=")) {
				throw Failures.protocol("The server's SCRAM message asks for an extension this driver does not know");
			}
		}
		if (serverNonce == null || salt == null || iterations <= 0) {
			throw Failures.protocol("The server's first SCRAM message lacks a nonce, a salt or an iteration count");
		}
		if (!serverNonce.startsWith(this.nonce) || serverNonce.length() == this.nonce.length()) {
			throw Failures.protocol("The server's SCRAM nonce does not extend the client's");
		}
		try {
			byte[] salted = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
				.generateSecret(
						new PBEKeySpec(this.password.toCharArray(), Base64.getDecoder().decode(salt), iterations, 256))
				.getEncoded();
			byte[] clientKey = hmac(salted, "Client Key");
			byte[] storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey);
			String withoutProof = "c=" + BINDING + ",r=" + serverNonce;
			String authMessage = clientFirstBare() + "," + serverFirst + "," + withoutProof;
			byte[] proof = hmac(storedKey, authMessage);
			for (int i = 0; i < proof.length; i++) {
				proof[i] = (byte) (proof[i] ^ clientKey[i]);
			}
			this.serverSignature = hmac(hmac(salted, "Server Key"), authMessage);
			return withoutProof + ",p=" + Base64.getEncoder().encodeToString(proof);
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			throw Failures.protocol("The SCRAM proof cannot be computed: " + ex.getMessage(), ex);
		}
	}

	/** Checks that the server, too, holds what the password derives. */
	void verify(String serverFinal) throws SQLException {
		if (serverFinal.startsWith("e=")) {
			throw Failures.login("The server refused the SCRAM exchange: " + serverFinal.substring(2));
		}
		byte[] signature = null;
		for (String attribute : serverFinal.split(",")) {
			if (attribute.startsWith("v=")) {
				try {
					signature = Base64.getDecoder().decode(attribute.substring(2));
				}
				catch (IllegalArgumentException ex) {
					signature = null;
				}
			}
		}
		if (this.serverSignature == null || signature == null
				|| !MessageDigest.isEqual(this.serverSignature, signature)) {
			throw Failures.login("The server's SCRAM signature is not the one the password derives");
		}
	}

	private static byte[] hmac(byte[] key, String message) throws GeneralSecurityException {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
	}

}
