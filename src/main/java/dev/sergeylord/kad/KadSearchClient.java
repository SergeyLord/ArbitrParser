package dev.sergeylord.kad;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
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
        humanPause(1_500, 3_000);
        dismissPromoModal();
        System.out.println("Title: " + page.title());
        dumpDebug("warmup");
        warmedUp = true;
        System.out.println("Session ready");
    }

    public List<KadCase> searchByParticipant(String innOrName, int maxPages) {
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
            throw new IllegalStateException("SearchInstances HTTP " + searchResponse.status());
        }

        String html = searchResponse.text();
        saveText("search-page1.html", html);

        int total = readHiddenInt(html, "documentsTotalCount");
        int pagesCount = readHiddenInt(html, "documentsPagesCount");
        int pagesToLoad = Math.min(maxPages, Math.max(pagesCount, 1));
        System.out.printf("Found cases: %d (pages: %d, loading: %d)%n", total, pagesCount, pagesToLoad);

        List<KadCase> all = new ArrayList<>(parseCasesHtml(html));
        System.out.println("Parsed from page 1: " + all.size());

        for (int p = 2; p <= pagesToLoad; p++) {
            final int pageNum = p;
            System.out.println("Loading page " + pageNum + "...");
            humanPause(900, 2_000);
            Response next = page.waitForResponse(
                    r -> r.url().contains("SearchInstances") && r.request().method().equalsIgnoreCase("POST"),
                    () -> page.locator("#pages a[href='#page" + pageNum + "']").first().click()
            );
            if (next.status() != 200) {
                break;
            }
            all.addAll(parseCasesHtml(next.text()));
        }

        dumpDebug("after-search");
        return all;
    }

    private void commitParticipantTag(String innOrName) {
        System.out.println("Typing participant: " + innOrName);

        // Поле ввода нового тега — textarea без disabled внутри #sug-participants
        Locator textarea = page.locator("#sug-participants textarea:not([disabled])").first();
        textarea.click();
        // Важно: pressSequentially шлёт keydown/keyup — fill() КАД часто игнорирует
        textarea.press("Control+A");
        textarea.press("Backspace");
        // Печать «как человек»: разный интервал между символами
        textarea.pressSequentially(innOrName, new Locator.PressSequentiallyOptions()
                .setDelay(ThreadLocalRandom.current().nextInt(35, 90)));
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
                    ""
            ));
        }
        return cases;
    }

    /**
     * 1) Открывает карточки во вкладках (пауза 0.2–0.5 с).
     * 2) На каждой: дата из {@code .b-instanceAdditional}; плюсик хронологии → сумма из {@code .additional-info}.
     */
    public List<KadCase> enrichWithHearings(List<KadCase> cases, ProgressListener progress) {
        ensureWarm();
        int total = cases.size();
        int steps = Math.max(total * 2, 1);
        List<Page> tabs = new ArrayList<>(total);

        for (int i = 0; i < total; i++) {
            KadCase c = cases.get(i);
            if (progress != null) {
                progress.onProgress(i + 1, steps,
                        "Открываю вкладку " + (i + 1) + " из " + total
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

        List<KadCase> out = new ArrayList<>(total);
        try {
            for (int i = 0; i < total; i++) {
                KadCase c = cases.get(i);
                if (progress != null) {
                    progress.onProgress(total + i + 1, steps,
                            "Карточка " + (i + 1) + " из " + total
                                    + (c.caseNumber().isBlank() ? "" : ": " + c.caseNumber()));
                }
                String hearing = "";
                String claimSum = "";
                Page tab = tabs.get(i);
                if (tab != null) {
                    try {
                        tab.bringToFront();
                        try {
                            tab.waitForLoadState(LoadState.DOMCONTENTLOADED,
                                    new Page.WaitForLoadStateOptions().setTimeout(15_000));
                        } catch (Exception ignored) {
                        }
                        tab.waitForTimeout(1_000);
                        hearing = extractNextHearing(tab);
                        expandChronologyPlus(tab);
                        claimSum = extractClaimSum(tab);
                        System.out.println("Hearing " + c.caseNumber() + " = [" + hearing + "], "
                                + "ClaimSum = [" + claimSum + "]");
                    } catch (Exception e) {
                        System.out.println("Card visit failed for " + c.caseNumber() + ": " + e.getMessage());
                    } finally {
                        closeQuietly(tab);
                        tabs.set(i, null);
                    }
                }
                out.add(c.withCardDetails(hearing, claimSum));
            }
        } finally {
            for (Page tab : tabs) {
                closeQuietly(tab);
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

    /**
     * Дата из блока карточки:
     * {@code <div class="b-instanceAdditional">… Следующее заседание: 01.12.2026, 10:20 , зал №1</div>}
     */
    private String extractNextHearing(Page card) {
        try {
            card.locator(".b-instanceAdditional").first()
                    .waitFor(new Locator.WaitForOptions().setTimeout(5_000));
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
        return raw == null ? "" : raw.toString().trim();
    }

    /**
     * Плюсик: {@code <div class="b-collapse js-collapse" title="Нажмите, чтобы ознакомиться с полной хронологией дела.">}
     */
    private void expandChronologyPlus(Page card) {
        try {
            Locator plus = card.locator(
                    "div.b-collapse.js-collapse[title*='полной хронологией'], "
                            + "div.b-collapse.js-collapse"
            ).first();
            plus.waitFor(new Locator.WaitForOptions().setTimeout(5_000));
            plus.click(new Locator.ClickOptions().setTimeout(5_000));
            card.waitForTimeout(800);
        } catch (Exception e) {
            System.out.println("Chronology plus click failed: " + e.getMessage());
            try {
                Object viaJs = card.evaluate(
                        """
                        () => {
                          const el = document.querySelector(
                            'div.b-collapse.js-collapse[title*="хронологией"]'
                          ) || document.querySelector('div.b-collapse.js-collapse');
                          if (!el) return 'none';
                          el.click();
                          return 'ok';
                        }
                        """
                );
                System.out.println("Chronology plus JS: " + viaJs);
                card.waitForTimeout(800);
            } catch (Exception e2) {
                System.out.println("Chronology plus JS failed: " + e2.getMessage());
            }
        }
    }

    /**
     * Сумма: {@code <span class="additional-info">. Сумма исковых требований 17370467,19</span>}
     */
    private String extractClaimSum(Page card) {
        try {
            card.locator("text=/Сумма\\s+исковых\\s+требований/i").first()
                    .waitFor(new Locator.WaitForOptions().setTimeout(8_000));
        } catch (Exception ignored) {
            try {
                card.locator(".additional-info").first()
                        .waitFor(new Locator.WaitForOptions().setTimeout(3_000));
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

    private static void closeQuietly(Page tab) {
        if (tab == null) {
            return;
        }
        try {
            tab.close();
        } catch (Exception ignored) {
        }
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
        humanPause(target, 200, 500);
        try {
            target.locator(".b-promo-modal .b-promo-modal-close, .js-modal-close")
                    .first()
                    .click(new Locator.ClickOptions().setTimeout(1_500));
        } catch (Exception ignored) {
        }
        target.keyboard().press("Escape");
        humanPause(target, 200, 500);
    }

    /** Случайная пауза [minMs; maxMs), чтобы не стучать метрономом. */
    private void humanPause(int minMs, int maxMs) {
        humanPause(page, minMs, maxMs);
    }

    private void humanPause(Page target, int minMs, int maxMs) {
        int lo = Math.max(0, Math.min(minMs, maxMs));
        int hi = Math.max(minMs, maxMs);
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
