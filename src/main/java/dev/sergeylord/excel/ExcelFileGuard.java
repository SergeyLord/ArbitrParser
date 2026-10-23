package dev.sergeylord.excel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Проверка/освобождение xlsx: если файл открыт в Excel — закрыть workbook с сохранением.
 */
public final class ExcelFileGuard {

    private ExcelFileGuard() {
    }

    /**
     * @return true, если файл можно открыть на запись (не занят другим процессом)
     */
    public static boolean isWritable(Path excelFile) {
        if (excelFile == null || !Files.isRegularFile(excelFile)) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(excelFile.toFile(), "rw");
             FileChannel channel = raf.getChannel();
             FileLock lock = channel.tryLock()) {
            return lock != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Если файл занят Excel — закрывает именно эту книгу с сохранением изменений
     * ({@code Workbook.Close(SaveChanges:=true)}), без диалогов.
     *
     * @return true, если после попытки файл доступен на запись
     */
    public static boolean ensureWritable(Path excelFile) {
        if (isWritable(excelFile)) {
            return true;
        }
        closeWorkbookSaving(excelFile);
        for (int i = 0; i < 10; i++) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (isWritable(excelFile)) {
                return true;
            }
        }
        return isWritable(excelFile);
    }

    private static void closeWorkbookSaving(Path excelFile) {
        Path absolute = excelFile.toAbsolutePath().normalize();
        String path = absolute.toString().replace("'", "''");
        // Close($true) = сохранить изменения; DisplayAlerts=$false = без «Сохранить?»
        String ps = """
                $ErrorActionPreference = 'Stop'
                $target = [System.IO.Path]::GetFullPath('%s')
                $xl = $null
                try {
                  $xl = [Runtime.InteropServices.Marshal]::GetActiveObject('Excel.Application')
                } catch {
                  exit 0
                }
                try {
                  $xl.DisplayAlerts = $false
                  $toClose = @()
                  foreach ($wb in @($xl.Workbooks)) {
                    try {
                      $full = [System.IO.Path]::GetFullPath([string]$wb.FullName)
                      if ($full -ieq $target) { $toClose += $wb }
                    } catch {}
                  }
                  foreach ($wb in $toClose) {
                    $wb.Close($true)
                  }
                } finally {
                  if ($xl -ne $null) {
                    try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($xl) } catch {}
                  }
                }
                """.formatted(path);

        try {
            Path script = Files.createTempFile("arbitr-close-excel-", ".ps1");
            Files.writeString(script, ps, StandardCharsets.UTF_8);
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-ExecutionPolicy", "Bypass",
                    "-File", script.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                output = sb.toString().trim();
            }
            boolean finished = p.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                System.out.println("Excel close timed out");
            } else if (p.exitValue() != 0 && !output.isBlank()) {
                System.out.println("Excel close: " + output);
            } else {
                System.out.println("Excel workbook close requested for " + absolute.getFileName());
            }
            Files.deleteIfExists(script);
        } catch (Exception e) {
            System.out.println("Excel close failed: " + e.getMessage());
        }
    }
}
