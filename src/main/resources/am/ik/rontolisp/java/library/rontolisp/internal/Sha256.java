// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// SHA-256 and SHA-224 as FIPS 180-4 states them (sections 4.2.2, 5.3.2, 5.3.3, 6.2 and
// 6.3): one compression of sixty-four steps; SHA-224 starts from other words and keeps
// the first seven.
package rontolisp.internal;

public final class Sha256 extends BlockDigest {

    // The first 32 bits of the fractions of the cube roots of the first 64 primes.
    private static final int[] K = { 0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
            0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
            0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa,
            0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
            0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb,
            0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624,
            0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
            0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb,
            0xbef9a3f7, 0xc67178f2 };

    private final int[] state = new int[8];

    private final int[] w = new int[64];

    public Sha256(String algorithm, int digest) {
        super(algorithm, digest, 64, digest == Hashing.SHA224 ? 28 : 32);
        initial();
    }

    // What a constant name makes: this class and no other digest's.
    public static Sha256 of(String algorithm, int digest) {
        return new Sha256(algorithm, digest);
    }

    void initial() {
        if (digest == Hashing.SHA224) {
            state[0] = 0xc1059ed8;
            state[1] = 0x367cd507;
            state[2] = 0x3070dd17;
            state[3] = 0xf70e5939;
            state[4] = 0xffc00b31;
            state[5] = 0x68581511;
            state[6] = 0x64f98fa7;
            state[7] = 0xbefa4fa4;
        } else {
            state[0] = 0x6a09e667;
            state[1] = 0xbb67ae85;
            state[2] = 0x3c6ef372;
            state[3] = 0xa54ff53a;
            state[4] = 0x510e527f;
            state[5] = 0x9b05688c;
            state[6] = 0x1f83d9ab;
            state[7] = 0x5be0cd19;
        }
    }

    void compress(byte[] data, int offset, int blocks) {
        if (Hashing.compressed(digest, state, data, offset, blocks)) {
            return;
        }
        int[] s = w;
        int[] k = K;
        for (int n = 0; n < blocks; n++) {
            int base = offset + 64 * n;
            for (int t = 0; t < 16; t++) {
                int j = base + 4 * t;
                s[t] = (data[j] << 24) | ((data[j + 1] & 0xff) << 16) | ((data[j + 2] & 0xff) << 8)
                        | (data[j + 3] & 0xff);
            }
            for (int t = 16; t < 64; t++) {
                int x = s[t - 15];
                int y = s[t - 2];
                int s0 = ((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3);
                int s1 = ((y >>> 17) | (y << 15)) ^ ((y >>> 19) | (y << 13)) ^ (y >>> 10);
                s[t] = s[t - 16] + s0 + s[t - 7] + s1;
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
                int t1 = h + (((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7)))
                        + ((e & f) ^ (~e & g)) + k[t] + s[t];
                int t2 = (((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10)))
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
    }

    void output(byte[] out, int offset) {
        for (int i = 0; i < length / 4; i++) {
            int v = state[i];
            out[offset + 4 * i] = (byte) (v >>> 24);
            out[offset + 4 * i + 1] = (byte) (v >>> 16);
            out[offset + 4 * i + 2] = (byte) (v >>> 8);
            out[offset + 4 * i + 3] = (byte) v;
        }
    }

    public void restore(BlockDigest other) {
        System.arraycopy(((Sha256) other).state, 0, state, 0, 8);
        restoreBuffer(other);
    }

    public BlockDigest fresh() {
        return new Sha256(getAlgorithm(), digest);
    }
}
