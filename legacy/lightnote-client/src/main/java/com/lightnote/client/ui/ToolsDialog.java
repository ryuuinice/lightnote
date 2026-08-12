package com.lightnote.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.io.StringWriter;

/**
 * 工具窗口：提供 JSON / XML 的格式化与压缩功能，后续可扩展更多小工具。
 */
public class ToolsDialog {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void show(Window owner) {
        Stage stage = new Stage();
        stage.setTitle("工具");
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }

        ListView<String> toolList = new ListView<>();
        toolList.getItems().addAll("JSON 格式化", "JSON 压缩", "XML 格式化", "XML 压缩");
        toolList.getSelectionModel().selectFirst();

        TextArea inputArea = new TextArea();
        inputArea.setPromptText("在此粘贴或输入要处理的文本...");
        TextArea outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.setPromptText("结果将显示在此处");

        Button runButton = new Button("执行");
        Button copyButton = new Button("复制输出");
        Button clearButton = new Button("清空");

        runButton.setOnAction(evt -> {
            String tool = toolList.getSelectionModel().getSelectedItem();
            String input = inputArea.getText();
            if (input == null) input = "";
            try {
                String result = switch (tool) {
                    case "JSON 格式化" -> formatJson(input);
                    case "JSON 压缩" -> minifyJson(input);
                    case "XML 格式化" -> formatXml(input);
                    case "XML 压缩" -> minifyXml(input);
                    default -> "";
                };
                outputArea.setText(result);
            } catch (Exception ex) {
                outputArea.setText("错误: " + ex.getMessage());
            }
        });

        copyButton.setOnAction(evt -> {
            String text = outputArea.getText();
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(text == null ? "" : text);
            clipboard.setContent(content);
        });

        clearButton.setOnAction(evt -> {
            inputArea.clear();
            outputArea.clear();
        });

        HBox controls = new HBox(8, runButton, copyButton, clearButton);
        controls.setAlignment(Pos.CENTER_LEFT);

        BorderPane right = new BorderPane();
        right.setPadding(new Insets(12));
        right.setTop(controls);
        right.setCenter(new SplitPane(inputArea, outputArea));
        BorderPane.setMargin(controls, new Insets(0,0,8,0));

        VBox left = new VBox(toolList);
        left.setPadding(new Insets(12));
        left.setPrefWidth(160);

        HBox root = new HBox(left, right);
        HBox.setHgrow(right, Priority.ALWAYS);

        Scene scene = new Scene(root, 900, 560);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        stage.setScene(scene);
        stage.show();
    }

    private String formatJson(String input) throws Exception {
        JsonNode tree = objectMapper.readTree(input);
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
    }

    private String minifyJson(String input) throws Exception {
        JsonNode tree = objectMapper.readTree(input);
        return objectMapper.writeValueAsString(tree);
    }

    private String formatXml(String input) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        // try to enable indentation
        try {
            tf.setAttribute("indent-number", 2);
        } catch (IllegalArgumentException ignored) {
        }
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter writer = new StringWriter();
        StreamSource src = new StreamSource(new StringReader(input));
        transformer.transform(src, new StreamResult(writer));
        return writer.toString();
    }

    private String minifyXml(String input) throws Exception {
        // simple minify: remove line breaks between tags and trim
        String compact = input.replaceAll(">\\s+<", "><");
        compact = compact.replaceAll("\\s+", " ").trim();
        return compact;
    }
}
