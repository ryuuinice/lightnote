package com.lightnote.client;

import com.lightnote.client.repository.DatabaseInitializer;
import com.lightnote.client.repository.NoteRepository;
import com.lightnote.client.repository.AppConfigRepository;
import com.lightnote.client.sync.ClientSyncService;
import com.lightnote.client.ui.DarkTitleBar;
import com.lightnote.client.ui.LoginView;
import com.lightnote.client.ui.MainView;
import com.lightnote.client.util.AppLogger;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 客户端应用入口，负责初始化数据库、日志与主界面启动流程。
 */
public class LightNoteClientApplication extends Application {

    private static final Logger LOGGER = AppLogger.logger(LightNoteClientApplication.class);

    private Stage stage;
    private DatabaseInitializer initializer;
    private NoteRepository noteRepository;
    private AppConfigRepository configRepository;
    private ClientSyncService syncService;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        installGlobalExceptionLogging();
        try {
            initializer = new DatabaseInitializer();
            initializer.initialize();
            AppLogger.configure(initializer.getDataDirectory());
            initializer.initializationLog().forEach(LOGGER::info);
        } catch (RuntimeException ex) {
            LOGGER.log(Level.SEVERE, "客户端启动失败: 数据库初始化未完成", ex);
            throw ex;
        }

        noteRepository = new NoteRepository(initializer.getDatabasePath());
        configRepository = new AppConfigRepository(initializer.getDatabasePath());
        syncService = new ClientSyncService(noteRepository, configRepository);
        LOGGER.info("客户端启动成功");

        if (configRepository.token().isPresent()) {
            showMain();
            Thread sessionCheck = new Thread(() -> {
                boolean ok = syncService.validateSession();
                if (!ok) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION, "当前登录已过期或不可用，请重新登录。", ButtonType.OK);
                        alert.setTitle("登录验证失败");
                        alert.setHeaderText("会话无效");
                        alert.showAndWait();
                        showLogin();
                    });
                }
            }, "lightnote-session-check");
            sessionCheck.setDaemon(true);
            sessionCheck.start();
        } else {
            showLogin();
        }

        stage.setTitle("LightNote");
        stage.setMinWidth(980);
        stage.setMinHeight(640);
        applyAppIcon(stage);
        stage.show();
        DarkTitleBar.apply(stage);
    }

    private void showLogin() {
        LOGGER.info("显示登录界面");
        LoginView loginView = new LoginView(configRepository, syncService, this::showMain);
        setScene(loginView.getRoot(), 900, 620);
    }

    private void showMain() {
        LOGGER.info("显示主界面");
        MainView mainView = new MainView(noteRepository, configRepository, syncService, this::showLogin);
        setScene(mainView.getRoot(), 1180, 760);
    }

    private void setScene(javafx.scene.Parent root, int width, int height) {
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(getClass().getResource("/styles/lightnote.css").toExternalForm());
        stage.setScene(scene);
    }

    private void applyAppIcon(Stage stage) {
        var iconUrl = getClass().getResource("/images/icon.png");
        if (iconUrl == null) {
            return;
        }
        stage.getIcons().setAll(new Image(iconUrl.toExternalForm()));
    }

    public static void main(String[] args) {
        launch(args);
    }

    private void installGlobalExceptionLogging() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                LOGGER.log(Level.SEVERE, "未捕获异常 [" + thread.getName() + "]", throwable));
    }
}

