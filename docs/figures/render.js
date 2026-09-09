const puppeteer = require('puppeteer-core');
const fs = require('fs');
const path = require('path');

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const SRC = __dirname;
const OUT = 'D:/STAGE S2I/Application/PMS/report/Rapport PFE TEKUP LATEX/img';
const SCALE = 3;

(async () => {
  const names = process.argv.slice(2);
  const files = (names.length ? names.map(n => n + '.svg')
                              : fs.readdirSync(SRC).filter(f => f.endsWith('.svg')));

  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--force-device-scale-factor=' + SCALE],
  });

  for (const f of files) {
    const svg = fs.readFileSync(path.join(SRC, f), 'utf8');
    const page = await browser.newPage();
    await page.setViewport({ width: 1400, height: 900, deviceScaleFactor: SCALE });
    await page.setContent(
      '<style>html,body{margin:0;padding:0;background:#fff}' +
      'svg{display:block;width:100%;height:auto}</style>' +
      '<div id="w" style="width:1300px">' + svg + '</div>',
      { waitUntil: 'networkidle0' });
    await page.evaluateHandle('document.fonts.ready');

    const box = await page.evaluate(() => {
      const r = document.querySelector('#w').getBoundingClientRect();
      return { w: Math.ceil(r.width), h: Math.ceil(r.height) };
    });
    await page.setViewport({ width: box.w, height: box.h, deviceScaleFactor: SCALE });

    const out = path.join(OUT, f.replace(/\.svg$/, '.png'));
    await page.screenshot({ path: out, clip: { x: 0, y: 0, width: box.w, height: box.h } });
    console.log('  ' + path.basename(out) + '   ' + (box.w * SCALE) + 'x' + (box.h * SCALE));
    await page.close();
  }
  await browser.close();
})().catch(e => { console.error('FAILED: ' + e.message); process.exit(1); });
