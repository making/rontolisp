/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.http;

import java.io.IOException;
import java.io.OutputStream;

/** A message a converter writes: its header fields and its body. */
public interface HttpOutputMessage extends HttpMessage {

	OutputStream getBody() throws IOException;

}
