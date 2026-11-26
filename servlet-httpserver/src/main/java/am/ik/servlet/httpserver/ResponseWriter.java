package am.ik.servlet.httpserver;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * The characters of a response, encoded straight into the body's buffer: nothing is held
 * here but the first half of a surrogate pair whose second half is not written yet, so
 * the buffer of the response is the only one there is to reset or to overflow.
 */
final class ResponseWriter extends Writer {

	private final ResponseBody body;

	private final Charset charset;

	private char pending;

	ResponseWriter(ResponseBody body, Charset charset) {
		this.body = body;
		this.charset = charset;
	}

	void clear() {
		this.pending = 0;
	}

	@Override
	public void write(char[] cbuf, int off, int len) throws IOException {
		if (len > 0) {
			encode(new String(cbuf, off, len));
		}
	}

	@Override
	public void write(String str, int off, int len) throws IOException {
		if (len > 0) {
			encode(off == 0 && len == str.length() ? str : str.substring(off, off + len));
		}
	}

	private void encode(String text) throws IOException {
		if (this.pending != 0) {
			text = this.pending + text;
			this.pending = 0;
		}
		int last = text.length() - 1;
		if (Character.isHighSurrogate(text.charAt(last))) {
			this.pending = text.charAt(last);
			text = text.substring(0, last);
		}
		if (!text.isEmpty()) {
			byte[] octets = text.getBytes(this.charset);
			this.body.write(octets, 0, octets.length);
		}
	}

	@Override
	public void flush() throws IOException {
		this.body.flush();
	}

	@Override
	public void close() throws IOException {
		this.body.close();
	}

}
