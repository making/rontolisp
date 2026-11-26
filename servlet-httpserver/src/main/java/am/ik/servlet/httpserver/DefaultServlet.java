package am.ik.servlet.httpserver;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;

/**
 * The servlet of the path {@code /} where the application maps none there (Servlet 6.1,
 * 12.2), registered as {@code default} where there is something to serve: the resource of
 * a path -- a file of the document root, else one under {@code META-INF/resources} of the
 * class path -- with its length, its media type and when it was modified, a range of its
 * octets where one is asked (RFC 9110, 14), and the answer to a request conditional on
 * its time (13); a directory by its welcome file. {@code WEB-INF} and {@code META-INF}
 * are not served.
 */
final class DefaultServlet extends HttpServlet {

	static final String NAME = "default";

	private final transient WebContext context;

	DefaultServlet(WebContext context) {
		this.context = context;
	}

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		String method = request.getMethod();
		boolean head = method.equals("HEAD");
		if (!head && !method.equals("GET")) {
			response.setHeader("Allow", "GET, HEAD, OPTIONS");
			if (!method.equals("OPTIONS")) {
				response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			}
			return;
		}
		boolean included = request.getDispatcherType() == DispatcherType.INCLUDE;
		String path = path(request, included);
		String name = path;
		Resource resource = null;
		if (!hidden(path)) {
			if (path.endsWith("/")) {
				name = path + "index.html";
				resource = file(name);
				if (resource == null) {
					name = path + "index.htm";
					resource = file(name);
				}
			}
			else {
				resource = this.context.resource(path);
			}
		}
		// A directory asked without its slash, against which its page's links are
		// resolved; where it is compiled ahead of time an archive's reads as nothing.
		if (resource != null && !path.endsWith("/")
				&& (resource.directory || resource.length == 0 && welcomed(path + "/"))) {
			if (!included) {
				String query = request.getQueryString();
				response.sendRedirect(request.getRequestURI() + "/" + (query == null ? "" : "?" + query));
			}
			return;
		}
		if (resource == null) {
			if (included) {
				throw new FileNotFoundException(path);
			}
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		if (included) {
			include(resource, response);
			return;
		}
		long modified = resource.modified / 1000 * 1000;
		int precondition = precondition(request, modified);
		if (precondition == HttpServletResponse.SC_NOT_MODIFIED) {
			response.setStatus(precondition);
			if (modified > 0) {
				response.setDateHeader("Last-Modified", modified);
			}
			return;
		}
		if (precondition != 0) {
			response.sendError(precondition);
			return;
		}
		response.setHeader("Accept-Ranges", "bytes");
		long length = resource.length;
		long from = 0;
		long count = length;
		String range = head ? null : request.getHeader("Range");
		if (range != null && length > 0 && unchanged(request, modified)) {
			long[] span = span(range, length);
			if (span != null && span.length == 0) {
				response.setHeader("Content-Range", "bytes */" + length);
				response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
				return;
			}
			if (span != null) {
				from = span[0];
				count = span[1] - span[0] + 1;
				response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
				response.setHeader("Content-Range", "bytes " + span[0] + "-" + span[1] + "/" + length);
			}
		}
		String type = this.context.getMimeType(name);
		if (type != null) {
			response.setContentType(type);
		}
		if (modified > 0) {
			response.setDateHeader("Last-Modified", modified);
		}
		response.setContentLengthLong(count);
		if (!head) {
			resource.write(response.getOutputStream(), from, count);
		}
	}

	/**
	 * Writes what is included, the octets alone: where the including servlet writes text,
	 * as the response's charset reads them, which it writes back as they were.
	 */
	private static void include(Resource resource, HttpServletResponse response) throws IOException {
		OutputStream out;
		try {
			out = response.getOutputStream();
		}
		catch (IllegalStateException ex) {
			byte[] octets;
			try (InputStream in = resource.open()) {
				octets = in.readAllBytes();
			}
			response.getWriter().write(new String(octets, Charset.forName(response.getCharacterEncoding())));
			return;
		}
		resource.write(out, 0, resource.length);
	}

	/** The file of a path, or null where there is none. */
	private @Nullable Resource file(String path) throws IOException {
		Resource found = this.context.resource(path);
		return found == null || found.directory ? null : found;
	}

	/** Whether a directory has a welcome file. */
	private boolean welcomed(String directory) throws IOException {
		return file(directory + "index.html") != null || file(directory + "index.htm") != null;
	}

	/** The path inside the context of the resource asked: an included one's. */
	private static String path(HttpServletRequest request, boolean included) {
		String servletPath = request.getServletPath();
		String pathInfo = request.getPathInfo();
		Object including = included ? request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH) : null;
		if (including != null) {
			servletPath = (String) including;
			pathInfo = (String) request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO);
		}
		String path = servletPath + (pathInfo == null ? "" : pathInfo);
		return path.isEmpty() ? "/" : path;
	}

	/** Whether a path is in {@code WEB-INF} or {@code META-INF}, which no client sees. */
	private static boolean hidden(String path) {
		return first(path, "/WEB-INF") || first(path, "/META-INF");
	}

	/** Whether a path's first segment is a name, its ASCII letters in either case. */
	private static boolean first(String path, String name) {
		int length = name.length();
		if (path.length() < length || path.length() > length && path.charAt(length) != '/') {
			return false;
		}
		for (int i = 0; i < length; i++) {
			char c = path.charAt(i);
			if ((c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c) != name.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * What the preconditions of a request answer (RFC 9110, 13.2.2): 0 where it goes on.
	 * A resource here has no entity tag, so none matches one.
	 */
	private static int precondition(HttpServletRequest request, long modified) {
		String match = request.getHeader("If-Match");
		if (match != null) {
			if (!match.trim().equals("*")) {
				return HttpServletResponse.SC_PRECONDITION_FAILED;
			}
		}
		else if (modified > 0) {
			long since = date(request, "If-Unmodified-Since");
			if (since >= 0 && modified > since) {
				return HttpServletResponse.SC_PRECONDITION_FAILED;
			}
		}
		String noneMatch = request.getHeader("If-None-Match");
		if (noneMatch != null) {
			return noneMatch.trim().equals("*") ? HttpServletResponse.SC_NOT_MODIFIED : 0;
		}
		if (modified > 0) {
			long since = date(request, "If-Modified-Since");
			if (since >= 0 && modified <= since) {
				return HttpServletResponse.SC_NOT_MODIFIED;
			}
		}
		return 0;
	}

	/** A field's date, or -1 where there is none or it is not a date. */
	private static long date(HttpServletRequest request, String name) {
		try {
			return request.getDateHeader(name);
		}
		catch (IllegalArgumentException ex) {
			return -1;
		}
	}

	/**
	 * Whether the range asked is of the resource as it is (RFC 9110, 13.1.5): no
	 * {@code If-Range}, or its time exactly. An entity tag is none of a resource here.
	 */
	private static boolean unchanged(HttpServletRequest request, long modified) {
		String ifRange = request.getHeader("If-Range");
		if (ifRange == null) {
			return true;
		}
		String validator = ifRange.trim();
		return modified > 0 && !validator.startsWith("\"") && !validator.startsWith("W/")
				&& date(request, "If-Range") == modified;
	}

	/**
	 * The octets a {@code Range} field asks for (RFC 9110, 14.1.2): the first and the
	 * last of one range of bytes; an empty array where the range cannot be satisfied or
	 * is not written as one; null where the field asks for another unit or for several
	 * ranges, which are answered with the whole resource.
	 */
	private static long @Nullable [] span(String range, long length) {
		int equals = range.indexOf('=');
		if (equals < 0 || !range.substring(0, equals).trim().equalsIgnoreCase("bytes")) {
			return null;
		}
		String spec = null;
		int start = equals + 1;
		while (start <= range.length()) {
			int comma = range.indexOf(',', start);
			int end = comma < 0 ? range.length() : comma;
			String each = range.substring(start, end).trim();
			if (!each.isEmpty()) {
				if (spec != null) {
					return null;
				}
				spec = each;
			}
			start = end + 1;
		}
		long[] none = new long[0];
		int dash = spec == null ? -1 : spec.indexOf('-');
		if (spec == null || dash < 0) {
			return none;
		}
		long first = number(spec.substring(0, dash).trim());
		long last = number(spec.substring(dash + 1).trim());
		if (first == -2 || last == -2 || (first == -1 && last <= 0)) {
			return none;
		}
		if (first == -1) {
			// the last octets
			return new long[] { Math.max(0, length - last), length - 1 };
		}
		if (first >= length || (last >= 0 && last < first)) {
			return none;
		}
		return new long[] { first, last < 0 || last >= length ? length - 1 : last };
	}

	/**
	 * The number of digits: -1 for none, -2 for what is not digits; a number past a
	 * {@code long}'s is its largest.
	 */
	private static long number(String digits) {
		if (digits.isEmpty()) {
			return -1;
		}
		long value = 0;
		for (int i = 0; i < digits.length(); i++) {
			char c = digits.charAt(i);
			if (c < '0' || c > '9') {
				return -2;
			}
			value = value > (Long.MAX_VALUE - 9) / 10 ? Long.MAX_VALUE : value * 10 + (c - '0');
		}
		return value;
	}

}
