# -*- coding: utf-8 -*-
"""Render the console/*.html capture pages to PNG.

render.js drives puppeteer, which is not installed here, and the pages need no
scripting: Chrome's own headless screenshot is enough. It cannot size the window
to the content, so the shot is taken tall and Pillow trims it back to the terminal
window, which is the only thing on an otherwise white page.
"""
import io
import os
import subprocess
import sys
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'console')
OUT = r'D:\STAGE S2I\Application\PMS\report\Rapport PFE TEKUP LATEX\img'
CHROME = r'C:\Program Files\Google\Chrome\Application\chrome.exe'
SCALE = 3
PAD = 10 * SCALE


def shoot(html, png):
    subprocess.run([CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars',
                    '--force-device-scale-factor=%d' % SCALE,
                    '--window-size=1280,2600', '--screenshot=' + png,
                    'file:///' + html.replace(chr(92), '/')],
                   check=True, capture_output=True)
    im = Image.open(png).convert('RGB')
    # everything but the terminal window is pure white; crop to what is not
    bg = Image.new('RGB', im.size, (255, 255, 255))
    box = Image.new('RGB', im.size)
    box.paste(im)
    diff = box
    bbox = None
    from PIL import ImageChops
    bbox = ImageChops.difference(diff, bg).getbbox()
    if bbox:
        l, t, r, b = bbox
        im = im.crop((max(0, l - PAD), max(0, t - PAD),
                      min(im.size[0], r + PAD), min(im.size[1], b + PAD)))
    im.save(png)
    return im.size


def main():
    names = sys.argv[1:] or [f[:-5] for f in sorted(os.listdir(SRC)) if f.endswith('.html')]
    for n in names:
        html = os.path.join(SRC, n + '.html')
        png = os.path.join(OUT, n + '.png')
        w, h = shoot(html, png)
        print('  %s   %dx%d' % (n + '.png', w, h))


if __name__ == '__main__':
    main()
