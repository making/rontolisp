package conformance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;

/** A converter of the application's own: a {@link Point} as {@code x,y} in {@code text/x-point}. */
public class OwnConverter extends AbstractHttpMessageConverter<OwnConverter.Point> {

	public record Point(int x, int y) {
	}

	public static final MediaType POINT = new MediaType("text", "x-point");

	public OwnConverter() {
		super(POINT);
	}

	@Override
	protected boolean supports(Class<?> clazz) {
		return clazz == Point.class;
	}

	@Override
	protected Point readInternal(Class<? extends Point> clazz, HttpInputMessage inputMessage) throws IOException {
		String text = new String(inputMessage.getBody().readAllBytes(), StandardCharsets.US_ASCII);
		String[] parts = text.split(",");
		try {
			return new Point(Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()));
		}
		catch (RuntimeException ex) {
			throw new HttpMessageNotReadableException("no point: " + text, ex, inputMessage);
		}
	}

	@Override
	protected void writeInternal(Point point, HttpOutputMessage outputMessage) throws IOException {
		outputMessage.getBody().write((point.x() + "," + point.y()).getBytes(StandardCharsets.US_ASCII));
	}

}
