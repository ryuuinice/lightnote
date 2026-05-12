package com.lightnote.client.ui;

import com.lightnote.client.repository.AppConfigRepository;
import com.lightnote.client.sync.ClientSyncService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * 登录界面，负责服务端地址、账号密码输入与登录结果反馈。
 */
public class LoginView {

    private final VBox root = new VBox(12);
    private final TextField serverUrlField = new TextField();
    private final TextField usernameField = new TextField("admin");
    private final PasswordField passwordField = new PasswordField();
    private final Label messageLabel = new Label();
    private final Button loginButton = new Button("登录");
    private final ClientSyncService syncService;
    private final Runnable onLoginSuccess;

    public LoginView(AppConfigRepository configRepository, ClientSyncService syncService, Runnable onLoginSuccess) {
        this.syncService = syncService;
        this.onLoginSuccess = onLoginSuccess;
        serverUrlField.setText(configRepository.serverUrl());
        passwordField.setText("admin123");
        build();
        bind();
    }

    public Parent getRoot() {
        return root;
    }

    private void build() {
        root.getStyleClass().addAll("app-root", "login-root");
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(40));

        Label title = new Label("LightNote");
        title.getStyleClass().add("login-title");

        Label subtitle = new Label("登录到你的私有同步服务");
        subtitle.getStyleClass().add("login-subtitle");

        serverUrlField.setPromptText("服务端地址");
        serverUrlField.setMaxWidth(360);
        usernameField.setPromptText("用户名");
        usernameField.setMaxWidth(360);
        passwordField.setPromptText("密码");
        passwordField.setMaxWidth(360);

        loginButton.getStyleClass().add("primary-button");
        loginButton.setMaxWidth(360);

        messageLabel.getStyleClass().add("message-label");

        root.getChildren().addAll(title, subtitle, serverUrlField, usernameField, passwordField, loginButton, messageLabel);
    }

    private void bind() {
        loginButton.setOnAction(event -> login());
        passwordField.setOnAction(event -> login());
    }

    private void login() {
        setBusy(true, "正在登录...");
        Thread thread = new Thread(() -> {
            try {
                syncService.login(serverUrlField.getText(), usernameField.getText(), passwordField.getText());
                Platform.runLater(onLoginSuccess);
            } catch (Exception ex) {
                Platform.runLater(() -> setBusy(false, loginFailureMessage(ex)));
            }
        }, "lightnote-login");
        thread.setDaemon(true);
        thread.start();
    }

    private void setBusy(boolean busy, String message) {
        loginButton.setDisable(busy);
        messageLabel.setText(message);
    }

    private String loginFailureMessage(Exception ex) {
        String message = ex == null || ex.getMessage() == null || ex.getMessage().isBlank()
                ? "登录失败，请检查服务端地址和账号信息"
                : ex.getMessage();
        return switch (message) {
            case "无法连接到服务端" -> "登录失败：无法连接到服务端，请检查地址和服务是否启动";
            case "无法解析服务端地址" -> "登录失败：服务端地址无法解析，请检查域名或 IP";
            case "连接服务端超时" -> "登录失败：连接服务端超时，请稍后重试";
            case "服务端地址格式不正确" -> "登录失败：服务端地址格式不正确";
            default -> "登录失败: " + message;
        };
    }
}

