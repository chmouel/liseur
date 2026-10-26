// Renders the store feature graphic for every locale in feature-graphic.json into fastlane's metadata,
// checks each against Play's crop and play button, and writes review previews.
import {cp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {homedir} from 'node:os';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import puppeteer from 'puppeteer';
import {directory, prepare} from './prepare';

type Copy = {lang: string; headline: string; chips: string[]};

const root = resolve(directory, '..');
const copies: Record<string, Copy> = JSON.parse(await readFile(resolve(directory, 'feature-graphic.json'), 'utf8'));
const only = process.argv.slice(2);
const locales = only.length ? only : Object.keys(copies);
for (const locale of locales) if (!copies[locale]) throw new Error(`no copy for ${locale} in feature-graphic.json`);

// Literata and Inter have no CJK; those glyphs come from a subset that must cover every one used.
const cjkChars = resolve(directory, 'assets/fonts/NotoSerifSC-Liseur.txt');
const covered = new Set(existsSync(cjkChars) ? await readFile(cjkChars, 'utf8') : '');
for (const locale of locales) {
  const text = copies[locale].headline.replace(/<[^>]+>/g, '') + copies[locale].chips.join('');
  const missing = [...new Set(text)].filter(c => c.codePointAt(0)! >= 0x2e80 && !covered.has(c));
  if (missing.length) throw new Error(`${locale}: ${missing.join('')} missing from the CJK subset; run hack/feature-graphic-font`);
}

// What Play shows: the hero crop with the video's play button and the carousel arrow, and a search-card size.
const preview = (src: string) => `<!doctype html><html><body style="margin:0;width:1340px;height:540px;background:#fff;position:relative">
<div style="position:absolute;left:20px;top:20px;width:888px;height:500px;border-radius:12px;overflow:hidden;box-shadow:0 1px 4px #0005">
<img src="${src}" style="position:absolute;left:-68px;top:0;width:1024px;height:500px">
<div style="position:absolute;left:396px;top:202px;width:96px;height:96px;border-radius:50%;background:#0007"></div>
<div style="position:absolute;left:434px;top:230px;border-left:30px solid #fff;border-top:20px solid transparent;border-bottom:20px solid transparent"></div></div>
<div style="position:absolute;left:871px;top:233px;width:74px;height:74px;border-radius:50%;background:#fff;box-shadow:0 1px 6px #0006"></div>
<img src="${src}" style="position:absolute;left:960px;top:20px;width:360px;border-radius:8px;box-shadow:0 1px 4px #0005">
</body></html>`;

await prepare();
const review = resolve(root, 'build/promo/feature-graphic');
const reviewCopy = resolve(homedir(), 'tmp/liseur/feature-graphic');
for (const dir of [review, reviewCopy]) await rm(dir, {recursive: true, force: true});
await mkdir(review, {recursive: true});
const written: string[] = [];
const failures: string[] = [];
const browser = await puppeteer.launch({
  executablePath: process.env.PUPPETEER_EXECUTABLE_PATH, args: ['--allow-file-access-from-files'],
});
try {
  const page = await browser.newPage();
  for (const locale of locales) {
    const copy = copies[locale];
    await page.setViewport({width: 1024, height: 500, deviceScaleFactor: 1});
    await page.goto(pathToFileURL(resolve(directory, 'feature-graphic.html')).href, {waitUntil: 'networkidle0'});
    await page.evaluate(c => (window as any).apply(c), copy);
    await page.evaluate('document.fonts.ready');
    const lines = copy.headline.split(/<br\s*\/?>/).length;
    const problems: string[] = await page.evaluate(n => (window as any).problems(n), lines);
    if (problems.length) {
      failures.push(...problems.map(p => `${locale}: ${p}`));
      await page.screenshot({path: resolve(review, `${locale}-rejected.png`)});
      continue;
    }
    const out = resolve(root, 'fastlane/metadata/android', locale, 'images/featureGraphic.png');
    await mkdir(resolve(out, '..'), {recursive: true});
    await page.screenshot({path: out});
    written.push(out);
    const html = resolve(review, `${locale}-play-preview.html`);
    await writeFile(html, preview(pathToFileURL(out).href));
    await page.setViewport({width: 1340, height: 540});
    await page.goto(pathToFileURL(html).href, {waitUntil: 'networkidle0'});
    await page.screenshot({path: resolve(review, `${locale}-play-preview.png`)});
  }
} finally {
  await browser.close();
}
if (failures.length) throw new Error(`feature graphic layout:\n${failures.join('\n')}\nRejected renders are in ${review}`);

// Chromium writes RGBA; Play wants RGB, and the screenshot rules would pad 1024x500 to 2:1.
execFileSync(resolve(root, 'hack/store-images'), ['--feature-graphic', ...written], {stdio: 'inherit'});

await mkdir(reviewCopy, {recursive: true});
await cp(review, reviewCopy, {recursive: true, filter: f => !f.endsWith('.html')});
console.log(`Wrote ${written.length} feature graphics; Play previews in ${review} and ${reviewCopy}`);
