package dev.sergeylord.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Сохранённые настройки пользователя (ИНН, путь к Excel). */
public final class UserSettings {

    public String inn = AppDefaults.DEFAULT_INN;
    public String excelPath = "";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), "." + AppDefaults.APP_DIR_NAME, "settings.json");
    }

    public static UserSettings load() {
        Path file = defaultFile();
        if (!Files.isRegularFile(file)) {
            return new UserSettings();
        }
        try {
            UserSettings s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), UserSettings.class);
            return s == null ? new UserSettings() : s;
        } catch (Exception e) {
            return new UserSettings();
        }
    }

    public void save() {
        Path file = defaultFile();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save settings: " + e.getMessage(), e);
        }
    }
}
