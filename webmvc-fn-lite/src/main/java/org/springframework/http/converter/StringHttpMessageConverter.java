/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.http.converter;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;

/**
 * Reads and writes a {@code String}: {@code text/plain} unless the response says another
 * type, in ISO-8859-1 unless the type names a charset or is JSON, which is UTF-8.
 */
public class StringHttpMessageConverter extends AbstractHttpMessageConverter<String> {

	/**
	 * Of the instance, not the class: the class then has nothing to make before its first
	 * use.
	 */
	private final MediaType applicationPlusJson = new MediaType("application", "*+json");

	public static final Charset DEFAULT_CHARSET = StandardCharsets.ISO_8859_1;

	public StringHttpMessageConverter() {
		this(DEFAULT_CHARSET);
	}

	public StringHttpMessageConverter(Charset defaultCharset) {
		super(defaultCharset, MediaType.TEXT_PLAIN, MediaType.ALL);
	}

	@Override
	public boolean supports(Class<?> clazz) {
		return String.class == clazz;
	}

	@Override
	protected String readInternal(Class<? extends String> clazz, HttpInputMessage inputMessage) throws IOException {
		Charset charset = getContentTypeCharset(inputMessage.getHeaders().getContentType());
		return new String(inputMessage.getBody().readAllBytes(), charset);
	}

	@Override
	protected void writeInternal(String str, HttpOutputMessage outputMessage) throws IOException {
		Charset charset = getContentTypeCharset(outputMessage.getHeaders().getContentType());
		outputMessage.getBody().write(str.getBytes(charset));
	}

	@Override
	protected void addDefaultHeaders(HttpHeaders headers, String s, @Nullable MediaType type) throws IOException {
		if (headers.getContentType() == null && type != null && type.isConcrete()
				&& (type.isCompatibleWith(MediaType.APPLICATION_JSON)
						|| type.isCompatibleWith(this.applicationPlusJson))) {
			// JSON is written without a charset parameter
			headers.setContentType(type);
		}
		super.addDefaultHeaders(headers, s, type);
	}

	@Override
	protected Long getContentLength(String str, @Nullable MediaType contentType) {
		return (long) str.getBytes(getContentTypeCharset(contentType)).length;
	}

	private Charset getContentTypeCharset(@Nullable MediaType contentType) {
		if (contentType != null) {
			Charset charset = contentType.getCharset();
			if (charset != null) {
				return charset;
			}
			if (contentType.isCompatibleWith(MediaType.APPLICATION_JSON)
					|| contentType.isCompatibleWith(this.applicationPlusJson)) {
				return StandardCharsets.UTF_8;
			}
		}
		Charset charset = getDefaultCharset();
		if (charset == null) {
			throw new IllegalStateException("No default charset");
		}
		return charset;
	}

}
