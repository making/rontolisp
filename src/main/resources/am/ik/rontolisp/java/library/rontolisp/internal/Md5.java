// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// MD5 as RFC 1321 states it: four rounds of sixteen steps over a block's sixteen words,
// read with their lowest byte first, and the table T of the integer parts of
// 2^32 * |sin(i)|. Each round is written as the RFC writes it, four steps at a time with
// the four registers in turn.
package rontolisp.internal;

public final class Md5 extends BlockDigest {

    private static final int[] T = { 0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a,
            0xa8304613, 0xfd469501, 0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193,
            0xa679438e, 0x49b40821, 0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453,
            0xd8a1e681, 0xe7d3fbc8, 0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8,
            0x676f02d9, 0x8d2a4c8a, 0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9,
            0xf6bb4b60, 0xbebfbc70, 0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5,
            0x1fa27cf8, 0xc4ac5665, 0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92,
            0xffeff47d, 0x85845dd1, 0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235,
            0x2ad7d2bb, 0xeb86d391 };

    private final int[] state = new int[4];

    private final int[] words = new int[16];

    public Md5(String algorithm) {
        super(algorithm, Hashing.MD5, 64, 16);
        initial();
    }

    // What a constant name makes: this class and no other digest's.
    public static Md5 of(String algorithm) {
        return new Md5(algorithm);
    }

    void initial() {
        state[0] = 0x67452301;
        state[1] = 0xefcdab89;
        state[2] = 0x98badcfe;
        state[3] = 0x10325476;
    }

    void compress(byte[] data, int offset, int blocks) {
        if (Hashing.compressed(Hashing.MD5, state, data, offset, blocks)) {
            return;
        }
        int[] x = words;
        int[] t = T;
        for (int n = 0; n < blocks; n++) {
            int base = offset + 64 * n;
            for (int i = 0; i < 16; i++) {
                int j = base + 4 * i;
                x[i] = (data[j] & 0xff) | ((data[j + 1] & 0xff) << 8) | ((data[j + 2] & 0xff) << 16)
                        | (data[j + 3] << 24);
            }
            int a = state[0];
            int b = state[1];
            int c = state[2];
            int d = state[3];
            for (int i = 0; i < 16; i += 4) {
                int v = a + ((b & c) | (~b & d)) + x[i] + t[i];
                a = b + ((v << 7) | (v >>> 25));
                v = d + ((a & b) | (~a & c)) + x[i + 1] + t[i + 1];
                d = a + ((v << 12) | (v >>> 20));
                v = c + ((d & a) | (~d & b)) + x[i + 2] + t[i + 2];
                c = d + ((v << 17) | (v >>> 15));
                v = b + ((c & d) | (~c & a)) + x[i + 3] + t[i + 3];
                b = c + ((v << 22) | (v >>> 10));
            }
            for (int i = 16; i < 32; i += 4) {
                int v = a + ((b & d) | (c & ~d)) + x[(5 * i + 1) & 15] + t[i];
                a = b + ((v << 5) | (v >>> 27));
                v = d + ((a & c) | (b & ~c)) + x[(5 * i + 6) & 15] + t[i + 1];
                d = a + ((v << 9) | (v >>> 23));
                v = c + ((d & b) | (a & ~b)) + x[(5 * i + 11) & 15] + t[i + 2];
                c = d + ((v << 14) | (v >>> 18));
                v = b + ((c & a) | (d & ~a)) + x[(5 * i + 16) & 15] + t[i + 3];
                b = c + ((v << 20) | (v >>> 12));
            }
            for (int i = 32; i < 48; i += 4) {
                int v = a + (b ^ c ^ d) + x[(3 * i + 5) & 15] + t[i];
                a = b + ((v << 4) | (v >>> 28));
                v = d + (a ^ b ^ c) + x[(3 * i + 8) & 15] + t[i + 1];
                d = a + ((v << 11) | (v >>> 21));
                v = c + (d ^ a ^ b) + x[(3 * i + 11) & 15] + t[i + 2];
                c = d + ((v << 16) | (v >>> 16));
                v = b + (c ^ d ^ a) + x[(3 * i + 14) & 15] + t[i + 3];
                b = c + ((v << 23) | (v >>> 9));
            }
            for (int i = 48; i < 64; i += 4) {
                int v = a + (c ^ (b | ~d)) + x[(7 * i) & 15] + t[i];
                a = b + ((v << 6) | (v >>> 26));
                v = d + (b ^ (a | ~c)) + x[(7 * i + 7) & 15] + t[i + 1];
                d = a + ((v << 10) | (v >>> 22));
                v = c + (a ^ (d | ~b)) + x[(7 * i + 14) & 15] + t[i + 2];
                c = d + ((v << 15) | (v >>> 17));
                v = b + (d ^ (c | ~a)) + x[(7 * i + 21) & 15] + t[i + 3];
                b = c + ((v << 21) | (v >>> 11));
            }
            state[0] += a;
            state[1] += b;
            state[2] += c;
            state[3] += d;
        }
    }


    // The length's bits, the lowest byte first.
    void lengthField(long bytes) {
        long bits = bytes << 3;
        for (int i = 0; i < 8; i++) {
            buffer[56 + i] = (byte) (bits >>> (8 * i));
        }
    }

    void output(byte[] out, int offset) {
        for (int i = 0; i < 4; i++) {
            int v = state[i];
            out[offset + 4 * i] = (byte) v;
            out[offset + 4 * i + 1] = (byte) (v >>> 8);
            out[offset + 4 * i + 2] = (byte) (v >>> 16);
            out[offset + 4 * i + 3] = (byte) (v >>> 24);
        }
    }

    public void restore(BlockDigest other) {
        System.arraycopy(((Md5) other).state, 0, state, 0, 4);
        restoreBuffer(other);
    }

    public BlockDigest fresh() {
        return new Md5(getAlgorithm());
    }
}
