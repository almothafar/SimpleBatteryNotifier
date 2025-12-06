package com.almothafar.simplebatterynotifier.util;

import android.content.res.Resources;
import android.util.TypedValue;

public class GeneralHelper {

	public static int dpToPixel(Resources res, int dpValue) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpValue, res.getDisplayMetrics());
	}

	public static int getColor(Resources res, int id)
			throws Resources.NotFoundException {
		/* TODO see what theme need. */
		Resources.Theme theme = null;
		return res.getColor(id, theme);
	}

	public static float fromCtoF(float temperature) {
		float f = (9.0f / 5.0f) * temperature + 32;
		f = (float) Math.ceil(f * 10.0f) / 10.0f;
		return f;
	}

	public static int getHour(String time) {
		String[] pieces = time.split(":");
		return Integer.parseInt(pieces[0]);
	}

	public static int getMinute(String time) {
		String[] pieces = time.split(":");
		return Integer.parseInt(pieces[1]);
	}
}
