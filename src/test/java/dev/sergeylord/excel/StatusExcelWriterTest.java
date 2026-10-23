package dev.sergeylord.excel;

import dev.sergeylord.kad.KadCase;
import dev.sergeylord.kad.Party;
import org.apache.poi.ss.usermodel.DataFormatter;
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
                        "150000.50"
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
            Row header = sheet.getRow(1);
            assertTrue(header != null);
            int caseCol = 1, sideCol = 2, otherCol = 3, courtCol = 14, numCol = 0;
            Row data = sheet.getRow(2);
            assertTrue(data != null);
            assertEquals("А40-1/2026", fmt.formatCellValue(data.getCell(caseCol)));
            assertEquals("Ответчик", fmt.formatCellValue(data.getCell(sideCol)));
            assertEquals("ООО Друг", fmt.formatCellValue(data.getCell(otherCol)));
            assertEquals("АС города Москвы", fmt.formatCellValue(data.getCell(courtCol)));
            assertEquals("1", fmt.formatCellValue(data.getCell(numCol)));
            assertEquals(150000.5, data.getCell(9).getNumericCellValue(), 0.001);
        }
        Files.deleteIfExists(copy);
    }
}
