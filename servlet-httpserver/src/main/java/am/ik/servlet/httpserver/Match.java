package am.ik.servlet.httpserver;

import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.MappingMatch;
import org.jspecify.annotations.Nullable;

/**
 * The servlet a path is mapped to and how (Servlet 6.1, 12.1 and 12.2): the part of the
 * path that names the servlet, the rest, and the pattern that matched.
 */
final class Match implements HttpServletMapping {

	final ServletEntry servlet;

	final @Nullable String servletPath;

	final @Nullable String pathInfo;

	private final @Nullable String matchValue;

	private final @Nullable String pattern;

	private final @Nullable MappingMatch mappingMatch;

	/** A path's match; the parts but the servlet are null for a dispatch by name. */
	Match(ServletEntry servlet, @Nullable String servletPath, @Nullable String pathInfo, @Nullable String matchValue,
			@Nullable String pattern, @Nullable MappingMatch mappingMatch) {
		this.servlet = servlet;
		this.servletPath = servletPath;
		this.pathInfo = pathInfo;
		this.matchValue = matchValue;
		this.pattern = pattern;
		this.mappingMatch = mappingMatch;
	}

	@Override
	public @Nullable String getMatchValue() {
		return this.matchValue;
	}

	@Override
	public @Nullable String getPattern() {
		return this.pattern;
	}

	@Override
	public String getServletName() {
		return this.servlet.getName();
	}

	@Override
	public @Nullable MappingMatch getMappingMatch() {
		return this.mappingMatch;
	}

	@Override
	public String toString() {
		return "HttpServletMapping[matchValue=" + this.matchValue + ", pattern=" + this.pattern + ", servletName="
				+ getServletName() + ", mappingMatch=" + this.mappingMatch + "]";
	}

}
