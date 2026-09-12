# -*- coding: utf-8 -*-
"""Emit the hand-laid-out class diagrams as .drawio files.

PlantUML delegates placement to GraphViz, which will neither put one class in the
middle with the rest around it, nor keep several relations arriving at the same
class far enough apart to be read. Both were asked for, so these diagrams are
placed by hand. The rest of the class diagrams stay in docs/uml/ as PlantUML.

Every class keeps a visible operations compartment even where it declares no
operation: `hide empty methods` suppresses it, and the supervisor's reference
diagram shows it.
"""
import io
import os
import sys

OUT_DIR = os.path.dirname(os.path.abspath(__file__))
NL = chr(10)

HEAD_H = 38          # name compartment
ROW_H = 24           # one attribute
PAD_Y = 10           # padding above and below the attribute block
EMPTY_H = 34         # the operations compartment, kept visible though empty
CHAR_W = 8.05
MIN_W = 236
CARD_GAP = 17        # how far a multiplicity sits off its association

CLS_STYLE = ('swimlane;fontStyle=1;align=center;verticalAlign=middle;'
             'startSize=%d;html=1;whiteSpace=wrap;fillColor=#FFFFFF;'
             'strokeColor=#33383D;fontColor=#16212B;fontSize=19;' % HEAD_H)
ATTR_STYLE = ('text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;'
              'spacingLeft=8;spacingRight=6;overflow=hidden;html=1;fontSize=16;'
              'fontColor=#16212B;points=[];portConstraint=eastwest;rotatable=0;')
SEP_STYLE = ('line;strokeWidth=1;strokeColor=#33383D;fillColor=none;align=left;'
             'verticalAlign=middle;spacingTop=-1;spacingLeft=3;spacingRight=3;'
             'rotatable=0;labelPosition=right;points=[];portConstraint=eastwest;')
OPS_STYLE = ('text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;'
             'spacingLeft=8;html=1;fontSize=16;points=[];portConstraint=eastwest;'
             'rotatable=0;')
CARD_STYLE = ('edgeLabel;html=1;align=center;verticalAlign=middle;resizable=0;'
              'points=[];fontSize=14;fontColor=#33383D;labelBackgroundColor=none;')
NOTE_STYLE = ('shape=note;whiteSpace=wrap;html=1;size=14;fontSize=14;'
              'fontColor=#4A5967;strokeColor=#AAAAAA;fillColor=#FAFAFA;align=left;'
              'spacingLeft=8;verticalAlign=middle;')
NOTELINK = ('edgeStyle=none;rounded=0;html=1;dashed=1;endArrow=none;startArrow=none;'
            'strokeColor=#AAAAAA;strokeWidth=1;')


def esc(t):
    return (t.replace('&', '&amp;').replace('<', '&lt;')
             .replace('>', '&gt;').replace('"', '&quot;'))


class Diagram(object):
    def __init__(self, spec):
        self.title = spec['title']
        self.classes = spec['classes']
        self.pos = spec['pos']
        self.rel = spec['rel']
        self.note = spec.get('note')
        self.box = {}
        for n in self.classes:
            self.box[n] = self.pos[n] + self.size(n)

    def size(self, name):
        lines = self.classes[name]
        w = max(MIN_W, int(max(len(t) for t in lines + [name]) * CHAR_W) + 34)
        h = HEAD_H + PAD_Y * 2 + ROW_H * len(lines) + EMPTY_H
        return w, h

    def anchor(self, name, fx, fy):
        x, y, w, h = self.box[name]
        return x + w * fx, y + h * fy

    def perpendicular(self, a, ex, ey, b, nx, ny):
        """Offset putting a multiplicity beside its association, not on it."""
        ax, ay = self.anchor(a, ex, ey)
        bx, by = self.anchor(b, nx, ny)
        dx, dy = bx - ax, by - ay
        n = (dx * dx + dy * dy) ** .5 or 1.0
        px, py = -dy / n, dx / n
        if abs(py) >= abs(px):
            if py > 0:
                px, py = -px, -py
        elif px < 0:
            px, py = -px, -py
        return int(round(px * CARD_GAP)), int(round(py * CARD_GAP))

    def diamond_style(self, dec):
        if dec == 'comp':
            return 'endArrow=none;startArrow=diamondThin;startFill=1;startSize=18;'
        if dec == 'aggr':
            return 'endArrow=none;startArrow=diamondThin;startFill=0;startSize=18;'
        if dec == 'dep':
            return 'endArrow=open;endFill=0;endSize=10;startArrow=none;dashed=1;'
        return 'endArrow=none;startArrow=none;'

    def render(self):
        w_total = max(x + w for x, y, w, h in self.box.values()) + 70
        h_total = max(y + h for x, y, w, h in self.box.values()) + 70
        if self.note:
            _, nx, ny, nw, nh, _ = self.note
            w_total = max(w_total, nx + nw + 70)
            h_total = max(h_total, ny + nh + 70)

        x = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<mxfile host="drawio" version="31.1.8">',
             '  <diagram name="%s">' % esc(self.title),
             '    <mxGraphModel dx="1200" dy="800" grid="1" gridSize="10" page="1" '
             'pageScale="1" pageWidth="1169" pageHeight="826" math="0" shadow="0">',
             '      <root>',
             '        <mxCell id="0" />',
             '        <mxCell id="1" parent="0" />']

        x.append('        <mxCell id="title" value="%s" style="text;html=1;align=center;'
                 'verticalAlign=middle;fontSize=26;fontStyle=1;fontColor=#16212B;'
                 'strokeColor=none;fillColor=none;" vertex="1" parent="1">' % esc(self.title))
        x.append('          <mxGeometry x="0" y="10" width="%d" height="44" as="geometry" />'
                 % w_total)
        x.append('        </mxCell>')

        for name in self.classes:
            cx, cy, cw, ch = self.box[name]
            cid = 'c_' + name
            x.append('        <mxCell id="%s" value="%s" style="%s" vertex="1" parent="1">'
                     % (cid, esc(name), CLS_STYLE))
            x.append('          <mxGeometry x="%d" y="%d" width="%d" height="%d" as="geometry" />'
                     % (cx, cy, cw, ch))
            x.append('        </mxCell>')
            y = HEAD_H
            for i, attr in enumerate(self.classes[name]):
                x.append('        <mxCell id="%s_a%d" value="%s" style="%s" vertex="1" parent="%s">'
                         % (cid, i, esc(attr), ATTR_STYLE, cid))
                x.append('          <mxGeometry y="%d" width="%d" height="%d" as="geometry" />'
                         % (y + PAD_Y, cw, ROW_H))
                x.append('        </mxCell>')
                y += ROW_H
            y += PAD_Y * 2
            x.append('        <mxCell id="%s_sep" value="" style="%s" vertex="1" parent="%s">'
                     % (cid, SEP_STYLE, cid))
            x.append('          <mxGeometry y="%d" width="%d" height="8" as="geometry" />'
                     % (y, cw))
            x.append('        </mxCell>')
            x.append('        <mxCell id="%s_ops" value="" style="%s" vertex="1" parent="%s">'
                     % (cid, OPS_STYLE, cid))
            x.append('          <mxGeometry y="%d" width="%d" height="%d" as="geometry" />'
                     % (y + 8, cw, EMPTY_H))
            x.append('        </mxCell>')

        for i, r in enumerate(self.rel):
            a, ex, ey, b, nx, ny, lab, ca, cb, dec = r[:10]
            eid = 'e%d' % i
            style = ('edgeStyle=none;rounded=0;html=1;%s'
                     'strokeColor=#33383D;strokeWidth=1.6;fontSize=15;fontColor=#16212B;'
                     'labelBackgroundColor=#FFFFFF;'
                     'exitX=%s;exitY=%s;exitDx=0;exitDy=0;'
                     'entryX=%s;entryY=%s;entryDx=0;entryDy=0;'
                     % (self.diamond_style(dec), ex, ey, nx, ny))
            x.append('        <mxCell id="%s" value="%s" style="%s" edge="1" parent="1" '
                     'source="c_%s" target="c_%s">' % (eid, esc(lab), style, a, b))
            x.append('          <mxGeometry relative="1" as="geometry" />')
            x.append('        </mxCell>')

            odx, ody = self.perpendicular(a, ex, ey, b, nx, ny)
            src_pos = '-0.52' if dec in ('comp', 'aggr') else '-0.78'
            for suffix, pos, card in (('s', src_pos, ca), ('t', '0.78', cb)):
                if not card:
                    continue
                x.append('        <mxCell id="%s_%s" value="%s" style="%s" vertex="1" '
                         'connectable="0" parent="%s">'
                         % (eid, suffix, esc(card), CARD_STYLE, eid))
                x.append('          <mxGeometry x="%s" relative="1" as="geometry">' % pos)
                x.append('            <mxPoint x="%d" y="%d" as="offset" />' % (odx, ody))
                x.append('          </mxGeometry>')
                x.append('        </mxCell>')

        if self.note:
            text, nx, ny, nw, nh, anchor = self.note
            x.append('        <mxCell id="note" value="%s" style="%s" vertex="1" parent="1">'
                     % (esc(text), NOTE_STYLE))
            x.append('          <mxGeometry x="%d" y="%d" width="%d" height="%d" as="geometry" />'
                     % (nx, ny, nw, nh))
            x.append('        </mxCell>')
            x.append('        <mxCell id="nl" style="%s" edge="1" parent="1" source="note" '
                     'target="c_%s">' % (NOTELINK, anchor))
            x.append('          <mxGeometry relative="1" as="geometry" />')
            x.append('        </mxCell>')

        x += ['      </root>', '    </mxGraphModel>', '  </diagram>', '</mxfile>']
        return NL.join(x) + NL


# ------------------------------------------------------------ global model --
CLASS_GLOBAL = {
    'title': 'Global class diagram — PMS',
    'classes': {
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
    },
    'pos': {
        'Resource': (40, 100), 'User': (520, 60), 'Role': (1000, 100),
        'Permission': (1480, 100), 'TeamAssignment': (40, 410),
        'ActualWorkload': (1000, 400), 'WorkloadPlan': (40, 730),
        'Project': (520, 700), 'KpiSnapshot': (1000, 720),
        'BillingMilestone': (40, 1000), 'BacklogItem': (1000, 1010),
        'Mission': (180, 1390), 'Deliverable': (460, 1390),
        'Risk': (740, 1390), 'Sprint': (1020, 1390),
    },
    'rel': [
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
    ],
}

# ------------------------------------------------------- sprint 7 governance --
# Project carries its real identity here rather than two fields, and the four
# governance registers are spread wide beneath it, so each composition leaves
# Project at its own point instead of four diamonds stacking in one corner.
CLASS_GOVERNANCE = {
    'title': 'Classes — Project governance',
    'classes': {
        'Project':            ['id : int', 'code : string', 'client : string',
                               'status : string', 'initialBudget : decimal',
                               'currency : string'],
        'Deliverable':        ['title : string', 'dueDate : date',
                               'status : DeliverableStatus'],
        'Risk':               ['description : string', 'probability : RiskLevel',
                               'impact : RiskLevel', 'status : RiskStatus',
                               'mitigationPlan : string'],
        'Stakeholder':        ['name : string', 'role : string',
                               'influence : RiskLevel'],
        'ChangeRequest':      ['title : string', 'priority : ChangePriority',
                               'status : ChangeStatus', 'requestDate : date',
                               'decisionDate : date'],
        'DeliverableStatus':  ['PENDING', 'IN_PROGRESS', 'DELIVERED', 'VALIDATED'],
        'RiskStatus':         ['OPEN', 'MITIGATED', 'CLOSED'],
        'RiskLevel':          ['LOW', 'MEDIUM', 'HIGH'],
        'ChangePriority':     ['LOW', 'NORMAL', 'HIGH', 'CRITICAL'],
        'ChangeStatus':       ['PENDING', 'APPROVED', 'REJECTED'],
    },
    'pos': {
        'Project': (640, 150),
        'Deliverable': (40, 560), 'Risk': (400, 560),
        'Stakeholder': (800, 560), 'ChangeRequest': (1160, 560),
        'DeliverableStatus': (40, 960), 'RiskStatus': (360, 960),
        'RiskLevel': (680, 960), 'ChangePriority': (1000, 960),
        'ChangeStatus': (1320, 960),
    },
    'rel': [
        ('Project', .12, 1, 'Deliverable', .55, 0, 'produces', '1', '*', 'comp'),
        ('Project', .37, 1, 'Risk', .55, 0, 'tracks', '1', '*', 'comp'),
        ('Project', .63, 1, 'Stakeholder', .45, 0, 'involves', '1', '*', 'comp'),
        ('Project', .88, 1, 'ChangeRequest', .45, 0, 'records', '1', '*', 'comp'),
        ('Deliverable', .50, 1, 'DeliverableStatus', .50, 0, '', '', '', 'dep'),
        ('Risk', .30, 1, 'RiskStatus', .60, 0, '', '', '', 'dep'),
        ('Risk', .70, 1, 'RiskLevel', .25, 0, 'probability, impact', '', '', 'dep'),
        ('Stakeholder', .35, 1, 'RiskLevel', .75, 0, 'influence', '', '', 'dep'),
        ('ChangeRequest', .30, 1, 'ChangePriority', .70, 0, '', '', '', 'dep'),
        ('ChangeRequest', .70, 1, 'ChangeStatus', .40, 0, '', '', '', 'dep'),
    ],
    'note': ('EVM Delivery % = delivered or validated deliverables '
             '÷ planned deliverables × 100.', 40, 180, 420, 80, 'Deliverable'),
}

SPECS = {'class_global': CLASS_GLOBAL, 'class_governance': CLASS_GOVERNANCE}


def main():
    for name in (sys.argv[1:] or sorted(SPECS)):
        path = os.path.join(OUT_DIR, name + '.drawio')
        io.open(path, 'w', encoding='utf-8', newline=NL).write(Diagram(SPECS[name]).render())
        print('wrote ' + os.path.basename(path))


if __name__ == '__main__':
    main()
