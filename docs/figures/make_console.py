# -*- coding: utf-8 -*-
"""Turn real captured console output into terminal-styled HTML, for rendering to PNG.

Chapter 7 claimed a test suite, a production build and a pipeline, and showed none
of them. The claims were true but the report asked the reader to take them on
trust. These pages carry the actual output of the commands, run on this machine,
so the figures in the chapter are reproductions of a real run rather than mock-ups.

The window frame is drawn as a Windows one, because that is the machine the
commands were run on: the prompts inside read PS D:\... and a macOS title bar
above them would contradict the very output it frames.

Nothing here invents a line. Each page is an excerpt of a captured log file: the
lines are copied verbatim, and only whole uninteresting stretches are dropped, with
the cut marked. Warnings are kept -- the frontend build really does exceed its
bundle budget, and hiding that would make the figure a lie.
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'console')
# The captured runs are kept beside this script, not in a temp folder, so the
# figures can be regenerated from the repository alone.
LOGS = os.path.join(HERE, 'console', 'logs')
NL = chr(10)

# ANSI SGR -> css class, for the logs that carry colour (the Angular build does)
SGR = {'30': 'k', '31': 'r', '32': 'g', '33': 'y', '34': 'b', '35': 'm', '36': 'c',
       '37': 'w', '90': 'd', '91': 'r', '92': 'g', '93': 'y', '94': 'b', '95': 'm',
       '96': 'c', '1': 'bold', '2': 'dim', '4': 'ul'}
ANSI = re.compile(r'\x1b\[([0-9;]*)m')

# markers for lines that are not raw output: a command typed, and a stretch left out
CMD = chr(0) + 'cmd' + chr(0)
CUT = chr(0) + 'cut' + chr(0)

CSS = """
:root { color-scheme: light; }
* { box-sizing: border-box; }
body { margin: 0; background: #FFFFFF; font-family: Arial, Helvetica, sans-serif; }
.term { width: 1180px; border: 1px solid #C6CCD2; border-radius: 8px; overflow: hidden;
        box-shadow: 0 1px 3px rgba(16,24,32,.10); background: #1C2128; }
.bar  { height: 32px; background: #E7EBEF; border-bottom: 1px solid #C6CCD2;
        display: flex; align-items: center; padding: 0 0 0 13px; }
.ttl  { flex: 1 1 auto; font-size: 12.5px; color: #41494F; letter-spacing: .1px; }
.win  { display: flex; height: 100%; }
.wb   { width: 44px; display: flex; align-items: center; justify-content: center;
        font-size: 13px; color: #41494F; }
pre   { margin: 0; padding: 13px 15px 15px; color: #D5DCE3; background: #1C2128;
        font-family: Consolas, "DejaVu Sans Mono", monospace;
        font-size: 12.9px; line-height: 1.47; white-space: pre; }
.p    { color: #63E6A0; }                 /* the prompt */
.cmd  { color: #FFFFFF; font-weight: 700; }
.cut  { color: #7C8896; font-style: italic; }
.k{color:#5A6672} .r{color:#FF7B72} .g{color:#7EE787} .y{color:#E3B341}
.b{color:#79C0FF} .m{color:#D2A8FF} .c{color:#76E3EA} .w{color:#D5DCE3}
.d{color:#7C8896} .dim{color:#8B95A1} .bold{font-weight:700} .ul{text-decoration:underline}
"""


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def ansi_to_html(line):
    """Keep the colours the terminal actually showed."""
    out, pos, open_n = [], 0, 0
    for m in ANSI.finditer(line):
        out.append(esc(line[pos:m.start()]))
        pos = m.end()
        codes = [c for c in m.group(1).split(';') if c]
        if not codes or codes == ['0']:
            out.append('</span>' * open_n)
            open_n = 0
            continue
        for c in codes:
            cls = SGR.get(c)
            if cls:
                out.append('<span class="%s">' % cls)
                open_n += 1
    out.append(esc(line[pos:]))
    out.append('</span>' * open_n)
    return ''.join(out)


def page(title, prompt, command, body_lines, keep_ansi):
    # One capture holds a whole session rather than a single command, so the
    # prompt is optional here and those lines carry their own.
    rows = []
    if command:
        rows += ['<span class="p">%s</span> <span class="cmd">%s</span>'
                 % (esc(prompt), esc(command)), '']
    for ln in body_lines:
        if ln.startswith(CMD):
            rows.append('<span class="p">$</span> <span class="cmd">%s</span>'
                        % esc(ln[len(CMD) + 2:]))
        elif ln.startswith(CUT):
            rows.append('<span class="cut">%s</span>' % esc(ln[len(CUT):]))
        else:
            rows.append(ansi_to_html(ln) if keep_ansi else esc(ANSI.sub('', ln)))
    return ('<meta charset="utf-8"><title>%s</title><style>%s</style>'
            '<div class="term"><div class="bar">'
            '<span class="ttl">%s</span>'
            '<span class="win"><span class="wb">&#8211;</span>'
            '<span class="wb">&#9633;</span><span class="wb">&#215;</span></span>'
            '</div><pre>%s</pre></div>'
            % (esc(title), CSS, esc(title), NL.join(rows)))


def cut(text):
    return CUT + text


def build_docker_page():
    """The stack under Docker, and the pipeline's own smoke checks run against it.

    Unlike the other two, this log holds a whole session: a line opening with a
    dollar is a command and is set as one. The access token is shortened to its
    first characters by the capture itself -- a report should not carry a usable
    credential, even one belonging to a demonstration account.
    """
    body = []
    for ln in read('docker_stack.log'):
        s = ln.rstrip()
        if s.startswith('$ '):
            body.append(CMD + s)
        elif s.startswith('#'):
            body.append(CUT + s)
        else:
            body.append(s)
    while body and not body[-1].strip():
        body.pop()
    return page('docker compose  ' + chr(8212) + '  PMS stack', None, None, body, False)


def read(name):
    p = os.path.join(LOGS, name)
    if not os.path.exists(p):
        sys.exit('missing capture: ' + p)
    return io.open(p, encoding='utf-8', errors='replace').read().split(NL)


def build_test_page():
    """mvn test: every class result, the total, and the build verdict."""
    log = read('mvn_test.log')
    keep = []
    for ln in log:
        s = ln.rstrip()
        if re.search(r'Tests run: \d+, Failures', s) or s.startswith('[INFO] Running com.pms'):
            keep.append(s)
        elif 'jacoco:0.8.12:report' in s or s.startswith('[INFO] BUILD') \
                or s.startswith('[INFO] Total time') or s.startswith('[INFO] Finished at'):
            keep.append(s)
    # the per-class pairs, then the summary block
    pairs = [l for l in keep if 'com.pms' in l]
    tail = [l for l in keep if 'com.pms' not in l
            and not re.search(r'Tests run: \d+, Failures', l)]
    total = [l for l in log if re.match(r'^\[INFO\] Tests run: \d+, Failures: \d+, Errors', l.strip())
             and 'in com.pms' not in l]
    head = [l.rstrip() for l in log
            if l.startswith('[INFO] Scanning for projects')
            or l.startswith('[INFO] Building PMS Backend')]
    body = (head + [cut('[ ... dependency resolution, compilation, Spring test contexts ... ]'),
                    ''] + pairs + [''] +
            ['[INFO] Results:', ''] + [t.rstrip() for t in total] + [''] + tail)
    return page('mvn test  —  pms-backend', 'PS D:\\...\\PMS\\backend>', 'mvn -B test', body, False)


def build_frontend_page():
    """ng build: the bundle table, the total, and the warnings as they came out."""
    log = read('npm_build.log')
    start = next(i for i, l in enumerate(log) if 'Initial chunk files' in l)
    end = next(i for i, l in enumerate(log) if 'Initial total' in l)
    body = [l.rstrip() for l in log[start:end + 1]]
    lazy = [l for l in log if 'more lazy chunks' in l]
    done = [l for l in log if 'Application bundle generation complete' in l]
    budget = [l for l in log if 'bundle initial exceeded maximum budget' in l]
    outloc = [l for l in log if l.startswith('Output location')]
    body += ['', cut('[ ... 15 lazy chunk files listed, one per lazily loaded route ... ]')] + \
            [l.rstrip() for l in lazy + done] + [''] + [l.rstrip() for l in budget] + \
            [''] + [l.rstrip() for l in outloc]
    return page('npm run build  —  pms-frontend', 'PS D:\\...\\frontend\\pms-frontend>',
                'npm run build', body, True)


def main():
    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    for name, html in (('cap_docker_stack', build_docker_page()),
                       ('cap_test_run', build_test_page()),
                       ('cap_frontend_build', build_frontend_page())):
        p = os.path.join(OUT, name + '.html')
        io.open(p, 'w', encoding='utf-8', newline=NL).write(html)
        print('wrote ' + os.path.relpath(p, HERE))


if __name__ == '__main__':
    main()
