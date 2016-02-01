package com.czbix.xposed.kitkat_sms_patch;

import android.os.Build;

public class Utils {
    static boolean isKkOrLater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
    static boolean isLpOrLater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    static boolean isMmOrLater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
}
