# -*- coding: utf-8 -*-
"""Emit the global class diagram as a .drawio file.

Project sits in the middle with the other classes around it, associations are
plain lines carrying cardinalities at both ends, and every class keeps a visible
operations compartment even though none of them declares an operation.
"""
import io
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'class_global.drawio')
NL = chr(10)

TITLE_H = 34
ROW_H = 30
SEP_H = 8
OPS_H = 38
W = 250

CLASSES = {
    'Resource':         ['id : int', 'dailyRate : decimal', 'tccRate : decimal'],
    'User':             ['id : int', 'lastName : string', 'firstName : string',
                         'email : string', 'active : boolean'],
    'Role':             ['id : int', 'name : string', 'description : string'],
    'Permission':       ['id : int', 'code : string', 'module : string'],
    'TeamAssignment':   ['id : int', 'roleInTeam : string', 'startDate : date',
                         'endDate : date'],
    'ActualWorkload':   ['id : int', 'period : date', 'actualDays : decimal',
                         'validatedAt : date'],
    'WorkloadPlan':     ['id : int', 'period : date', 'plannedDays : decimal'],
    'Project':          ['id : int', 'code : string', 'client : string',
                         'status : string', 'initialBudget : decimal',
                         'currency : string'],
    'KpiSnapshot':      ['id : int', 'snapshotDate : date', 'eac : decimal',
                         'margin : decimal'],
    'BillingMilestone': ['id : int', 'label : string', 'percentage : decimal',
                         'amount : decimal', 'status : string'],
    'BacklogItem':      ['id : int', 'title : string', 'priority : string',
                         'estimateDays : decimal', 'status : string'],
    'Mission':          ['id : int', 'subject : string', 'startDate : date',
                         'endDate : date'],
    'Deliverable':      ['id : int', 'title : string', 'dueDate : date',
                         'status : string'],
    'Risk':             ['id : int', 'description : string',
                         'probability : string', 'impact : string'],
    'Sprint':           ['id : int', 'name : string', 'startDate : date',
                         'endDate : date', 'status : string'],
}

POS = {
    'Resource':         (40,   100),
    'User':             (520,  60),
    'Role':             (1000, 100),
    'Permission':       (1480, 100),
    'TeamAssignment':   (40,   410),
    'ActualWorkload':   (1000, 400),
    'WorkloadPlan':     (40,   730),
    'Project':          (520,  700),
    'KpiSnapshot':      (1000, 720),
    'BillingMilestone': (40,   1000),
    'BacklogItem':      (1000, 1010),
    'Mission':          (180,  1390),
    'Deliverable':      (460,  1390),
    'Risk':             (740,  1390),
    'Sprint':           (1020, 1390),
}

# source, exitX, exitY, target, entryX, entryY, label, cardSrc, cardTgt, diamond
REL = [
    ('User', 1, .45, 'Role', 0, .45, 'holds', '1..*', '1', None),
    ('Role', 1, .45, 'Permission', 0, .45, 'grants', '1..*', '1', None),
    ('Resource', 1, .40, 'User', 0, .30, 'cost of', '0..1', '1', None),
    ('Project', .30, 0, 'User', .30, 1, 'directed by', '1..*', '1', None),
    ('Project', .70, 0, 'User', .70, 1, 'managed by', '1..*', '0..1', None),
    ('TeamAssignment', 1, .25, 'User', 0, .75, 'member', '1..*', '1', None),
    ('Project', 0, .25, 'TeamAssignment', 1, .70, 'staffed by', '1', '1..*', None),
    ('Project', 0, .50, 'WorkloadPlan', 1, .45, 'forecasts', '1', '1..*', None),
    ('Project', 0, .78, 'BillingMilestone', 1, .30, 'invoiced by', '1', '1..*', None),
    ('Project', 1, .25, 'ActualWorkload', 0, .70, 'consumes', '1', '1..*', None),
    ('Project', 1, .50, 'KpiSnapshot', 0, .45, 'measured by', '1', '1..*', None),
    ('Project', 1, .78, 'BacklogItem', 0, .30, 'owns', '1', '1..*', 'comp'),
    ('Project', .25, 1, 'Mission', .55, 0, 'incurs', '1', '1..*', None),
    ('Project', .50, 1, 'Deliverable', .50, 0, 'produces', '1', '1..*', None),
    ('Project', .75, 1, 'Risk', .40, 0, 'tracks', '1', '1..*', None),
    ('Sprint', .50, 0, 'BacklogItem', .50, 1, 'gathers', '0..1', '1..*', 'aggr'),
]

CLS_STYLE = ('swimlane;fontStyle=1;align=center;verticalAlign=middle;'
             'startSize=%d;html=1;whiteSpace=wrap;'
             'fillColor=#FFFFFF;strokeColor=#33383D;fontColor=#16212B;'
             'fontSize=19;' % TITLE_H)
ATTR_STYLE = ('text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;'
              'spacingLeft=8;spacingRight=6;overflow=hidden;html=1;'
              'fontSize=16;fontColor=#16212B;points=[];portConstraint=eastwest;'
              'rotatable=0;')
SEP_STYLE = ('line;strokeWidth=1;strokeColor=#33383D;fillColor=none;align=left;'
             'verticalAlign=middle;spacingTop=-1;spacingLeft=3;spacingRight=3;'
             'rotatable=0;labelPosition=right;points=[];portConstraint=eastwest;')
OPS_STYLE = ('text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;'
             'spacingLeft=8;html=1;fontSize=16;points=[];portConstraint=eastwest;'
             'rotatable=0;')
CARD_STYLE = ('edgeLabel;html=1;align=center;verticalAlign=middle;resizable=0;'
              'points=[];fontSize=14;fontColor=#33383D;'
              'labelBackgroundColor=none;')


def esc(t):
    return (t.replace('&', '&amp;').replace('<', '&lt;')
             .replace('>', '&gt;').replace('"', '&quot;'))


def height(name):
    return TITLE_H + ROW_H * len(CLASSES[name]) + SEP_H + OPS_H


CARD_GAP = 17          # how far a multiplicity sits off its association


def anchor(name, fx, fy):
    px, py = POS[name]
    return px + W * fx, py + height(name) * fy


def perpendicular(a, ex, ey, b, nx, ny):
    """Offset that puts a multiplicity beside its association rather than on it.

    Perpendicular to the line, chosen so a near-horizontal association carries
    its multiplicities above it and a near-vertical one carries them to the
    right; that keeps every label on a predictable side of the diagram.
    """
    ax, ay = anchor(a, ex, ey)
    bx, by = anchor(b, nx, ny)
    dx, dy = bx - ax, by - ay
    n = (dx * dx + dy * dy) ** .5 or 1.0
    px, py = -dy / n, dx / n
    if abs(py) >= abs(px):
        if py > 0:
            px, py = -px, -py
    elif px < 0:
        px, py = -px, -py
    return int(round(px * CARD_GAP)), int(round(py * CARD_GAP))


def main():
    x = []
    x.append('<?xml version="1.0" encoding="UTF-8"?>')
    x.append('<mxfile host="drawio" version="31.1.8">')
    x.append('  <diagram name="Global class diagram">')
    x.append('    <mxGraphModel dx="1200" dy="800" grid="1" gridSize="10" '
             'page="1" pageScale="1" pageWidth="1169" pageHeight="826" '
             'math="0" shadow="0">')
    x.append('      <root>')
    x.append('        <mxCell id="0" />')
    x.append('        <mxCell id="1" parent="0" />')

    x.append('        <mxCell id="title" value="Global class diagram &#8212; PMS" '
             'style="text;html=1;align=center;verticalAlign=middle;fontSize=26;'
             'fontStyle=1;fontColor=#16212B;strokeColor=none;fillColor=none;" '
             'vertex="1" parent="1">')
    x.append('          <mxGeometry x="690" y="0" width="600" height="40" as="geometry" />')
    x.append('        </mxCell>')

    # ------------------------------------------------------------- classes --
    for name in CLASSES:
        px, py = POS[name]
        h = height(name)
        cid = 'c_' + name
        x.append('        <mxCell id="%s" value="%s" style="%s" vertex="1" parent="1">'
                 % (cid, esc(name), CLS_STYLE))
        x.append('          <mxGeometry x="%d" y="%d" width="%d" height="%d" as="geometry" />'
                 % (px, py, W, h))
        x.append('        </mxCell>')
        y = TITLE_H
        for i, attr in enumerate(CLASSES[name]):
            x.append('        <mxCell id="%s_a%d" value="%s" style="%s" vertex="1" parent="%s">'
                     % (cid, i, esc(attr), ATTR_STYLE, cid))
            x.append('          <mxGeometry y="%d" width="%d" height="%d" as="geometry" />'
                     % (y, W, ROW_H))
            x.append('        </mxCell>')
            y += ROW_H
        x.append('        <mxCell id="%s_sep" value="" style="%s" vertex="1" parent="%s">'
                 % (cid, SEP_STYLE, cid))
        x.append('          <mxGeometry y="%d" width="%d" height="%d" as="geometry" />'
                 % (y, W, SEP_H))
        x.append('        </mxCell>')
        # the operations compartment: kept visible although no class declares one
        x.append('        <mxCell id="%s_ops" value="" style="%s" vertex="1" parent="%s">'
                 % (cid, OPS_STYLE, cid))
        x.append('          <mxGeometry y="%d" width="%d" height="%d" as="geometry" />'
                 % (y + SEP_H, W, OPS_H))
        x.append('        </mxCell>')

    # ----------------------------------------------------------- relations --
    for i, (a, ex, ey, b, nx, ny, lab, ca, cb, dec) in enumerate(REL):
        eid = 'e%d' % i
        arrows = 'endArrow=none;startArrow=none;'
        if dec == 'comp':
            arrows = 'endArrow=none;startArrow=diamondThin;startFill=1;startSize=18;'
        elif dec == 'aggr':
            arrows = 'endArrow=none;startArrow=diamondThin;startFill=0;startSize=18;'
        style = ('edgeStyle=none;rounded=0;html=1;%s'
                 'strokeColor=#33383D;strokeWidth=1.6;fontSize=15;fontColor=#16212B;'
                 'labelBackgroundColor=#FFFFFF;'
                 'exitX=%s;exitY=%s;exitDx=0;exitDy=0;'
                 'entryX=%s;entryY=%s;entryDx=0;entryDy=0;'
                 % (arrows, ex, ey, nx, ny))
        x.append('        <mxCell id="%s" value="%s" style="%s" edge="1" parent="1" '
                 'source="c_%s" target="c_%s">' % (eid, esc(lab), style, a, b))
        x.append('          <mxGeometry relative="1" as="geometry" />')
        x.append('        </mxCell>')
        # A multiplicity must sit BESIDE the association, not on it: left on the
        # line it interrupts the stroke and reads as a break in the association.
        # Offset each one perpendicular to the line it belongs to.
        odx, ody = perpendicular(a, ex, ey, b, nx, ny)
        # a cardinality on top of a diamond also has its label background clip
        # the diamond, so pull that end in along the line as well
        src_pos = '-0.52' if dec else '-0.78'
        for suffix, pos, card in (('s', src_pos, ca), ('t', '0.78', cb)):
            x.append('        <mxCell id="%s_%s" value="%s" style="%s" vertex="1" '
                     'connectable="0" parent="%s">' % (eid, suffix, esc(card), CARD_STYLE, eid))
            x.append('          <mxGeometry x="%s" relative="1" as="geometry">' % pos)
            x.append('            <mxPoint x="%d" y="%d" as="offset" />' % (odx, ody))
            x.append('          </mxGeometry>')
            x.append('        </mxCell>')

    x.append('      </root>')
    x.append('    </mxGraphModel>')
    x.append('  </diagram>')
    x.append('</mxfile>')

    io.open(OUT, 'w', encoding='utf-8', newline=NL).write(NL.join(x) + NL)
    print('wrote ' + OUT)


if __name__ == '__main__':
    main()
