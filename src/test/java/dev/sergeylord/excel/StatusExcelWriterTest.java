package dev.sergeylord.excel;

import dev.sergeylord.kad.KadCase;
import dev.sergeylord.kad.Party;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusExcelWriterTest {

    @Test
    void writesMappedColumnsIntoTemplateCopy() throws Exception {
        Path desktopTemplate = Path.of("c:/Users/SChuevskiy/Desktop/TEMPLATE.xlsx");
        if (!Files.isRegularFile(desktopTemplate)) {
            return;
        }
        Path copy = Files.createTempFile("status-test-", ".xlsx");
        Files.copy(desktopTemplate, copy, StandardCopyOption.REPLACE_EXISTING);
        clearDataRows(copy); // чистый лист: upsert не должен цеплять старые дела из TEMPLATE

        List<KadCase> cases = List.of(
                new KadCase(
                        "id-1",
                        "А40-1/2026",
                        "G",
                        "АС города Москвы",
                        "Иванов И. И.",
                        "01.01.2026",
                        List.of(new Party("ООО Друг", "123")),
                        List.of(new Party("ООО РЕ ТРЭЙДИНГ", "7725776121")),
                        "15.09.2026, 10:30",
                        "150000.50",
                        "есть (1)"
                )
        );

        int written = StatusExcelWriter.write(copy, cases, "7725776121");
        assertEquals(1, written);

        try (InputStream in = Files.newInputStream(copy);
             var wb = WorkbookFactory.create(in)) {
            Sheet sheet = null;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetAt(i).getLastRowNum() > 0) {
                    sheet = wb.getSheetAt(i);
                    break;
                }
            }
            assertTrue(sheet != null);
            DataFormatter fmt = new DataFormatter();
            int caseCol = 1, sideCol = 2, otherCol = 3, courtCol = 14, numCol = 0;
            Row data = sheet.getRow(2);
            assertTrue(data != null);
            assertEquals("А40-1/2026", fmt.formatCellValue(data.getCell(caseCol)));
            assertEquals("Ответчик", fmt.formatCellValue(data.getCell(sideCol)));
            assertEquals("ООО Друг", fmt.formatCellValue(data.getCell(otherCol)));
            assertEquals("АС города Москвы", fmt.formatCellValue(data.getCell(courtCol)));
            assertEquals("1", fmt.formatCellValue(data.getCell(numCol)));
            assertEquals("15.09.2026, 10:30", fmt.formatCellValue(data.getCell(15)));
            assertEquals(150000.5, data.getCell(9).getNumericCellValue(), 0.001);
            assertEquals(FillPatternType.SOLID_FOREGROUND, data.getCell(caseCol).getCellStyle().getFillPattern());
            assertEquals(IndexedColors.ROSE.getIndex(), data.getCell(caseCol).getCellStyle().getFillForegroundColor());
        }

        // повторный прогон без изменений — строка не дублируется
        assertEquals(1, StatusExcelWriter.write(copy, cases, "7725776121"));
        try (InputStream in = Files.newInputStream(copy);
             var wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetAt(i).getLastRowNum() > 0) {
                    sheet = wb.getSheetAt(i);
                    break;
                }
            }
            int dataRows = 0;
            for (int r = 2; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row != null && row.getCell(1) != null
                        && !new DataFormatter().formatCellValue(row.getCell(1)).isBlank()) {
                    dataRows++;
                }
            }
            assertEquals(1, dataRows);
        }

        // дело пропало из КАД → серая строка
        assertEquals(0, StatusExcelWriter.write(copy, List.of(), "7725776121"));
        try (InputStream in = Files.newInputStream(copy);
             var wb = WorkbookFactory.create(in)) {
            Sheet sheet = null;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetAt(i).getLastRowNum() > 0) {
                    sheet = wb.getSheetAt(i);
                    break;
                }
            }
            assertTrue(sheet != null);
            Row data = sheet.getRow(2);
            assertTrue(data != null);
            assertEquals(FillPatternType.SOLID_FOREGROUND, data.getCell(1).getCellStyle().getFillPattern());
            assertEquals(IndexedColors.GREY_25_PERCENT.getIndex(),
                    data.getCell(1).getCellStyle().getFillForegroundColor());
            assertEquals("А40-1/2026", new DataFormatter().formatCellValue(data.getCell(1)));
        }

        Files.deleteIfExists(copy);
    }

    private static void clearDataRows(Path xlsx) throws Exception {
        try (InputStream in = Files.newInputStream(xlsx);
             var wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetAt(i).getLastRowNum() > 0) {
                    sheet = wb.getSheetAt(i);
                    break;
                }
            }
            int header = 1;
            for (int r = sheet.getLastRowNum(); r > header; r--) {
                Row row = sheet.getRow(r);
                if (row != null) {
                    sheet.removeRow(row);
                }
            }
            try (var out = Files.newOutputStream(xlsx)) {
                wb.write(out);
            }
        }
    }
}
