package am.ik.servlet.httpserver;

import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.MappingMatch;

/**
 * The servlet a path is mapped to and how (Servlet 6.1, 12.1 and 12.2): the part of the
 * path that names the servlet, the rest, and the pattern that matched.
 */
final class Match implements HttpServletMapping {

	final ServletEntry servlet;

	final String servletPath;

	final String pathInfo;

	private final String matchValue;

	private final String pattern;

	private final MappingMatch mappingMatch;

	Match(ServletEntry servlet, String servletPath, String pathInfo, String matchValue, String pattern,
			MappingMatch mappingMatch) {
		this.servlet = servlet;
		this.servletPath = servletPath;
		this.pathInfo = pathInfo;
		this.matchValue = matchValue;
		this.pattern = pattern;
		this.mappingMatch = mappingMatch;
	}

	@Override
	public String getMatchValue() {
		return this.matchValue;
	}

	@Override
	public String getPattern() {
		return this.pattern;
	}

	@Override
	public String getServletName() {
		return this.servlet.getName();
	}

	@Override
	public MappingMatch getMappingMatch() {
		return this.mappingMatch;
	}

	@Override
	public String toString() {
		return "HttpServletMapping[matchValue=" + this.matchValue + ", pattern=" + this.pattern + ", servletName="
				+ getServletName() + ", mappingMatch=" + this.mappingMatch + "]";
	}

}
