package am.ik.servlet.httpserver;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.MultipartConfigElement;
import org.jspecify.annotations.Nullable;

/**
 * How a servlet's {@code multipart/form-data} requests are read: the limits and the
 * threshold of its configuration, and the directory a part past the threshold goes to.
 * Made only for a servlet that has a configuration, so a program that gives none holds
 * none of the reading.
 */
final class Multipart {

	final MultipartConfigElement config;

	/**
	 * The directory: the location where it is absolute, else the location below
	 * {@code java.io.tmpdir}; null where there is no such property (a program compiled to
	 * WebAssembly, whose host may give it no directory), and every part stays in memory.
	 */
	final @Nullable Path directory;

	Multipart(MultipartConfigElement config) {
		this.config = config;
		String location = config.getLocation() == null ? "" : config.getLocation();
		Path given = Paths.get(location);
		if (given.isAbsolute()) {
			this.directory = given;
		}
		else {
			String temporary = System.getProperty("java.io.tmpdir");
			this.directory = temporary == null ? null : Paths.get(temporary).resolve(given);
		}
	}

	/**
	 * The parts of a request, read once; what failed the read is thrown again.
	 * @param request the request
	 * @param taken whether the servlet read the body through its stream or its reader
	 * @throws IllegalStateException if it did before the parts were read
	 */
	List<FormPart> parts(Request request, boolean taken) throws IOException {
		if (request.parts == null && request.partsFailure == null) {
			if (taken) {
				throw new IllegalStateException("the body has been read through getInputStream() or getReader()");
			}
			try {
				request.parts = read(request, request.bodyCharset());
			}
			catch (IOException | RuntimeException ex) {
				request.partsFailure = ex;
			}
		}
		if (request.partsFailure instanceof IOException failure) {
			throw failure;
		}
		if (request.partsFailure != null) {
			throw (RuntimeException) request.partsFailure;
		}
		// read, as nothing failed
		return Objects.requireNonNull(request.parts);
	}

	/**
	 * Adds the parts of a {@code multipart/form-data} request with no file name to its
	 * parameters.
	 * @throws IllegalStateException if the parts cannot be read (Servlet 6.1)
	 */
	void parameters(Request request, Map<String, List<String>> into) {
		if (!Request.multipart(request.getContentType())) {
			return;
		}
		try {
			parameters(parts(request, false), request.bodyCharset(), into);
		}
		catch (IOException ex) {
			throw new IllegalStateException("the parts of the request could not be read", ex);
		}
	}

	/**
	 * Reads the parts of a request's body.
	 * @param request the request, whose body nothing has read
	 * @param charset what the fields of a part are read as
	 * @return the parts in their order
	 * @throws IOException if the body is no {@code multipart/form-data} body or cannot be
	 * read
	 * @throws IllegalStateException if the body or a part is longer than the
	 * configuration allows, or a part's fields are longer than
	 * {@link MultipartBody#FIELDS_LIMIT}
	 */
	private List<FormPart> read(Request request, Charset charset) throws IOException {
		String boundary = MultipartBody.boundary(request.getContentType());
		if (boundary == null) {
			throw new IOException("the Content-Type of the request names no boundary: " + request.getContentType());
		}
		long max = this.config.getMaxRequestSize();
		if (max >= 0 && request.getContentLengthLong() > max) {
			throw new IllegalStateException("the multipart body is longer than " + max + " octets");
		}
		MultipartBody body = new MultipartBody(request.body(), boundary, max);
		List<FormPart> parts = new ArrayList<>();
		try {
			boolean closed = body.next(null);
			while (!closed) {
				FormPart part = new FormPart(this, body.fields(charset));
				parts.add(part);
				closed = body.next(part);
				part.complete();
			}
		}
		catch (IOException | RuntimeException ex) {
			for (int i = 0; i < parts.size(); i++) {
				parts.get(i).discard();
			}
			throw ex;
		}
		return parts;
	}

	/**
	 * Adds the parts with no file name to parameters (RFC 7578, 4.4 and 4.6): each read
	 * in the charset of its own {@code Content-Type}, else of the field
	 * {@code _charset_}, else the request's.
	 * @param parts the parts
	 * @param charset the request's charset
	 * @param into the parameters
	 */
	private static void parameters(List<FormPart> parts, Charset charset, Map<String, List<String>> into)
			throws IOException {
		Charset fields = charset;
		for (int i = 0; i < parts.size(); i++) {
			FormPart part = parts.get(i);
			if (part.getSubmittedFileName() == null && part.getName().equals("_charset_")) {
				Charset named = charset(part.text(StandardCharsets.ISO_8859_1).trim());
				fields = named == null ? fields : named;
			}
		}
		for (int i = 0; i < parts.size(); i++) {
			FormPart part = parts.get(i);
			if (part.getSubmittedFileName() == null) {
				Charset own = charset(MultipartBody.parameters(part.getContentType()).get("charset"));
				List<String> values = into.get(part.getName());
				if (values == null) {
					values = new ArrayList<>();
					into.put(part.getName(), values);
				}
				values.add(part.text(own == null ? fields : own));
			}
		}
	}

	private static @Nullable Charset charset(@Nullable String name) {
		try {
			return name == null ? null : Charset.forName(name);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

}
