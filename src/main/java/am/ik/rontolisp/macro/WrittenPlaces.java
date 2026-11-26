package am.ik.rontolisp.macro;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * The places a form of a place-assigning macro assigns, each of which lowers to a
 * {@code setf} of it: {@code setf} and {@code psetf} every other argument,
 * {@code rotatef} and {@code shiftf} all of them.
 */
public final class WrittenPlaces {

	private WrittenPlaces() {
	}

	/**
	 * {@return the places {@code form} assigns, empty for any other form}
	 * @param member the operator's package-stripped name
	 * @param form the form
	 */
	public static List<LispVal> of(String member, LispCons form) {
		boolean pairs = LispNames.SETF.equals(member) || LispNames.PSETF.equals(member);
		if (!pairs && !LispNames.ROTATEF.equals(member) && !LispNames.SHIFTF.equals(member) || !form.isProperList()) {
			return List.of();
		}
		List<LispVal> parts = form.toList();
		if (!pairs) {
			return parts.subList(1, parts.size());
		}
		List<LispVal> places = new ArrayList<>();
		for (int i = 1; i + 1 < parts.size(); i += 2) {
			places.add(parts.get(i));
		}
		return places;
	}

}
