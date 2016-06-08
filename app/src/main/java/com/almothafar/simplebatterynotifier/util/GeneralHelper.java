package com.almothafar.simplebatterynotifier.util;

import android.content.res.Resources;
import android.os.Build;
import android.util.TypedValue;

public class GeneralHelper {

    public static int dpToPixel(Resources res, int dpValue) {
        int pixel = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                dpValue, res.getDisplayMetrics());
        return pixel;
    }

    public static int getColor(Resources res, int id)
            throws Resources.NotFoundException {
        /* TODO see what theme need. */
        Resources.Theme theme = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return res.getColor(id, theme);
        } else {
            //noinspection deprecation
            return res.getColor(id);
        }
    }

    public static float fromCtoF(float temperature) {
        float f = (9.0f/5.0f) * temperature + 32;
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
