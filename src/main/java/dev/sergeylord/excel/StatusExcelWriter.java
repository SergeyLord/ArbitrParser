package dev.sergeylord.excel;

import dev.sergeylord.kad.KadCase;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Пишет в xlsx только заполняемые колонки (см. ColumnMapping без «—»).
 * Остальные ячейки строки не трогает при обновлении… для MVP: перезаписывает
 * блок данных с строки после заголовка (полная перезапись строк дел).
 */
public final class StatusExcelWriter {

    private static final DataFormatter FMT = new DataFormatter(Locale.ROOT);

    private StatusExcelWriter() {
    }

    public static int write(Path excelFile, List<KadCase> cases, String ourInn) throws IOException {
        if (excelFile == null || !Files.isRegularFile(excelFile)) {
            throw new IOException("Excel-файл не найден: " + excelFile);
        }

        Workbook workbook;
        try (InputStream in = Files.newInputStream(excelFile)) {
            workbook = WorkbookFactory.create(in);
        }

        try (workbook) {
            Sheet sheet = pickSheet(workbook);
            int headerRowIdx = findHeaderRow(sheet);
            if (headerRowIdx < 0) {
                throw new IOException("Не найдена строка заголовков (ожидался столбец «№ дела»). "
                        + "sheet=" + sheet.getSheetName()
                        + " lastRow=" + sheet.getLastRowNum());
            }

            Map<String, Integer> cols = mapColumns(sheet.getRow(headerRowIdx));
            requireCol(cols, "caseNumber");
            requireCol(cols, "ourSide");
            requireCol(cols, "otherSides");
            requireCol(cols, "court");
            // № п\п желателен, но если нет — просто не пишем

            int firstDataRow = headerRowIdx + 1;
            clearDataRows(sheet, firstDataRow);

            CellStyle claimSumStyle = createClaimSumStyle(workbook);

            int written = 0;
            for (KadCase kadCase : cases) {
                CaseRowMapper.MappedRow mapped = CaseRowMapper.map(kadCase, ourInn, written + 1);
                Row row = sheet.getRow(firstDataRow + written);
                if (row == null) {
                    row = sheet.createRow(firstDataRow + written);
                }
                if (cols.containsKey("rowNum")) {
                    setCell(row, cols.get("rowNum"), mapped.rowNumber());
                }
                setCell(row, cols.get("caseNumber"), mapped.caseNumber());
                setCell(row, cols.get("ourSide"), mapped.ourSide());
                setCell(row, cols.get("otherSides"), mapped.otherSides());
                setCell(row, cols.get("court"), mapped.court());
                if (cols.containsKey("hearing")) {
                    setCell(row, cols.get("hearing"), mapped.nextHearing());
                }
                if (cols.containsKey("claimSum")) {
                    setClaimSumCell(row, cols.get("claimSum"), mapped.claimSum(), claimSumStyle);
                }
                written++;
            }

            enableWrapText(sheet);

            try (OutputStream out = Files.newOutputStream(excelFile)) {
                workbook.write(out);
            }
            return written;
        }
    }

    /** Включает перенос текста во всех ячейках используемой области листа. */
    private static void enableWrapText(Sheet sheet) {
        Workbook workbook = sheet.getWorkbook();
        Map<Short, CellStyle> wrappedByIndex = new HashMap<>();
        int lastRow = sheet.getLastRowNum();
        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            short lastCell = row.getLastCellNum();
            if (lastCell < 0) {
                continue;
            }
            for (int c = 0; c < lastCell; c++) {
                Cell cell = row.getCell(c);
                if (cell == null) {
                    continue;
                }
                CellStyle orig = cell.getCellStyle();
                short idx = orig.getIndex();
                CellStyle wrapped = wrappedByIndex.computeIfAbsent(idx, ignored -> {
                    CellStyle style = workbook.createCellStyle();
                    style.cloneStyleFrom(orig);
                    style.setWrapText(true);
                    style.setVerticalAlignment(VerticalAlignment.TOP);
                    return style;
                });
                cell.setCellStyle(wrapped);
            }
        }
    }

    private static Sheet pickSheet(Workbook workbook) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            if (findHeaderRow(sheet) >= 0) {
                return sheet;
            }
        }
        return workbook.getSheetAt(0);
    }

    private static void requireCol(Map<String, Integer> cols, String key) throws IOException {
        if (!cols.containsKey(key)) {
            throw new IOException("В Excel нет нужной колонки для поля: " + key);
        }
    }

    private static int findHeaderRow(Sheet sheet) {
        int last = Math.min(Math.max(sheet.getLastRowNum(), 5), 40);
        for (int r = 0; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            int hits = 0;
            boolean hasCase = false;
            for (Cell cell : row) {
                String v = normalize(FMT.formatCellValue(cell));
                if (v.isBlank()) {
                    continue;
                }
                if (isCaseNumberHeader(v)) {
                    hasCase = true;
                    hits++;
                }
                if (v.contains("сторон") || v.contains("арбитраж") || v.contains("п\\п") || v.contains("п/п")) {
                    hits++;
                }
            }
            if (hasCase && hits >= 2) {
                return r;
            }
        }
        // fallback: строка 2 в шаблоне (1-based) = index 1
        if (sheet.getRow(1) != null) {
            return 1;
        }
        return -1;
    }

    private static Map<String, Integer> mapColumns(Row header) {
        Map<String, Integer> map = new HashMap<>();
        if (header == null) {
            return map;
        }
        short last = header.getLastCellNum();
        for (int c = 0; c < last; c++) {
            Cell cell = header.getCell(c);
            String raw = normalize(FMT.formatCellValue(cell));
            if (raw.isBlank()) {
                continue;
            }
            if (raw.contains("п\\п") || raw.contains("п/п") || raw.startsWith("№ п") || raw.equals("n п\\п")) {
                map.putIfAbsent("rowNum", c);
            } else if (isCaseNumberHeader(raw)) {
                map.putIfAbsent("caseNumber", c);
            } else if (raw.contains("сторон") && (raw.contains("трэ") || raw.contains("трейд")
                    || raw.contains("ре ") || raw.contains("«ре") || raw.contains("\"ре"))) {
                map.putIfAbsent("ourSide", c);
            } else if (raw.contains("другая сторона") || (raw.contains("третьи") && raw.contains("сторон"))) {
                map.putIfAbsent("otherSides", c);
            } else if (raw.contains("арбитражный суд") || raw.equals("суд")) {
                map.putIfAbsent("court", c);
            } else if (raw.contains("очередного заседания") || raw.contains("дата и время заседания")
                    || (raw.contains("заседани") && raw.contains("дата"))) {
                map.putIfAbsent("hearing", c);
            } else if (raw.contains("цена иска") || raw.contains("сумма иска")
                    || (raw.contains("исков") && raw.contains("руб"))) {
                map.putIfAbsent("claimSum", c);
            }
        }
        // Жёсткие позиции шаблона TEMPLATE, если заголовки не распознались
        map.putIfAbsent("rowNum", 0);
        map.putIfAbsent("caseNumber", 1);
        map.putIfAbsent("ourSide", 2);
        map.putIfAbsent("otherSides", 3);
        map.putIfAbsent("claimSum", 9); // колонка J — Цена иска, руб
        map.putIfAbsent("court", 14); // колонка O
        map.putIfAbsent("hearing", 15); // колонка P — Дата очередного заседания
        return map;
    }

    private static boolean isCaseNumberHeader(String v) {
        String s = v.replace("№", "n").replace("no.", "n").replace("номер", "n");
        return (s.contains("дела") && (s.contains("n") || v.contains("№"))) || s.equals("n дела");
    }

    private static void clearDataRows(Sheet sheet, int fromRowInclusive) {
        int last = sheet.getLastRowNum();
        for (int r = last; r >= fromRowInclusive; r--) {
            Row row = sheet.getRow(r);
            if (row != null) {
                sheet.removeRow(row);
            }
        }
    }

    private static void setCell(Row row, int col, String value) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            cell = row.createCell(col, CellType.STRING);
        }
        cell.setCellValue(value == null ? "" : value);
    }

    private static void setCell(Row row, int col, int value) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            cell = row.createCell(col, CellType.NUMERIC);
        }
        cell.setCellValue(value);
    }

    /** Бухгалтерский формат цены: пробел — тысячи, запятая — копейки. */
    private static final String CLAIM_SUM_FORMAT =
            "_-* # ##0,00_-;-* # ##0,00_-;_-* \"-\"??_-;_-@_-";

    private static CellStyle createClaimSumStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setDataFormat(workbook.createDataFormat().getFormat(CLAIM_SUM_FORMAT));
        return style;
    }

    private static void setClaimSumCell(Row row, int col, String value, CellStyle claimSumStyle) {
        if (value == null || value.isBlank()) {
            setCell(row, col, "");
            return;
        }
        try {
            double num = Double.parseDouble(value.replace(" ", "").replace(',', '.'));
            Cell cell = row.getCell(col);
            if (cell == null) {
                cell = row.createCell(col, CellType.NUMERIC);
            }
            cell.setCellValue(num);
            cell.setCellStyle(claimSumStyle);
        } catch (NumberFormatException e) {
            setCell(row, col, value);
        }
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\u00A0', ' ').trim().toLowerCase(Locale.ROOT);
    }
}
