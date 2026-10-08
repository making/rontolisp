package am.ik.artifact;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * An expected digest of a downloaded artifact: the {@link MessageDigest} algorithm and
 * the lowercase hex value a repository index published for it. A download is checked
 * BEFORE anything is installed, because a cache entry's existence is its "installed" mark
 * ({@link AtomicInstall}) -- a corrupted archive accepted once would be used forever.
 *
 * @param algorithm the {@link MessageDigest} algorithm name ({@code MD5}, {@code SHA-1},
 * {@code SHA-256})
 * @param hex the expected digest, lowercase hex
 */
public record Checksum(String algorithm, String hex) {

	/**
	 * Validates the digest's shape against its algorithm, so a malformed index entry is
	 * reported where it is read rather than as a mismatch against every download.
	 * @param algorithm the {@link MessageDigest} algorithm name
	 * @param hex the expected digest, hex in either case
	 */
	public Checksum {
		hex = hex.trim().toLowerCase(Locale.ROOT);
		int length = digestLength(algorithm) * 2;
		if (hex.length() != length || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
			throw new IllegalArgumentException(
					"not a " + algorithm + " digest (" + length + " hex digits expected): '" + hex + "'");
		}
	}

	/**
	 * An expected MD5 digest.
	 * @param hex the digest, 32 hex digits
	 * @return the checksum
	 */
	public static Checksum md5(String hex) {
		return new Checksum("MD5", hex);
	}

	/**
	 * An expected SHA-1 digest.
	 * @param hex the digest, 40 hex digits
	 * @return the checksum
	 */
	public static Checksum sha1(String hex) {
		return new Checksum("SHA-1", hex);
	}

	/**
	 * An expected SHA-256 digest.
	 * @param hex the digest, 64 hex digits
	 * @return the checksum
	 */
	public static Checksum sha256(String hex) {
		return new Checksum("SHA-256", hex);
	}

	/**
	 * Checks {@code bytes} against this digest.
	 * @param bytes the downloaded bytes
	 * @param source what the bytes are, for the message (typically the URL)
	 * @throws IOException if the digest differs
	 */
	public void verify(byte[] bytes, String source) throws IOException {
		String actual = HexFormat.of().formatHex(digest(this.algorithm).digest(bytes));
		if (!actual.equals(this.hex)) {
			throw new IOException(
					this.algorithm + " mismatch for " + source + ": expected " + this.hex + ", got " + actual);
		}
	}

	/**
	 * The digest length in bytes, from a table rather than {@link MessageDigest}: the
	 * index parse that builds a checksum must not need a JCA provider lookup (the browser
	 * build has none, and verification there is substituted away).
	 */
	private static int digestLength(String algorithm) {
		return switch (algorithm) {
			case "MD5" -> 16;
			case "SHA-1" -> 20;
			case "SHA-256" -> 32;
			case "SHA-512" -> 64;
			default -> throw new IllegalArgumentException("unsupported digest algorithm '" + algorithm + "'");
		};
	}

	private static MessageDigest digest(String algorithm) {
		try {
			return MessageDigest.getInstance(algorithm);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalArgumentException("unknown digest algorithm '" + algorithm + "'", ex);
		}
	}

}
