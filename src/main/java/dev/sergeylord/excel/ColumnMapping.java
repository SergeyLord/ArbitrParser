package dev.sergeylord.excel;

import java.util.List;

/**
 * Текущие правила соответствия: колонка Excel ↔ источник в КАД.
 * Excel обновляется по № дела (не пересобирается с нуля).
 */
public final class ColumnMapping {

    public record Rule(String excelColumn, String kadSource) {
    }

    private ColumnMapping() {
    }

    public static List<Rule> currentRules() {
        return List.of(
                rule("№ п\\п", "✓ порядковый номер при записи"),
                rule("№ дела", "✓ номер дела из списка КАД"),
                rule("ООО «РЕ Трэйдинг» сторона", "✓ роль по ИНН: Истец / Ответчик / Третье лицо"),
                rule("Другая сторона спора, третьи лица", "✓ остальные участники из списка"),
                rule("Город", "—"),
                rule("Название ТЦ", "—"),
                rule("Бренд", "—"),
                rule("Юрист", "—"),
                rule("LM", "—"),
                rule("Цена иска, руб", "✓ плюсик .b-collapse.js-collapse → .additional-info «Сумма исковых требований»"),
                rule("Исковые требования, расшифровка", "—"),
                rule("Подтверждена сумма иска да/нет", "—"),
                rule("Задолженность на текущий момент", "—"),
                rule("Предмет спора", "—"),
                rule("Арбитражный суд", "✓ суд из списка КАД"),
                rule("Дата очередного заседания", "✓ .b-instanceAdditional «Следующее заседание: …»"),
                rule("Дата подачи доп документов", "—"),
                rule("Документы", "✓ PDF из хронологии (a.js-case-result-text--doc_link / Подписано) → папка «kad.arbitr документы»"),
                rule("Заседание", "—"),
                rule("Статус", "—"),
                rule("Комментарии", "—"),
                rule("Перспективы", "—"),
                rule("Инфо юристов…", "—"),
                rule("Дата мирового/завершения", "—")
        );
    }

    private static Rule rule(String col, String src) {
        return new Rule(col, src);
    }
}
