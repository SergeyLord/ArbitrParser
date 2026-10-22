package dev.sergeylord.ui;

import dev.sergeylord.config.AppDefaults;
import dev.sergeylord.config.UserSettings;
import dev.sergeylord.excel.ExcelPathFinder;
import dev.sergeylord.excel.StatusExcelWriter;
import dev.sergeylord.history.RunHistoryStore;
import dev.sergeylord.history.RunRecord;
import dev.sergeylord.kad.KadCase;
import dev.sergeylord.kad.KadSearchClient;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** Главный экран: ИНН, Excel, кнопка прогона, история. */
public final class MainView {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final TextField innField = new TextField();
    private final TextField excelField = new TextField();
    private final Label statusLabel = new Label("");
    private final Label progressLabel = new Label("");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button runButton = new Button("Запустить прогон");
    private final ObservableList<RunRecord> historyRows = FXCollections.observableArrayList();
    private final RunHistoryStore historyStore = new RunHistoryStore();
    private final UserSettings settings;

    public MainView(Stage stage) {
        this.stage = stage;
        this.settings = UserSettings.load();
        buildUi();
        loadInitialState();
        reloadHistory();
    }

    public BorderPane getRoot() {
        return root;
    }

    private void buildUi() {
        root.setTop(buildTopBar());
        root.setCenter(buildCenter());
        root.getStyleClass().add("root");
    }

    private VBox buildTopBar() {
        Label title = new Label("ArbitrParser");
        title.getStyleClass().add("top-title");
        Label sub = new Label("Парсинг kad.arbitr.ru → таблица статусов по искам");
        sub.getStyleClass().add("top-subtitle");
        VBox box = new VBox(4, title, sub);
        box.getStyleClass().add("top-bar");
        return box;
    }

    private VBox buildCenter() {
        VBox center = new VBox(14);
        center.setPadding(new Insets(16));

        VBox formCard = new VBox(12);
        formCard.getStyleClass().add("card");

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setPercentWidth(14);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(66);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(20);
        form.getColumnConstraints().addAll(c0, c1, c2);

        Label innLabel = new Label("ИНН");
        innLabel.getStyleClass().add("section-label");
        innField.setPromptText(AppDefaults.DEFAULT_INN);
        form.add(innLabel, 0, 0);
        form.add(innField, 1, 0, 2, 1);

        Label excelLabel = new Label("Excel");
        excelLabel.getStyleClass().add("section-label");
        excelField.setPromptText("Файл не найден — выберите «" + AppDefaults.EXCEL_FILE_NAME + "»");
        Button browse = new Button("Обзор…");
        browse.getStyleClass().add("button-secondary");
        browse.setMaxWidth(Double.MAX_VALUE);
        browse.setOnAction(e -> browseExcel());
        form.add(excelLabel, 0, 1);
        form.add(excelField, 1, 1);
        form.add(browse, 2, 1);

        runButton.getStyleClass().add("button-primary");
        runButton.setPrefWidth(180);
        runButton.setOnAction(e -> startRun());

        Button rulesButton = new Button("ПРАВИЛА");
        rulesButton.getStyleClass().add("button-secondary");
        rulesButton.setOnAction(e -> RulesDialog.show(stage));

        Button excelButton = new Button("EXCEL");
        excelButton.getStyleClass().add("button-secondary");
        excelButton.setOnAction(e -> openExcelFile());

        statusLabel.getStyleClass().add("status-muted");
        progressLabel.getStyleClass().add("status-muted");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(14);
        progressBar.setProgress(0);
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        HBox actions = new HBox(12, runButton, rulesButton, excelButton, statusLabel);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox progressBox = new VBox(4, progressLabel, progressBar);
        formCard.getChildren().addAll(form, actions, progressBox);

        VBox historyCard = new VBox(10);
        historyCard.getStyleClass().add("card");
        VBox.setVgrow(historyCard, Priority.ALWAYS);

        Label historyTitle = new Label("История запусков");
        historyTitle.getStyleClass().add("section-label");

        TableView<RunRecord> table = buildHistoryTable();
        VBox.setVgrow(table, Priority.ALWAYS);
        historyCard.getChildren().addAll(historyTitle, table);

        center.getChildren().addAll(formCard, historyCard);
        VBox.setVgrow(historyCard, Priority.ALWAYS);
        return center;
    }

    private TableView<RunRecord> buildHistoryTable() {
        TableView<RunRecord> table = new TableView<>(historyRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Пока запусков не было"));
        table.setSortPolicy(tv -> false); // порядок только по времени добавления (новые сверху)

        TableColumn<RunRecord, String> started = col("Время", r -> formatIso(r.startedAtIso()));
        TableColumn<RunRecord, String> inn = col("ИНН", RunRecord::inn);
        TableColumn<RunRecord, String> status = col("Статус", RunRecord::status);
        TableColumn<RunRecord, String> cases = col("Дел", r -> String.valueOf(r.casesFound()));
        TableColumn<RunRecord, String> dur = col("Длит.", r -> formatDuration(r.durationMs()));
        TableColumn<RunRecord, String> msg = col("Сообщение", RunRecord::message);

        started.setMaxWidth(160);
        inn.setMaxWidth(120);
        status.setMaxWidth(100);
        cases.setMaxWidth(70);
        dur.setMaxWidth(90);

        table.getColumns().add(started);
        table.getColumns().add(inn);
        table.getColumns().add(status);
        table.getColumns().add(cases);
        table.getColumns().add(dur);
        table.getColumns().add(msg);
        return table;
    }

    private static TableColumn<RunRecord, String> col(String title, java.util.function.Function<RunRecord, String> getter) {
        TableColumn<RunRecord, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new SimpleStringProperty(getter.apply(cd.getValue())));
        c.setSortable(false);
        c.setReorderable(false);
        return c;
    }

    private void loadInitialState() {
        String inn = settings.inn == null || settings.inn.isBlank()
                ? AppDefaults.DEFAULT_INN
                : settings.inn.trim();
        innField.setText(inn);

        Path excel = null;
        if (settings.excelPath != null && !settings.excelPath.isBlank()) {
            Path saved = Path.of(settings.excelPath);
            if (Files.isRegularFile(saved)) {
                excel = saved;
            }
        }
        if (excel == null) {
            try {
                excel = ExcelPathFinder.find().orElse(null);
            } catch (Exception e) {
                // Поиск не должен валить старт UI
                excel = null;
            }
        }
        excelField.setText(excel == null ? "" : excel.toString());
    }

    private void browseExcel() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите «" + AppDefaults.EXCEL_FILE_NAME + "»");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx")
        );
        var file = chooser.showOpenDialog(stage);
        if (file != null) {
            excelField.setText(file.getAbsolutePath());
            persistSettings();
        }
    }

    private void openExcelFile() {
        String excelPath = excelField.getText() == null ? "" : excelField.getText().trim();
        if (excelPath.isBlank() || !Files.isRegularFile(Path.of(excelPath))) {
            alert("Сначала укажите существующий Excel-файл");
            return;
        }
        try {
            java.awt.Desktop.getDesktop().open(Path.of(excelPath).toFile());
        } catch (Exception e) {
            alert("Не удалось открыть файл: " + e.getMessage());
        }
    }

    private void startRun() {
        String inn = innField.getText() == null ? "" : innField.getText().trim();
        if (inn.isBlank()) {
            alert("Укажите ИНН");
            return;
        }

        String excelPath = excelField.getText() == null ? "" : excelField.getText().trim();
        if (excelPath.isBlank() || !Files.isRegularFile(Path.of(excelPath))) {
            alert("Укажите существующий Excel-файл («" + AppDefaults.EXCEL_FILE_NAME + "»)");
            return;
        }
        persistSettings();

        runButton.setDisable(true);
        showProgress(0, 1, "Открываю КАД…");
        setStatus("running", "Прогон…");

        Task<RunRecord> task = new Task<>() {
            @Override
            protected RunRecord call() {
                Instant started = Instant.now();
                String startedIso = started.toString();
                try (KadSearchClient client = new KadSearchClient(false)) {
                    updateMessage("Сессия КАД…");
                    Platform.runLater(() -> showProgress(0, 1, "Сессия КАД…"));
                    client.warmUp();

                    updateMessage("Поиск дел…");
                    Platform.runLater(() -> showProgress(0, 1, "Поиск дел…"));
                    List<KadCase> cases = client.searchByParticipant(inn, 1);

                    updateMessage("Карточки дел…");
                    cases = client.enrichWithHearings(cases, (current, total, msg) -> {
                        updateMessage(msg);
                        updateProgress(current, total);
                        Platform.runLater(() -> showProgress(current, total, msg));
                    });

                    long withHearing = cases.stream()
                            .filter(c -> c.nextHearing() != null && !c.nextHearing().isBlank())
                            .count();

                    updateMessage("Запись в Excel…");
                    Platform.runLater(() -> showProgress(1, 1, "Запись в Excel…"));
                    int written = StatusExcelWriter.write(Path.of(excelPath), cases, inn);
                    Instant finished = Instant.now();

                    return new RunRecord(
                            UUID.randomUUID().toString(),
                            startedIso,
                            finished.toString(),
                            inn,
                            excelPath,
                            "OK",
                            cases.size(),
                            Duration.between(started, finished).toMillis(),
                            "Дел: " + cases.size()
                                    + ", заседаний найдено: " + withHearing
                                    + ", строк в Excel: " + written
                                    + " → " + Path.of(excelPath).getFileName()
                    );
                } catch (Exception e) {
                    Instant finished = Instant.now();
                    return new RunRecord(
                            UUID.randomUUID().toString(),
                            startedIso,
                            finished.toString(),
                            inn,
                            excelPath,
                            "ERROR",
                            0,
                            Duration.between(started, finished).toMillis(),
                            e.getMessage() == null ? e.toString() : e.getMessage()
                    );
                }
            }
        };

        task.messageProperty().addListener((obs, o, n) -> {
            if (n != null && !n.isBlank()) {
                setStatus("running", n);
            }
        });

        task.setOnSucceeded(e -> {
            RunRecord record = task.getValue();
            historyStore.add(record);
            reloadHistory();
            runButton.setDisable(false);
            hideProgress();
            if ("OK".equals(record.status())) {
                setStatus("ok", record.message());
            } else {
                setStatus("error", record.message());
            }
        });

        task.setOnFailed(e -> {
            runButton.setDisable(false);
            hideProgress();
            Throwable err = task.getException();
            setStatus("error", err == null ? "Ошибка" : err.getMessage());
        });

        Thread t = new Thread(task, "kad-run");
        t.setDaemon(true);
        t.start();
    }

    private void reloadHistory() {
        historyRows.setAll(historyStore.loadNewestFirst());
    }

    private void persistSettings() {
        settings.inn = innField.getText() == null ? AppDefaults.DEFAULT_INN : innField.getText().trim();
        settings.excelPath = excelField.getText() == null ? "" : excelField.getText().trim();
        settings.save();
    }

    private void setStatus(String kind, String text) {
        statusLabel.getStyleClass().removeAll("status-ok", "status-error", "status-running", "status-muted");
        switch (kind) {
            case "ok" -> statusLabel.getStyleClass().add("status-ok");
            case "error" -> statusLabel.getStyleClass().add("status-error");
            case "running" -> statusLabel.getStyleClass().add("status-running");
            default -> statusLabel.getStyleClass().add("status-muted");
        }
        statusLabel.setText(text);
    }

    private void showProgress(int current, int total, String message) {
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        double pct = total <= 0 ? 0 : (double) current / (double) total;
        progressBar.setProgress(Math.max(0, Math.min(1, pct)));
        int percent = (int) Math.round(pct * 100);
        progressLabel.setText(message + "  —  " + current + " / " + total + " (" + percent + "%)");
    }

    private void hideProgress() {
        progressBar.setProgress(0);
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
        progressLabel.setText("");
    }

    private void alert(String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg);
        a.setHeaderText(null);
        a.setTitle("ArbitrParser");
        a.showAndWait();
    }

    private static String formatIso(String iso) {
        try {
            return TIME_FMT.format(Instant.parse(iso));
        } catch (Exception e) {
            return iso == null ? "" : iso;
        }
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + " мс";
        }
        long sec = ms / 1000;
        if (sec < 60) {
            return sec + " с";
        }
        return (sec / 60) + " м " + (sec % 60) + " с";
    }
}
