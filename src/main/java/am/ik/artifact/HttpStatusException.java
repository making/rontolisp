package am.ik.artifact;

import java.io.IOException;

/**
 * A download the server answered with a status other than {@code 200}. A consumer that
 * searches several repositories tells "not here" ({@code 404}, see {@link #isNotFound()})
 * from a failure that must not be skipped silently.
 */
public final class HttpStatusException extends IOException {

	private static final long serialVersionUID = 1L;

	private final int statusCode;

	/**
	 * Creates the exception.
	 * @param statusCode the HTTP status
	 * @param url the URL that answered it
	 */
	public HttpStatusException(int statusCode, String url) {
		super("HTTP " + statusCode + " for " + url);
		this.statusCode = statusCode;
	}

	/**
	 * Creates the exception with what the status meant.
	 * @param statusCode the HTTP status
	 * @param url the URL that answered it
	 * @param detail why the answer is final
	 */
	public HttpStatusException(int statusCode, String url, String detail) {
		super("HTTP " + statusCode + " for " + url + ": " + detail);
		this.statusCode = statusCode;
	}

	/**
	 * Returns the HTTP status.
	 * @return the status code
	 */
	public int statusCode() {
		return this.statusCode;
	}

	/**
	 * Answers whether the server has nothing at the URL ({@code 404}).
	 * @return {@code true} for a 404
	 */
	public boolean isNotFound() {
		return this.statusCode == 404;
	}

}
