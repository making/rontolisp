package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * SHA-224 / SHA-256, HMAC and PBKDF2 over plain {@code byte[]} -- the native kernel
 * {@link IroncladNative} drives. Nothing here knows a Lisp type.
 *
 * <p>
 * Hand-written rather than delegated to {@code java.security.MessageDigest} on purpose:
 * this class is reachable from {@link LispEvaluator}, so it is also compiled into the
 * GraalVM native binary AND into the browser Web Image build, where a JCA provider lookup
 * is either a registration burden or simply absent. Sixty lines of FIPS 180-4 round
 * function have no such tail.
 *
 * <p>
 * The output is a spec-defined function of the input, so this kernel and ironclad's own
 * Lisp code are interchangeable by construction; {@code IroncladE2eTest}'s published
 * vectors (FIPS 180-2, RFC 4231, RFC 7677) are the pinning oracle, and
 * {@code Sha2KernelsTest} additionally checks it against the JDK's own PBKDF2.
 */
final class Sha2Kernels {

	/** The SHA-256 digest length in bytes. */
	static final int SHA256_LENGTH = 32;

	/** The SHA-224 digest length in bytes. */
	static final int SHA224_LENGTH = 28;

	/** The SHA-2 (32-bit family) block length in bytes. */
	private static final int BLOCK_LENGTH = 64;

	private static final int[] SHA256_IV = { 0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c,
			0x1f83d9ab, 0x5be0cd19 };

	private static final int[] SHA224_IV = { 0xc1059ed8, 0x367cd507, 0x3070dd17, 0xf70e5939, 0xffc00b31, 0x68581511,
			0x64f98fa7, 0xbefa4fa4 };

	private static final int[] K = { 0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
			0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
			0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152,
			0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138,
			0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70,
			0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
			0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa,
			0xa4506ceb, 0xbef9a3f7, 0xc67178f2 };

	private Sha2Kernels() {
	}

	/**
	 * Returns the digest length of a SHA-2 variant, in bytes.
	 * @param sha224 {@code true} for SHA-224, {@code false} for SHA-256
	 * @return 28 or 32
	 */
	static int digestLength(boolean sha224) {
		return sha224 ? SHA224_LENGTH : SHA256_LENGTH;
	}

	/**
	 * Digests a message.
	 * @param sha224 {@code true} for SHA-224, {@code false} for SHA-256
	 * @param message the message
	 * @return the digest
	 */
	static byte[] digest(boolean sha224, byte[] message) {
		State state = new State(sha224);
		state.update(message, 0, message.length);
		return state.finish();
	}

	/**
	 * Derives a key with PBKDF2 (RFC 2898 section 5.2) over HMAC-SHA-224/256. The
	 * password's two HMAC pad states are absorbed once and reused for every iteration,
	 * which is what makes this the fast path: the interpreted ironclad loop re-derives
	 * them per iteration.
	 * @param sha224 {@code true} for SHA-224, {@code false} for SHA-256
	 * @param password the passphrase octets
	 * @param salt the salt octets
	 * @param iterations the iteration count (>= 1)
	 * @param keyLength the derived key length in bytes (>= 1)
	 * @return the derived key
	 */
	static byte[] pbkdf2(boolean sha224, byte[] password, byte[] salt, int iterations, int keyLength) {
		Hmac hmac = new Hmac(sha224, password);
		int hLength = digestLength(sha224);
		byte[] key = new byte[keyLength];
		byte[] counter = new byte[4];
		int position = 0;
		for (int block = 1; position < keyLength; block++) {
			counter[0] = (byte) (block >>> 24);
			counter[1] = (byte) (block >>> 16);
			counter[2] = (byte) (block >>> 8);
			counter[3] = (byte) block;
			byte[] u = hmac.of(salt, counter);
			byte[] accumulated = u.clone();
			for (int i = 1; i < iterations; i++) {
				u = hmac.of(u, null);
				for (int j = 0; j < hLength; j++) {
					accumulated[j] ^= u[j];
				}
			}
			int size = Math.min(hLength, keyLength - position);
			System.arraycopy(accumulated, 0, key, position, size);
			position += size;
		}
		return key;
	}

	/**
	 * An HMAC key schedule: the inner and outer digest states with the key's padded
	 * blocks already absorbed, so a message costs exactly two compressions plus the
	 * message itself.
	 */
	private static final class Hmac {

		private final State inner;

		private final State outer;

		private final int length;

		Hmac(boolean sha224, byte[] key) {
			byte[] padded = new byte[BLOCK_LENGTH];
			byte[] effective = key.length > BLOCK_LENGTH ? digest(sha224, key) : key;
			System.arraycopy(effective, 0, padded, 0, effective.length);
			byte[] pad = new byte[BLOCK_LENGTH];
			for (int i = 0; i < BLOCK_LENGTH; i++) {
				pad[i] = (byte) (padded[i] ^ 0x36);
			}
			this.inner = new State(sha224);
			this.inner.update(pad, 0, BLOCK_LENGTH);
			for (int i = 0; i < BLOCK_LENGTH; i++) {
				pad[i] = (byte) (padded[i] ^ 0x5c);
			}
			this.outer = new State(sha224);
			this.outer.update(pad, 0, BLOCK_LENGTH);
			this.length = digestLength(sha224);
		}

		/**
		 * Returns the MAC of one or two concatenated message parts.
		 * @param first the first message part
		 * @param second the second message part, or {@code null} for none
		 * @return the MAC
		 */
		byte[] of(byte[] first, byte @Nullable [] second) {
			State state = new State(this.inner);
			state.update(first, 0, first.length);
			if (second != null) {
				state.update(second, 0, second.length);
			}
			byte[] innerHash = state.finish();
			State out = new State(this.outer);
			out.update(innerHash, 0, this.length);
			return out.finish();
		}

	}

	/**
	 * A SHA-2 hashing state: the eight registers, the partial block and the message
	 * length. Copyable, which is what lets an HMAC key schedule be absorbed once.
	 */
	private static final class State {

		private final int[] registers;

		private final byte[] buffer = new byte[BLOCK_LENGTH];

		private final int[] schedule = new int[64];

		private final boolean sha224;

		private int buffered;

		private long total;

		State(boolean sha224) {
			this.registers = (sha224 ? SHA224_IV : SHA256_IV).clone();
			this.sha224 = sha224;
		}

		State(State other) {
			this.registers = other.registers.clone();
			System.arraycopy(other.buffer, 0, this.buffer, 0, BLOCK_LENGTH);
			this.sha224 = other.sha224;
			this.buffered = other.buffered;
			this.total = other.total;
		}

		void update(byte[] data, int offset, int length) {
			this.total += length;
			int position = offset;
			int remaining = length;
			if (this.buffered > 0) {
				int fill = Math.min(BLOCK_LENGTH - this.buffered, remaining);
				System.arraycopy(data, position, this.buffer, this.buffered, fill);
				this.buffered += fill;
				position += fill;
				remaining -= fill;
				if (this.buffered == BLOCK_LENGTH) {
					compress(this.buffer, 0);
					this.buffered = 0;
				}
			}
			while (remaining >= BLOCK_LENGTH) {
				compress(data, position);
				position += BLOCK_LENGTH;
				remaining -= BLOCK_LENGTH;
			}
			if (remaining > 0) {
				System.arraycopy(data, position, this.buffer, 0, remaining);
				this.buffered = remaining;
			}
		}

		byte[] finish() {
			long bits = this.total * 8;
			byte[] padding = new byte[BLOCK_LENGTH * 2];
			padding[0] = (byte) 0x80;
			int padLength = this.buffered < 56 ? 56 - this.buffered : 120 - this.buffered;
			for (int i = 0; i < 8; i++) {
				padding[padLength + i] = (byte) (bits >>> (56 - 8 * i));
			}
			update(padding, 0, padLength + 8);
			int words = this.sha224 ? 7 : 8;
			byte[] out = new byte[words * 4];
			for (int i = 0; i < words; i++) {
				out[i * 4] = (byte) (this.registers[i] >>> 24);
				out[i * 4 + 1] = (byte) (this.registers[i] >>> 16);
				out[i * 4 + 2] = (byte) (this.registers[i] >>> 8);
				out[i * 4 + 3] = (byte) this.registers[i];
			}
			return out;
		}

		private void compress(byte[] block, int offset) {
			int[] w = this.schedule;
			for (int i = 0; i < 16; i++) {
				int j = offset + i * 4;
				w[i] = (block[j] & 0xff) << 24 | (block[j + 1] & 0xff) << 16 | (block[j + 2] & 0xff) << 8
						| (block[j + 3] & 0xff);
			}
			for (int i = 16; i < 64; i++) {
				int x = w[i - 15];
				int y = w[i - 2];
				int s0 = Integer.rotateRight(x, 7) ^ Integer.rotateRight(x, 18) ^ (x >>> 3);
				int s1 = Integer.rotateRight(y, 17) ^ Integer.rotateRight(y, 19) ^ (y >>> 10);
				w[i] = w[i - 16] + s0 + w[i - 7] + s1;
			}
			int a = this.registers[0];
			int b = this.registers[1];
			int c = this.registers[2];
			int d = this.registers[3];
			int e = this.registers[4];
			int f = this.registers[5];
			int g = this.registers[6];
			int h = this.registers[7];
			for (int i = 0; i < 64; i++) {
				int s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
				int ch = (e & f) ^ (~e & g);
				int temp1 = h + s1 + ch + K[i] + w[i];
				int s0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
				int maj = (a & b) ^ (a & c) ^ (b & c);
				int temp2 = s0 + maj;
				h = g;
				g = f;
				f = e;
				e = d + temp1;
				d = c;
				c = b;
				b = a;
				a = temp1 + temp2;
			}
			this.registers[0] += a;
			this.registers[1] += b;
			this.registers[2] += c;
			this.registers[3] += d;
			this.registers[4] += e;
			this.registers[5] += f;
			this.registers[6] += g;
			this.registers[7] += h;
		}

	}

}
