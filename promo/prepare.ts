import {cp, mkdir, readdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';

export const directory = fileURLToPath(new URL('.', import.meta.url));
export async function prepare() {
  const root = resolve(directory, '..');
  await mkdir(resolve(directory, 'assets'), {recursive: true});
  await mkdir(resolve(directory, 'public/fonts'), {recursive: true});
  await cp(resolve(root, 'docs/screenshots'), resolve(directory, 'public/screenshots'), {recursive: true});
  for (const font of ['Literata.ttf', 'Inter.ttf']) {
    await cp(resolve(root, 'app/src/main/assets/fonts', font), resolve(directory, 'public/fonts', font));
  }
  await cp(resolve(directory, 'assets/fonts'), resolve(directory, 'public/fonts'),
    {recursive: true, filter: file => !file.endsWith('.txt')});
  await cp(resolve(root, 'app/src/main/res/drawable-xxxhdpi/ic_brand_emblem.webp'), resolve(directory, 'public/emblem.webp'));
  const music = (await readdir(resolve(directory, 'assets'))).filter(name => /^music\.(mp3|wav|m4a|ogg|flac)$/.test(name));
  if (music.length > 1) throw new Error('Keep only one music track in promo/assets.');
  return music.length ? resolve(directory, 'assets', music[0]) : undefined;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) await prepare();
