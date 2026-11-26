// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour, RFC 4648 and RFC 2045; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

// RFC 4648's two alphabets (section 4, and section 5's URL and filename safe one) and RFC
// 2045's lines. What the specification leaves open was learned from a JDK's answers to
// every text of a short alphabet up to seven characters and to longer ones drawn at random
// (.kb/java-frontend.md, "java.util.Base64"): the size a decoder asks of the array it
// decodes into, which refusal comes first and its words, where a MIME decoder skips.
public class Base64 {

    private static final String DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private Base64() {
    }

    public static Encoder getEncoder() {
        return Encoder.RFC4648;
    }

    public static Encoder getUrlEncoder() {
        return Encoder.URL;
    }

    public static Encoder getMimeEncoder() {
        return Encoder.MIME;
    }

    // A line of a length that is no multiple of four is cut to one; a line shorter than
    // four characters is no line at all. The separator is the caller's array, not a copy.
    public static Encoder getMimeEncoder(int lineLength, byte[] lineSeparator) {
        Objects.requireNonNull(lineSeparator);
        for (byte b : lineSeparator) {
            if (Decoder.value(b & 0xff, false) != Decoder.NONE) {
                throw new IllegalArgumentException(
                        "Illegal base64 line separator character 0x" + Integer.toString(b & 0xff, 16));
            }
        }
        int lineMax = lineLength / 4 * 4;
        if (lineMax <= 0) {
            return Encoder.RFC4648;
        }
        return new Encoder(false, lineMax, lineSeparator, true);
    }

    public static Decoder getDecoder() {
        return Decoder.RFC4648;
    }

    public static Decoder getUrlDecoder() {
        return Decoder.URL;
    }

    public static Decoder getMimeDecoder() {
        return Decoder.MIME;
    }

    public static class Encoder {

        private static final Encoder RFC4648 = new Encoder(false, 0, null, true);

        private static final Encoder URL = new Encoder(true, 0, null, true);

        private static final Encoder MIME = new Encoder(false, 76, new byte[] { '\r', '\n' }, true);

        private final boolean url;

        // Characters a line holds, a multiple of four; 0 when the text is one line.
        private final int lineMax;

        private final byte[] separator;

        private final boolean padding;

        private Encoder(boolean url, int lineMax, byte[] separator, boolean padding) {
            this.url = url;
            this.lineMax = lineMax;
            this.separator = separator;
            this.padding = padding;
        }

        public byte[] encode(byte[] src) {
            byte[] dst = new byte[length(src.length)];
            put(src, dst);
            return dst;
        }

        public int encode(byte[] src, byte[] dst) {
            if (dst.length < length(src.length)) {
                throw new IllegalArgumentException("Output byte array is too small for encoding all input bytes");
            }
            return put(src, dst);
        }

        // the bytes from the position to the limit, which it moves to the limit
        public ByteBuffer encode(ByteBuffer buffer) {
            byte[] src = new byte[buffer.remaining()];
            buffer.get(src);
            return ByteBuffer.wrap(encode(src));
        }

        public String encodeToString(byte[] src) {
            return new String(encode(src), StandardCharsets.ISO_8859_1);
        }

        public Encoder withoutPadding() {
            return padding ? new Encoder(url, lineMax, separator, false) : this;
        }

        public OutputStream wrap(OutputStream os) {
            Objects.requireNonNull(os);
            return new Encoding(this, os);
        }

        private int length(int bytes) {
            int chars = padding ? (bytes + 2) / 3 * 4 : bytes / 3 * 4 + (bytes % 3 == 0 ? 0 : bytes % 3 + 1);
            if (lineMax > 0 && chars > 0) {
                chars += (chars - 1) / lineMax * separator.length;
            }
            return chars;
        }

        private int put(byte[] src, byte[] dst) {
            int at = 0;
            int column = 0;
            for (int i = 0; i < src.length; i += 3) {
                if (column == lineMax && lineMax > 0) {
                    System.arraycopy(separator, 0, dst, at, separator.length);
                    at += separator.length;
                    column = 0;
                }
                at = quantum(src, i, Math.min(3, src.length - i), dst, at);
                column += 4;
            }
            return at;
        }

        // The characters of `count` bytes (one to three) from src[from], at dst[at]; a
        // quantum of fewer than three bytes is padded when this encoder pads.
        int quantum(byte[] src, int from, int count, byte[] dst, int at) {
            int bits = (src[from] & 0xff) << 16;
            if (count > 1) {
                bits |= (src[from + 1] & 0xff) << 8;
            }
            if (count > 2) {
                bits |= src[from + 2] & 0xff;
            }
            for (int k = 0; k <= count; k++) {
                dst[at++] = digit((bits >> (18 - 6 * k)) & 63);
            }
            if (padding) {
                for (int k = count; k < 3; k++) {
                    dst[at++] = '=';
                }
            }
            return at;
        }

        private byte digit(int v) {
            if (url && v >= 62) {
                return (byte) (v == 62 ? '-' : '_');
            }
            return (byte) DIGITS.charAt(v);
        }

    }

    // A stream that writes the text of what it is handed: a quantum when it has three
    // bytes, the bytes short of one at its close. What reaches the stream under it and in
    // which calls is a JDK's answer: a quantum completed by a write, then the rest of the
    // write's whole quanta a line at a time, a separator a call of its own before the line
    // it starts, and at the close a character a call.
    private static final class Encoding extends FilterOutputStream {

        private final Encoder encoder;

        private final byte[] held = new byte[3];

        private int holding;

        private int column;

        private boolean closed;

        Encoding(Encoder encoder, OutputStream out) {
            super(out);
            this.encoder = encoder;
        }

        public void write(int b) throws IOException {
            write(new byte[] { (byte) b }, 0, 1);
        }

        public void write(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("Stream is closed");
            }
            if (off < 0 || len < 0 || off > b.length - len) {
                throw new ArrayIndexOutOfBoundsException(
                        "Range [" + off + ", " + off + " + " + len + ") out of bounds for length " + b.length);
            }
            int end = off + len;
            if (holding > 0) {
                while (holding < 3 && off < end) {
                    held[holding++] = b[off++];
                }
                if (holding < 3) {
                    return;
                }
                emit(held, 0, 1);
                holding = 0;
            }
            int quanta = (end - off) / 3;
            while (quanta > 0) {
                int n = quanta;
                if (encoder.lineMax > 0) {
                    n = Math.min(n, (encoder.lineMax - (column == encoder.lineMax ? 0 : column)) / 4);
                }
                emit(b, off, n);
                off += 3 * n;
                quanta -= n;
            }
            while (off < end) {
                held[holding++] = b[off++];
            }
        }

        // `quanta` whole quanta from b[off], on the current line.
        private void emit(byte[] b, int off, int quanta) throws IOException {
            separate();
            byte[] chars = new byte[4 * quanta];
            for (int q = 0; q < quanta; q++) {
                encoder.quantum(b, off + 3 * q, 3, chars, 4 * q);
            }
            out.write(chars);
            column += chars.length;
        }

        private void separate() throws IOException {
            if (encoder.lineMax > 0 && column == encoder.lineMax) {
                out.write(encoder.separator);
                column = 0;
            }
        }

        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            if (holding > 0) {
                separate();
                byte[] chars = new byte[4];
                int n = encoder.quantum(held, 0, holding, chars, 0);
                for (int i = 0; i < n; i++) {
                    out.write(chars[i]);
                }
            }
            out.close();
        }

    }

    public static class Decoder {

        private static final Decoder RFC4648 = new Decoder(false, false);

        private static final Decoder URL = new Decoder(true, false);

        private static final Decoder MIME = new Decoder(false, true);

        static final int NONE = -1;

        static final int PAD = -2;

        private final boolean url;

        // A MIME decoder skips what is no character of its alphabet.
        private final boolean mime;

        private Decoder(boolean url, boolean mime) {
            this.url = url;
            this.mime = mime;
        }

        // The value of a character of RFC 4648's table, PAD for '=' and NONE for any other.
        static int value(int b, boolean url) {
            if (b >= 'A' && b <= 'Z') {
                return b - 'A';
            }
            if (b >= 'a' && b <= 'z') {
                return b - 'a' + 26;
            }
            if (b >= '0' && b <= '9') {
                return b - '0' + 52;
            }
            if (b == (url ? '-' : '+')) {
                return 62;
            }
            if (b == (url ? '_' : '/')) {
                return 63;
            }
            return b == '=' ? PAD : NONE;
        }

        public byte[] decode(byte[] src) {
            byte[] dst = new byte[length(src)];
            int n = take(src, dst);
            return n == dst.length ? dst : Arrays.copyOf(dst, n);
        }

        // The bytes from the position to the limit, which it moves to the limit; a refusal
        // leaves it. What follows the padding is refused at an index of the buffer's array
        // where it has one, and of the bytes from the position where it has none (a JDK's
        // answers): the bytes are decoded from a copy and the index of that refusal moved.
        public ByteBuffer decode(ByteBuffer buffer) {
            int at = buffer.position();
            byte[] src = new byte[buffer.remaining()];
            buffer.get(at, src);
            byte[] decoded;
            try {
                decoded = decode(src);
            }
            catch (IllegalArgumentException e) {
                String words = "Input byte array has incorrect ending byte at ";
                String message = e.getMessage();
                if (buffer.hasArray() && message.startsWith(words)) {
                    int index = Integer.parseInt(message.substring(words.length()));
                    throw new IllegalArgumentException(words + (index + buffer.arrayOffset() + at));
                }
                throw e;
            }
            buffer.position(buffer.limit());
            return ByteBuffer.wrap(decoded);
        }

        // A character past ISO 8859-1 is the '?' that charset's encoder writes for it.
        public byte[] decode(String src) {
            return decode(src.getBytes(StandardCharsets.ISO_8859_1));
        }

        public int decode(byte[] src, byte[] dst) {
            if (dst.length < length(src)) {
                throw new IllegalArgumentException("Output byte array is too small for decoding all input bytes");
            }
            return take(src, dst);
        }

        public InputStream wrap(InputStream is) {
            Objects.requireNonNull(is);
            return new Decoding(is, url, mime);
        }

        // The bytes the text's characters would make, asked before it is read (a JDK's
        // answers): the basic decoders read the padding off the last two characters and
        // refuse a text of one; a MIME decoder counts the characters of its alphabet
        // before the first '='.
        private int length(byte[] src) {
            int n = src.length;
            int pads = 0;
            if (mime) {
                if (n < 2) {
                    return 0;
                }
                int chars = 0;
                for (int i = 0; i < n; i++) {
                    int v = value(src[i] & 0xff, false);
                    if (v == PAD) {
                        break;
                    }
                    if (v != NONE) {
                        chars++;
                    }
                }
                n = chars;
            }
            else if (n == 1) {
                throw new IllegalArgumentException("Input byte[] should at least have 2 bytes for base64 bytes");
            }
            else if (n > 1 && src[n - 1] == '=') {
                pads = src[n - 2] == '=' ? 2 : 1;
            }
            if (n == 0) {
                return 0;
            }
            if (pads == 0 && n % 4 != 0) {
                pads = 4 - n % 4;
            }
            return 3 * ((n + 3) / 4) - pads;
        }

        // Reads the text into dst and answers the bytes it made. The padding ends the
        // data: two '=' after two characters of a quantum, one after three, and after it
        // nothing (a MIME decoder: nothing of its alphabet).
        private int take(byte[] src, byte[] dst) {
            int at = 0;
            int bits = 0;
            int chars = 0;
            int i = 0;
            int n = src.length;
            while (i < n) {
                int b = src[i++];
                int v = value(b & 0xff, url);
                if (v >= 0) {
                    bits = bits << 6 | v;
                    if (++chars == 4) {
                        dst[at++] = (byte) (bits >> 16);
                        dst[at++] = (byte) (bits >> 8);
                        dst[at++] = (byte) bits;
                        bits = 0;
                        chars = 0;
                    }
                }
                else if (v == PAD) {
                    if (chars == 0 || chars == 2 && (i == n || src[i++] != '=')) {
                        throw new IllegalArgumentException("Input byte array has wrong 4-byte ending unit");
                    }
                    break;
                }
                else if (!mime) {
                    throw new IllegalArgumentException("Illegal base64 character " + Integer.toString(b, 16));
                }
            }
            if (chars == 1) {
                throw new IllegalArgumentException("Last unit does not have enough valid bits");
            }
            if (chars == 2) {
                dst[at++] = (byte) (bits >> 4);
            }
            else if (chars == 3) {
                dst[at++] = (byte) (bits >> 10);
                dst[at++] = (byte) (bits >> 2);
            }
            // Only the padding stops the loop short of the end. A MIME decoder names the
            // position after the character it refuses, the others the character's own.
            while (i < n) {
                if (!mime || value(src[i++] & 0xff, url) >= 0) {
                    throw new IllegalArgumentException("Input byte array has incorrect ending byte at " + i);
                }
            }
            return at;
        }

    }

    // A stream of the bytes of the text it reads, a character at a time and no further than
    // a read needs. Its rules are a JDK's answers and differ from the arrays': the padding
    // ends the stream whatever follows it, a refusal is an IOException in words of its own,
    // and a refused read loses the bytes it had and the quantum under way, the stream
    // reading on after the character it refused.
    private static final class Decoding extends InputStream {

        private final InputStream in;

        private final boolean url;

        private final boolean mime;

        private int bits;

        private int chars;

        // The bytes of the last quantum a read has not handed out: the low `ready` bytes
        // of `made`, the highest first.
        private int made;

        private int ready;

        private boolean ended;

        private boolean closed;

        Decoding(InputStream in, boolean url, boolean mime) {
            this.in = in;
            this.url = url;
            this.mime = mime;
        }

        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("Stream is closed");
            }
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            int n = 0;
            try {
                while (n < len) {
                    if (ready > 0) {
                        ready--;
                        b[off + n++] = (byte) (made >> (8 * ready));
                        continue;
                    }
                    if (ended) {
                        break;
                    }
                    int c = in.read();
                    if (c < 0) {
                        ended = true;
                        if (chars == 1) {
                            throw new IOException("Base64 stream has one un-decoded dangling byte.");
                        }
                        finish();
                        continue;
                    }
                    int v = Decoder.value(c, url);
                    if (v >= 0) {
                        bits = bits << 6 | v;
                        if (++chars == 4) {
                            hold(3, bits);
                        }
                    }
                    else if (v == Decoder.PAD) {
                        if (chars < 2 || chars == 2 && in.read() != '=') {
                            throw new IOException("Illegal base64 ending sequence:" + (24 - 6 * chars));
                        }
                        ended = true;
                        finish();
                    }
                    else if (!mime) {
                        throw new IOException("Illegal base64 character 0x" + Integer.toString(c, 16));
                    }
                }
            }
            catch (IOException e) {
                bits = 0;
                chars = 0;
                ready = 0;
                throw e;
            }
            return n == 0 ? -1 : n;
        }

        private void finish() {
            if (chars == 2) {
                hold(1, bits >> 4);
            }
            else if (chars == 3) {
                hold(2, bits >> 2);
            }
        }

        private void hold(int count, int value) {
            made = value;
            ready = count;
            bits = 0;
            chars = 0;
        }

        public int available() throws IOException {
            if (closed) {
                throw new IOException("Stream is closed");
            }
            return in.available();
        }

        public void close() throws IOException {
            if (!closed) {
                closed = true;
                in.close();
            }
        }

    }

}
