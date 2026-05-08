package com.lightnote.client.ui;

import com.lightnote.client.repository.AppConfigRepository;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

public class SettingsDialog {

    private final AppConfigRepository configRepository;
    private final Runnable onLogout;

    public SettingsDialog(AppConfigRepository configRepository, Runnable onLogout) {
        this.configRepository = configRepository;
        this.onLogout = onLogout;
    }

    public void show(Window owner) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("设置");
        dialog.initOwner(owner);

        TextField serverUrlField = new TextField(configRepository.serverUrl());
        serverUrlField.setPromptText("服务端地址");
        HBox.setHgrow(serverUrlField, Priority.ALWAYS);

        Button saveButton = new Button("保存");
        saveButton.getStyleClass().add("primary-button");
        saveButton.setOnAction(event -> {
            configRepository.put("server_url", serverUrlField.getText());
            closeDialog(dialog);
        });

        Button logoutButton = new Button("退出登录");
        logoutButton.getStyleClass().add("danger-button");
        logoutButton.setOnAction(event -> {
            configRepository.clearLogin();
            closeDialog(dialog);
            Platform.runLater(onLogout);
        });

        HBox urlRow = new HBox(8, serverUrlField, saveButton);
        VBox content = new VBox(12,
                new Label("服务端"),
                urlRow,
                new Label("登录状态"),
                logoutButton
        );
        content.setPadding(new Insets(16));
        content.setPrefWidth(440);
        content.getStyleClass().add("settings-content");

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().clear();
        dialog.showAndWait();
    }

    private void closeDialog(Dialog<?> dialog) {
        Window window = dialog.getDialogPane().getScene() == null
                ? null
                : dialog.getDialogPane().getScene().getWindow();
        if (window != null) {
            window.hide();
        } else {
            dialog.close();
        }
    }
}
