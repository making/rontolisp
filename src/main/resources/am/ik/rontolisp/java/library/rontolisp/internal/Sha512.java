// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// SHA-512, SHA-384, SHA-512/224 and SHA-512/256 as FIPS 180-4 states them (sections
// 4.2.3, 5.3.4 to 5.3.6, 6.4 to 6.7): one compression of eighty steps over 64-bit words;
// the four start from their own words and keep as many bytes as they are long.
package rontolisp.internal;

public final class Sha512 extends BlockDigest {

    // The first 64 bits of the fractions of the cube roots of the first 80 primes, each
    // as its high 32 bits and its low 32 bits: words a packed array holds.
    private static final int[] K = { 0x428a2f98, 0xd728ae22, 0x71374491, 0x23ef65cd, 0xb5c0fbcf, 0xec4d3b2f,
            0xe9b5dba5, 0x8189dbbc, 0x3956c25b, 0xf348b538, 0x59f111f1, 0xb605d019, 0x923f82a4, 0xaf194f9b,
            0xab1c5ed5, 0xda6d8118, 0xd807aa98, 0xa3030242, 0x12835b01, 0x45706fbe, 0x243185be, 0x4ee4b28c,
            0x550c7dc3, 0xd5ffb4e2, 0x72be5d74, 0xf27b896f, 0x80deb1fe, 0x3b1696b1, 0x9bdc06a7, 0x25c71235,
            0xc19bf174, 0xcf692694, 0xe49b69c1, 0x9ef14ad2, 0xefbe4786, 0x384f25e3, 0x0fc19dc6, 0x8b8cd5b5,
            0x240ca1cc, 0x77ac9c65, 0x2de92c6f, 0x592b0275, 0x4a7484aa, 0x6ea6e483, 0x5cb0a9dc, 0xbd41fbd4,
            0x76f988da, 0x831153b5, 0x983e5152, 0xee66dfab, 0xa831c66d, 0x2db43210, 0xb00327c8, 0x98fb213f,
            0xbf597fc7, 0xbeef0ee4, 0xc6e00bf3, 0x3da88fc2, 0xd5a79147, 0x930aa725, 0x06ca6351, 0xe003826f,
            0x14292967, 0x0a0e6e70, 0x27b70a85, 0x46d22ffc, 0x2e1b2138, 0x5c26c926, 0x4d2c6dfc, 0x5ac42aed,
            0x53380d13, 0x9d95b3df, 0x650a7354, 0x8baf63de, 0x766a0abb, 0x3c77b2a8, 0x81c2c92e, 0x47edaee6,
            0x92722c85, 0x1482353b, 0xa2bfe8a1, 0x4cf10364, 0xa81a664b, 0xbc423001, 0xc24b8b70, 0xd0f89791,
            0xc76c51a3, 0x0654be30, 0xd192e819, 0xd6ef5218, 0xd6990624, 0x5565a910, 0xf40e3585, 0x5771202a,
            0x106aa070, 0x32bbd1b8, 0x19a4c116, 0xb8d2d0c8, 0x1e376c08, 0x5141ab53, 0x2748774c, 0xdf8eeb99,
            0x34b0bcb5, 0xe19b48a8, 0x391c0cb3, 0xc5c95a63, 0x4ed8aa4a, 0xe3418acb, 0x5b9cca4f, 0x7763e373,
            0x682e6ff3, 0xd6b2b8a3, 0x748f82ee, 0x5defb2fc, 0x78a5636f, 0x43172f60, 0x84c87814, 0xa1f0ab72,
            0x8cc70208, 0x1a6439ec, 0x90befffa, 0x23631e28, 0xa4506ceb, 0xde82bde9, 0xbef9a3f7, 0xb2c67915,
            0xc67178f2, 0xe372532b, 0xca273ece, 0xea26619c, 0xd186b8c7, 0x21c0c207, 0xeada7dd6, 0xcde0eb1e,
            0xf57d4f7f, 0xee6ed178, 0x06f067aa, 0x72176fba, 0x0a637dc5, 0xa2c898a6, 0x113f9804, 0xbef90dae,
            0x1b710b35, 0x131c471b, 0x28db77f5, 0x23047d84, 0x32caab7b, 0x40c72493, 0x3c9ebe0a, 0x15c9bebc,
            0x431d67c4, 0x9c100d4c, 0x4cc5d4be, 0xcb3e42b6, 0x597f299c, 0xfc657e2a, 0x5fcb6fab, 0x3ad6faec,
            0x6c44198c, 0x4a475817 };

    // The words each digest starts from, as K's are written: SHA-384's, SHA-512/224's,
    // SHA-512/256's, then SHA-512's.
    private static final int[] IV = { 0xcbbb9d5d, 0xc1059ed8, 0x629a292a, 0x367cd507, 0x9159015a, 0x3070dd17,
            0x152fecd8, 0xf70e5939, 0x67332667, 0xffc00b31, 0x8eb44a87, 0x68581511, 0xdb0c2e0d, 0x64f98fa7,
            0x47b5481d, 0xbefa4fa4, 0x8c3d37c8, 0x19544da2, 0x73e19966, 0x89dcd4d6, 0x1dfab7ae, 0x32ff9c82,
            0x679dd514, 0x582f9fcf, 0x0f6d2b69, 0x7bd44da8, 0x77e36f73, 0x04c48942, 0x3f9d85a8, 0x6a1d36c8,
            0x1112e6ad, 0x91d692a1, 0x22312194, 0xfc2bf72c, 0x9f555fa3, 0xc84c64c2, 0x2393b86b, 0x6f53b151,
            0x96387719, 0x5940eabd, 0x96283ee2, 0xa88effe3, 0xbe5e1e25, 0x53863992, 0x2b0199fc, 0x2c85b8aa,
            0x0eb72ddc, 0x81c52ca2, 0x6a09e667, 0xf3bcc908, 0xbb67ae85, 0x84caa73b, 0x3c6ef372, 0xfe94f82b,
            0xa54ff53a, 0x5f1d36f1, 0x510e527f, 0xade682d1, 0x9b05688c, 0x2b3e6c1f, 0x1f83d9ab, 0xfb41bd6b,
            0x5be0cd19, 0x137e2179 };

    private final long[] state = new long[8];

    private final long[] w = new long[80];

    public Sha512(String algorithm, int digest) {
        super(algorithm, digest, 128, digest == Hashing.SHA384 ? 48
                : digest == Hashing.SHA512_224 ? 28 : digest == Hashing.SHA512_256 ? 32 : 64);
        initial();
    }

    // What a constant name makes: this class and no other digest's.
    public static Sha512 of(String algorithm, int digest) {
        return new Sha512(algorithm, digest);
    }

    void initial() {
        int from = digest == Hashing.SHA384 ? 0
                : digest == Hashing.SHA512_224 ? 16 : digest == Hashing.SHA512_256 ? 32 : 48;
        for (int i = 0; i < 8; i++) {
            state[i] = ((long) IV[from + 2 * i] << 32) | (IV[from + 2 * i + 1] & 0xffffffffL);
        }
    }

    void compress(byte[] data, int offset, int blocks) {
        if (Hashing.compressed(digest, state, data, offset, blocks)) {
            return;
        }
        for (int n = 0; n < blocks; n++) {
            block(state, w, data, offset + 128 * n);
        }
    }

    // One block from BASE into the state, the schedule in W.
    private static void block(long[] state, long[] w, byte[] data, int base) {
        int[] k = K;
        for (int t = 0; t < 16; t++) {
            int j = base + 8 * t;
            w[t] = ((long) data[j] << 56) | ((long) (data[j + 1] & 0xff) << 48) | ((long) (data[j + 2] & 0xff) << 40)
                    | ((long) (data[j + 3] & 0xff) << 32) | ((long) (data[j + 4] & 0xff) << 24)
                    | ((data[j + 5] & 0xff) << 16) | ((data[j + 6] & 0xff) << 8) | (data[j + 7] & 0xff);
        }
        for (int t = 16; t < 80; t++) {
            long x = w[t - 15];
            long y = w[t - 2];
            long s0 = ((x >>> 1) | (x << 63)) ^ ((x >>> 8) | (x << 56)) ^ (x >>> 7);
            long s1 = ((y >>> 19) | (y << 45)) ^ ((y >>> 61) | (y << 3)) ^ (y >>> 6);
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
            long t1 = h + (((e >>> 14) | (e << 50)) ^ ((e >>> 18) | (e << 46)) ^ ((e >>> 41) | (e << 23)))
                    + ((e & f) ^ (~e & g)) + (((long) k[2 * t] << 32) | (k[2 * t + 1] & 0xffffffffL)) + w[t];
            long t2 = (((a >>> 28) | (a << 36)) ^ ((a >>> 34) | (a << 30)) ^ ((a >>> 39) | (a << 25)))
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

    // A 128-bit length: its high half holds the bits a long's bytes leave.
    void lengthField(long bytes) {
        bigEndianLength(bytes);
        long high = bytes >>> 61;
        for (int i = 0; i < 8; i++) {
            buffer[119 - i] = (byte) (high >>> (8 * i));
        }
    }

    void output(byte[] out, int offset) {
        for (int i = 0; i < length; i++) {
            out[offset + i] = (byte) (state[i >>> 3] >>> (56 - 8 * (i & 7)));
        }
    }

    public void restore(BlockDigest other) {
        System.arraycopy(((Sha512) other).state, 0, state, 0, 8);
        restoreBuffer(other);
    }

    public BlockDigest fresh() {
        return new Sha512(getAlgorithm(), digest);
    }
}
