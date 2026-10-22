package dev.sergeylord.excel;

import dev.sergeylord.config.AppDefaults;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Ищет «Статус по искам.xlsx» в безопасных папках.
 * Рабочий стол — в приоритете (Desktop / Рабочий стол / OneDrive).
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

        String home = firstNonBlank(System.getProperty("user.home"), System.getenv("USERPROFILE"));
        if (home != null) {
            Path homePath = Path.of(home);
            // Сначала рабочий стол (EN / RU / OneDrive)
            add(dirs, homePath.resolve("Desktop"));
            add(dirs, homePath.resolve("Рабочий стол"));
            add(dirs, homePath.resolve("OneDrive").resolve("Desktop"));
            add(dirs, homePath.resolve("OneDrive").resolve("Рабочий стол"));
            // Иногда OneDrive в другом корне
            String oneDrive = System.getenv("OneDrive");
            if (oneDrive != null && !oneDrive.isBlank()) {
                Path od = Path.of(oneDrive);
                add(dirs, od.resolve("Desktop"));
                add(dirs, od.resolve("Рабочий стол"));
            }
        }

        // Общий рабочий стол
        String publicDir = System.getenv("PUBLIC");
        if (publicDir != null && !publicDir.isBlank()) {
            add(dirs, Path.of(publicDir).resolve("Desktop"));
            add(dirs, Path.of(publicDir).resolve("Рабочий стол"));
        }

        add(dirs, Path.of("").toAbsolutePath().normalize());
        add(dirs, Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());

        if (home != null) {
            Path homePath = Path.of(home);
            add(dirs, homePath.resolve("Documents"));
            add(dirs, homePath.resolve("Документы"));
            add(dirs, homePath.resolve("Downloads"));
            add(dirs, homePath.resolve("Загрузки"));
            add(dirs, homePath.resolve("OneDrive"));
            add(dirs, homePath.resolve("OneDrive").resolve("Documents"));
            add(dirs, homePath.resolve("OneDrive").resolve("Документы"));
        }

        // Рядом с jar / exe
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

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
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
