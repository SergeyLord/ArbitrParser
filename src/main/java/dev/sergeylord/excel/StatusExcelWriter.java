package dev.sergeylord.excel;

import dev.sergeylord.kad.KadCase;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.RichTextString;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Обновляет xlsx по ключу «№ дела»:
 * — совпало → skip;
 * — изменилось → перезапись + лёгкий красный + комментарий «БЫЛО: …»;
 * — новая строка → заполнение + красный на все записанные ячейки;
 * — есть в Excel, нет в КАД → серая строка.
 */
public final class StatusExcelWriter {

    private static final DataFormatter FMT = new DataFormatter(Locale.ROOT);
    private static final DateTimeFormatter COMMENT_DAY =
            DateTimeFormatter.ofPattern("dd.MM");

    /** Бухгалтерский формат цены. */
    private static final String CLAIM_SUM_FORMAT =
            "_-* # ##0,00_-;-* # ##0,00_-;_-* \"-\"??_-;_-@_-";

    private enum Mark {
        NONE,
        RED,
        GRAY
    }

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

            int firstDataRow = headerRowIdx + 1;
            int caseCol = cols.get("caseNumber");
            LinkedHashMap<String, Integer> byCase = indexByCaseNumber(sheet, firstDataRow, caseCol);
            int nextRowNum = nextSequentialNumber(sheet, firstDataRow, cols.getOrDefault("rowNum", -1));
            int appendAt = nextEmptyDataRow(sheet, firstDataRow, caseCol);

            short headerLastCol = sheet.getRow(headerRowIdx) != null
                    ? sheet.getRow(headerRowIdx).getLastCellNum()
                    : 20;
            if (headerLastCol < 1) {
                headerLastCol = 20;
            }

            StyleKit styles = new StyleKit(workbook);
            short claimFormat = workbook.createDataFormat().getFormat(CLAIM_SUM_FORMAT);

            Set<String> seenInKad = new HashSet<>();
            int processed = 0;

            for (KadCase kadCase : cases) {
                CaseRowMapper.MappedRow mapped = CaseRowMapper.map(kadCase, ourInn, nextRowNum);
                String key = caseKey(mapped.caseNumber());
                if (key.isBlank()) {
                    continue;
                }
                seenInKad.add(key);

                Integer existing = byCase.get(key);
                if (existing == null) {
                    Row row = sheet.getRow(appendAt);
                    if (row == null) {
                        row = sheet.createRow(appendAt);
                    }
                    writeNewRow(row, cols, mapped, styles, claimFormat, headerLastCol);
                    byCase.put(key, appendAt);
                    appendAt++;
                    nextRowNum++;
                } else {
                    Row row = sheet.getRow(existing);
                    if (row == null) {
                        row = sheet.createRow(existing);
                    }
                    // дело снова в КАД — снимаем серую пометку «пропало»
                    clearGrayMarks(row, styles, headerLastCol);
                    updateExistingRow(row, cols, mapped, styles, claimFormat);
                }
                processed++;
            }

            for (Map.Entry<String, Integer> e : byCase.entrySet()) {
                if (seenInKad.contains(e.getKey())) {
                    continue;
                }
                Row row = sheet.getRow(e.getValue());
                if (row != null) {
                    paintRow(row, styles, Mark.GRAY, headerLastCol);
                }
            }

            enableWrapText(sheet);

            try (OutputStream out = Files.newOutputStream(excelFile)) {
                workbook.write(out);
            }
            return processed;
        }
    }

    private static void writeNewRow(
            Row row,
            Map<String, Integer> cols,
            CaseRowMapper.MappedRow mapped,
            StyleKit styles,
            short claimFormat,
            short headerLastCol
    ) {
        // сначала вся строка красным, потом значения (чтобы формат цены не сбросился)
        paintRow(row, styles, Mark.RED, headerLastCol);
        if (cols.containsKey("rowNum")) {
            writeInt(row, cols.get("rowNum"), mapped.rowNumber(), styles, Mark.RED);
        }
        writeText(row, cols.get("caseNumber"), mapped.caseNumber(), styles, Mark.RED);
        writeText(row, cols.get("ourSide"), mapped.ourSide(), styles, Mark.RED);
        writeText(row, cols.get("otherSides"), mapped.otherSides(), styles, Mark.RED);
        writeText(row, cols.get("court"), mapped.court(), styles, Mark.RED);
        if (cols.containsKey("hearing")) {
            writeText(row, cols.get("hearing"), mapped.nextHearing(), styles, Mark.RED);
        }
        if (cols.containsKey("claimSum")) {
            writeClaimSum(row, cols.get("claimSum"), mapped.claimSum(), styles, claimFormat, Mark.RED);
        }
    }

    private static void updateExistingRow(
            Row row,
            Map<String, Integer> cols,
            CaseRowMapper.MappedRow mapped,
            StyleKit styles,
            short claimFormat
    ) {
        // № п/п не трогаем при обновлении
        upsertText(row, cols.get("caseNumber"), mapped.caseNumber(), styles);
        upsertText(row, cols.get("ourSide"), mapped.ourSide(), styles);
        upsertText(row, cols.get("otherSides"), mapped.otherSides(), styles);
        upsertText(row, cols.get("court"), mapped.court(), styles);
        if (cols.containsKey("hearing")) {
            upsertText(row, cols.get("hearing"), mapped.nextHearing(), styles);
        }
        if (cols.containsKey("claimSum")) {
            upsertClaimSum(row, cols.get("claimSum"), mapped.claimSum(), styles, claimFormat);
        }
    }

    private static void upsertText(Row row, int col, String newValue, StyleKit styles) {
        String oldVal = cellText(row, col);
        if (sameText(oldVal, newValue)) {
            return;
        }
        writeText(row, col, newValue, styles, Mark.RED);
        setWasComment(row.getCell(col), oldVal);
    }

    private static void upsertClaimSum(
            Row row, int col, String newValue, StyleKit styles, short claimFormat
    ) {
        Cell existing = row.getCell(col);
        if (sameClaimSum(existing, newValue)) {
            return;
        }
        String oldVal = existing == null ? "" : FMT.formatCellValue(existing).replace('\u00A0', ' ').trim();
        writeClaimSum(row, col, newValue, styles, claimFormat, Mark.RED);
        setWasComment(row.getCell(col), oldVal);
    }

    /**
     * Комментарий «Было ДД.ММ: …».
     * В Excel у ячейки только один comment — если уже есть, дописываем строку (ADD).
     */
    private static void setWasComment(Cell cell, String oldValue) {
        if (cell == null) {
            return;
        }
        String shown = (oldValue == null || oldValue.isBlank()) ? "—" : oldValue.trim();
        String line = "Было " + LocalDate.now().format(COMMENT_DAY) + ": " + shown;
        Sheet sheet = cell.getSheet();
        CreationHelper helper = sheet.getWorkbook().getCreationHelper();

        Comment existing = cell.getCellComment();
        if (existing != null) {
            String prev = existing.getString() == null ? "" : existing.getString().getString();
            String merged = prev.isBlank() ? line : prev.trim() + "\n" + line;
            existing.setString(helper.createRichTextString(merged));
            if (existing.getAuthor() == null || existing.getAuthor().isBlank()) {
                existing.setAuthor("ArbitrParser");
            }
            return;
        }

        Drawing<?> drawing = sheet.createDrawingPatriarch();
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setCol1(cell.getColumnIndex());
        anchor.setCol2(cell.getColumnIndex() + 3);
        anchor.setRow1(cell.getRowIndex());
        anchor.setRow2(cell.getRowIndex() + 4);
        Comment comment = drawing.createCellComment(anchor);
        RichTextString str = helper.createRichTextString(line);
        comment.setString(str);
        comment.setAuthor("ArbitrParser");
        cell.setCellComment(comment);
    }

    private static void writeText(Row row, int col, String value, StyleKit styles, Mark mark) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            cell = row.createCell(col, CellType.STRING);
        }
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(styles.styled(cell.getCellStyle(), mark, (short) -1));
    }

    private static void writeInt(Row row, int col, int value, StyleKit styles, Mark mark) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            cell = row.createCell(col, CellType.NUMERIC);
        }
        cell.setCellValue(value);
        cell.setCellStyle(styles.styled(cell.getCellStyle(), mark, (short) -1));
    }

    private static void writeClaimSum(
            Row row, int col, String value, StyleKit styles, short claimFormat, Mark mark
    ) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            cell = row.createCell(col, CellType.NUMERIC);
        }
        if (value == null || value.isBlank()) {
            cell.setBlank();
            cell.setCellStyle(styles.styled(cell.getCellStyle(), mark, claimFormat));
            return;
        }
        try {
            double num = Double.parseDouble(value.replace(" ", "").replace(',', '.'));
            cell.setCellValue(num);
            cell.setCellStyle(styles.styled(cell.getCellStyle(), mark, claimFormat));
        } catch (NumberFormatException e) {
            cell.setCellValue(value);
            cell.setCellStyle(styles.styled(cell.getCellStyle(), mark, (short) -1));
        }
    }

    private static void paintRow(Row row, StyleKit styles, Mark mark, short lastCol) {
        for (int c = 0; c < lastCol; c++) {
            Cell cell = row.getCell(c);
            if (cell == null) {
                cell = row.createCell(c, CellType.BLANK);
            }
            cell.setCellStyle(styles.styled(cell.getCellStyle(), mark, (short) -1));
        }
    }

    private static void clearGrayMarks(Row row, StyleKit styles, short lastCol) {
        for (int c = 0; c < lastCol; c++) {
            Cell cell = row.getCell(c);
            if (cell == null) {
                continue;
            }
            CellStyle cs = cell.getCellStyle();
            if (cs.getFillPattern() == FillPatternType.SOLID_FOREGROUND
                    && cs.getFillForegroundColor() == IndexedColors.GREY_25_PERCENT.getIndex()) {
                cell.setCellStyle(styles.styled(cs, Mark.NONE, (short) -1));
            }
        }
    }

    private static LinkedHashMap<String, Integer> indexByCaseNumber(
            Sheet sheet, int firstDataRow, int caseCol
    ) {
        LinkedHashMap<String, Integer> map = new LinkedHashMap<>();
        int last = sheet.getLastRowNum();
        for (int r = firstDataRow; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String key = caseKey(cellText(row, caseCol));
            if (!key.isBlank()) {
                map.putIfAbsent(key, r);
            }
        }
        return map;
    }

    private static int nextEmptyDataRow(Sheet sheet, int firstDataRow, int caseCol) {
        int last = sheet.getLastRowNum();
        int max = firstDataRow - 1;
        for (int r = firstDataRow; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            if (!caseKey(cellText(row, caseCol)).isBlank()) {
                max = r;
            }
        }
        return max + 1;
    }

    private static int nextSequentialNumber(Sheet sheet, int firstDataRow, int rowNumCol) {
        int max = 0;
        if (rowNumCol < 0) {
            return 1;
        }
        int last = sheet.getLastRowNum();
        for (int r = firstDataRow; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Cell cell = row.getCell(rowNumCol);
            if (cell == null) {
                continue;
            }
            if (cell.getCellType() == CellType.NUMERIC) {
                max = Math.max(max, (int) cell.getNumericCellValue());
            } else {
                try {
                    String t = FMT.formatCellValue(cell).trim();
                    if (!t.isBlank()) {
                        max = Math.max(max, Integer.parseInt(t.replaceAll("\\D", "")));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return max + 1;
    }

    private static String cellText(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            return "";
        }
        return FMT.formatCellValue(cell).replace('\u00A0', ' ').trim();
    }

    private static String caseKey(String caseNumber) {
        if (caseNumber == null) {
            return "";
        }
        return caseNumber.replace('\u00A0', ' ').trim().toLowerCase(Locale.ROOT);
    }

    private static boolean sameText(String a, String b) {
        return normalizeCompare(a).equals(normalizeCompare(b));
    }

    private static String normalizeCompare(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static boolean sameClaimSum(Cell cell, String newValue) {
        Double neu = parseClaim(newValue);
        Double old = readClaim(cell);
        if (neu == null && old == null) {
            return true;
        }
        if (neu == null || old == null) {
            return false;
        }
        return Math.abs(neu - old) < 0.005;
    }

    private static Double parseClaim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.replace('\u00A0', ' ').replace(" ", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double readClaim(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        return parseClaim(FMT.formatCellValue(cell));
    }

    /** Кэш стилей: база + метка (+ опционально числовой формат). */
    private static final class StyleKit {
        private final Workbook workbook;
        private final Map<String, CellStyle> cache = new HashMap<>();

        StyleKit(Workbook workbook) {
            this.workbook = workbook;
        }

        CellStyle styled(CellStyle base, Mark mark, short dataFormat) {
            short baseIdx = base == null ? 0 : base.getIndex();
            String key = baseIdx + "|" + mark + "|" + dataFormat;
            return cache.computeIfAbsent(key, ignored -> {
                CellStyle style = workbook.createCellStyle();
                if (base != null) {
                    style.cloneStyleFrom(base);
                }
                style.setWrapText(true);
                style.setVerticalAlignment(VerticalAlignment.TOP);
                applyFullBorder(style);
                if (dataFormat >= 0) {
                    style.setDataFormat(dataFormat);
                }
                if (mark == Mark.RED) {
                    style.setFillForegroundColor(IndexedColors.ROSE.getIndex());
                    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                } else if (mark == Mark.GRAY) {
                    style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
                    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                } else {
                    style.setFillPattern(FillPatternType.NO_FILL);
                }
                return style;
            });
        }
    }

    private static void applyFullBorder(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        short color = IndexedColors.GREY_50_PERCENT.getIndex();
        style.setTopBorderColor(color);
        style.setBottomBorderColor(color);
        style.setLeftBorderColor(color);
        style.setRightBorderColor(color);
    }

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
                    applyFullBorder(style);
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
        map.putIfAbsent("rowNum", 0);
        map.putIfAbsent("caseNumber", 1);
        map.putIfAbsent("ourSide", 2);
        map.putIfAbsent("otherSides", 3);
        map.putIfAbsent("claimSum", 9);
        map.putIfAbsent("court", 14);
        map.putIfAbsent("hearing", 15);
        return map;
    }

    private static boolean isCaseNumberHeader(String v) {
        String s = v.replace("№", "n").replace("no.", "n").replace("номер", "n");
        return (s.contains("дела") && (s.contains("n") || v.contains("№"))) || s.equals("n дела");
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\u00A0', ' ').trim().toLowerCase(Locale.ROOT);
    }
}
