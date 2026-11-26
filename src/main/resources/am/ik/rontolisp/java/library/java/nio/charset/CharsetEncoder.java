// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.charset;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

// The state an encoding goes through (reset, coding, at the end of its input, flushed), the
// actions on malformed and unmappable input and the replacement, around the loop a subclass
// writes. Read off a JDK's answers: the words of each refusal and of an illegal change of
// state, that canEncode of a text resets a flushed encoder and refuses one that is coding,
// what encode of a whole buffer allocates (the characters times averageBytesPerChar, then
// twice that and one more), and the hooks each member calls.
public abstract class CharsetEncoder {

    private static final String[] STATES = { "RESET", "CODING", "CODING_END", "FLUSHED" };

    private static final int RESET = 0;

    private static final int CODING = 1;

    private static final int END = 2;

    private static final int FLUSHED = 3;

    private final Charset charset;

    private final float averageBytesPerChar;

    private final float maxBytesPerChar;

    private byte[] replacement;

    private CodingErrorAction malformedInputAction = CodingErrorAction.REPORT;

    private CodingErrorAction unmappableCharacterAction = CodingErrorAction.REPORT;

    private int state = RESET;

    protected CharsetEncoder(Charset cs, float averageBytesPerChar, float maxBytesPerChar, byte[] replacement) {
        this(cs, averageBytesPerChar, maxBytesPerChar, replacement, false);
        replaceWith(replacement);
    }

    protected CharsetEncoder(Charset cs, float averageBytesPerChar, float maxBytesPerChar) {
        this(cs, averageBytesPerChar, maxBytesPerChar, new byte[] { '?' });
    }

    // Takes the replacement as it is: for the encoders of this library, whose replacements are
    // legal, so that no decoder is made to check them.
    CharsetEncoder(Charset cs, float averageBytesPerChar, float maxBytesPerChar, byte[] replacement, boolean legal) {
        if (!(averageBytesPerChar > 0)) {
            throw new IllegalArgumentException("Non-positive averageBytesPerChar");
        }
        if (!(maxBytesPerChar > 0)) {
            throw new IllegalArgumentException("Non-positive maxBytesPerChar");
        }
        if (averageBytesPerChar > maxBytesPerChar) {
            throw new IllegalArgumentException("averageBytesPerChar exceeds maxBytesPerChar");
        }
        this.charset = cs;
        this.averageBytesPerChar = averageBytesPerChar;
        this.maxBytesPerChar = maxBytesPerChar;
        this.replacement = replacement;
    }

    public final Charset charset() {
        return charset;
    }

    public final byte[] replacement() {
        return replacement.clone();
    }

    public final CharsetEncoder replaceWith(byte[] newReplacement) {
        if (newReplacement == null) {
            throw new IllegalArgumentException("Null replacement");
        }
        int n = newReplacement.length;
        if (n == 0) {
            throw new IllegalArgumentException("Empty replacement");
        }
        if (n > maxBytesPerChar) {
            throw new IllegalArgumentException("Replacement too long");
        }
        if (!isLegalReplacement(newReplacement)) {
            throw new IllegalArgumentException("Illegal replacement");
        }
        replacement = newReplacement.clone();
        implReplaceWith(replacement);
        return this;
    }

    protected void implReplaceWith(byte[] newReplacement) {
    }

    // Whether the bytes decode in this charset with no error.
    public boolean isLegalReplacement(byte[] repl) {
        CharsetDecoder decoder = charset.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer out = CharBuffer.allocate((int) (repl.length * decoder.maxCharsPerByte()));
        return !decoder.decode(ByteBuffer.wrap(repl), out, true).isError();
    }

    public CodingErrorAction malformedInputAction() {
        return malformedInputAction;
    }

    public final CharsetEncoder onMalformedInput(CodingErrorAction newAction) {
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

    public final CharsetEncoder onUnmappableCharacter(CodingErrorAction newAction) {
        if (newAction == null) {
            throw new IllegalArgumentException("Null action");
        }
        unmappableCharacterAction = newAction;
        implOnUnmappableCharacter(newAction);
        return this;
    }

    protected void implOnUnmappableCharacter(CodingErrorAction newAction) {
    }

    public final float averageBytesPerChar() {
        return averageBytesPerChar;
    }

    public final float maxBytesPerChar() {
        return maxBytesPerChar;
    }

    // throws rather than answers the refusal: a class whose members name a class of the
    // library makes that class known when it is, and this one is known with Charset
    private void refuse(int to) {
        throw new IllegalStateException("Current state = " + STATES[state] + ", new state = " + STATES[to]);
    }

    // Runs the loop until it underflows, overflows or meets an error the action reports; the
    // end of the input makes what is left of it malformed.
    public final CoderResult encode(CharBuffer in, ByteBuffer out, boolean endOfInput) {
        int to = endOfInput ? END : CODING;
        if (state != RESET && state != CODING && !(endOfInput && state == END)) {
            refuse(to);
        }
        state = to;
        while (true) {
            CoderResult cr;
            try {
                cr = encodeLoop(in, out);
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
                if (out.remaining() < replacement.length) {
                    return CoderResult.OVERFLOW;
                }
                out.put(replacement);
            }
            in.position(in.position() + cr.length());
        }
    }

    public final CoderResult flush(ByteBuffer out) {
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

    protected CoderResult implFlush(ByteBuffer out) {
        return CoderResult.UNDERFLOW;
    }

    public final CharsetEncoder reset() {
        implReset();
        state = RESET;
        return this;
    }

    protected void implReset() {
    }

    protected abstract CoderResult encodeLoop(CharBuffer in, ByteBuffer out);

    // The bytes of the characters from the position to the limit, in a buffer that grows to
    // hold them; an error the action reports is thrown.
    public final ByteBuffer encode(CharBuffer in) throws CharacterCodingException {
        int n = (int) (in.remaining() * averageBytesPerChar);
        ByteBuffer out = ByteBuffer.allocate(n);
        if (n == 0 && in.remaining() == 0) {
            return out;
        }
        reset();
        while (true) {
            CoderResult cr = encode(in, out, true);
            if (cr.isUnderflow()) {
                cr = flush(out);
            }
            if (cr.isUnderflow()) {
                break;
            }
            if (cr.isOverflow()) {
                n = 2 * n + 1;
                ByteBuffer grown = ByteBuffer.allocate(n);
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

    public boolean canEncode(char c) {
        return canEncode(CharBuffer.wrap(new char[] { c }));
    }

    public boolean canEncode(CharSequence cs) {
        if (state == FLUSHED) {
            reset();
        }
        else if (state != RESET) {
            refuse(CODING);
        }
        CodingErrorAction malformed = malformedInputAction;
        CodingErrorAction unmappable = unmappableCharacterAction;
        boolean can = true;
        try {
            onMalformedInput(CodingErrorAction.REPORT);
            onUnmappableCharacter(CodingErrorAction.REPORT);
            encode(CharBuffer.wrap(cs));
        }
        catch (CharacterCodingException e) {
            can = false;
        }
        finally {
            onMalformedInput(malformed);
            onUnmappableCharacter(unmappable);
            reset();
        }
        return can;
    }
}
