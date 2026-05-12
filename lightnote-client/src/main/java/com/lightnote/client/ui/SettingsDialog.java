package com.lightnote.client.ui;

import com.lightnote.client.repository.AppConfigRepository;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * 设置对话框，用于编辑服务端地址和本地客户端配置。
 */
public class SettingsDialog {

    private final AppConfigRepository configRepository;
    private final Runnable onLogout;

    public SettingsDialog(AppConfigRepository configRepository, Runnable onLogout) {
        this.configRepository = configRepository;
        this.onLogout = onLogout;
    }

    public void show(Window owner) {
        Stage stage = new Stage();
        stage.setTitle("设置");
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }

        Label title = new Label("客户端设置");
        title.getStyleClass().add("login-title");

        Label subtitle = new Label("调整服务端地址，或在这里退出当前登录。");
        subtitle.getStyleClass().add("login-subtitle");

        Label serverLabel = new Label("服务端地址");
        TextField serverUrlField = new TextField(configRepository.serverUrl());
        serverUrlField.setPromptText("例如：http://127.0.0.1:8080");

        Label loginLabel = new Label("登录状态");
        Label loginHint = new Label(configRepository.token().isPresent() ? "当前已登录，可继续同步。" : "当前未登录。");
        loginHint.getStyleClass().add("message-label");

        Button saveButton = new Button("保存");
        saveButton.getStyleClass().add("primary-button");
        saveButton.setOnAction(event -> {
            configRepository.put("server_url", serverUrlField.getText());
            stage.close();
        });

        Button closeButton = new Button("关闭");
        closeButton.getStyleClass().add("ghost-button");
        closeButton.setOnAction(event -> stage.close());

        Button logoutButton = new Button("退出登录");
        logoutButton.getStyleClass().add("danger-button");
        logoutButton.setOnAction(event -> {
            configRepository.clearLogin();
            stage.close();
            Platform.runLater(onLogout);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(10, logoutButton, spacer, closeButton, saveButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(14,
                title,
                subtitle,
                serverLabel,
                serverUrlField,
                loginLabel,
                loginHint,
                actions
        );
        content.setPadding(new Insets(20));
        content.getStyleClass().addAll("app-root", "settings-content");

        Scene scene = new Scene(content, 520, 280);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        stage.setScene(scene);
        stage.showAndWait();
    }
}
