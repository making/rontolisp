package annotated;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** What the application was told, in the order it was told. */
public final class Events {

	public static final List<String> ALL = new CopyOnWriteArrayList<>();

	private Events() {
	}

}
