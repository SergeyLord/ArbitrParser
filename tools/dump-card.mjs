const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const browser = await chromium.launch({ headless: false, args: ['--disable-blink-features=AutomationControlled'] });
  const ctx = await browser.newContext({ locale: 'ru-RU', viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(() => Object.defineProperty(navigator, 'webdriver', { get: () => undefined }));
  const page = await ctx.newPage();
  await page.goto('https://kad.arbitr.ru/', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  try { await page.locator('.b-promo-modal .b-promo-modal-close, .js-modal-close').first().click({ timeout: 2000 }); } catch {}
  await page.goto('https://kad.arbitr.ru/Card/6fb9afec-b71d-4183-b917-4cace5958c16', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(5000);
  try { await page.locator('.b-promo-modal .b-promo-modal-close, .js-modal-close').first().click({ timeout: 2000 }); } catch {}
  fs.mkdirSync('build/kad-debug', { recursive: true });
  fs.writeFileSync('build/kad-debug/card-before.html', await page.content());
  const btnInfo = await page.evaluate(() => {
    const texts = [];
    for (const el of document.querySelectorAll('a,button,span,div,i')) {
      const t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
      if (/хронолог|ознакомиться/i.test(t) && t.length < 150) {
        texts.push(el.tagName + '|' + el.className + '|' + t);
      }
    }
    // also collect expanders
    for (const el of document.querySelectorAll('[class*=chrono], [class*=collapse], [class*=expand], .b-case-chrono, #chrono')) {
      texts.push('SEL|' + el.tagName + '|' + el.className + '|' + (el.innerText||'').slice(0,80));
    }
    return texts.slice(0, 50).join('\n');
  });
  fs.writeFileSync('build/kad-debug/chrono-candidates.txt', btnInfo || '');
  console.log('CANDIDATES:\n' + btnInfo);
  const clicked = await page.evaluate(() => {
    const el = [...document.querySelectorAll('a,button,span,div')].find(e => /полной хронологией/i.test(e.innerText||''));
    if (el) { el.click(); return 'clicked text'; }
    const plus = document.querySelector('.b-case-chrono-button, a.js-case-result-collapse, .l-right a.b-icon');
    if (plus) { plus.click(); return 'clicked plus ' + plus.className; }
    return 'none';
  });
  console.log('CLICK:', clicked);
  await page.waitForTimeout(4000);
  fs.writeFileSync('build/kad-debug/card-after.html', await page.content());
  const add = await page.evaluate(() => [...document.querySelectorAll('.additional-info')].map(e => e.innerText.trim()).filter(Boolean).slice(0,40).join('\n---\n'));
  fs.writeFileSync('build/kad-debug/additional-info.txt', add || '');
  console.log('ADD:\n' + add);
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
