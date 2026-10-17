package com.tencent.wecarflow.carplayguard;
import android.content.Context;
import java.io.FileInputStream;
/** Only the dedicated DiPlay process skips the original application's business initialization. */
public final class BridgeBootstrap {
    private BridgeBootstrap() {}
    public static boolean isBridgeProcess(Context context) {
        try {
            FileInputStream input = new FileInputStream("/proc/self/cmdline");
            try {
                byte[] bytes = new byte[256]; int n = input.read(bytes), end = 0;
                while (end < n && bytes[end] != 0) end++;
                return new String(bytes, 0, end, "UTF-8").equals(context.getPackageName() + ":carplayguard");
            } finally { input.close(); }
        } catch (Exception e) { return false; }
    }
}
