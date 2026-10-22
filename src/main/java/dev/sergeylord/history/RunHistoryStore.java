package dev.sergeylord.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.sergeylord.config.AppDefaults;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** История запусков в JSON рядом с данными приложения. */
public final class RunHistoryStore {

    private static final Type LIST_TYPE = new TypeToken<List<RunRecord>>() {
    }.getType();

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public RunHistoryStore() {
        this(defaultFile());
    }

    public RunHistoryStore(Path file) {
        this.file = file;
    }

    public static Path defaultFile() {
        Path dir = Path.of(System.getProperty("user.home"), "." + AppDefaults.APP_DIR_NAME);
        return dir.resolve("run-history.json");
    }

    public synchronized List<RunRecord> load() {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            List<RunRecord> list = gson.fromJson(json, LIST_TYPE);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public synchronized void add(RunRecord record) {
        List<RunRecord> list = load();
        list.add(0, record); // свежие сверху
        if (list.size() > 200) {
            list = new ArrayList<>(list.subList(0, 200));
        }
        save(list);
    }

    private void save(List<RunRecord> list) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(list), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save history: " + e.getMessage(), e);
        }
    }

    public List<RunRecord> loadNewestFirst() {
        return Collections.unmodifiableList(load());
    }
}
