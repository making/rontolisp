/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.http.server;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpOutputMessage;

/**
 * A servlet response as the message a converter writes. Its header fields start with the
 * type and the length the response has and are handed to the response when the body is
 * asked for, or when the message is closed; a flush of the body is left to the container,
 * which then sends a body that fits its buffer with its length.
 */
public class ServletServerHttpResponse implements HttpOutputMessage {

	private final HttpServletResponse servletResponse;

	private final HttpHeaders headers = new HttpHeaders();

	private boolean headersWritten;

	public ServletServerHttpResponse(HttpServletResponse servletResponse) {
		this.servletResponse = servletResponse;
		if (servletResponse.getContentType() != null) {
			this.headers.set(HttpHeaders.CONTENT_TYPE, servletResponse.getContentType());
		}
		String length = servletResponse.getHeader(HttpHeaders.CONTENT_LENGTH);
		if (length != null) {
			this.headers.set(HttpHeaders.CONTENT_LENGTH, length);
		}
	}

	public HttpServletResponse getServletResponse() {
		return this.servletResponse;
	}

	@Override
	public HttpHeaders getHeaders() {
		return this.headersWritten ? HttpHeaders.readOnlyHttpHeaders(this.headers) : this.headers;
	}

	@Override
	public OutputStream getBody() throws IOException {
		writeHeaders();
		return new Body(this.servletResponse.getOutputStream());
	}

	/** Hands the fields to the response, if the body has not done so. */
	public void close() {
		writeHeaders();
	}

	private void writeHeaders() {
		if (!this.headersWritten) {
			this.headersWritten = true;
			for (String name : this.headers.headerNames()) {
				List<String> values = this.headers.getOrEmpty(name);
				for (int i = 0; i < values.size(); i++) {
					if (i == 0) {
						this.servletResponse.setHeader(name, values.get(i));
					}
					else {
						this.servletResponse.addHeader(name, values.get(i));
					}
				}
			}
		}
	}

	/** The response's stream, a flush of which goes no further. */
	private static final class Body extends OutputStream {

		private final OutputStream out;

		Body(OutputStream out) {
			this.out = out;
		}

		@Override
		public void write(int b) throws IOException {
			this.out.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			this.out.write(b, off, len);
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}

	}

}
