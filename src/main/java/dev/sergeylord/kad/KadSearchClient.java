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
                    ""
            ));
        }
        return cases;
    }

    /**
     * Для каждого дела открывает карточку и достаёт дату/время заседания.
     * Прогресс: current = 1..N по карточкам.
     */
    public List<KadCase> enrichWithHearings(List<KadCase> cases, ProgressListener progress) {
        ensureWarm();
        List<KadCase> out = new ArrayList<>(cases.size());
        int total = cases.size();
        for (int i = 0; i < total; i++) {
            KadCase c = cases.get(i);
            if (progress != null) {
                progress.onProgress(i + 1, total,
                        "Карточка " + (i + 1) + " из " + total
                                + (c.caseNumber().isBlank() ? "" : ": " + c.caseNumber()));
            }
            String hearing = "";
            if (c.caseId() != null && !c.caseId().isBlank()) {
                try {
                    hearing = fetchHearingFromCard(c.caseId());
                } catch (Exception e) {
                    System.out.println("Hearing failed for " + c.caseNumber() + ": " + e.getMessage());
                }
                // Главное антибот-место: пауза между карточками
                humanPause(800, 2_400);
            }
            out.add(c.withNextHearing(hearing));
        }
        if (progress != null && total > 0) {
            progress.onProgress(total, total, "Карточки обработаны: " + total);
        }
        return out;
    }

    private String fetchHearingFromCard(String caseId) {
        page.navigate("https://kad.arbitr.ru/Card/" + caseId, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        } catch (Exception ignored) {
        }
        humanPause(900, 1_800);
        dismissPromoModal();

        Object raw = page.evaluate(
                """
                () => {
                  const text = document.body ? (document.body.innerText || '') : '';
                  const patterns = [
                    /Следующее заседание:\\s*([0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{4}\\s*,?\\s*[0-9]{1,2}:[0-9]{2})/i,
                    /Следующее заседание:\\s*([0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{4})/i,
                    /Дата и время судебного заседания\\s*([0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{4}\\s*,?\\s*[0-9]{1,2}:[0-9]{2})/i,
                    /судебного заседания\\s*([0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{4}\\s*,\\s*[0-9]{1,2}:[0-9]{2})/i
                  ];
                  for (const p of patterns) {
                    const m = text.match(p);
                    if (m && m[1]) return m[1].replace(/\\s+/g, ' ').trim();
                  }
                  // ISO из возможных JSON-вставок на странице
                  const iso = text.match(/"Start"\\s*:\\s*"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2})/);
                  if (iso) {
                    const [d, t] = iso[1].split('T');
                    const [y, mo, da] = d.split('-');
                    return da + '.' + mo + '.' + y + ', ' + t;
                  }
                  return '';
                }
                """
        );
        return raw == null ? "" : raw.toString().trim();
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
        page.keyboard().press("Escape");
        humanPause(250, 600);
        try {
            page.locator(".b-promo-modal .b-promo-modal-close, .js-modal-close")
                    .first()
                    .click(new Locator.ClickOptions().setTimeout(1_500));
        } catch (Exception ignored) {
        }
        page.keyboard().press("Escape");
        humanPause(250, 600);
    }

    /** Случайная пауза [minMs; maxMs), чтобы не стучать метрономом. */
    private void humanPause(int minMs, int maxMs) {
        int lo = Math.max(0, Math.min(minMs, maxMs));
        int hi = Math.max(minMs, maxMs);
        int ms = (hi <= lo) ? lo : ThreadLocalRandom.current().nextInt(lo, hi);
        page.waitForTimeout(ms);
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
