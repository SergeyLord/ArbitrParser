package dev.sergeylord.ui;

import dev.sergeylord.excel.ColumnMapping;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Окно с таблицей соответствий Excel ↔ КАД. */
public final class RulesDialog {

    private RulesDialog() {
    }

    public static void show(Window owner) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Правила соответствия");
        dialog.setMinWidth(640);
        dialog.setMinHeight(480);

        Label note = new Label("Список КАД + карточки для даты заседания. Остальное с «—» пока не трогаем.");
        note.getStyleClass().add("status-muted");

        TableView<ColumnMapping.Rule> table = new TableView<>(
                FXCollections.observableArrayList(ColumnMapping.currentRules())
        );
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setSortPolicy(tv -> false);

        TableColumn<ColumnMapping.Rule, String> colExcel = new TableColumn<>("Колонка Excel");
        colExcel.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().excelColumn()));
        colExcel.setSortable(false);
        colExcel.setPrefWidth(260);

        TableColumn<ColumnMapping.Rule, String> colKad = new TableColumn<>("Откуда в КАД");
        colKad.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().kadSource()));
        colKad.setSortable(false);

        table.getColumns().add(colExcel);
        table.getColumns().add(colKad);

        VBox root = new VBox(10, note, table);
        root.setPadding(new Insets(14));
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        root.getStyleClass().add("root");

        Scene scene = new Scene(root, 720, 560);
        var css = RulesDialog.class.getResource("/dev/sergeylord/ui/app.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        dialog.setScene(scene);
        dialog.showAndWait();
    }
}
