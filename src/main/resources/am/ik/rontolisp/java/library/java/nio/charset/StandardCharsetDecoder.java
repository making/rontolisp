// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.charset;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

// The decoder of each charset of this library (Charset.kind()), with a JDK 25's answers. UTF-8
// counts what Charset.malformed counts of a decoder: a byte that leads nothing, or a lead
// whose second byte cannot follow it, one; the bytes of a sequence before one that is no
// continuation; an encoded surrogate's three as one sequence (two when the third is no
// continuation). A sequence the input ends in before it is whole waits. Which comes first,
// the room or the input, is a JDK's: a byte that leads nothing and the bytes of a lead of
// three or four but the last are looked at before the room, the rest after it. US-ASCII
// refuses a byte above 7F, one, once there is room. UTF-16 reads a unit, a pair waits for its
// second unit; a high surrogate with a unit after it that is no low one is malformed with it
// (four), a low one alone (two), a byte with no partner at the end (one), all before the
// room. UTF-16 looks for a mark in the first two bytes, after a reset again, and reads
// FE FF or FF FE later as characters.
final class StandardCharsetDecoder extends CharsetDecoder {

    private final int kind;

    // UTF-16's order once the start is read: 0 before, 1 big-endian, 2 little-endian
    private int order;

    StandardCharsetDecoder(Charset cs, int kind) {
        super(cs, kind < 3 ? 1.0f : 0.5f, 1.0f);
        this.kind = kind;
    }

    protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
        if (kind >= 3) {
            return units(in, out);
        }
        while (in.hasRemaining()) {
            int at = in.position();
            int c = in.get(at) & 0xff;
            int size = c < 0x80 || kind == 1 ? 1 : kind == 2 ? 0
                    : c >= 0xc2 && c <= 0xdf ? 2 : c >= 0xe0 && c <= 0xef ? 3 : c >= 0xf0 && c <= 0xf4 ? 4 : 0;
            if (size == 0 && kind == 0) {
                return CoderResult.malformedForLength(1);
            }
            // the bytes looked at before the room: a lead of three its second, of four its
            // second and third
            int early = size < 3 ? 1 : size - 1;
            int left = in.limit() - at;
            int bad = malformed(in, at, c, 1, Math.min(left, early));
            if (bad > 0) {
                return CoderResult.malformedForLength(bad);
            }
            if (left < size) {
                return CoderResult.UNDERFLOW;
            }
            if (out.remaining() < (size == 4 ? 2 : 1)) {
                return CoderResult.OVERFLOW;
            }
            if (size == 0) {
                return CoderResult.malformedForLength(1);
            }
            bad = malformed(in, at, c, early, size);
            if (bad > 0) {
                return CoderResult.malformedForLength(bad);
            }
            int cp = size == 1 ? c : c & (0x7f >> size);
            for (int k = 1; k < size; k++) {
                cp = cp << 6 | in.get(at + k) & 0x3f;
            }
            if (cp >= 0x10000) {
                out.put((char) (0xd7c0 + (cp >> 10))).put((char) (0xdc00 | cp & 0x3ff));
            }
            else {
                out.put((char) cp);
            }
            in.position(at + size);
        }
        return CoderResult.UNDERFLOW;
    }

    // How many bytes of the UTF-8 sequence at `at` are malformed, looking at its bytes from
    // `from` to `to`, or 0. The second byte's range is the lead's (E0 from A0, F0 from 90, F4 to
    // 8F); ED's to 9F is looked at with the third byte, after the room: an encoded surrogate's
    // three bytes are one sequence, two when the third is no continuation.
    private static int malformed(ByteBuffer in, int at, int c, int from, int to) {
        for (int k = from; k < to; k++) {
            int b = in.get(at + k) & 0xff;
            if (k == 1) {
                if (b < (c == 0xe0 ? 0xa0 : c == 0xf0 ? 0x90 : 0x80) || b > (c == 0xf4 ? 0x8f : 0xbf)) {
                    return 1;
                }
            }
            else if (k == 2 && c == 0xed && (in.get(at + 1) & 0xff) >= 0xa0) {
                return (b & 0xc0) == 0x80 ? 3 : 2;
            }
            else if ((b & 0xc0) != 0x80) {
                return k;
            }
        }
        return 0;
    }

    private CoderResult units(ByteBuffer in, CharBuffer out) {
        if (order == 0) {
            if (kind == 3) {
                if (in.remaining() < 2) {
                    return CoderResult.UNDERFLOW;
                }
                int mark = unit(in, in.position(), false);
                if (mark == 0xfeff || mark == 0xfffe) {
                    in.position(in.position() + 2);
                }
                order = mark == 0xfffe ? 2 : 1;
            }
            else {
                order = kind == 5 ? 2 : 1;
            }
        }
        boolean little = order == 2;
        while (in.hasRemaining()) {
            int at = in.position();
            int left = in.limit() - at;
            if (left < 2) {
                return CoderResult.UNDERFLOW;
            }
            int c = unit(in, at, little);
            int d = 0;
            if (c >= 0xd800 && c <= 0xdbff) {
                if (left < 4) {
                    return CoderResult.UNDERFLOW;
                }
                d = unit(in, at + 2, little);
                if (d < 0xdc00 || d > 0xdfff) {
                    return CoderResult.malformedForLength(4);
                }
            }
            else if (c >= 0xdc00 && c <= 0xdfff) {
                return CoderResult.malformedForLength(2);
            }
            if (out.remaining() < (d == 0 ? 1 : 2)) {
                return CoderResult.OVERFLOW;
            }
            out.put((char) c);
            if (d != 0) {
                out.put((char) d);
            }
            in.position(at + (d == 0 ? 2 : 4));
        }
        return CoderResult.UNDERFLOW;
    }

    private static int unit(ByteBuffer in, int at, boolean little) {
        int first = in.get(at) & 0xff;
        int second = in.get(at + 1) & 0xff;
        return little ? second << 8 | first : first << 8 | second;
    }

    protected void implReset() {
        order = 0;
    }
}
