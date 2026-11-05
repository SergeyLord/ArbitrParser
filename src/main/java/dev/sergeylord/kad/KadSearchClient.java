package dev.sergeylord.kad;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MVP: Chromium как пользователь → форма КАД → HTML SearchInstances → список дел.
 */
public final class KadSearchClient implements AutoCloseable {

    private static final String KAD_URL = "https://kad.arbitr.ru/";
    private static final Path DEBUG_DIR = Path.of("build", "kad-debug");
    private static final Pattern CASE_ID = Pattern.compile("/Card/([0-9a-fA-F-]{36})");

    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private final Page page;
    private boolean warmedUp;

    public KadSearchClient(boolean headless) {
        this.playwright = Playwright.create();
        this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setSlowMo(headless ? 0 : 30)
                .setArgs(List.of(
                        "--disable-blink-features=AutomationControlled",
                        "--no-sandbox"
                )));
        this.context = browser.newContext(new Browser.NewContextOptions()
                .setLocale("ru-RU")
                .setTimezoneId("Europe/Moscow")
                .setViewportSize(1440, 900)
                .setUserAgent(
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"));
        context.addInitScript("Object.defineProperty(navigator, 'webdriver', { get: () => undefined });");
        this.page = context.newPage();
        this.page.setDefaultTimeout(90_000);
    }

    public void warmUp() {
        System.out.println("Opening kad.arbitr.ru ...");
        Response nav = page.navigate(KAD_URL, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        System.out.println("HTTP navigate: " + (nav == null ? "?" : nav.status()));
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        humanPause(300, 600);
        dismissPromoModal();
        System.out.println("Title: " + page.title());
        dumpDebug("warmup");
        warmedUp = true;
        System.out.println("Session ready");
    }

    /**
     * Поиск по участнику. Грузит страницы выдачи, пока не наберётся {@code maxCases}
     * дел с годом регистрации &gt;= {@code yearFrom} (или все, если {@code maxCases <= 0}).
     */
    public List<KadCase> searchByParticipant(String innOrName, int maxCases) {
        return searchByParticipant(innOrName, maxCases, 0);
    }

    public List<KadCase> searchByParticipant(String innOrName, int maxCases, int yearFrom) {
        ensureWarm();
        commitParticipantTag(innOrName);

        AtomicReference<String> requestBody = new AtomicReference<>();
        page.onRequest(req -> {
            if (req.url().contains("SearchInstances") && "POST".equalsIgnoreCase(req.method())) {
                requestBody.set(req.postData());
            }
        });

        System.out.println("Submitting search...");
        Response searchResponse = page.waitForResponse(
                r -> r.url().contains("SearchInstances") && r.request().method().equalsIgnoreCase("POST"),
                () -> page.locator("#b-form-submit button, #b-form-submit").first().click()
        );

        System.out.println("SearchInstances HTTP " + searchResponse.status());
        System.out.println("POST body: " + requestBody.get());
        saveText("last-post-body.json", requestBody.get() == null ? "" : requestBody.get());

        if (searchResponse.status() != 200) {
            dumpDebug("bad-status");
            throw httpError(searchResponse.status(), "SearchInstances");
        }

        String html = searchResponse.text();
        saveText("search-page1.html", html);

        int total = readHiddenInt(html, "documentsTotalCount");
        int pagesCount = Math.max(readHiddenInt(html, "documentsPagesCount"), 1);
        System.out.printf("Found cases: %d (pages: %d), limit: %s, yearFrom: %s%n",
                total, pagesCount,
                maxCases <= 0 ? "all" : String.valueOf(maxCases),
                yearFrom > 0 ? String.valueOf(yearFrom) : "any");

        List<KadCase> all = new ArrayList<>();
        int rawPage1 = addFiltered(all, parseCasesHtml(html), yearFrom, maxCases);
        System.out.println("Page 1: raw=" + rawPage1 + ", kept=" + all.size());

        for (int p = 2; p <= pagesCount; p++) {
            if (maxCases > 0 && all.size() >= maxCases) {
                break;
            }
            final int pageNum = p;
            System.out.println("Loading page " + pageNum + " / " + pagesCount + "...");
            humanPause(900, 2_000);
            Response next = page.waitForResponse(
                    r -> r.url().contains("SearchInstances") && r.request().method().equalsIgnoreCase("POST"),
                    () -> page.locator("#pages a[href='#page" + pageNum + "']").first().click()
            );
            if (next.status() != 200) {
                System.out.println("Page " + pageNum + " HTTP " + next.status() + " — stop");
                throw httpError(next.status(), "SearchInstances page " + pageNum);
            }
            int before = all.size();
            int raw = addFiltered(all, parseCasesHtml(next.text()), yearFrom, maxCases);
            System.out.println("Page " + pageNum + ": raw=" + raw + ", kept +" + (all.size() - before)
                    + " (total: " + all.size() + ")");
        }

        System.out.println("Cases to process: " + all.size());

        dumpDebug("after-search");
        return all;
    }

    /** Добавляет дела с годом &gt;= yearFrom, пока не упрёмся в maxCases. */
    private static int addFiltered(List<KadCase> dest, List<KadCase> chunk, int yearFrom, int maxCases) {
        int raw = chunk.size();
        for (KadCase c : chunk) {
            if (maxCases > 0 && dest.size() >= maxCases) {
                break;
            }
            int y = registrationYear(c);
            if (yearFrom > 0 && y > 0 && y < yearFrom) {
                continue;
            }
            dest.add(c);
        }
        return raw;
    }

    /** Год регистрации из даты списка КАД (дд.мм.гггг). 0 если не разобрали. */
    static int registrationYear(KadCase c) {
        if (c == null || c.date() == null || c.date().isBlank()) {
            return 0;
        }
        Matcher m = Pattern.compile("(20\\d{2}|19\\d{2})").matcher(c.date());
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private void commitParticipantTag(String innOrName) {
        System.out.println("Typing participant: " + innOrName);

        // Поле ввода нового тега — textarea без disabled внутри #sug-participants
        Locator textarea = page.locator("#sug-participants textarea:not([disabled])").first();
        textarea.click();
        textarea.press("Control+A");
        textarea.press("Backspace");
        // Печать по символам (Ctrl+V иногда хуже воспринимается антиботом)
        textarea.pressSequentially(innOrName, new Locator.PressSequentiallyOptions()
                .setDelay(ThreadLocalRandom.current().nextInt(40, 100)));
        humanPause(400, 900);
        textarea.press("Enter");
        humanPause(700, 1_500);

        int added = page.locator("#sug-participants .tag.added").count();
        System.out.println("Added tags count: " + added);
        if (added == 0) {
            System.out.println("No .tag.added — trying add icon");
            page.locator("#sug-participants i.b-icon.add").first().click();
            humanPause(600, 1_200);
            added = page.locator("#sug-participants .tag.added").count();
            System.out.println("Added tags after icon: " + added);
        }

        // Автокомплит, если вылез
        try {
            Locator item = page.locator(".sug-item:visible, .ui-menu-item:visible, .b-autocompleate li:visible").first();
            if (item.count() > 0) {
                System.out.println("Clicking suggest item");
                item.click();
                humanPause(400, 900);
            }
        } catch (Exception ignored) {
        }

        dumpDebug("after-tag");
    }

    static List<KadCase> parseCasesHtml(String html) {
        List<KadCase> cases = new ArrayList<>();
        // Ответ — фрагмент: colgroup + tr... без <table>
        String wrapped = "<table>" + html + "</table>";
        Document doc = Jsoup.parseBodyFragment(wrapped);
        for (Element tr : doc.select("tr")) {
            Element link = tr.selectFirst("a.num_case");
            if (link == null) {
                continue;
            }
            String href = link.attr("href");
            String caseId = "";
            Matcher m = CASE_ID.matcher(href);
            if (m.find()) {
                caseId = m.group(1);
            }
            String caseNumber = link.text().trim();

            String caseType = "";
            Element typeDiv = tr.selectFirst("td.num .b-container > div[class]");
            if (typeDiv != null) {
                String cls = typeDiv.className();
                if (cls.contains("administrative")) caseType = "A";
                else if (cls.contains("civil")) caseType = "G";
                else if (cls.contains("bankruptcy")) caseType = "B";
            }
            String date = "";
            Element dateSpan = tr.selectFirst("td.num .b-container > div > span");
            if (dateSpan != null) {
                date = dateSpan.text().trim();
            }

            String court = "";
            String judge = "";
            Elements courtDivs = tr.select("td.court .b-container > div");
            for (Element div : courtDivs) {
                String title = div.attr("title").trim();
                String text = div.text().trim();
                String candidate = !title.isBlank() ? title : text;
                if (candidate.isBlank()) {
                    continue;
                }
                if (looksLikeCourt(candidate)) {
                    if (court.isBlank()) {
                        court = candidate;
                    }
                } else if (judge.isBlank()) {
                    judge = candidate;
                }
            }
            if (court.isBlank() && !courtDivs.isEmpty()) {
                Element first = courtDivs.first();
                court = first.attr("title");
                if (court.isBlank()) {
                    court = first.text().trim();
                }
            }

            cases.add(new KadCase(
                    caseId,
                    caseNumber,
                    caseType,
                    court,
                    judge,
                    date,
                    rolloverParties(tr.select("td.plaintiff .js-rolloverHtml")),
                    rolloverParties(tr.select("td.respondent .js-rolloverHtml")),
                    "",
                    "",
                    "",
                    "",
                    ""
            ));
        }
        return cases;
    }

    /** Сколько карточек держим открытыми одновременно (меньше нагрузка → меньше 429). */
    private static final int CARD_TAB_BATCH = 5;
    private static final Gson GSON = new Gson();

    /**
     * Карточки пачками по {@link #CARD_TAB_BATCH}: открыл вкладки → прочитал → закрыл → следующая пачка.
     * На каждой: дата из {@code .b-instanceAdditional}; плюсик хронологии → сумма + PDF в {@code docsDir}.
     */
    public List<KadCase> enrichWithHearings(List<KadCase> cases, ProgressListener progress) {
        return enrichWithHearings(cases, null, progress);
    }

    public List<KadCase> enrichWithHearings(List<KadCase> cases, Path docsDir, ProgressListener progress) {
        ensureWarm();
        if (docsDir != null) {
            try {
                Files.createDirectories(docsDir);
            } catch (Exception e) {
                System.out.println("Cannot create docs dir: " + e.getMessage());
            }
        }
        int total = cases.size();
        int steps = Math.max(total * 2, 1);
        List<KadCase> out = new ArrayList<>(total);

        for (int batchStart = 0; batchStart < total; batchStart += CARD_TAB_BATCH) {
            int batchEnd = Math.min(batchStart + CARD_TAB_BATCH, total);
            int batchNo = batchStart / CARD_TAB_BATCH + 1;
            int batchCount = (total + CARD_TAB_BATCH - 1) / CARD_TAB_BATCH;
            System.out.println("Card batch " + batchNo + "/" + batchCount
                    + " (cases " + (batchStart + 1) + "–" + batchEnd + " of " + total + ")");

            List<Page> tabs = new ArrayList<>(batchEnd - batchStart);
            try {
                for (int i = batchStart; i < batchEnd; i++) {
                    KadCase c = cases.get(i);
                    if (progress != null) {
                        progress.onProgress(i + 1, steps,
                                "Открываю вкладку " + (i + 1) + " из " + total
                                        + " (пачка " + batchNo + "/" + batchCount + ")"
                                        + (c.caseNumber().isBlank() ? "" : ": " + c.caseNumber()));
                    }
                    Page tab = null;
                    if (c.caseId() != null && !c.caseId().isBlank()) {
                        try {
                            tab = context.newPage();
                            tab.setDefaultTimeout(90_000);
                            tab.navigate("https://kad.arbitr.ru/Card/" + c.caseId(),
                                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT));
                        } catch (Exception e) {
                            System.out.println("Open tab failed for " + c.caseNumber() + ": " + e.getMessage());
                            closeQuietly(tab);
                            tab = null;
                        }
                    }
                    tabs.add(tab);
                    humanPause(page, 200, 500);
                }

                for (int i = batchStart; i < batchEnd; i++) {
                    KadCase c = cases.get(i);
                    if (progress != null) {
                        progress.onProgress(total + i + 1, steps,
                                "Карточка " + (i + 1) + " из " + total
                                        + " (пачка " + batchNo + "/" + batchCount + ")"
                                        + (c.caseNumber().isBlank() ? "" : ": " + c.caseNumber()));
                    }
                    String hearing = "";
                    String hearingHall = "";
                    String claimSum = "";
                    String documents = "";
                    String documentsLink = "";
                    Page tab = tabs.get(i - batchStart);
                    if (tab != null) {
                        try {
                            tab.bringToFront();
                            try {
                                tab.waitForLoadState(LoadState.DOMCONTENTLOADED,
                                        new Page.WaitForLoadStateOptions().setTimeout(10_000));
                            } catch (Exception ignored) {
                            }
                            tab.waitForTimeout(250);
                            HearingInfo hi = extractNextHearing(tab);
                            hearing = hi.dateTime();
                            hearingHall = hi.hall();
                            expandChronologyPlus(tab);
                            claimSum = extractClaimSum(tab);
                            if (docsDir != null) {
                                DocDownload docs = downloadCaseDocuments(tab, docsDir, c.caseNumber());
                                documents = docs.label();
                                documentsLink = docs.linkPath();
                            }
                            System.out.println("Hearing " + c.caseNumber() + " = [" + hearing + "], "
                                    + "Hall = [" + hearingHall + "], "
                                    + "ClaimSum = [" + claimSum + "], Docs = [" + documents + "]");
                        } catch (Exception e) {
                            System.out.println("Card visit failed for " + c.caseNumber() + ": " + e.getMessage());
                        }
                    }
                    out.add(c.withCardDetails(hearing, hearingHall, claimSum, documents, documentsLink));
                }
            } finally {
                for (Page tab : tabs) {
                    closeQuietly(tab);
                }
            }

            if (batchEnd < total) {
                humanPause(page, 400, 900);
            }
        }

        if (progress != null && total > 0) {
            progress.onProgress(steps, steps, "Карточки обработаны: " + total);
        }
        try {
            page.bringToFront();
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Дата/время и зал из текста «Следующее заседание: …». */
    record HearingInfo(String dateTime, String hall) {
        static HearingInfo empty() {
            return new HearingInfo("", "");
        }
    }

    /**
     * Из {@code .b-instanceAdditional}: «Следующее заседание: 01.12.2026, 10:20 , зал №1»
     * → дата/время отдельно, зал («Зал судебных заседаний № …») отдельно.
     */
    private HearingInfo extractNextHearing(Page card) {
        try {
            card.locator(".b-instanceAdditional").first()
                    .waitFor(new Locator.WaitForOptions().setTimeout(1_500));
        } catch (Exception ignored) {
            // блока может не быть (нет назначенного заседания)
        }
        Object raw = card.evaluate(
                """
                () => {
                  const blocks = [...document.querySelectorAll('.b-instanceAdditional')];
                  const re = /Следующее\\s+заседание:\\s*(.+)/i;
                  for (const el of blocks) {
                    const t = (el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim();
                    const m = t.match(re);
                    if (m && m[1]) return m[1].replace(/\\s+,/g, ',').trim();
                  }
                  return '';
                }
                """
        );
        return splitHearing(raw == null ? "" : raw.toString().trim());
    }

    /** «01.12.2026, 10:20 , Зал судебных заседаний № 5079» → dateTime + hall. */
    static HearingInfo splitHearing(String raw) {
        if (raw == null || raw.isBlank()) {
            return HearingInfo.empty();
        }
        String t = raw.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        Matcher m = Pattern.compile(
                "^(\\d{2}\\.\\d{2}\\.\\d{4}\\s*,\\s*\\d{1,2}:\\d{2})\\s*,?\\s*(.*)$"
        ).matcher(t);
        if (!m.matches()) {
            return new HearingInfo(t, "");
        }
        String dateTime = m.group(1).replaceAll("\\s*,\\s*", ", ").trim();
        String hall = m.group(2) == null ? "" : m.group(2).replaceAll("^[,\\s]+", "").trim();
        return new HearingInfo(dateTime, hall);
    }

    /**
     * Плюсики хронологии по всем инстанциям:
     * {@code <div class="b-collapse js-collapse" title="Нажмите, чтобы ознакомиться с полной хронологией дела.">}
     */
    private void expandChronologyPlus(Page card) {
        try {
            Locator pluses = card.locator("div.b-collapse.js-collapse");
            int n = pluses.count();
            if (n == 0) {
                return;
            }
            for (int i = 0; i < n; i++) {
                try {
                    pluses.nth(i).click(new Locator.ClickOptions().setTimeout(2_000));
                } catch (Exception e) {
                    System.out.println("Chronology plus #" + (i + 1) + " failed: " + e.getMessage());
                }
            }
            // хронология подгружается AJAX — без ожидания PdfDocument ещё нет в DOM
            waitForChronologyDocuments(card);
        } catch (Exception e) {
            System.out.println("Chronology plus click failed: " + e.getMessage());
            try {
                Object viaJs = card.evaluate(
                        """
                        () => {
                          const els = [...document.querySelectorAll('div.b-collapse.js-collapse')];
                          if (!els.length) return 'none';
                          els.forEach(el => el.click());
                          return 'ok:' + els.length;
                        }
                        """
                );
                System.out.println("Chronology plus JS: " + viaJs);
                waitForChronologyDocuments(card);
            } catch (Exception e2) {
                System.out.println("Chronology plus JS failed: " + e2.getMessage());
            }
        }
    }

    /** Ждём появления PDF/результатов после плюсика (AJAX). */
    private void waitForChronologyDocuments(Page card) {
        try {
            card.locator(
                    "a[href*='PdfDocument'], a.js-case-result-text--doc_link, "
                            + "h2.b-case-result, .g-valid_sign.js-signers-rollover"
            ).first().waitFor(new Locator.WaitForOptions().setTimeout(10_000));
        } catch (Exception e) {
            System.out.println("Chronology docs wait: " + e.getMessage());
            card.waitForTimeout(1_200);
        }
        card.waitForTimeout(400);
    }

    /**
     * Сумма: {@code <span class="additional-info">. Сумма исковых требований 17370467,19</span>}
     */
    private String extractClaimSum(Page card) {
        try {
            card.locator("text=/Сумма\\s+исковых\\s+требований/i").first()
                    .waitFor(new Locator.WaitForOptions().setTimeout(4_000));
        } catch (Exception ignored) {
            try {
                card.locator(".additional-info").first()
                        .waitFor(new Locator.WaitForOptions().setTimeout(1_500));
            } catch (Exception ignored2) {
            }
        }
        Object raw = card.evaluate(
                """
                () => {
                  const normalize = s => (s || '').replace(/\\s+/g, ' ').trim();
                  const re = /Сумма\\s+исковых\\s+требований\\s*([0-9][0-9\\s\\u00A0]*(?:[.,][0-9]{1,2})?)/i;
                  for (const el of document.querySelectorAll('.additional-info')) {
                    const t = normalize(el.innerText || el.textContent);
                    const m = t.match(re);
                    if (m && m[1]) return m[1].replace(/[\\s\\u00A0]/g, '').replace(',', '.');
                  }
                  const body = normalize(document.body && document.body.innerText);
                  const m2 = body.match(re);
                  if (m2 && m2[1]) return m2[1].replace(/[\\s\\u00A0]/g, '').replace(',', '.');
                  return '';
                }
                """
        );
        return raw == null ? "" : raw.toString().trim();
    }

    private record DocDownload(String label, String linkPath) {
        static DocDownload empty() {
            return new DocDownload("", "");
        }
    }

    /**
     * PDF после плюсика: ссылки {@code a.js-case-result-text--doc_link} (рядом title
     * {@code .js-judges-rollover} и часто {@code .g-valid_sign} «[Подписано]»).
     * Уже скачанные файлы не трогаем.
     */
    private DocDownload downloadCaseDocuments(Page card, Path docsDir, String caseNumber) {
        waitForChronologyDocuments(card);
        Map<String, String> links = collectPdfLinks(card);
        if (links.isEmpty()) {
            // ещё одна попытка: плюсик мог не раскрыться с первого раза
            try {
                card.evaluate(
                        """
                        () => document.querySelectorAll('div.b-collapse.js-collapse')
                          .forEach(el => el.click())
                        """
                );
                waitForChronologyDocuments(card);
            } catch (Exception ignored) {
            }
            links = collectPdfLinks(card);
        }
        if (links.isEmpty()) {
            System.out.println("No PDF links for " + caseNumber);
            return DocDownload.empty();
        }
        int kept = 0;
        int downloaded = 0;
        int skipped = 0;
        Path firstFile = null;
        for (Map.Entry<String, String> e : links.entrySet()) {
            String url = e.getKey();
            String fileName = e.getValue();
            Path target = docsDir.resolve(fileName).toAbsolutePath().normalize();
            try {
                if (Files.isRegularFile(target) && Files.size(target) > 500) {
                    skipped++;
                    kept++;
                    if (firstFile == null) {
                        firstFile = target;
                    }
                    System.out.println("Skip existing PDF: " + fileName);
                    continue;
                }
                humanPause(card, 400, 900);
                byte[] body = fetchPdfViaBrowser(url);
                if (body == null) {
                    System.out.println("PDF fail " + fileName);
                    continue;
                }
                Files.write(target, body);
                downloaded++;
                kept++;
                if (firstFile == null) {
                    firstFile = target;
                }
                System.out.println("Saved PDF: " + target.getFileName() + " (" + body.length + " bytes)");
            } catch (IllegalStateException ex) {
                throw ex;
            } catch (Exception ex) {
                System.out.println("PDF download failed " + fileName + ": " + ex.getMessage());
            }
        }
        System.out.println("Docs " + caseNumber + ": links=" + links.size()
                + " saved=" + downloaded + " skipped=" + skipped);
        if (kept <= 0 || firstFile == null) {
            return DocDownload.empty();
        }
        return new DocDownload("есть (" + kept + ")", firstFile.toString());
    }

    /**
     * PdfDocument: JS-антибот + Chrome PDF-viewer съедает тело ответа.
     * Берём сырые байты через CDP Fetch (stage=Response) при реальном navigate.
     */
    private byte[] fetchPdfViaBrowser(String url) {
        AtomicReference<byte[]> found = new AtomicReference<>();
        AtomicBoolean hit429 = new AtomicBoolean(false);
        Page docPage = context.newPage();
        CDPSession cdp = null;
        try {
            cdp = context.newCDPSession(docPage);
            JsonObject enable = new JsonObject();
            JsonArray patterns = new JsonArray();
            for (String pat : List.of("*Pdf*", "*pdf*")) {
                JsonObject p = new JsonObject();
                p.addProperty("urlPattern", pat);
                p.addProperty("requestStage", "Response");
                patterns.add(p);
            }
            enable.add("patterns", patterns);
            cdp.send("Fetch.enable", enable);

            CDPSession session = cdp;
            cdp.on("Fetch.requestPaused", ev -> {
                JsonObject o = GSON.toJsonTree(ev).getAsJsonObject();
                String requestId = o.get("requestId").getAsString();
                try {
                    int status = o.has("responseStatusCode") ? o.get("responseStatusCode").getAsInt() : 0;
                    if (status == 429) {
                        hit429.set(true);
                    }
                    JsonObject bodyParams = new JsonObject();
                    bodyParams.addProperty("requestId", requestId);
                    Object bodyObj = session.send("Fetch.getResponseBody", bodyParams);
                    JsonObject bodyJson = GSON.toJsonTree(bodyObj).getAsJsonObject();
                    String bodyStr = bodyJson.get("body").getAsString();
                    boolean b64 = bodyJson.has("base64Encoded")
                            && bodyJson.get("base64Encoded").getAsBoolean();
                    byte[] bytes = b64
                            ? Base64.getDecoder().decode(bodyStr)
                            : bodyStr.getBytes(StandardCharsets.ISO_8859_1);
                    if (isPdfMagic(bytes) && bytes.length > 1000) {
                        found.compareAndSet(null, bytes);
                    }
                } catch (Exception e) {
                    // challenge/html often pauses before body is ready — just continue
                } finally {
                    try {
                        JsonObject cont = new JsonObject();
                        cont.addProperty("requestId", requestId);
                        session.send("Fetch.continueRequest", cont);
                    } catch (Exception ignored) {
                    }
                }
            });

            try {
                docPage.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.COMMIT)
                        .setTimeout(60_000));
            } catch (Exception e) {
                System.out.println("PDF navigate: " + e.getMessage());
            }
            for (int i = 0; i < 60 && found.get() == null && !hit429.get(); i++) {
                docPage.waitForTimeout(250);
            }
            if (hit429.get()) {
                throw httpError(429, "PdfDocument");
            }
            byte[] body = found.get();
            if (body == null) {
                System.out.println("PDF not captured after browser navigate: " + url);
            }
            return body;
        } finally {
            if (cdp != null) {
                try {
                    cdp.send("Fetch.disable");
                } catch (Exception ignored) {
                }
                try {
                    cdp.detach();
                } catch (Exception ignored) {
                }
            }
            closeQuietly(docPage);
        }
    }

    private static boolean isPdfMagic(byte[] bytes) {
        return bytes != null && bytes.length >= 5
                && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    /** url → fileName из пути PdfDocument (уникальные). */
    private Map<String, String> collectPdfLinks(Page card) {
        Map<String, String> out = new LinkedHashMap<>();
        Object raw = card.evaluate(
                """
                () => {
                  const seen = new Set();
                  const items = [];
                  for (const a of document.querySelectorAll('a[href*="PdfDocument"]')) {
                    const href = a.href || '';
                    if (!href || seen.has(href)) continue;
                    seen.add(href);
                    let file = href.split('/').pop() || '';
                    try { file = decodeURIComponent(file.split('?')[0] || ''); } catch (e) {}
                    if (!file.toLowerCase().endsWith('.pdf')) file = file + '.pdf';
                    items.push({ href, fileName: file });
                  }
                  return JSON.stringify(items);
                }
                """
        );
        if (raw == null) {
            return out;
        }
        String json = raw.toString();
        if (json.isBlank() || "null".equals(json)) {
            return out;
        }
        JsonArray arr = GSON.fromJson(json, JsonArray.class);
        if (arr == null) {
            return out;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String href = o.has("href") ? o.get("href").getAsString() : "";
            String fileName = o.has("fileName") ? o.get("fileName").getAsString() : "";
            if (href.isBlank() || fileName.isBlank()) {
                continue;
            }
            out.putIfAbsent(href, sanitizeFileName(fileName));
        }
        return out;
    }

    private static String sanitizeFileName(String name) {
        String n = name.replace('\u00A0', ' ').trim();
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (n.length() > 180) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 ? n.substring(dot) : "";
            n = n.substring(0, Math.min(180, n.length() - ext.length())) + ext;
        }
        return n;
    }

    private static void closeQuietly(Page tab) {
        if (tab == null) {
            return;
        }
        try {
            tab.close();
        } catch (Exception ignored) {
        }
    }

    private static IllegalStateException httpError(int status, String where) {
        if (status == 429) {
            return new IllegalStateException("429: сайт ограничил частоту запросов");
        }
        return new IllegalStateException(where + " HTTP " + status);
    }

    private static boolean looksLikeCourt(String value) {
        String v = value.toLowerCase();
        return v.contains("арбитраж") || v.startsWith("ас ") || v.contains("ас г") || v.contains("суд");
    }

    private static List<Party> rolloverParties(Elements rollovers) {
        List<Party> parties = new ArrayList<>();
        for (Element el : rollovers) {
            Element strong = el.selectFirst("strong");
            String name = strong != null ? strong.text().trim() : "";
            if (name.isBlank()) {
                name = el.ownText().trim();
            }
            String inn = "";
            for (Element div : el.select("div")) {
                String t = div.ownText().isBlank() ? div.text() : div.ownText();
                if (t.contains("ИНН")) {
                    inn = t.replace("ИНН:", "").replace("ИНН", "").replaceAll("[^0-9]", "");
                }
            }
            if (!name.isBlank()) {
                parties.add(new Party(name, inn));
            }
        }
        return parties;
    }

    private static int readHiddenInt(String html, String id) {
        Element el = Jsoup.parseBodyFragment(html).getElementById(id);
        if (el == null) {
            return 0;
        }
        try {
            return Integer.parseInt(el.attr("value"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void dismissPromoModal() {
        dismissPromoModal(page);
    }

    private void dismissPromoModal(Page target) {
        target.keyboard().press("Escape");
        humanPause(target, 80, 180);
        try {
            target.locator(".b-promo-modal .b-promo-modal-close, .js-modal-close")
                    .first()
                    .click(new Locator.ClickOptions().setTimeout(800));
        } catch (Exception ignored) {
        }
        target.keyboard().press("Escape");
        humanPause(target, 80, 180);
    }

    /** Множитель пауз: >1.0 = медленнее (+10% к текущим). */
    private static final double PAUSE_SCALE = 0.99;

    /** Случайная пауза [minMs; maxMs), чтобы не стучать метрономом. */
    private void humanPause(int minMs, int maxMs) {
        humanPause(page, minMs, maxMs);
    }

    private void humanPause(Page target, int minMs, int maxMs) {
        int lo = (int) Math.round(Math.max(0, Math.min(minMs, maxMs)) * PAUSE_SCALE);
        int hi = (int) Math.round(Math.max(minMs, maxMs) * PAUSE_SCALE);
        int ms = (hi <= lo) ? lo : ThreadLocalRandom.current().nextInt(lo, hi);
        target.waitForTimeout(ms);
    }

    private void saveText(String name, String content) {
        try {
            Files.createDirectories(DEBUG_DIR);
            Files.writeString(DEBUG_DIR.resolve(name), content == null ? "" : content);
            System.out.println("Saved: " + DEBUG_DIR.resolve(name).toAbsolutePath());
        } catch (Exception e) {
            System.out.println("Cannot save: " + e.getMessage());
        }
    }

    private void dumpDebug(String label) {
        try {
            Files.createDirectories(DEBUG_DIR);
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(DEBUG_DIR.resolve(label + ".png"))
                    .setFullPage(true));
            Files.writeString(DEBUG_DIR.resolve(label + ".html"), page.content());
            System.out.println("Debug: " + DEBUG_DIR.resolve(label + ".png").toAbsolutePath());
        } catch (Exception e) {
            System.out.println("Debug dump failed: " + e.getMessage());
        }
    }

    private void ensureWarm() {
        if (!warmedUp) {
            warmUp();
        }
    }

    @Override
    public void close() {
        context.close();
        browser.close();
        playwright.close();
    }
}
