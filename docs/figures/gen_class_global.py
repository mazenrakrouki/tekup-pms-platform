# -*- coding: utf-8 -*-
"""Emit the global class diagram as SVG.

PlantUML delegates placement to GraphViz, which will not put one class in the
middle and arrange the rest around it. The supervisor's reference layout does
exactly that, so this diagram is laid out by hand while the rest stay in
PlantUML. Relations follow the corrected model: Project composes BacklogItem,
Sprint aggregates the items it gathers, and there is no Project--Sprint edge.
"""
import io
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'class_global.svg')
NL = chr(10)

# ----------------------------------------------------------------- geometry --
HEAD_H = 38          # name compartment
ROW_H = 24           # one attribute
PAD_Y = 10           # padding above/below the attribute block
EMPTY_H = 34         # the operations compartment, kept visible though empty
CHAR_W = 8.05        # at 15px in the chosen stack
MIN_W = 236

CLASSES = {
    'User':             ['id : int', 'lastName : string', 'firstName : string',
                         'email : string', 'active : boolean'],
    'Role':             ['id : int', 'name : string', 'description : string'],
    'Permission':       ['id : int', 'code : string', 'module : string'],
    'Resource':         ['id : int', 'dailyRate : decimal', 'tccRate : decimal'],
    'Project':          ['id : int', 'code : string', 'client : string',
                         'status : string', 'initialBudget : decimal',
                         'currency : string'],
    'TeamAssignment':   ['id : int', 'roleInTeam : string', 'startDate : date',
                         'endDate : date'],
    'WorkloadPlan':     ['id : int', 'period : date', 'plannedDays : decimal'],
    'ActualWorkload':   ['id : int', 'period : date', 'actualDays : decimal',
                         'validatedAt : date'],
    'BillingMilestone': ['id : int', 'label : string', 'percentage : decimal',
                         'amount : decimal', 'status : string'],
    'Mission':          ['id : int', 'subject : string', 'startDate : date',
                         'endDate : date'],
    'Sprint':           ['id : int', 'name : string', 'startDate : date',
                         'endDate : date', 'status : string'],
    'BacklogItem':      ['id : int', 'title : string', 'priority : string',
                         'estimateDays : decimal', 'status : string'],
    'Deliverable':      ['id : int', 'title : string', 'dueDate : date',
                         'status : string'],
    'Risk':             ['id : int', 'description : string',
                         'probability : string', 'impact : string'],
    'KpiSnapshot':      ['id : int', 'snapshotDate : date', 'eac : decimal',
                         'margin : decimal'],
}

# top-left corner of each class; Project sits in the middle, the rest around it
POS = {
    'Resource':         (60,   170),
    'User':             (800,  120),
    'Role':             (1330, 150),
    'Permission':       (1730, 150),
    'TeamAssignment':   (60,   470),
    'WorkloadPlan':     (60,   790),
    'BillingMilestone': (60,  1040),
    'Project':          (790,  660),
    'ActualWorkload':   (1330, 500),
    'KpiSnapshot':      (1330, 790),
    'BacklogItem':      (1330, 1060),
    'Sprint':           (1330, 1510),
    'Mission':          (430,  1410),
    'Deliverable':      (790,  1410),
    'Risk':             (1080, 1410),
}


def size(name):
    lines = CLASSES[name]
    w = max(MIN_W, int(max(len(t) for t in lines + [name]) * CHAR_W) + 34)
    h = HEAD_H + PAD_Y * 2 + ROW_H * len(lines) + EMPTY_H
    return w, h


BOX = {n: (POS[n][0], POS[n][1]) + size(n) for n in CLASSES}


def anchor(name, side, frac=0.5):
    x, y, w, h = BOX[name]
    if side == 'l':
        return x, y + h * frac
    if side == 'r':
        return x + w, y + h * frac
    if side == 't':
        return x + w * frac, y
    return x + w * frac, y + h


# (A, sideA, fracA, B, sideB, fracB, label, cardA, cardB, decoration)
REL = [
    ('User', 'r', .45, 'Role', 'l', .45, 'holds', '1..*', '1', None),
    ('Role', 'r', .45, 'Permission', 'l', .45, 'grants', '1..*', '1', None),
    ('Resource', 'r', .40, 'User', 'l', .30, 'cost of', '0..1', '1', None),
    ('Project', 't', .30, 'User', 'b', .30, 'directed by', '1..*', '1', None, .34),
    ('Project', 't', .62, 'User', 'b', .70, 'managed by', '1..*', '0..1', None, .66),
    ('TeamAssignment', 'r', .25, 'User', 'l', .75, 'member', '1..*', '1', None),
    ('Project', 'l', .22, 'TeamAssignment', 'r', .72, 'staffed by', '1', '1..*', None),
    ('Project', 'l', .52, 'WorkloadPlan', 'r', .40, 'forecasts', '1', '1..*', None),
    ('Project', 'l', .80, 'BillingMilestone', 'r', .32, 'invoiced by', '1', '1..*', None),
    ('Project', 'r', .22, 'ActualWorkload', 'l', .68, 'consumes', '1', '1..*', None),
    ('Project', 'r', .52, 'KpiSnapshot', 'l', .40, 'measured by', '1', '1..*', None),
    ('Project', 'r', .82, 'BacklogItem', 'l', .28, 'owns', '1', '1..*', 'comp'),
    ('Project', 'b', .18, 'Mission', 't', .60, 'incurs', '1', '1..*', None),
    ('Project', 'b', .48, 'Deliverable', 't', .50, 'produces', '1', '1..*', None),
    ('Project', 'b', .78, 'Risk', 't', .40, 'tracks', '1', '1..*', None),
    ('Sprint', 't', .50, 'BacklogItem', 'b', .50, 'gathers', '0..1', '1..*', 'aggr'),
]


def esc(t):
    return t.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def diamond(px, py, qx, qy, filled):
    """A UML diamond at (px,py), pointing along the line towards (qx,qy)."""
    dx, dy = qx - px, qy - py
    n = (dx * dx + dy * dy) ** .5 or 1
    ux, uy = dx / n, dy / n
    vx, vy = -uy, ux
    L, W = 27.0, 11.0
    pts = [(px, py),
           (px + ux * L / 2 + vx * W, py + uy * L / 2 + vy * W),
           (px + ux * L, py + uy * L),
           (px + ux * L / 2 - vx * W, py + uy * L / 2 - vy * W)]
    fill = '#33383D' if filled else '#FFFFFF'
    return ('<polygon points="%s" fill="%s" stroke="#33383D" stroke-width="1.6"/>'
            % (' '.join('%.1f,%.1f' % p for p in pts), fill))


def main():
    out = []
    w_total = max(x + w for x, y, w, h in BOX.values()) + 70
    h_total = max(y + h for x, y, w, h in BOX.values()) + 70
    out.append('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d">' % (w_total, h_total))
    out.append('''  <style>
    text { font-family: "Segoe UI","Helvetica Neue",Arial,sans-serif; fill:#16212B; }
    .cn { font-size:17px; font-weight:700; }
    .at { font-size:15px; }
    .lb { font-size:15px; font-weight:600; }
    .cd { font-size:14px; fill:#33383D; }
    .bx { fill:#FFFFFF; stroke:#33383D; stroke-width:1.7; }
    .sp { stroke:#33383D; stroke-width:1.3; }
    .ln { fill:none; stroke:#33383D; stroke-width:1.6; }
  </style>''')
    out.append('  <rect x="0" y="0" width="%d" height="%d" fill="#FFFFFF"/>' % (w_total, h_total))
    out.append('  <text class="cn" x="%d" y="52" text-anchor="middle" style="font-size:26px;">'
               'Global class diagram --- PMS</text>' % (w_total / 2))

    # ------------------------------------------------------------- relations --
    labels = []
    for rel in REL:
        a, sa, fa, b, sb, fb, lab, ca, cb, dec = rel[:10]
        lpos = rel[10] if len(rel) > 10 else .5
        ax, ay = anchor(a, sa, fa)
        bx, by = anchor(b, sb, fb)
        out.append('  <line class="ln" x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f"/>' % (ax, ay, bx, by))
        if dec:
            out.append('  ' + diamond(ax, ay, bx, by, dec == 'comp'))
        mx, my = ax + (bx - ax) * lpos, ay + (by - ay) * lpos
        labels.append((mx, my, lab))
        # cardinalities, nudged inward from each end
        ends = ((ax, ay, bx, by, ca, bool(dec)), (bx, by, ax, ay, cb, False))
        for (px, py, qx, qy, card, near_diamond) in ends:
            dx, dy = qx - px, qy - py
            n = (dx * dx + dy * dy) ** .5 or 1
            step = 58 if near_diamond else 32
            ox, oy = px + dx / n * step, py + dy / n * step
            labels.append((ox, oy - 9, card, 'cd'))

    for item in labels:
        mx, my, txt = item[0], item[1], item[2]
        cls = item[3] if len(item) > 3 else 'lb'
        fs = 14 if cls == 'cd' else 15
        wpx = len(txt) * (fs * 0.55) + 10
        out.append('  <rect x="%.1f" y="%.1f" width="%.1f" height="%d" fill="#FFFFFF"/>'
                   % (mx - wpx / 2, my - fs * 0.78, wpx, fs + 5))
        out.append('  <text class="%s" x="%.1f" y="%.1f" text-anchor="middle">%s</text>'
                   % (cls, mx, my + fs * 0.36, esc(txt)))

    # --------------------------------------------------------------- classes --
    for name in CLASSES:
        x, y, w, h = BOX[name]
        attrs = CLASSES[name]
        y1 = y + HEAD_H                                   # end of the name band
        y2 = y1 + PAD_Y * 2 + ROW_H * len(attrs)          # end of the attributes
        out.append('  <rect class="bx" x="%d" y="%d" width="%d" height="%d" rx="3"/>' % (x, y, w, h))
        out.append('  <line class="sp" x1="%d" y1="%d" x2="%d" y2="%d"/>' % (x, y1, x + w, y1))
        out.append('  <line class="sp" x1="%d" y1="%d" x2="%d" y2="%d"/>' % (x, y2, x + w, y2))
        out.append('  <text class="cn" x="%d" y="%d" text-anchor="middle">%s</text>'
                   % (x + w / 2, y + 26, esc(name)))
        for i, a in enumerate(attrs):
            out.append('  <text class="at" x="%d" y="%d">%s</text>'
                       % (x + 16, y1 + PAD_Y + 17 + i * ROW_H, esc(a)))

    out.append('</svg>')
    io.open(OUT, 'w', encoding='utf-8', newline=NL).write(NL.join(out) + NL)
    print('wrote %s  (%dx%d)' % (OUT, w_total, h_total))


if __name__ == '__main__':
    main()
