// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// SHA3-224, SHA3-256, SHA3-384, SHA3-512, SHAKE128 and SHAKE256 as FIPS 202 states them:
// the sponge of Keccak-f[1600] (sections 3 and 4), whose block is its rate -- 200 bytes
// less twice the digest's for SHA-3, 168 and 136 for SHAKE128 and SHAKE256 -- and whose
// end is the domain's bits (01 for SHA-3, 1111 for SHAKE: section 6) then pad10*1
// (section 5.1). A SHAKE's digest is its first 32 or 64 bytes, as a JDK's SHAKE128-256
// and SHAKE256-512 are. The lanes are the state's 25 words, x + 5y, each little-endian.
package rontolisp.internal;

public final class Sha3 extends BlockDigest {

    // The round constants of iota as 32-bit halves, the high one first: rc of FIPS 202's
    // Algorithm 5 at bit 2^j - 1 of round i's word, j from 0 to 6 (Algorithm 6).
    private static final int[] RC = constants();

    private final long[] state = new long[25];

    public Sha3(String algorithm, int digest) {
        super(algorithm, digest, rate(digest), digest == Hashing.SHA3_224 ? 28
                : digest == Hashing.SHA3_384 ? 48 : digest == Hashing.SHA3_512 || digest == Hashing.SHAKE256 ? 64 : 32);
    }

    // What a constant name makes: this class and no other digest's.
    public static Sha3 of(String algorithm, int digest) {
        return new Sha3(algorithm, digest);
    }

    // The bytes of a block: 200 less the capacity, which is twice the digest's length for
    // SHA-3 and twice the strength for SHAKE.
    private static int rate(int digest) {
        switch (digest) {
            case Hashing.SHA3_224:
                return 144;
            case Hashing.SHA3_256, Hashing.SHAKE256:
                return 136;
            case Hashing.SHA3_384:
                return 104;
            case Hashing.SHA3_512:
                return 72;
            default:
                return 168;
        }
    }

    private static int[] constants() {
        int[] rc = new int[48];
        // the linear feedback register of Algorithm 5, its bit 0 the low bit; one step
        // a t from 1 on
        int r = 1;
        for (int t = 0; t < 168; t++) {
            if (t > 0) {
                r <<= 1;
                if ((r & 0x100) != 0) {
                    r ^= 0x171;
                }
            }
            int j = t % 7;
            if ((r & 1) != 0) {
                int bit = (1 << j) - 1;
                int i = t / 7;
                if (bit < 32) {
                    rc[2 * i + 1] |= 1 << bit;
                } else {
                    rc[2 * i] |= 1 << (bit - 32);
                }
            }
        }
        return rc;
    }

    void initial() {
        for (int i = 0; i < 25; i++) {
            state[i] = 0;
        }
    }

    void compress(byte[] data, int offset, int blocks) {
        if (Hashing.compressed(digest, state, data, offset, blocks)) {
            return;
        }
        int lanes = block / 8;
        for (int n = 0; n < blocks; n++) {
            int base = offset + block * n;
            for (int i = 0; i < lanes; i++) {
                int j = base + 8 * i;
                state[i] ^= (data[j] & 0xffL) | ((data[j + 1] & 0xffL) << 8) | ((data[j + 2] & 0xffL) << 16)
                        | ((data[j + 3] & 0xffL) << 24) | ((data[j + 4] & 0xffL) << 32) | ((data[j + 5] & 0xffL) << 40)
                        | ((data[j + 6] & 0xffL) << 48) | ((long) data[j + 7] << 56);
            }
            permute(state);
        }
    }

    // Keccak-f[1600]: twenty-four rounds of theta, rho and pi, chi and iota (section 3.2)
    // over the lanes held as locals, each lane's offset of rho a constant.
    private static void permute(long[] s) {
        long a00 = s[0];
        long a10 = s[1];
        long a20 = s[2];
        long a30 = s[3];
        long a40 = s[4];
        long a01 = s[5];
        long a11 = s[6];
        long a21 = s[7];
        long a31 = s[8];
        long a41 = s[9];
        long a02 = s[10];
        long a12 = s[11];
        long a22 = s[12];
        long a32 = s[13];
        long a42 = s[14];
        long a03 = s[15];
        long a13 = s[16];
        long a23 = s[17];
        long a33 = s[18];
        long a43 = s[19];
        long a04 = s[20];
        long a14 = s[21];
        long a24 = s[22];
        long a34 = s[23];
        long a44 = s[24];
        int[] rc = RC;
        for (int round = 0; round < 24; round++) {
            long c0 = a00 ^ a01 ^ a02 ^ a03 ^ a04;
            long c1 = a10 ^ a11 ^ a12 ^ a13 ^ a14;
            long c2 = a20 ^ a21 ^ a22 ^ a23 ^ a24;
            long c3 = a30 ^ a31 ^ a32 ^ a33 ^ a34;
            long c4 = a40 ^ a41 ^ a42 ^ a43 ^ a44;
            long d0 = c4 ^ ((c1 << 1) | (c1 >>> 63));
            long d1 = c0 ^ ((c2 << 1) | (c2 >>> 63));
            long d2 = c1 ^ ((c3 << 1) | (c3 >>> 63));
            long d3 = c2 ^ ((c4 << 1) | (c4 >>> 63));
            long d4 = c3 ^ ((c0 << 1) | (c0 >>> 63));
            long b00 = a00 ^ d0;
            long e10 = a11 ^ d1;
            long b10 = (e10 << 44) | (e10 >>> 20);
            long e20 = a22 ^ d2;
            long b20 = (e20 << 43) | (e20 >>> 21);
            long e30 = a33 ^ d3;
            long b30 = (e30 << 21) | (e30 >>> 43);
            long e40 = a44 ^ d4;
            long b40 = (e40 << 14) | (e40 >>> 50);
            long e01 = a30 ^ d3;
            long b01 = (e01 << 28) | (e01 >>> 36);
            long e11 = a41 ^ d4;
            long b11 = (e11 << 20) | (e11 >>> 44);
            long e21 = a02 ^ d0;
            long b21 = (e21 << 3) | (e21 >>> 61);
            long e31 = a13 ^ d1;
            long b31 = (e31 << 45) | (e31 >>> 19);
            long e41 = a24 ^ d2;
            long b41 = (e41 << 61) | (e41 >>> 3);
            long e02 = a10 ^ d1;
            long b02 = (e02 << 1) | (e02 >>> 63);
            long e12 = a21 ^ d2;
            long b12 = (e12 << 6) | (e12 >>> 58);
            long e22 = a32 ^ d3;
            long b22 = (e22 << 25) | (e22 >>> 39);
            long e32 = a43 ^ d4;
            long b32 = (e32 << 8) | (e32 >>> 56);
            long e42 = a04 ^ d0;
            long b42 = (e42 << 18) | (e42 >>> 46);
            long e03 = a40 ^ d4;
            long b03 = (e03 << 27) | (e03 >>> 37);
            long e13 = a01 ^ d0;
            long b13 = (e13 << 36) | (e13 >>> 28);
            long e23 = a12 ^ d1;
            long b23 = (e23 << 10) | (e23 >>> 54);
            long e33 = a23 ^ d2;
            long b33 = (e33 << 15) | (e33 >>> 49);
            long e43 = a34 ^ d3;
            long b43 = (e43 << 56) | (e43 >>> 8);
            long e04 = a20 ^ d2;
            long b04 = (e04 << 62) | (e04 >>> 2);
            long e14 = a31 ^ d3;
            long b14 = (e14 << 55) | (e14 >>> 9);
            long e24 = a42 ^ d4;
            long b24 = (e24 << 39) | (e24 >>> 25);
            long e34 = a03 ^ d0;
            long b34 = (e34 << 41) | (e34 >>> 23);
            long e44 = a14 ^ d1;
            long b44 = (e44 << 2) | (e44 >>> 62);
            a00 = b00 ^ (~b10 & b20);
            a10 = b10 ^ (~b20 & b30);
            a20 = b20 ^ (~b30 & b40);
            a30 = b30 ^ (~b40 & b00);
            a40 = b40 ^ (~b00 & b10);
            a01 = b01 ^ (~b11 & b21);
            a11 = b11 ^ (~b21 & b31);
            a21 = b21 ^ (~b31 & b41);
            a31 = b31 ^ (~b41 & b01);
            a41 = b41 ^ (~b01 & b11);
            a02 = b02 ^ (~b12 & b22);
            a12 = b12 ^ (~b22 & b32);
            a22 = b22 ^ (~b32 & b42);
            a32 = b32 ^ (~b42 & b02);
            a42 = b42 ^ (~b02 & b12);
            a03 = b03 ^ (~b13 & b23);
            a13 = b13 ^ (~b23 & b33);
            a23 = b23 ^ (~b33 & b43);
            a33 = b33 ^ (~b43 & b03);
            a43 = b43 ^ (~b03 & b13);
            a04 = b04 ^ (~b14 & b24);
            a14 = b14 ^ (~b24 & b34);
            a24 = b24 ^ (~b34 & b44);
            a34 = b34 ^ (~b44 & b04);
            a44 = b44 ^ (~b04 & b14);
            a00 ^= ((long) rc[2 * round] << 32) | (rc[2 * round + 1] & 0xffffffffL);
        }
        s[0] = a00;
        s[1] = a10;
        s[2] = a20;
        s[3] = a30;
        s[4] = a40;
        s[5] = a01;
        s[6] = a11;
        s[7] = a21;
        s[8] = a31;
        s[9] = a41;
        s[10] = a02;
        s[11] = a12;
        s[12] = a22;
        s[13] = a32;
        s[14] = a42;
        s[15] = a03;
        s[16] = a13;
        s[17] = a23;
        s[18] = a33;
        s[19] = a43;
        s[20] = a04;
        s[21] = a14;
        s[22] = a24;
        s[23] = a34;
        s[24] = a44;
    }

    // The domain's bits and pad10*1 in the block's rest, the block absorbed, the digest
    // squeezed from the lanes; no length field.
    void finish(byte[] out, int offset) {
        for (int i = buffered; i < block; i++) {
            buffer[i] = 0;
        }
        buffer[buffered] = (byte) (digest >= Hashing.SHAKE128 ? 0x1f : 0x06);
        buffer[block - 1] = (byte) (buffer[block - 1] | 0x80);
        compress(buffer, 0, 1);
        output(out, offset);
    }

    void output(byte[] out, int offset) {
        for (int i = 0; i < length; i++) {
            out[offset + i] = (byte) (state[i >>> 3] >>> (8 * (i & 7)));
        }
    }

    public void restore(BlockDigest other) {
        System.arraycopy(((Sha3) other).state, 0, state, 0, 25);
        restoreBuffer(other);
    }

    public BlockDigest fresh() {
        return new Sha3(getAlgorithm(), digest);
    }
}
