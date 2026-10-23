package dev.sergeylord.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.util.List;

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
        stage.getIcons().setAll(loadAppIcons());
        stage.setMinWidth(820);
        stage.setMinHeight(520);
        stage.setScene(scene);
        stage.show();
    }

    /** Иконка приложения (орёл) в нескольких размерах. */
    private static List<Image> loadAppIcons() {
        String[] paths = {
                "/dev/sergeylord/ui/icons/apple-icon-57x57.png",
                "/dev/sergeylord/ui/icons/apple-icon-76x76.png",
                "/dev/sergeylord/ui/icons/apple-icon-120x120.png",
                "/dev/sergeylord/ui/icons/apple-icon-152x152.png",
                "/dev/sergeylord/ui/icons/apple-icon-180x180.png",
                "/dev/sergeylord/ui/icons/android-icon-192x192.png"
        };
        java.util.ArrayList<Image> icons = new java.util.ArrayList<>();
        for (String path : paths) {
            var url = ArbitrFxApp.class.getResource(path);
            if (url != null) {
                icons.add(new Image(url.toExternalForm()));
            }
        }
        return icons;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
