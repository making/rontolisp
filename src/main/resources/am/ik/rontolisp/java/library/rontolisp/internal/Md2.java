// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// MD2 as RFC 1319 states it (with its published correction of step 2, a checksum byte
// taking the exclusive or of S): the message padded with i bytes of the value i, its
// checksum of sixteen bytes appended, and each block of sixteen mixed into a state of
// forty-eight bytes in eighteen rounds through the RFC's permutation S of the bytes.
package rontolisp.internal;

public final class Md2 extends BlockDigest {

    // RFC 1319's permutation of 0 to 255, made of the digits of pi.
    private static final byte[] S = {
            41, 46, 67, (byte) 201, (byte) 162, (byte) 216, 124, 1, 61, 54, 84, (byte) 161, (byte) 236, (byte) 240, 6,
            19, 98, (byte) 167, 5, (byte) 243, (byte) 192, (byte) 199, 115, (byte) 140, (byte) 152, (byte) 147, 43,
            (byte) 217, (byte) 188, 76, (byte) 130, (byte) 202, 30, (byte) 155, 87, 60, (byte) 253, (byte) 212,
            (byte) 224, 22, 103, 66, 111, 24, (byte) 138, 23, (byte) 229, 18, (byte) 190, 78, (byte) 196, (byte) 214,
            (byte) 218, (byte) 158, (byte) 222, 73, (byte) 160, (byte) 251, (byte) 245, (byte) 142, (byte) 187, 47,
            (byte) 238, 122, (byte) 169, 104, 121, (byte) 145, 21, (byte) 178, 7, 63, (byte) 148, (byte) 194, 16,
            (byte) 137, 11, 34, 95, 33, (byte) 128, 127, 93, (byte) 154, 90, (byte) 144, 50, 39, 53, 62, (byte) 204,
            (byte) 231, (byte) 191, (byte) 247, (byte) 151, 3, (byte) 255, 25, 48, (byte) 179, 72, (byte) 165,
            (byte) 181, (byte) 209, (byte) 215, 94, (byte) 146, 42, (byte) 172, 86, (byte) 170, (byte) 198, 79,
            (byte) 184, 56, (byte) 210, (byte) 150, (byte) 164, 125, (byte) 182, 118, (byte) 252, 107, (byte) 226,
            (byte) 156, 116, 4, (byte) 241, 69, (byte) 157, 112, 89, 100, 113, (byte) 135, 32, (byte) 134, 91,
            (byte) 207, 101, (byte) 230, 45, (byte) 168, 2, 27, 96, 37, (byte) 173, (byte) 174, (byte) 176,
            (byte) 185, (byte) 246, 28, 70, 97, 105, 52, 64, 126, 15, 85, 71, (byte) 163, 35, (byte) 221, 81,
            (byte) 175, 58, (byte) 195, 92, (byte) 249, (byte) 206, (byte) 186, (byte) 197, (byte) 234, 38, 44, 83,
            13, 110, (byte) 133, 40, (byte) 132, 9, (byte) 211, (byte) 223, (byte) 205, (byte) 244, 65, (byte) 129,
            77, 82, 106, (byte) 220, 55, (byte) 200, 108, (byte) 193, (byte) 171, (byte) 250, 36, (byte) 225, 123, 8,
            12, (byte) 189, (byte) 177, 74, 120, (byte) 136, (byte) 149, (byte) 139, (byte) 227, 99, (byte) 232, 109,
            (byte) 233, (byte) 203, (byte) 213, (byte) 254, 59, 0, 29, 57, (byte) 242, (byte) 239, (byte) 183, 14,
            102, 88, (byte) 208, (byte) 228, (byte) 166, 119, 114, (byte) 248, (byte) 235, 117, 75, 10, 49, 68, 80,
            (byte) 180, (byte) 143, (byte) 237, 31, 26, (byte) 219, (byte) 153, (byte) 141, 51, (byte) 159, 17,
            (byte) 131, 20 };

    // The forty-eight bytes of X, then the sixteen of the checksum C.
    private final int[] state = new int[64];

    public Md2(String algorithm) {
        super(algorithm, Hashing.MD2, 16, 16);
    }

    // What a constant name makes: this class and no other digest's.
    public static Md2 of(String algorithm) {
        return new Md2(algorithm);
    }

    void initial() {
        for (int i = 0; i < 64; i++) {
            state[i] = 0;
        }
    }

    // Each block into the checksum, whose last byte is the L the next block starts
    // from, and into X.
    void compress(byte[] data, int offset, int blocks) {
        if (Hashing.compressed(Hashing.MD2, state, data, offset, blocks)) {
            return;
        }
        int[] x = state;
        byte[] s = S;
        for (int n = 0; n < blocks; n++) {
            int base = offset + 16 * n;
            int l = x[63];
            for (int j = 0; j < 16; j++) {
                l = x[48 + j] ^ (s[(data[base + j] & 0xff) ^ l] & 0xff);
                x[48 + j] = l;
            }
            for (int j = 0; j < 16; j++) {
                int m = data[base + j] & 0xff;
                x[16 + j] = m;
                x[32 + j] = m ^ x[j];
            }
            int t = 0;
            for (int j = 0; j < 18; j++) {
                for (int k = 0; k < 48; k++) {
                    t = x[k] ^ (s[t] & 0xff);
                    x[k] = t;
                }
                t = (t + j) & 0xff;
            }
        }
    }

    // The padding's block, then the checksum's as one more.
    void finish(byte[] out, int offset) {
        int pad = 16 - buffered;
        for (int i = buffered; i < 16; i++) {
            buffer[i] = (byte) pad;
        }
        compress(buffer, 0, 1);
        for (int i = 0; i < 16; i++) {
            buffer[i] = (byte) state[48 + i];
        }
        compress(buffer, 0, 1);
        output(out, offset);
    }

    void output(byte[] out, int offset) {
        for (int i = 0; i < 16; i++) {
            out[offset + i] = (byte) state[i];
        }
    }

    public void restore(BlockDigest other) {
        System.arraycopy(((Md2) other).state, 0, state, 0, 64);
        restoreBuffer(other);
    }

    public BlockDigest fresh() {
        return new Md2(getAlgorithm());
    }
}
