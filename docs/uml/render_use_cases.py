# -*- coding: utf-8 -*-
"""Render the nine use case diagrams and copy them into the report's img/."""
import io
import os
import shutil
import sys
import urllib.request

UML = r'D:\STAGE S2I\Application\PMS\docs\uml'
IMG = r'D:\STAGE S2I\Application\PMS\report\Rapport PFE TEKUP LATEX\img'
KROKI = 'https://kroki.io/plantuml/png'
PNG = b'\x89PNG\r\n\x1a\n'
UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')

# source path relative to docs/uml  ->  figure name in img/
JOBS = [('01-use-case.puml', 'use_case')]
JOBS += [(os.path.join('sprint', 'uc_sprint%d.puml' % i), 'uc_sprint%d' % i)
         for i in range(1, 9)]


def main():
    only = set(sys.argv[1:])
    failed = []
    for rel, out_name in JOBS:
        if only and out_name not in only:
            continue
        src = io.open(os.path.join(UML, rel), encoding='utf-8').read()
        req = urllib.request.Request(KROKI, data=src.encode('utf-8'),
                                     headers={'Content-Type': 'text/plain',
                                              'User-Agent': UA})
        try:
            data = urllib.request.urlopen(req, timeout=90).read()
        except Exception as exc:                     # noqa: BLE001 - reported
            failed.append((out_name, str(exc)[:140]))
            continue
        if not data.startswith(PNG):
            failed.append((out_name, data[:180].decode('utf-8', 'replace')))
            continue
        local = os.path.join(UML, os.path.dirname(rel), out_name + '.png')
        open(local, 'wb').write(data)
        shutil.copy(local, os.path.join(IMG, out_name + '.png'))
        print('  %-14s %7d bytes' % (out_name + '.png', len(data)))
    print('failed: %d' % len(failed))
    for n, w in failed:
        print('  FAILED %s: %s' % (n, w))
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
