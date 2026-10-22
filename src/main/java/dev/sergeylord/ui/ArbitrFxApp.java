package dev.sergeylord.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** JavaFX entrypoint. */
public class ArbitrFxApp extends Application {

    @Override
    public void start(Stage stage) {
        MainView view = new MainView(stage);
        Scene scene = new Scene(view.getRoot(), 980, 640);
        var css = getClass().getResource("/dev/sergeylord/ui/app.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setTitle("ArbitrParser — КАД → Excel");
        stage.setMinWidth(820);
        stage.setMinHeight(520);
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
