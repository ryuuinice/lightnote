package com.lightnote.client;

import com.lightnote.client.repository.DatabaseInitializer;
import com.lightnote.client.repository.NoteRepository;
import com.lightnote.client.repository.AppConfigRepository;
import com.lightnote.client.sync.ClientSyncService;
import com.lightnote.client.ui.DarkTitleBar;
import com.lightnote.client.ui.LoginView;
import com.lightnote.client.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class LightNoteClientApplication extends Application {

    private Stage stage;
    private DatabaseInitializer initializer;
    private NoteRepository noteRepository;
    private AppConfigRepository configRepository;
    private ClientSyncService syncService;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        initializer = new DatabaseInitializer();
        initializer.initialize();

        noteRepository = new NoteRepository(initializer.getDatabasePath());
        configRepository = new AppConfigRepository(initializer.getDatabasePath());
        syncService = new ClientSyncService(noteRepository, configRepository);

        if (configRepository.token().isPresent()) {
            showMain();
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
        LoginView loginView = new LoginView(configRepository, syncService, this::showMain);
        setScene(loginView.getRoot(), 900, 620);
    }

    private void showMain() {
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
}
