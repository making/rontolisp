package am.ik.rontolisp.eval;

import java.util.Arrays;

import org.jspecify.annotations.Nullable;

/**
 * MD5 (RFC 1321), MD2 (RFC 1319), SHA-1 and the SHA-2 family (FIPS 180-4), SHA-3 and
 * SHAKE (FIPS 202), HMAC (RFC 2104) and PBKDF2 (RFC 8018, section 5.2) over plain arrays:
 * the interpreter's kernels, which {@link IroncladNative} puts in the place of ironclad's
 * PBKDF2 and {@link JavaDigestNative} in the place of the Java subset's digest functions.
 * Nothing here knows a Lisp type.
 *
 * <p>
 * Written here from the standards rather than delegated to
 * {@code java.security.MessageDigest}: this class is reachable from
 * {@link LispEvaluator}, so it is also compiled into the GraalVM native binary and into
 * the browser Web Image, where a JCA provider lookup is a registration burden or simply
 * absent. The digests are numbered as the Java subset's library numbers them
 * ({@code rontolisp.internal.Hashing}); the outputs are the standards' functions of the
 * inputs, which {@code IroncladNativeTest} holds to the JDK's own.
 */
final class DigestKernels {

	static final int MD5 = 0;

	static final int SHA1 = 1;

	static final int SHA224 = 2;

	static final int SHA256 = 3;

	static final int SHA384 = 4;

	static final int SHA512 = 5;

	static final int SHA512_224 = 6;

	static final int SHA512_256 = 7;

	static final int MD2 = 8;

	static final int SHA3_224 = 9;

	static final int SHA3_256 = 10;

	static final int SHA3_384 = 11;

	static final int SHA3_512 = 12;

	/** SHAKE128's first 32 bytes, a JDK's SHAKE128-256. */
	static final int SHAKE128 = 13;

	/** SHAKE256's first 64 bytes, a JDK's SHAKE256-512. */
	static final int SHAKE256 = 14;

	/** The last digest's number. */
	static final int LAST = SHAKE256;

	/** RFC 1319's permutation of the bytes, made of the digits of pi. */
	private static final int[] PI_SUBST = { 41, 46, 67, 201, 162, 216, 124, 1, 61, 54, 84, 161, 236, 240, 6, 19, 98,
			167, 5, 243, 192, 199, 115, 140, 152, 147, 43, 217, 188, 76, 130, 202, 30, 155, 87, 60, 253, 212, 224, 22,
			103, 66, 111, 24, 138, 23, 229, 18, 190, 78, 196, 214, 218, 158, 222, 73, 160, 251, 245, 142, 187, 47, 238,
			122, 169, 104, 121, 145, 21, 178, 7, 63, 148, 194, 16, 137, 11, 34, 95, 33, 128, 127, 93, 154, 90, 144, 50,
			39, 53, 62, 204, 231, 191, 247, 151, 3, 255, 25, 48, 179, 72, 165, 181, 209, 215, 94, 146, 42, 172, 86, 170,
			198, 79, 184, 56, 210, 150, 164, 125, 182, 118, 252, 107, 226, 156, 116, 4, 241, 69, 157, 112, 89, 100, 113,
			135, 32, 134, 91, 207, 101, 230, 45, 168, 2, 27, 96, 37, 173, 174, 176, 185, 246, 28, 70, 97, 105, 52, 64,
			126, 15, 85, 71, 163, 35, 221, 81, 175, 58, 195, 92, 249, 206, 186, 197, 234, 38, 44, 83, 13, 110, 133, 40,
			132, 9, 211, 223, 205, 244, 65, 129, 77, 82, 106, 220, 55, 200, 108, 193, 171, 250, 36, 225, 123, 8, 12,
			189, 177, 74, 120, 136, 149, 139, 227, 99, 232, 109, 233, 203, 213, 254, 59, 0, 29, 57, 242, 239, 183, 14,
			102, 88, 208, 228, 166, 119, 114, 248, 235, 117, 75, 10, 49, 68, 80, 180, 143, 237, 31, 26, 219, 153, 141,
			51, 159, 17, 131, 20 };

	/**
	 * The offsets of FIPS 202's rho by lane, {@code x + 5y}, and its round constants:
	 * both computed from the standard's Algorithms 2 and 5.
	 */
	private static final int[] RHO = new int[25];

	private static final long[] IOTA = new long[24];

	static {
		int x = 1;
		int y = 0;
		for (int t = 0; t < 24; t++) {
			RHO[x + 5 * y] = ((t + 1) * (t + 2) / 2) % 64;
			int next = (2 * x + 3 * y) % 5;
			x = y;
			y = next;
		}
		int r = 1;
		for (int t = 0; t < 168; t++) {
			if (t > 0) {
				r <<= 1;
				if ((r & 0x100) != 0) {
					r ^= 0x171;
				}
			}
			if ((r & 1) != 0) {
				IOTA[t / 7] |= 1L << ((1 << (t % 7)) - 1);
			}
		}
	}

	private static final int[] T = { 0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613,
			0xfd469501, 0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
			0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8, 0x21e1cde6,
			0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a, 0xfffa3942, 0x8771f681,
			0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70, 0x289b7ec6, 0xeaa127fa, 0xd4ef3085,
			0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665, 0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039,
			0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1, 0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82,
			0xbd3af235, 0x2ad7d2bb, 0xeb86d391 };

	private static final int[] S = { 7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21 };

	private static final int[] K256 = { 0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
			0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7,
			0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
			0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85,
			0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
			0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c,
			0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
			0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2 };

	private static final long[] K512 = { 0x428a2f98d728ae22L, 0x7137449123ef65cdL, 0xb5c0fbcfec4d3b2fL,
			0xe9b5dba58189dbbcL, 0x3956c25bf348b538L, 0x59f111f1b605d019L, 0x923f82a4af194f9bL, 0xab1c5ed5da6d8118L,
			0xd807aa98a3030242L, 0x12835b0145706fbeL, 0x243185be4ee4b28cL, 0x550c7dc3d5ffb4e2L, 0x72be5d74f27b896fL,
			0x80deb1fe3b1696b1L, 0x9bdc06a725c71235L, 0xc19bf174cf692694L, 0xe49b69c19ef14ad2L, 0xefbe4786384f25e3L,
			0x0fc19dc68b8cd5b5L, 0x240ca1cc77ac9c65L, 0x2de92c6f592b0275L, 0x4a7484aa6ea6e483L, 0x5cb0a9dcbd41fbd4L,
			0x76f988da831153b5L, 0x983e5152ee66dfabL, 0xa831c66d2db43210L, 0xb00327c898fb213fL, 0xbf597fc7beef0ee4L,
			0xc6e00bf33da88fc2L, 0xd5a79147930aa725L, 0x06ca6351e003826fL, 0x142929670a0e6e70L, 0x27b70a8546d22ffcL,
			0x2e1b21385c26c926L, 0x4d2c6dfc5ac42aedL, 0x53380d139d95b3dfL, 0x650a73548baf63deL, 0x766a0abb3c77b2a8L,
			0x81c2c92e47edaee6L, 0x92722c851482353bL, 0xa2bfe8a14cf10364L, 0xa81a664bbc423001L, 0xc24b8b70d0f89791L,
			0xc76c51a30654be30L, 0xd192e819d6ef5218L, 0xd69906245565a910L, 0xf40e35855771202aL, 0x106aa07032bbd1b8L,
			0x19a4c116b8d2d0c8L, 0x1e376c085141ab53L, 0x2748774cdf8eeb99L, 0x34b0bcb5e19b48a8L, 0x391c0cb3c5c95a63L,
			0x4ed8aa4ae3418acbL, 0x5b9cca4f7763e373L, 0x682e6ff3d6b2b8a3L, 0x748f82ee5defb2fcL, 0x78a5636f43172f60L,
			0x84c87814a1f0ab72L, 0x8cc702081a6439ecL, 0x90befffa23631e28L, 0xa4506cebde82bde9L, 0xbef9a3f7b2c67915L,
			0xc67178f2e372532bL, 0xca273eceea26619cL, 0xd186b8c721c0c207L, 0xeada7dd6cde0eb1eL, 0xf57d4f7fee6ed178L,
			0x06f067aa72176fbaL, 0x0a637dc5a2c898a6L, 0x113f9804bef90daeL, 0x1b710b35131c471bL, 0x28db77f523047d84L,
			0x32caab7b40c72493L, 0x3c9ebe0a15c9bebcL, 0x431d67c49c100d4cL, 0x4cc5d4becb3e42b6L, 0x597f299cfc657e2aL,
			0x5fcb6fab3ad6faecL, 0x6c44198c4a475817L };

	private DigestKernels() {
	}

	/**
	 * Returns the digest length of a digest, in bytes.
	 * @param digest the digest's number
	 * @return its length
	 */
	static int digestLength(int digest) {
		return switch (digest) {
			case MD5, MD2 -> 16;
			case SHA1 -> 20;
			case SHA224, SHA512_224, SHA3_224 -> 28;
			case SHA256, SHA512_256, SHA3_256, SHAKE128 -> 32;
			case SHA384, SHA3_384 -> 48;
			default -> 64;
		};
	}

	/**
	 * Whether a digest's words are 64 bits wide: the SHA-512 family's and the sponge's.
	 * @param digest the digest's number
	 * @return {@code true} for SHA-384, SHA-512, SHA-512/t, SHA-3 and SHAKE
	 */
	static boolean wide(int digest) {
		return (digest >= SHA384 && digest <= SHA512_256) || sponge(digest);
	}

	/**
	 * Whether a digest is FIPS 202's sponge.
	 * @param digest the digest's number
	 * @return {@code true} for SHA-3 and SHAKE
	 */
	static boolean sponge(int digest) {
		return digest >= SHA3_224;
	}

	/**
	 * Returns the bytes of a digest's block: a sponge's is its rate.
	 * @param digest the digest's number
	 * @return its block's length
	 */
	static int block(int digest) {
		return switch (digest) {
			case MD2 -> 16;
			case SHA384, SHA512, SHA512_224, SHA512_256 -> 128;
			case SHA3_224 -> 144;
			case SHA3_256, SHAKE256 -> 136;
			case SHA3_384 -> 104;
			case SHA3_512 -> 72;
			case SHAKE128 -> 168;
			default -> 64;
		};
	}

	/**
	 * Returns the words of a digest's state.
	 * @param digest the digest's number
	 * @return how many
	 */
	static int stateLength(int digest) {
		return switch (digest) {
			case MD5 -> 4;
			case SHA1 -> 5;
			case MD2 -> 64;
			default -> sponge(digest) ? 25 : 8;
		};
	}

	/**
	 * Returns the initial state of a digest of 32-bit words.
	 * @param digest the digest's number, not {@link #wide}
	 * @return its words
	 */
	static int[] initialWords(int digest) {
		return switch (digest) {
			case MD2 -> new int[64];
			case MD5 -> new int[] { 0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476 };
			case SHA1 -> new int[] { 0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0 };
			case SHA224 -> new int[] { 0xc1059ed8, 0x367cd507, 0x3070dd17, 0xf70e5939, 0xffc00b31, 0x68581511,
					0x64f98fa7, 0xbefa4fa4 };
			default -> new int[] { 0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
					0x5be0cd19 };
		};
	}

	/**
	 * Returns the initial state of a digest of 64-bit words.
	 * @param digest the digest's number, {@link #wide}
	 * @return its words
	 */
	static long[] initialLongs(int digest) {
		if (sponge(digest)) {
			return new long[25];
		}
		return switch (digest) {
			case SHA384 ->
				new long[] { 0xcbbb9d5dc1059ed8L, 0x629a292a367cd507L, 0x9159015a3070dd17L, 0x152fecd8f70e5939L,
						0x67332667ffc00b31L, 0x8eb44a8768581511L, 0xdb0c2e0d64f98fa7L, 0x47b5481dbefa4fa4L };
			case SHA512_224 ->
				new long[] { 0x8c3d37c819544da2L, 0x73e1996689dcd4d6L, 0x1dfab7ae32ff9c82L, 0x679dd514582f9fcfL,
						0x0f6d2b697bd44da8L, 0x77e36f7304c48942L, 0x3f9d85a86a1d36c8L, 0x1112e6ad91d692a1L };
			case SHA512_256 ->
				new long[] { 0x22312194fc2bf72cL, 0x9f555fa3c84c64c2L, 0x2393b86b6f53b151L, 0x963877195940eabdL,
						0x96283ee2a88effe3L, 0xbe5e1e2553863992L, 0x2b0199fc2c85b8aaL, 0x0eb72ddc81c52ca2L };
			default -> new long[] { 0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
					0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L };
		};
	}

	/**
	 * Compresses blocks into the state of MD5, MD2, SHA-1 or SHA-224/256.
	 * @param digest the digest's number, not {@link #wide}
	 * @param state its words
	 * @param data the message
	 * @param offset where the first block starts
	 * @param blocks how many
	 */
	static void compress(int digest, int[] state, byte[] data, int offset, int blocks) {
		compress(digest, state, new int[digest == SHA1 ? 80 : 64], data, offset, blocks);
	}

	private static void compress(int digest, int[] state, int[] w, byte[] data, int offset, int blocks) {
		int block = block(digest);
		for (int n = 0; n < blocks; n++) {
			int base = offset + block * n;
			switch (digest) {
				case MD5 -> md5(state, w, data, base);
				case MD2 -> md2(state, data, base);
				case SHA1 -> sha1(state, w, data, base);
				default -> sha256(state, w, data, base);
			}
		}
	}

	/**
	 * Compresses blocks into the state of SHA-384, SHA-512, SHA-512/t, SHA-3 or SHAKE.
	 * @param digest the digest's number, {@link #wide}
	 * @param state its words
	 * @param data the message
	 * @param offset where the first block starts
	 * @param blocks how many
	 */
	static void compress(int digest, long[] state, byte[] data, int offset, int blocks) {
		compress(digest, state, sponge(digest) ? new long[0] : new long[80], data, offset, blocks);
	}

	private static void compress(int digest, long[] state, long[] w, byte[] data, int offset, int blocks) {
		int block = block(digest);
		for (int n = 0; n < blocks; n++) {
			if (sponge(digest)) {
				absorb(state, block, data, offset + block * n);
			}
			else {
				sha512(state, w, data, offset + 128 * n);
			}
		}
	}

	/**
	 * Digests a message.
	 * @param digest the digest's number
	 * @param message the message
	 * @return the digest
	 */
	static byte[] digest(int digest, byte[] message) {
		State state = new State(digest);
		state.update(message, 0, message.length);
		byte[] out = new byte[digestLength(digest)];
		state.finish(out);
		return out;
	}

	/**
	 * Derives a key with PBKDF2 over HMAC of a digest. The password's two HMAC pad states
	 * are absorbed once and copied for every round.
	 * @param digest the digest's number
	 * @param password the passphrase octets
	 * @param salt the salt octets
	 * @param iterations the iteration count (at least 1)
	 * @param keyLength the derived key length in bytes (at least 0)
	 * @return the derived key
	 */
	static byte[] pbkdf2(int digest, byte[] password, byte[] salt, int iterations, int keyLength) {
		State inner = new State(digest);
		State outer = new State(digest);
		keyed(password, inner, outer);
		State work = new State(digest);
		int h = digestLength(digest);
		byte[] key = new byte[keyLength];
		byte[] u = new byte[h];
		byte[] t = new byte[h];
		byte[] index = new byte[4];
		for (int i = 1, at = 0; at < keyLength; i++, at += h) {
			index[0] = (byte) (i >>> 24);
			index[1] = (byte) (i >>> 16);
			index[2] = (byte) (i >>> 8);
			index[3] = (byte) i;
			work.copy(inner);
			work.update(salt, 0, salt.length);
			work.update(index, 0, 4);
			work.finish(u);
			work.copy(outer);
			work.update(u, 0, h);
			work.finish(u);
			System.arraycopy(u, 0, t, 0, h);
			for (int j = 1; j < iterations; j++) {
				work.copy(inner);
				work.update(u, 0, h);
				work.finish(u);
				work.copy(outer);
				work.update(u, 0, h);
				work.finish(u);
				for (int k = 0; k < h; k++) {
					t[k] ^= u[k];
				}
			}
			System.arraycopy(t, 0, key, at, Math.min(h, keyLength - at));
		}
		return key;
	}

	/**
	 * Takes an HMAC key into two states at their start: the inner pad into one, the outer
	 * into the other; a key longer than a block is its digest.
	 */
	private static void keyed(byte[] key, State inner, State outer) {
		int block = inner.block();
		byte[] k = key.length > block ? digest(inner.digest, key) : key;
		byte[] pad = new byte[block];
		for (int i = 0; i < block; i++) {
			pad[i] = (byte) ((i < k.length ? k[i] : 0) ^ 0x36);
		}
		inner.update(pad, 0, block);
		for (int i = 0; i < block; i++) {
			pad[i] = (byte) ((i < k.length ? k[i] : 0) ^ 0x5c);
		}
		outer.update(pad, 0, block);
	}

	private static int bigEndian(byte[] b, int i) {
		return (b[i] << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
	}

	private static void md5(int[] state, int[] x, byte[] data, int base) {
		for (int i = 0; i < 16; i++) {
			int j = base + 4 * i;
			x[i] = (data[j] & 0xff) | ((data[j + 1] & 0xff) << 8) | ((data[j + 2] & 0xff) << 16) | (data[j + 3] << 24);
		}
		int a = state[0];
		int b = state[1];
		int c = state[2];
		int d = state[3];
		for (int i = 0; i < 64; i++) {
			int f;
			int g;
			if (i < 16) {
				f = (b & c) | (~b & d);
				g = i;
			}
			else if (i < 32) {
				f = (b & d) | (c & ~d);
				g = (5 * i + 1) & 15;
			}
			else if (i < 48) {
				f = b ^ c ^ d;
				g = (3 * i + 5) & 15;
			}
			else {
				f = c ^ (b | ~d);
				g = (7 * i) & 15;
			}
			int v = a + f + x[g] + T[i];
			a = d;
			d = c;
			c = b;
			b = b + Integer.rotateLeft(v, S[((i >>> 4) << 2) + (i & 3)]);
		}
		state[0] += a;
		state[1] += b;
		state[2] += c;
		state[3] += d;
	}

	private static void sha1(int[] state, int[] w, byte[] data, int base) {
		for (int t = 0; t < 16; t++) {
			w[t] = bigEndian(data, base + 4 * t);
		}
		for (int t = 16; t < 80; t++) {
			w[t] = Integer.rotateLeft(w[t - 3] ^ w[t - 8] ^ w[t - 14] ^ w[t - 16], 1);
		}
		int a = state[0];
		int b = state[1];
		int c = state[2];
		int d = state[3];
		int e = state[4];
		for (int t = 0; t < 80; t++) {
			int f;
			int k;
			if (t < 20) {
				f = (b & c) ^ (~b & d);
				k = 0x5a827999;
			}
			else if (t < 40) {
				f = b ^ c ^ d;
				k = 0x6ed9eba1;
			}
			else if (t < 60) {
				f = (b & c) ^ (b & d) ^ (c & d);
				k = 0x8f1bbcdc;
			}
			else {
				f = b ^ c ^ d;
				k = 0xca62c1d6;
			}
			int v = Integer.rotateLeft(a, 5) + f + e + k + w[t];
			e = d;
			d = c;
			c = Integer.rotateLeft(b, 30);
			b = a;
			a = v;
		}
		state[0] += a;
		state[1] += b;
		state[2] += c;
		state[3] += d;
		state[4] += e;
	}

	private static void sha256(int[] state, int[] w, byte[] data, int base) {
		for (int t = 0; t < 16; t++) {
			w[t] = bigEndian(data, base + 4 * t);
		}
		for (int t = 16; t < 64; t++) {
			int x = w[t - 15];
			int y = w[t - 2];
			int s0 = Integer.rotateRight(x, 7) ^ Integer.rotateRight(x, 18) ^ (x >>> 3);
			int s1 = Integer.rotateRight(y, 17) ^ Integer.rotateRight(y, 19) ^ (y >>> 10);
			w[t] = w[t - 16] + s0 + w[t - 7] + s1;
		}
		int a = state[0];
		int b = state[1];
		int c = state[2];
		int d = state[3];
		int e = state[4];
		int f = state[5];
		int g = state[6];
		int h = state[7];
		for (int t = 0; t < 64; t++) {
			int t1 = h + (Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25))
					+ ((e & f) ^ (~e & g)) + K256[t] + w[t];
			int t2 = (Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22))
					+ ((a & b) ^ (a & c) ^ (b & c));
			h = g;
			g = f;
			f = e;
			e = d + t1;
			d = c;
			c = b;
			b = a;
			a = t1 + t2;
		}
		state[0] += a;
		state[1] += b;
		state[2] += c;
		state[3] += d;
		state[4] += e;
		state[5] += f;
		state[6] += g;
		state[7] += h;
	}

	/**
	 * One block of MD2: into the checksum, the state's last sixteen bytes, then into X.
	 */
	private static void md2(int[] state, byte[] data, int base) {
		int l = state[63];
		for (int j = 0; j < 16; j++) {
			l = state[48 + j] ^ PI_SUBST[(data[base + j] & 0xff) ^ l];
			state[48 + j] = l;
		}
		for (int j = 0; j < 16; j++) {
			int m = data[base + j] & 0xff;
			state[16 + j] = m;
			state[32 + j] = m ^ state[j];
		}
		int t = 0;
		for (int j = 0; j < 18; j++) {
			for (int k = 0; k < 48; k++) {
				t = state[k] ^ PI_SUBST[t];
				state[k] = t;
			}
			t = (t + j) & 0xff;
		}
	}

	/** One block of a sponge: its lanes, little-endian, into the state, then Keccak-f. */
	private static void absorb(long[] state, int block, byte[] data, int base) {
		for (int i = 0; i < block / 8; i++) {
			long lane = 0;
			for (int b = 7; b >= 0; b--) {
				lane = (lane << 8) | (data[base + 8 * i + b] & 0xff);
			}
			state[i] ^= lane;
		}
		keccak(state);
	}

	/**
	 * Keccak-f[1600] (FIPS 202, section 3.3): theta, rho, pi, chi and iota, 24 rounds.
	 */
	private static void keccak(long[] a) {
		long[] c = new long[5];
		long[] b = new long[25];
		for (int round = 0; round < 24; round++) {
			for (int x = 0; x < 5; x++) {
				c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20];
			}
			for (int x = 0; x < 5; x++) {
				long d = c[(x + 4) % 5] ^ Long.rotateLeft(c[(x + 1) % 5], 1);
				for (int y = 0; y < 5; y++) {
					a[x + 5 * y] ^= d;
				}
			}
			for (int x = 0; x < 5; x++) {
				for (int y = 0; y < 5; y++) {
					b[y + 5 * ((2 * x + 3 * y) % 5)] = Long.rotateLeft(a[x + 5 * y], RHO[x + 5 * y]);
				}
			}
			for (int y = 0; y < 5; y++) {
				for (int x = 0; x < 5; x++) {
					a[x + 5 * y] = b[x + 5 * y] ^ (~b[(x + 1) % 5 + 5 * y] & b[(x + 2) % 5 + 5 * y]);
				}
			}
			a[0] ^= IOTA[round];
		}
	}

	private static void sha512(long[] state, long[] w, byte[] data, int base) {
		for (int t = 0; t < 16; t++) {
			int j = base + 8 * t;
			w[t] = ((long) bigEndian(data, j) << 32) | (bigEndian(data, j + 4) & 0xffffffffL);
		}
		for (int t = 16; t < 80; t++) {
			long x = w[t - 15];
			long y = w[t - 2];
			long s0 = Long.rotateRight(x, 1) ^ Long.rotateRight(x, 8) ^ (x >>> 7);
			long s1 = Long.rotateRight(y, 19) ^ Long.rotateRight(y, 61) ^ (y >>> 6);
			w[t] = w[t - 16] + s0 + w[t - 7] + s1;
		}
		long a = state[0];
		long b = state[1];
		long c = state[2];
		long d = state[3];
		long e = state[4];
		long f = state[5];
		long g = state[6];
		long h = state[7];
		for (int t = 0; t < 80; t++) {
			long t1 = h + (Long.rotateRight(e, 14) ^ Long.rotateRight(e, 18) ^ Long.rotateRight(e, 41))
					+ ((e & f) ^ (~e & g)) + K512[t] + w[t];
			long t2 = (Long.rotateRight(a, 28) ^ Long.rotateRight(a, 34) ^ Long.rotateRight(a, 39))
					+ ((a & b) ^ (a & c) ^ (b & c));
			h = g;
			g = f;
			f = e;
			e = d + t1;
			d = c;
			c = b;
			b = a;
			a = t1 + t2;
		}
		state[0] += a;
		state[1] += b;
		state[2] += c;
		state[3] += d;
		state[4] += e;
		state[5] += f;
		state[6] += g;
		state[7] += h;
	}

	/**
	 * A digest's state: its words, the partial block and the message's length. Copyable,
	 * which is what lets an HMAC key schedule be absorbed once.
	 */
	private static final class State {

		private final int digest;

		private final int @Nullable [] words;

		private final long @Nullable [] longs;

		private final int @Nullable [] schedule;

		private final long @Nullable [] longSchedule;

		private final byte[] buffer;

		private int buffered;

		private long total;

		State(int digest) {
			this.digest = digest;
			boolean wide = wide(digest);
			this.words = wide ? null : initialWords(digest);
			this.longs = wide ? initialLongs(digest) : null;
			this.schedule = wide ? null : new int[80];
			this.longSchedule = wide ? new long[80] : null;
			this.buffer = new byte[block()];
		}

		int block() {
			return DigestKernels.block(this.digest);
		}

		void copy(State other) {
			if (this.words != null && other.words != null) {
				System.arraycopy(other.words, 0, this.words, 0, this.words.length);
			}
			if (this.longs != null && other.longs != null) {
				System.arraycopy(other.longs, 0, this.longs, 0, this.longs.length);
			}
			System.arraycopy(other.buffer, 0, this.buffer, 0, other.buffered);
			this.buffered = other.buffered;
			this.total = other.total;
		}

		private void blocks(byte[] data, int offset, int count) {
			if (this.longs != null && this.longSchedule != null) {
				compress(this.digest, this.longs, this.longSchedule, data, offset, count);
			}
			else if (this.words != null && this.schedule != null) {
				compress(this.digest, this.words, this.schedule, data, offset, count);
			}
		}

		void update(byte[] data, int offset, int length) {
			int block = block();
			this.total += length;
			int position = offset;
			int remaining = length;
			if (this.buffered > 0) {
				int fill = Math.min(block - this.buffered, remaining);
				System.arraycopy(data, position, this.buffer, this.buffered, fill);
				this.buffered += fill;
				position += fill;
				remaining -= fill;
				if (this.buffered < block) {
					return;
				}
				blocks(this.buffer, 0, 1);
				this.buffered = 0;
			}
			int whole = remaining / block;
			if (whole > 0) {
				blocks(data, position, whole);
				position += whole * block;
				remaining -= whole * block;
			}
			if (remaining > 0) {
				System.arraycopy(data, position, this.buffer, 0, remaining);
				this.buffered = remaining;
			}
		}

		/**
		 * Compresses the end of the message and writes the digest into OUT, which is at
		 * least the digest's length; the state is spent.
		 */
		void finish(byte[] out) {
			if (this.digest == MD2) {
				finishMd2(out);
				return;
			}
			if (sponge(this.digest) && this.longs != null) {
				Arrays.fill(this.buffer, this.buffered, block(), (byte) 0);
				this.buffer[this.buffered] = (byte) (this.digest >= SHAKE128 ? 0x1f : 0x06);
				this.buffer[block() - 1] |= (byte) 0x80;
				blocks(this.buffer, 0, 1);
				for (int i = 0; i < digestLength(this.digest); i++) {
					out[i] = (byte) (this.longs[i >>> 3] >>> (8 * (i & 7)));
				}
				return;
			}
			int block = block();
			long bits = this.total << 3;
			this.buffer[this.buffered++] = (byte) 0x80;
			if (this.buffered > block - block / 8) {
				Arrays.fill(this.buffer, this.buffered, block, (byte) 0);
				blocks(this.buffer, 0, 1);
				this.buffered = 0;
			}
			Arrays.fill(this.buffer, this.buffered, block, (byte) 0);
			for (int i = 0; i < 8; i++) {
				int at = this.digest == MD5 ? block - 8 + i : block - 1 - i;
				this.buffer[at] = (byte) (bits >>> (8 * i));
			}
			if (this.longs != null) {
				long high = this.total >>> 61;
				for (int i = 0; i < 8; i++) {
					this.buffer[block - 9 - i] = (byte) (high >>> (8 * i));
				}
			}
			blocks(this.buffer, 0, 1);
			int length = digestLength(this.digest);
			for (int i = 0; i < length; i++) {
				if (this.longs != null) {
					out[i] = (byte) (this.longs[i >>> 3] >>> (56 - 8 * (i & 7)));
				}
				else if (this.words != null) {
					int word = this.words[i >>> 2];
					out[i] = (byte) (this.digest == MD5 ? word >>> (8 * (i & 3)) : word >>> (24 - 8 * (i & 3)));
				}
			}
		}

		/** MD2's end: i bytes of the value i, then the checksum as one more block. */
		private void finishMd2(byte[] out) {
			int[] state = this.words;
			if (state == null) {
				return;
			}
			Arrays.fill(this.buffer, this.buffered, 16, (byte) (16 - this.buffered));
			blocks(this.buffer, 0, 1);
			for (int i = 0; i < 16; i++) {
				this.buffer[i] = (byte) state[48 + i];
			}
			blocks(this.buffer, 0, 1);
			for (int i = 0; i < 16; i++) {
				out[i] = (byte) state[i];
			}
		}

	}

}
