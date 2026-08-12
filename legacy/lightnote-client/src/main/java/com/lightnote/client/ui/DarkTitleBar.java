package com.lightnote.client.ui;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.win32.StdCallLibrary;
import javafx.stage.Stage;

public final class DarkTitleBar {

    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19;
    private static final int DWMWA_BORDER_COLOR = 34;
    private static final int DWMWA_CAPTION_COLOR = 35;
    private static final int DWMWA_TEXT_COLOR = 36;
    private static final int WINDOW_TITLE_COLOR = 0x00fbf7f4;
    private static final int WINDOW_BORDER_COLOR = 0x00eee3dc;
    private static final int WINDOW_TEXT_COLOR = 0x0037291f;

    private DarkTitleBar() {
    }

    public static void apply(Stage stage) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        try {
            HWND hwnd = User32.INSTANCE.FindWindow(null, stage.getTitle());
            if (hwnd == null) {
                return;
            }
            setBooleanAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, false);
            setBooleanAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, false);
            setColorAttribute(hwnd, DWMWA_CAPTION_COLOR, WINDOW_TITLE_COLOR);
            setColorAttribute(hwnd, DWMWA_BORDER_COLOR, WINDOW_BORDER_COLOR);
            setColorAttribute(hwnd, DWMWA_TEXT_COLOR, WINDOW_TEXT_COLOR);
        } catch (Throwable ignored) {
            // Native title-bar styling is best-effort; the app should still run normally without it.
        }
    }

    private static void setBooleanAttribute(HWND hwnd, int attribute, boolean enabled) {
        try (Memory value = new Memory(Native.getNativeSize(Integer.TYPE))) {
            value.setInt(0, enabled ? 1 : 0);
            DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd, attribute, value, (int) value.size());
        }
    }

    private static void setColorAttribute(HWND hwnd, int attribute, int color) {
        try (Memory value = new Memory(Native.getNativeSize(Integer.TYPE))) {
            value.setInt(0, color);
            DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd, attribute, value, (int) value.size());
        }
    }

    private interface DwmApi extends StdCallLibrary {
        DwmApi INSTANCE = Native.load("dwmapi", DwmApi.class);

        int DwmSetWindowAttribute(HWND hwnd, int attribute, Pointer attributeValue, int attributeSize);
    }
}
