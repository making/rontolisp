package am.ik.servlet.httpserver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.http.Part;
import org.jspecify.annotations.Nullable;

/**
 * A part of a {@code multipart/form-data} body: its fields, its name and file name, and
 * its octets -- in memory up to the threshold of its servlet's configuration, past it in
 * a file of the configuration's directory where there is one.
 */
final class FormPart implements Part {

	private static final AtomicLong FILES = new AtomicLong();

	private final Multipart multipart;

	/** Each field's name and value in turn, as the part has them. */
	private final List<String> fields;

	private final String name;

	private final @Nullable String fileName;

	private @Nullable ByteArrayOutputStream memory = new ByteArrayOutputStream();

	private byte @Nullable [] octets;

	/** The file the octets are in, or null where they are in memory. */
	private @Nullable Path file;

	private @Nullable OutputStream out;

	/** Whether the file is the container's, deleted at the end of the request. */
	private boolean owned;

	private long size;

	/**
	 * A part of its fields.
	 * @throws IOException if they have no {@code Content-Disposition} of
	 * {@code form-data} with a name (RFC 7578, 4.2)
	 */
	FormPart(Multipart multipart, List<String> fields) throws IOException {
		this.multipart = multipart;
		this.fields = fields;
		String disposition = field("Content-Disposition");
		Map<String, String> parameters = MultipartBody.parameters(disposition);
		String name = parameters.get("name");
		if (!"form-data".equals(MultipartBody.type(disposition)) || name == null) {
			throw new IOException("a part has no Content-Disposition of form-data with a name: " + disposition);
		}
		this.name = name;
		String encoded = parameters.get("filename*");
		String extended = encoded != null ? MultipartBody.extended(encoded) : null;
		this.fileName = extended != null ? extended : parameters.get("filename");
	}

	private @Nullable String field(String name) {
		for (int i = 0; i < this.fields.size(); i += 2) {
			if (this.fields.get(i).equalsIgnoreCase(name)) {
				return this.fields.get(i + 1);
			}
		}
		return null;
	}

	/**
	 * Adds octets of the body, to a file once they pass the threshold.
	 * @throws IllegalStateException if the part is longer than its configuration allows
	 */
	void append(byte[] octets, int offset, int length) throws IOException {
		this.size += length;
		long max = this.multipart.config.getMaxFileSize();
		if (max >= 0 && this.size > max) {
			throw new IllegalStateException("the part " + this.name + " is longer than " + max + " octets");
		}
		// in memory until a file is made, in the file from then on
		ByteArrayOutputStream memory = this.memory;
		OutputStream out = this.out;
		Path directory = this.multipart.directory;
		if (memory != null && directory != null && this.size > this.multipart.config.getFileSizeThreshold()) {
			for (int tries = 0; out == null; tries++) {
				Path file = directory
					.resolve("part-" + Long.toString(System.nanoTime(), 36) + "-" + FILES.incrementAndGet() + ".tmp");
				try {
					out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
					this.file = file;
					this.owned = true;
				}
				catch (FileAlreadyExistsException ex) {
					if (tries == 16) {
						throw ex;
					}
				}
			}
			memory.writeTo(out);
			this.out = out;
			this.memory = null;
			memory = null;
		}
		if (memory != null) {
			memory.write(octets, offset, length);
		}
		else if (out != null) {
			out.write(octets, offset, length);
		}
	}

	/** The part's octets are all there. */
	void complete() throws IOException {
		OutputStream out = this.out;
		ByteArrayOutputStream memory = this.memory;
		if (out != null) {
			out.close();
			this.out = null;
		}
		else if (memory != null) {
			this.octets = memory.toByteArray();
			this.memory = null;
		}
	}

	/** Deletes the file the container made for the part, at the end of the request. */
	void discard() {
		try {
			if (this.out != null) {
				this.out.close();
			}
			delete();
		}
		catch (IOException ex) {
			// a file that cannot be deleted is left behind
		}
	}

	/** The part's text, for a part with no file name, which is a parameter. */
	String text(Charset charset) throws IOException {
		byte[] octets = this.octets != null ? this.octets : getInputStream().readAllBytes();
		if ("UTF-8".equals(charset.name()) && !UrlCodec.utf8(octets, octets.length)) {
			throw new IllegalStateException("the part " + this.name + " is no text in UTF-8");
		}
		return new String(octets, charset);
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return this.file != null ? Files.newInputStream(this.file)
				: new ByteArrayInputStream(this.octets == null ? new byte[0] : this.octets);
	}

	@Override
	public @Nullable String getContentType() {
		return field("Content-Type");
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public @Nullable String getSubmittedFileName() {
		return this.fileName;
	}

	@Override
	public long getSize() {
		return this.size;
	}

	/**
	 * Writes the part to a file: a relative name is in the directory of the servlet's
	 * configuration. A part in a file of the container's is moved there, and read from
	 * there afterwards.
	 */
	@Override
	public void write(String fileName) throws IOException {
		Path target = Paths.get(fileName);
		if (!target.isAbsolute()) {
			if (this.multipart.directory == null) {
				throw new IOException("no directory to write " + fileName
						+ " in: the multipart configuration has no absolute location and java.io.tmpdir no value");
			}
			target = this.multipart.directory.resolve(fileName);
		}
		if (this.file != null) {
			Files.move(this.file, target, StandardCopyOption.REPLACE_EXISTING);
			this.file = target;
			this.owned = false;
		}
		else {
			Files.write(target, this.octets == null ? new byte[0] : this.octets);
		}
	}

	@Override
	public void delete() throws IOException {
		if (this.file != null && this.owned) {
			Files.deleteIfExists(this.file);
			this.owned = false;
		}
		this.octets = null;
	}

	@Override
	public @Nullable String getHeader(String name) {
		return field(name);
	}

	@Override
	public Collection<String> getHeaders(String name) {
		List<String> values = new ArrayList<>();
		for (int i = 0; i < this.fields.size(); i += 2) {
			if (this.fields.get(i).equalsIgnoreCase(name)) {
				values.add(this.fields.get(i + 1));
			}
		}
		return values;
	}

	@Override
	public Collection<String> getHeaderNames() {
		List<String> names = new ArrayList<>();
		for (int i = 0; i < this.fields.size(); i += 2) {
			boolean seen = false;
			for (int k = 0; k < names.size() && !seen; k++) {
				seen = names.get(k).equalsIgnoreCase(this.fields.get(i));
			}
			if (!seen) {
				names.add(this.fields.get(i));
			}
		}
		return names;
	}

}
