package dev.sergeylord;

import dev.sergeylord.ui.ArbitrFxApp;
import javafx.application.Application;

/**
 * Точка входа: по умолчанию JavaFX UI.
 *
 *   gradlew run
 *   gradlew run --args="--console 7725776121"   // старый консольный режим
 */
public class Main {

    public static void main(String[] args) {
        if (hasFlag(args, "--console")) {
            ConsoleRunner.run(args);
            return;
        }
        Application.launch(ArbitrFxApp.class, args);
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) {
            if (flag.equalsIgnoreCase(a)) {
                return true;
            }
        }
        return false;
    }
}
