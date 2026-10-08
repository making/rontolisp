package am.ik.maven;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;

/**
 * Maven 3.9's password decryption (plexus-sec-dispatcher 2.0 over plexus-cipher 2.0): a
 * {@code settings.xml} password holding {@code {...}} is AES-encrypted with the master
 * password, which {@code ~/.m2/settings-security.xml} (or the file the
 * {@code settings.security} system property names, following its {@code <relocation>})
 * holds encrypted with the literal {@code settings.security}. Maven keeps a value it
 * cannot decrypt as written, and so does this; the reason is kept to explain the
 * {@code 401} that follows.
 */
final class SettingsPasswords {

	/** plexus-cipher's {@code ENCRYPTED_STRING_PATTERN}. */
	private static final Pattern ENCRYPTED = Pattern.compile(".*?[^\\\\]?\\{(.*?[^\\\\])\\}.*");

	private static final String MASTER_PASSPHRASE = "settings.security";

	private static final int SALT_SIZE = 8;

	private static final int SPICE_SIZE = 16;

	private final String location;

	private boolean loaded;

	private @Nullable String master;

	private @Nullable String masterProblem;

	private SettingsPasswords(String location) {
		this.location = location;
	}

	/**
	 * The decrypter of these system properties: {@code settings.security}, else
	 * {@code ~/.m2/settings-security.xml}, a leading {@code ~} the user's home.
	 * @param system the system properties
	 * @return the decrypter
	 */
	static SettingsPasswords of(Map<String, String> system) {
		String location = system.getOrDefault("settings.security", "~/.m2/settings-security.xml");
		if (!location.isEmpty() && location.charAt(0) == '~') {
			location = system.getOrDefault("user.home", "") + location.substring(1);
		}
		return new SettingsPasswords(location);
	}

	/**
	 * A value as Maven uses it.
	 *
	 * @param value the decrypted value, or the value as written when it cannot be
	 * decrypted
	 * @param problem why it could not be, or {@code null}
	 */
	record Decrypted(@Nullable String value, @Nullable String problem) {
	}

	/**
	 * Decrypts a value that looks encrypted; any other is itself.
	 * @param value the value, or {@code null}
	 * @return the value Maven uses, and why it is not decrypted when it is not
	 */
	Decrypted decrypt(@Nullable String value) {
		if (value == null || value.isEmpty()) {
			return new Decrypted(value, null);
		}
		Matcher matcher = ENCRYPTED.matcher(value);
		if (!matcher.matches() && !matcher.find()) {
			return new Decrypted(value, null);
		}
		String bare = matcher.group(1);
		try {
			String masterPassword = master();
			Map<String, @Nullable String> attributes = attributes(bare);
			if (attributes != null && attributes.get("type") != null) {
				throw new GeneralSecurityException(
						"no dispatcher for the encryption type '" + attributes.get("type") + "' (Maven 3.9 has none)");
			}
			return new Decrypted(decrypt64(bare, masterPassword), null);
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			return new Decrypted(value, ex.getMessage());
		}
	}

	/** {@code DefaultSecDispatcher.stripAttributes}: {@code [k=v, ...]} at the start. */
	private static @Nullable Map<String, @Nullable String> attributes(String bare) {
		int start = bare.indexOf('[');
		int stop = bare.indexOf(']');
		if (start != 0 || stop <= start + 1) {
			return null;
		}
		String text = bare.substring(start + 1, stop).trim();
		if (text.isEmpty()) {
			return null;
		}
		Map<String, @Nullable String> attributes = new HashMap<>();
		StringTokenizer pairs = new StringTokenizer(text, ", ");
		while (pairs.hasMoreTokens()) {
			String pair = pairs.nextToken();
			int equals = pair.indexOf('=');
			if (equals >= 0) {
				attributes.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
			}
		}
		return attributes;
	}

	private String master() throws GeneralSecurityException {
		if (!this.loaded) {
			this.loaded = true;
			try {
				Matcher matcher = ENCRYPTED.matcher(readMaster());
				if (!matcher.matches() && !matcher.find()) {
					throw new GeneralSecurityException("the master password in " + this.location
							+ " is not encrypted ({...}, mvn --encrypt-master-password)");
				}
				this.master = decrypt64(matcher.group(1), MASTER_PASSPHRASE);
			}
			catch (GeneralSecurityException | IllegalArgumentException ex) {
				this.masterProblem = ex.getMessage();
			}
		}
		if (this.master == null) {
			throw new GeneralSecurityException(String.valueOf(this.masterProblem));
		}
		return this.master;
	}

	/** {@code SecUtil.read(location, true)}: the master, following relocations. */
	private String readMaster() throws GeneralSecurityException {
		String current = this.location;
		Set<String> seen = new HashSet<>();
		while (true) {
			if (!seen.add(current)) {
				throw new GeneralSecurityException("settings-security.xml relocations form a cycle at " + current);
			}
			XmlElement security = readSecurity(current);
			XmlElement relocation = security.child("relocation");
			if (relocation != null && !relocation.text().trim().isEmpty()) {
				current = relocation.text().trim();
				continue;
			}
			XmlElement master = security.child("master");
			if (master == null || master.text().trim().isEmpty()) {
				throw new GeneralSecurityException(
						"master password is not set in the setting security file " + current);
			}
			return master.text().trim();
		}
	}

	private static XmlElement readSecurity(String location) throws GeneralSecurityException {
		Path file;
		try {
			if (location.contains("://")) {
				if (!location.startsWith("file:")) {
					throw new GeneralSecurityException(
							"the master password location " + location + " is a URL; only a file is read");
				}
				file = Path.of(URI.create(location));
			}
			else {
				file = Path.of(location);
			}
		}
		catch (IllegalArgumentException ex) {
			throw new GeneralSecurityException(
					"cannot read the master password from " + location + ": " + ex.getMessage(), ex);
		}
		try {
			return XmlParser.parse(Files.readAllBytes(file));
		}
		catch (IOException ex) {
			throw new GeneralSecurityException("cannot retrieve master password: cannot read " + location, ex);
		}
		catch (XmlParser.Malformed ex) {
			throw new GeneralSecurityException(location + " is not well-formed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * plexus-cipher's {@code PBECipher.decrypt64}: base64 of an 8-byte salt, a pad length
	 * byte, the AES/CBC ciphertext and the padding; key and IV are SHA-256 of the
	 * passphrase and the salt.
	 */
	static String decrypt64(String encrypted, String passphrase) throws GeneralSecurityException {
		byte[] all = Base64.getMimeDecoder().decode(encrypted.getBytes(StandardCharsets.UTF_8));
		if (all.length <= SALT_SIZE + 1) {
			throw new GeneralSecurityException("not an encrypted value: " + encrypted);
		}
		byte[] salt = Arrays.copyOfRange(all, 0, SALT_SIZE);
		int padding = all[SALT_SIZE];
		int length = all.length - SALT_SIZE - 1 - padding;
		if (padding < 0 || length < 0) {
			throw new GeneralSecurityException("not an encrypted value: " + encrypted);
		}
		byte[] ciphertext = Arrays.copyOfRange(all, SALT_SIZE + 1, SALT_SIZE + 1 + length);
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		digest.update(passphrase.getBytes(StandardCharsets.UTF_8));
		digest.update(salt, 0, SALT_SIZE);
		byte[] keyAndIv = digest.digest();
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(keyAndIv, 0, SPICE_SIZE), "AES"),
				new IvParameterSpec(Arrays.copyOfRange(keyAndIv, SPICE_SIZE, 2 * SPICE_SIZE)));
		return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
	}

}
