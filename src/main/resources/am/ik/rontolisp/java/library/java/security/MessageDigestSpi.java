// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security;

public abstract class MessageDigestSpi {

    public MessageDigestSpi() {
    }

    protected int engineGetDigestLength() {
        return 0;
    }

    protected abstract void engineUpdate(byte input);

    protected abstract void engineUpdate(byte[] input, int offset, int len);

    protected abstract byte[] engineDigest();

    // A digest of the program's own written into BUF: the digest is taken first, then
    // refused where LEN cannot hold it.
    protected int engineDigest(byte[] buf, int offset, int len) throws DigestException {
        byte[] digest = engineDigest();
        if (len < digest.length) {
            throw new DigestException("partial digests not returned");
        }
        System.arraycopy(digest, 0, buf, offset, digest.length);
        return digest.length;
    }

    protected abstract void engineReset();

    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }
}
