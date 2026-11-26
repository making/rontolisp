// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.charset;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

// CharsetEncoder's states, actions and replacement the other way round (CharsetEncoder.java):
// the replacement is a text, any text no longer than maxCharsPerByte, and no decoder here
// detects its charset.
public abstract class CharsetDecoder {

    private static final String[] STATES = { "RESET", "CODING", "CODING_END", "FLUSHED" };

    private static final int RESET = 0;

    private static final int CODING = 1;

    private static final int END = 2;

    private static final int FLUSHED = 3;

    private final Charset charset;

    private final float averageCharsPerByte;

    private final float maxCharsPerByte;

    private String replacement;

    private CodingErrorAction malformedInputAction = CodingErrorAction.REPORT;

    private CodingErrorAction unmappableCharacterAction = CodingErrorAction.REPORT;

    private int state = RESET;

    protected CharsetDecoder(Charset cs, float averageCharsPerByte, float maxCharsPerByte) {
        if (!(averageCharsPerByte > 0)) {
            throw new IllegalArgumentException("Non-positive averageCharsPerByte");
        }
        if (!(maxCharsPerByte > 0)) {
            throw new IllegalArgumentException("Non-positive maxCharsPerByte");
        }
        if (averageCharsPerByte > maxCharsPerByte) {
            throw new IllegalArgumentException("averageCharsPerByte exceeds maxCharsPerByte");
        }
        this.charset = cs;
        this.averageCharsPerByte = averageCharsPerByte;
        this.maxCharsPerByte = maxCharsPerByte;
        replaceWith("\ufffd");
    }

    public final Charset charset() {
        return charset;
    }

    public final String replacement() {
        return replacement;
    }

    public final CharsetDecoder replaceWith(String newReplacement) {
        if (newReplacement == null) {
            throw new IllegalArgumentException("Null replacement");
        }
        int n = newReplacement.toCharArray().length;
        if (n == 0) {
            throw new IllegalArgumentException("Empty replacement");
        }
        if (n > maxCharsPerByte) {
            throw new IllegalArgumentException("Replacement too long");
        }
        replacement = newReplacement;
        implReplaceWith(newReplacement);
        return this;
    }

    protected void implReplaceWith(String newReplacement) {
    }

    public CodingErrorAction malformedInputAction() {
        return malformedInputAction;
    }

    public final CharsetDecoder onMalformedInput(CodingErrorAction newAction) {
        if (newAction == null) {
            throw new IllegalArgumentException("Null action");
        }
        malformedInputAction = newAction;
        implOnMalformedInput(newAction);
        return this;
    }

    protected void implOnMalformedInput(CodingErrorAction newAction) {
    }

    public CodingErrorAction unmappableCharacterAction() {
        return unmappableCharacterAction;
    }

    public final CharsetDecoder onUnmappableCharacter(CodingErrorAction newAction) {
        if (newAction == null) {
            throw new IllegalArgumentException("Null action");
        }
        unmappableCharacterAction = newAction;
        implOnUnmappableCharacter(newAction);
        return this;
    }

    protected void implOnUnmappableCharacter(CodingErrorAction newAction) {
    }

    public final float averageCharsPerByte() {
        return averageCharsPerByte;
    }

    public final float maxCharsPerByte() {
        return maxCharsPerByte;
    }

    // throws rather than answers the refusal: a class whose members name a class of the
    // library makes that class known when it is, and this one is known with Charset
    private void refuse(int to) {
        throw new IllegalStateException("Current state = " + STATES[state] + ", new state = " + STATES[to]);
    }

    public final CoderResult decode(ByteBuffer in, CharBuffer out, boolean endOfInput) {
        int to = endOfInput ? END : CODING;
        if (state != RESET && state != CODING && !(endOfInput && state == END)) {
            refuse(to);
        }
        state = to;
        while (true) {
            CoderResult cr;
            try {
                cr = decodeLoop(in, out);
            }
            catch (RuntimeException e) {
                throw new CoderMalfunctionError(e);
            }
            if (cr.isOverflow()) {
                return cr;
            }
            if (cr.isUnderflow()) {
                if (!endOfInput || !in.hasRemaining()) {
                    return cr;
                }
                cr = CoderResult.malformedForLength(in.remaining());
            }
            CodingErrorAction action = cr.isMalformed() ? malformedInputAction : unmappableCharacterAction;
            if (action == CodingErrorAction.REPORT) {
                return cr;
            }
            if (action == CodingErrorAction.REPLACE) {
                if (out.remaining() < replacement.toCharArray().length) {
                    return CoderResult.OVERFLOW;
                }
                out.put(replacement);
            }
            in.position(in.position() + cr.length());
        }
    }

    public final CoderResult flush(CharBuffer out) {
        if (state == END) {
            CoderResult cr = implFlush(out);
            if (cr.isUnderflow()) {
                state = FLUSHED;
            }
            return cr;
        }
        if (state != FLUSHED) {
            refuse(FLUSHED);
        }
        return CoderResult.UNDERFLOW;
    }

    protected CoderResult implFlush(CharBuffer out) {
        return CoderResult.UNDERFLOW;
    }

    public final CharsetDecoder reset() {
        implReset();
        state = RESET;
        return this;
    }

    protected void implReset() {
    }

    protected abstract CoderResult decodeLoop(ByteBuffer in, CharBuffer out);

    public final CharBuffer decode(ByteBuffer in) throws CharacterCodingException {
        int n = (int) (in.remaining() * averageCharsPerByte);
        CharBuffer out = CharBuffer.allocate(n);
        if (n == 0 && in.remaining() == 0) {
            return out;
        }
        reset();
        while (true) {
            CoderResult cr = decode(in, out, true);
            if (cr.isUnderflow()) {
                cr = flush(out);
            }
            if (cr.isUnderflow()) {
                break;
            }
            if (cr.isOverflow()) {
                n = 2 * n + 1;
                CharBuffer grown = CharBuffer.allocate(n);
                out.flip();
                grown.put(out);
                out = grown;
                continue;
            }
            cr.throwException();
        }
        out.flip();
        return out;
    }

    public boolean isAutoDetecting() {
        return false;
    }

    public boolean isCharsetDetected() {
        throw new UnsupportedOperationException();
    }

    public Charset detectedCharset() {
        throw new UnsupportedOperationException();
    }
}
