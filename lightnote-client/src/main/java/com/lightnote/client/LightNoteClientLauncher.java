package com.lightnote.client;

import javafx.application.Application;

/**
 * 普通 Java main 启动器，用于避开 jpackage/classpath 对 JavaFX Application 主类的特殊检测。
 */
public final class LightNoteClientLauncher {

    private LightNoteClientLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(LightNoteClientApplication.class, args);
    }
}
