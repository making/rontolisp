// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// What the digests share: a message is taken a block at a time into a state. Those of
// RFC 1321 and FIPS 180-4 end with the bit 1, the zeros up to the block's last length
// field and the message's length in bits there; MD2 and FIPS 202's sponges end their own
// way. A class under this one has the state, the compression of a block into it, and the
// order of the bytes.
package rontolisp.internal;

import java.security.DigestException;
import java.security.MessageDigest;

public abstract class BlockDigest extends MessageDigest {

    // The digest, by Hashing's number.
    final int digest;

    // The bytes of a block and of the digest.
    final int block;

    final int length;

    // The bytes of the block not yet compressed, how many, and how many bytes in all.
    final byte[] buffer;

    int buffered;

    long count;

    BlockDigest(String algorithm, int digest, int block, int length) {
        super(algorithm);
        this.digest = digest;
        this.block = block;
        this.length = length;
        this.buffer = new byte[block];
    }

    // Compresses BLOCKS blocks of DATA from OFFSET into the state.
    abstract void compress(byte[] data, int offset, int blocks);

    // The state the digest starts from.
    abstract void initial();

    // The message's length in bits written at the end of the buffer's last block: its
    // last eight bytes, the highest first, unless a digest has it another way.
    void lengthField(long bytes) {
        bigEndianLength(bytes);
    }

    // The state's first LENGTH bytes into OUT from OFFSET.
    abstract void output(byte[] out, int offset);

    // The state of another digest of the same class taken as this one's.
    public abstract void restore(BlockDigest other);

    // A digest of the same class and name at its start.
    public abstract BlockDigest fresh();

    protected int engineGetDigestLength() {
        return length;
    }

    protected void engineUpdate(byte input) {
        buffer[buffered] = input;
        buffered++;
        count++;
        if (buffered == block) {
            compress(buffer, 0, 1);
            buffered = 0;
        }
    }

    protected void engineUpdate(byte[] input, int offset, int len) {
        count += len;
        int at = offset;
        int left = len;
        if (buffered > 0) {
            int taken = Math.min(block - buffered, left);
            System.arraycopy(input, at, buffer, buffered, taken);
            buffered += taken;
            at += taken;
            left -= taken;
            if (buffered < block) {
                return;
            }
            compress(buffer, 0, 1);
            buffered = 0;
        }
        int blocks = left / block;
        if (blocks > 0) {
            compress(input, at, blocks);
            at += blocks * block;
            left -= blocks * block;
        }
        if (left > 0) {
            System.arraycopy(input, at, buffer, 0, left);
            buffered = left;
        }
    }

    protected byte[] engineDigest() {
        byte[] out = new byte[length];
        finish(out, 0);
        engineReset();
        return out;
    }

    protected int engineDigest(byte[] buf, int offset, int len) throws DigestException {
        if (len < length) {
            throw new DigestException("Length must be at least " + length + " for " + Hashing.name(digest) + "digests");
        }
        if (offset < 0 || buf.length - offset < length) {
            throw new DigestException("Buffer too short to store digest");
        }
        finish(buf, offset);
        engineReset();
        return length;
    }

    protected void engineReset() {
        initial();
        buffered = 0;
        count = 0;
    }

    // The end of the message compressed and the digest written into OUT from OFFSET; the
    // state is left as it is. A digest of another end has it its own way.
    void finish(byte[] out, int offset) {
        long bytes = count;
        int field = block / 8;
        buffer[buffered] = (byte) 0x80;
        buffered++;
        if (buffered > block - field) {
            for (int i = buffered; i < block; i++) {
                buffer[i] = 0;
            }
            compress(buffer, 0, 1);
            buffered = 0;
        }
        for (int i = buffered; i < block - 8; i++) {
            buffer[i] = 0;
        }
        lengthField(bytes);
        compress(buffer, 0, 1);
        output(out, offset);
    }

    // The bits of a length into the last eight bytes of the buffer, the highest first.
    final void bigEndianLength(long bytes) {
        long bits = bytes << 3;
        for (int i = 0; i < 8; i++) {
            buffer[block - 1 - i] = (byte) (bits >>> (8 * i));
        }
    }

    protected MessageDigest copied() {
        BlockDigest copy = fresh();
        copy.restore(this);
        return copy;
    }

    // What restore() takes of every digest: the buffer and the count.
    final void restoreBuffer(BlockDigest other) {
        if (other.buffered > 0) {
            System.arraycopy(other.buffer, 0, buffer, 0, other.buffered);
        }
        buffered = other.buffered;
        count = other.count;
    }

    // PBKDF2 (RFC 8018, section 5.2) over HMAC (RFC 2104) of this digest's algorithm:
    // LENGTH bytes of the key a password and a salt derive in ITERATIONS rounds. The key's
    // two padded blocks are taken once; each round is then two blocks. This digest is
    // the HMAC's inner state; its state is lost.
    public byte[] derive(byte[] password, byte[] salt, int iterations, int length) {
        byte[] kernel = Hashing.derived(digest, password, salt, iterations, length);
        if (kernel != null) {
            return kernel;
        }
        BlockDigest outer = fresh();
        BlockDigest work = fresh();
        keyed(password, outer);
        BlockDigest inner = this;
        int h = this.length;
        byte[] derived = new byte[length];
        byte[] u = new byte[h];
        byte[] t = new byte[h];
        byte[] index = new byte[4];
        for (int i = 1, at = 0; at < length; i++, at += h) {
            index[0] = (byte) (i >>> 24);
            index[1] = (byte) (i >>> 16);
            index[2] = (byte) (i >>> 8);
            index[3] = (byte) i;
            work.restore(inner);
            work.engineUpdate(salt, 0, salt.length);
            work.engineUpdate(index, 0, 4);
            work.finish(u, 0);
            work.restore(outer);
            work.engineUpdate(u, 0, h);
            work.finish(u, 0);
            System.arraycopy(u, 0, t, 0, h);
            for (int j = 1; j < iterations; j++) {
                work.restore(inner);
                work.engineUpdate(u, 0, h);
                work.finish(u, 0);
                work.restore(outer);
                work.engineUpdate(u, 0, h);
                work.finish(u, 0);
                for (int k = 0; k < h; k++) {
                    t[k] ^= u[k];
                }
            }
            System.arraycopy(t, 0, derived, at, Math.min(h, length - at));
        }
        return derived;
    }

    // HMAC's key taken into this digest, at its start, as the inner pad and into OUTER,
    // at its start, as the outer one: a key longer than a block is its digest.
    public final void keyed(byte[] key, BlockDigest outer) {
        byte[] k = key;
        if (k.length > block) {
            k = new byte[length];
            engineUpdate(key, 0, key.length);
            finish(k, 0);
            engineReset();
        }
        byte[] pad = new byte[block];
        for (int i = 0; i < block; i++) {
            pad[i] = (byte) ((i < k.length ? k[i] : 0) ^ 0x36);
        }
        engineUpdate(pad, 0, block);
        for (int i = 0; i < block; i++) {
            pad[i] = (byte) ((i < k.length ? k[i] : 0) ^ 0x5c);
        }
        outer.engineUpdate(pad, 0, block);
    }

    // The digest of the HMAC whose inner state has taken the message, into OUT from
    // OFFSET: the inner digest through the OUTER state, which WORK is set to.
    public final void hmacFinish(BlockDigest outer, BlockDigest work, byte[] out, int offset) {
        byte[] inner = new byte[length];
        finish(inner, 0);
        work.restore(outer);
        work.engineUpdate(inner, 0, length);
        work.finish(out, offset);
    }

    public final int digestNumber() {
        return digest;
    }
}
