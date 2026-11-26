package refused;

import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpSessionBindingListener;

/** Classes whose annotations cannot mean what they say. */
public class Refused {

	@WebServlet("/plain")
	public static class NoServlet {

	}

	@WebFilter("/*")
	public static class NoFilter {

	}

	@WebListener
	public static class NoListener {

	}

	/** A value bound to a session hears of it as the value, not as a listener. */
	@WebListener
	public static class OfBinding implements HttpSessionBindingListener {

	}

	@WebServlet(value = "/a", urlPatterns = "/b")
	public static class Both extends HttpServlet {

	}

	@WebServlet(name = "unmapped")
	public static class Unmapped extends HttpServlet {

	}

	@WebServlet(name = "taken", value = "/free")
	public static class NameTaken extends HttpServlet {

	}

	@WebServlet("/taken")
	public static class PatternTaken extends HttpServlet {

	}

	@WebFilter(filterName = "taken", value = "/*")
	public static class FilterTaken extends HttpFilter {

	}

	@WebServlet("/hidden")
	static class Hidden extends HttpServlet {

	}

	@WebServlet("/secured")
	@ServletSecurity
	public static class Secured extends HttpServlet {

	}

	/** What the names and the pattern of the cases are taken by. */
	@WebServlet(name = "taken", value = "/taken")
	public static class Taken extends HttpServlet {

	}

	@WebFilter(filterName = "taken", value = "/taken")
	public static class TakenFilter extends HttpFilter {

	}

}
