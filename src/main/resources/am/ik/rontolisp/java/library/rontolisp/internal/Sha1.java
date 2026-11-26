// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// SHA-1 as FIPS 180-4 states it (sections 5.3.1 and 6.1): eighty steps over a schedule
// of eighty words, four functions and four constants, twenty steps each. The steps are
// one loop choosing its function and constant by the step's range, as section 4.1.1
// writes f: four loops of twenty, each assigning the registers, cost wasm their boxes.
package rontolisp.internal;

public final class Sha1 extends BlockDigest {

    private final int[] state = new int[5];

    private final int[] w = new int[80];

    public Sha1(String algorithm) {
        super(algorithm, Hashing.SHA1, 64, 20);
        initial();
    }

    // What a constant name makes: this class and no other digest's.
    public static Sha1 of(String algorithm) {
        return new Sha1(algorithm);
    }

    void initial() {
        state[0] = 0x67452301;
        state[1] = 0xefcdab89;
        state[2] = 0x98badcfe;
        state[3] = 0x10325476;
        state[4] = 0xc3d2e1f0;
    }

    void compress(byte[] data, int offset, int blocks) {
        if (Hashing.compressed(Hashing.SHA1, state, data, offset, blocks)) {
            return;
        }
        int[] s = w;
        for (int n = 0; n < blocks; n++) {
            int base = offset + 64 * n;
            for (int t = 0; t < 16; t++) {
                int j = base + 4 * t;
                s[t] = (data[j] << 24) | ((data[j + 1] & 0xff) << 16) | ((data[j + 2] & 0xff) << 8)
                        | (data[j + 3] & 0xff);
            }
            for (int t = 16; t < 80; t++) {
                int v = s[t - 3] ^ s[t - 8] ^ s[t - 14] ^ s[t - 16];
                s[t] = (v << 1) | (v >>> 31);
            }
            int a = state[0];
            int b = state[1];
            int c = state[2];
            int d = state[3];
            int e = state[4];
            for (int t = 0; t < 80; t++) {
                int f;
                if (t < 20) {
                    f = ((b & c) ^ (~b & d)) + 0x5a827999;
                } else if (t < 40) {
                    f = (b ^ c ^ d) + 0x6ed9eba1;
                } else if (t < 60) {
                    f = ((b & c) ^ (b & d) ^ (c & d)) + 0x8f1bbcdc;
                } else {
                    f = (b ^ c ^ d) + 0xca62c1d6;
                }
                int v = ((a << 5) | (a >>> 27)) + f + e + s[t];
                e = d;
                d = c;
                c = (b << 30) | (b >>> 2);
                b = a;
                a = v;
            }
            state[0] += a;
            state[1] += b;
            state[2] += c;
            state[3] += d;
            state[4] += e;
        }
    }

    void output(byte[] out, int offset) {
        for (int i = 0; i < 5; i++) {
            int v = state[i];
            out[offset + 4 * i] = (byte) (v >>> 24);
            out[offset + 4 * i + 1] = (byte) (v >>> 16);
            out[offset + 4 * i + 2] = (byte) (v >>> 8);
            out[offset + 4 * i + 3] = (byte) v;
        }
    }

    public void restore(BlockDigest other) {
        System.arraycopy(((Sha1) other).state, 0, state, 0, 5);
        restoreBuffer(other);
    }

    public BlockDigest fresh() {
        return new Sha1(getAlgorithm());
    }
}
