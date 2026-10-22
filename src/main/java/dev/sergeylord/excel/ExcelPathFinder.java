package dev.sergeylord.excel;

import dev.sergeylord.config.AppDefaults;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Ищет «Статус по искам.xlsx» в безопасных папках.
 * Не обходит весь user.home — там junction'ы вроде Application Data → AccessDenied.
 */
public final class ExcelPathFinder {

    private ExcelPathFinder() {
    }

    public static Optional<Path> find() {
        for (Path dir : candidateDirs()) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            Path direct = dir.resolve(AppDefaults.EXCEL_FILE_NAME);
            if (isFile(direct)) {
                return Optional.of(direct.toAbsolutePath().normalize());
            }
            Path inData = dir.resolve("data").resolve(AppDefaults.EXCEL_FILE_NAME);
            if (isFile(inData)) {
                return Optional.of(inData.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    private static boolean isFile(Path path) {
        try {
            return Files.isRegularFile(path);
        } catch (Exception e) {
            return false;
        }
    }

    private static List<Path> candidateDirs() {
        Set<Path> dirs = new LinkedHashSet<>();
        add(dirs, Path.of("").toAbsolutePath().normalize());
        add(dirs, Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());

        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            Path homePath = Path.of(home);
            // Только явные «обычные» папки — без обхода всего профиля
            add(dirs, homePath.resolve("Desktop"));
            add(dirs, homePath.resolve("Documents"));
            add(dirs, homePath.resolve("Downloads"));
            add(dirs, homePath.resolve("OneDrive"));
            add(dirs, homePath.resolve("OneDrive").resolve("Desktop"));
            add(dirs, homePath.resolve("OneDrive").resolve("Documents"));
            add(dirs, homePath.resolve("OneDrive").resolve("Downloads"));
        }

        // Рядом с jar / exe (jpackage: .../app → родитель = корень образа)
        try {
            var location = ExcelPathFinder.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                Path codeSource = Path.of(location.toURI());
                Path parent = codeSource.getParent();
                if (parent != null) {
                    add(dirs, parent);
                    if (parent.getParent() != null) {
                        add(dirs, parent.getParent());
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return new ArrayList<>(dirs);
    }

    private static void add(Set<Path> dirs, Path path) {
        if (path == null) {
            return;
        }
        try {
            dirs.add(path.toAbsolutePath().normalize());
        } catch (Exception ignored) {
        }
    }
}
