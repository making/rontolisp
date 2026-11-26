// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.charset;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

// The encoder of each charset of this library (Charset.kind()), with a JDK 25's answers: a
// surrogate with no partner is malformed, one unit; a character a charset of one byte lacks
// is unmappable, one unit or the two of a pair; a high surrogate that ends the input waits
// for its partner. UTF-16 writes its mark at the start of the first step that has input,
// before it looks at that input, and again after a reset.
final class StandardCharsetEncoder extends CharsetEncoder {

    private final int kind;

    private boolean marked;

    StandardCharsetEncoder(Charset cs, int kind) {
        super(cs, kind == 0 ? 1.1f : kind < 3 ? 1.0f : 2.0f, kind == 0 ? 3.0f : kind < 3 ? 1.0f : kind == 3 ? 4.0f : 2.0f,
                kind < 3 ? new byte[] { '?' } : kind == 5 ? new byte[] { -3, -1 } : new byte[] { -1, -3 }, true);
        this.kind = kind;
    }

    protected CoderResult encodeLoop(CharBuffer in, ByteBuffer out) {
        if (kind == 3 && !marked && in.hasRemaining()) {
            if (out.remaining() < 2) {
                return CoderResult.OVERFLOW;
            }
            out.put((byte) 0xfe).put((byte) 0xff);
            marked = true;
        }
        while (in.hasRemaining()) {
            int at = in.position();
            int c = in.get(at);
            int low = 0;
            if (c >= 0xd800 && c <= 0xdbff) {
                if (at + 1 >= in.limit()) {
                    return CoderResult.UNDERFLOW;
                }
                low = in.get(at + 1);
                if (low < 0xdc00 || low > 0xdfff) {
                    return CoderResult.malformedForLength(1);
                }
            }
            else if (c >= 0xdc00 && c <= 0xdfff) {
                return CoderResult.malformedForLength(1);
            }
            int units = low == 0 ? 1 : 2;
            int cp = low == 0 ? c : ((c - 0xd800) << 10) + (low - 0xdc00) + 0x10000;
            if (kind == 1 && cp > 0xff || kind == 2 && cp > 0x7f) {
                return CoderResult.unmappableForLength(units);
            }
            int n = kind >= 3 ? 2 * units : kind > 0 || cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (out.remaining() < n) {
                return CoderResult.OVERFLOW;
            }
            if (kind >= 3) {
                unit(out, c);
                if (low != 0) {
                    unit(out, low);
                }
            }
            else if (n == 1) {
                out.put((byte) cp);
            }
            else {
                // RFC 3629: the lead byte holds the high bits, each byte after it six more
                out.put((byte) (n == 2 ? 0xc0 | cp >> 6 : n == 3 ? 0xe0 | cp >> 12 : 0xf0 | cp >> 18));
                for (int shift = 6 * (n - 2); shift >= 0; shift -= 6) {
                    out.put((byte) (0x80 | cp >> shift & 0x3f));
                }
            }
            in.position(at + units);
        }
        return CoderResult.UNDERFLOW;
    }

    private void unit(ByteBuffer out, int unit) {
        if (kind == 5) {
            out.put((byte) unit).put((byte) (unit >> 8));
        }
        else {
            out.put((byte) (unit >> 8)).put((byte) unit);
        }
    }

    protected void implReset() {
        marked = false;
    }

    // what a JDK answers whatever the state
    public boolean canEncode(char c) {
        return (c < 0xd800 || c > 0xdfff) && (kind == 1 ? c <= 0xff : kind != 2 || c <= 0x7f);
    }
}
