# -*- coding: utf-8 -*-
"""Emit the nine use case diagrams as .drawio files.

PlantUML hands placement to GraphViz, which routes every edge itself: splines by
default, and straight *segments with corners* under `linetype polyline`. Neither
gives a ruler-straight association, and no PlantUML setting does, because the bend
is the router's answer to where it chose to put the nodes.

So the nodes are placed here instead. A draw.io edge with `edgeStyle=none` and no
waypoints is one straight line from source to target, always.

The layout rule that makes that safe: every use case sits in a single column at the
same x, and every actor sits to the left of that column. An actor's line therefore
only enters the column at its own target, so it cannot clip a neighbouring ellipse.
Each actor is placed at the mean height of the use cases it reaches, and the use
cases are ordered so that one actor's set stays contiguous.
"""
import io
import os
import sys

OUT_DIR = os.path.dirname(os.path.abspath(__file__))
NL = chr(10)

UC_X, UC_W, UC_H = 380, 230, 66      # use case column
PITCH = 112                          # vertical distance between use case centres
TOP = 100                            # y of the first use case
ACT_X, ACT_W, ACT_H = 80, 40, 80     # actor column, left of the use cases
ACTOR_GAP = 132                      # least vertical room two actors need
AUTH_X = 880                         # the Authenticate column
BOX_X = 330
BOX_PAD_TOP, BOX_PAD_BOTTOM = 40, 44

S_ACTOR = ('shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;'
           'html=1;outlineConnect=0;fontSize=13;fontColor=#16212B;'
           'strokeColor=#33383D;fillColor=none;')
S_UC = ('ellipse;whiteSpace=wrap;html=1;fontSize=13;fontColor=#16212B;'
        'strokeColor=#33383D;fillColor=#FFFFFF;')
S_BOX = ('rounded=0;whiteSpace=wrap;html=1;verticalAlign=top;fontStyle=1;'
         'fontSize=14;fontColor=#16212B;strokeColor=#33383D;fillColor=none;')
S_NOTE = ('shape=note;whiteSpace=wrap;html=1;size=14;fontSize=12;'
          'fontColor=#4A5967;strokeColor=#AAAAAA;fillColor=#FAFAFA;align=left;'
          'spacingLeft=6;verticalAlign=middle;')
S_TITLE = ('text;html=1;align=center;verticalAlign=middle;fontSize=20;'
           'fontStyle=1;fontColor=#16212B;strokeColor=none;fillColor=none;')
S_ASSOC = ('edgeStyle=none;rounded=0;html=1;endArrow=none;startArrow=none;'
           'strokeColor=#33383D;strokeWidth=1.3;')
S_DEP = ('edgeStyle=none;rounded=0;html=1;dashed=1;endArrow=open;endFill=0;'
         'endSize=8;strokeColor=#33383D;strokeWidth=1.2;fontSize=12;'
         'fontColor=#16212B;labelBackgroundColor=#FFFFFF;')
S_NOTELINK = ('edgeStyle=none;rounded=0;html=1;dashed=1;endArrow=none;'
              'startArrow=none;strokeColor=#AAAAAA;strokeWidth=1;')

INCLUDE = '&#171;include&#187;'
EXTEND = '&#171;extend&#187;'


def esc(t):
    return (t.replace('&', '&amp;').replace('<', '&lt;')
             .replace('>', '&gt;').replace('"', '&quot;'))


def build(spec):
    order = spec['order']                     # use case ids, top to bottom
    labels = spec['uc']                       # id -> label
    actors = spec['actors']                   # id -> (label, [uc ids])
    auth = spec.get('auth')                   # id of Authenticate, or None

    pos = {}
    for i, uid in enumerate(order):
        pos[uid] = (UC_X, TOP + i * PITCH)
    span = (len(order) - 1) * PITCH
    if auth:
        pos[auth] = (AUTH_X, TOP + span // 2)

    box_h = span + UC_H + BOX_PAD_TOP + BOX_PAD_BOTTOM
    box_y = TOP - BOX_PAD_TOP
    box_w = (AUTH_X + UC_W + 50 - BOX_X) if auth else (UC_X + UC_W + 60 - BOX_X)

    x = ['<?xml version="1.0" encoding="UTF-8"?>',
         '<mxfile host="drawio" version="31.1.8">',
         '  <diagram name="%s">' % esc(spec['title']),
         '    <mxGraphModel dx="1200" dy="800" grid="1" gridSize="10" page="1" '
         'pageScale="1" pageWidth="1169" pageHeight="826" math="0" shadow="0">',
         '      <root>',
         '        <mxCell id="0" />',
         '        <mxCell id="1" parent="0" />']

    x.append('        <mxCell id="title" value="%s" style="%s" vertex="1" parent="1">'
             % (esc(spec['title']), S_TITLE))
    x.append('          <mxGeometry x="0" y="%d" width="%d" height="40" as="geometry" />'
             % (box_y - 66, BOX_X + box_w))
    x.append('        </mxCell>')

    x.append('        <mxCell id="box" value="PMS Platform" style="%s" vertex="1" parent="1">'
             % S_BOX)
    x.append('          <mxGeometry x="%d" y="%d" width="%d" height="%d" as="geometry" />'
             % (BOX_X, box_y, box_w, box_h))
    x.append('        </mxCell>')

    # Each actor wants to sit at the mean height of the use cases it reaches, but
    # several actors often reach the same ones (in Sprint 1 all four reach both),
    # which lands them on the same y with their labels written over each other.
    # Spread any that collide, then recentre the group on its original mean.
    ideal = {}
    for aid, (_, targets) in actors.items():
        ys = [pos[t][1] for t in targets]
        ideal[aid] = int(sum(ys) / len(ys)) + UC_H // 2 - ACT_H // 2
    ordered = sorted(actors, key=lambda a: (ideal[a], list(actors).index(a)))
    placed, prev = {}, None
    for aid in ordered:
        y = ideal[aid] if prev is None else max(ideal[aid], prev + ACTOR_GAP)
        placed[aid] = y
        prev = y
    if ordered:
        drift = (placed[ordered[-1]] - ideal[ordered[-1]]) // 2
        for aid in placed:
            placed[aid] -= drift

    for aid, (label, _) in actors.items():
        x.append('        <mxCell id="%s" value="%s" style="%s" vertex="1" parent="1">'
                 % (aid, esc(label), S_ACTOR))
        x.append('          <mxGeometry x="%d" y="%d" width="%d" height="%d" as="geometry" />'
                 % (ACT_X, placed[aid], ACT_W, ACT_H))
        x.append('        </mxCell>')

    for uid in order + ([auth] if auth else []):
        ux, uy = pos[uid]
        x.append('        <mxCell id="%s" value="%s" style="%s" vertex="1" parent="1">'
                 % (uid, esc(labels[uid]), S_UC))
        x.append('          <mxGeometry x="%d" y="%d" width="%d" height="%d" as="geometry" />'
                 % (ux, uy, UC_W, UC_H))
        x.append('        </mxCell>')

    n = 0
    for aid, (_, targets) in actors.items():
        for t in targets:
            x.append('        <mxCell id="a%d" style="%s" edge="1" parent="1" '
                     'source="%s" target="%s">' % (n, S_ASSOC, aid, t))
            x.append('          <mxGeometry relative="1" as="geometry" />')
            x.append('        </mxCell>')
            n += 1

    for src, tgt, label in spec.get('deps', []):
        x.append('        <mxCell id="d%d" value="%s" style="%s" edge="1" parent="1" '
                 'source="%s" target="%s">' % (n, label, S_DEP, src, tgt))
        x.append('          <mxGeometry relative="1" as="geometry" />')
        x.append('        </mxCell>')
        n += 1

    if spec.get('note'):
        text, anchor = spec['note']
        # Clear the box AND the lowest actor: an actor carries its label beneath
        # it, and when several were spread apart the last one can reach below the
        # platform, which is where the note used to be written straight over it.
        lowest_actor = max(placed.values()) + ACT_H + 34 if placed else 0
        ny = max(box_y + box_h, lowest_actor) + 46
        x.append('        <mxCell id="note" value="%s" style="%s" vertex="1" parent="1">'
                 % (esc(text), S_NOTE))
        x.append('          <mxGeometry x="%d" y="%d" width="420" height="62" as="geometry" />'
                 % (ACT_X - 20, ny))
        x.append('        </mxCell>')
        x.append('        <mxCell id="nl" style="%s" edge="1" parent="1" '
                 'source="note" target="%s">' % (S_NOTELINK, anchor))
        x.append('          <mxGeometry relative="1" as="geometry" />')
        x.append('        </mxCell>')

    x += ['      </root>', '    </mxGraphModel>', '  </diagram>', '</mxfile>']
    return NL.join(x) + NL


A_ADMIN = 'Administrator (ADMIN)'
A_DIR = 'Director (DIRECTEUR)'
A_PM = 'Project Manager (CHEF_PROJET)'
A_DEV = 'Developer (DEVELOPPEUR)'

SPECS = {
    'use_case': {
        'title': 'Global use case diagram — PMS Platform',
        # u10 extends u9, so it sits directly under it: with a use case in
        # between, the extend label landed on that one's text.
        'order': ['u1', 'u2', 'u3', 'u4', 'u5', 'u7', 'u8', 'u9', 'u10', 'u6'],
        'auth': 'auth',
        'uc': {'u1': 'Manage users and access rights',
               'u2': 'Manage resources and cost rates',
               'u3': 'Manage projects',
               'u4': 'Define the internal quote (restricted)',
               'u5': 'Manage project execution',
               'u7': 'Manage financial tracking',
               'u8': 'Manage project governance',
               'u9': 'Monitor performance indicators',
               'u6': 'Record own workload',
               'u10': 'Freeze the monthly review',
               'auth': 'Authenticate'},
        'actors': {'admin': (A_ADMIN, ['u1', 'u2']),
                   'dir': (A_DIR, ['u3', 'u4', 'u5', 'u9']),
                   'pm': (A_PM, ['u5', 'u7', 'u8', 'u9']),
                   'dev': (A_DEV, ['u6', 'u9'])},
        'deps': [('u10', 'u9', EXTEND)] +
                [('u%s' % i, 'auth', INCLUDE) for i in
                 ['1', '2', '3', '4', '5', '6', '7', '8', '9']],
    },
    'uc_sprint1': {
        'title': 'Sprint 1 — Authentication',
        'order': ['u2', 'u1', 'u3'],
        'auth': None,
        'uc': {'u1': 'Authenticate',
               'u2': 'Change an imposed password',
               'u3': 'Terminate the session'},
        'actors': {'admin': (A_ADMIN, ['u1', 'u3']),
                   'dir': (A_DIR, ['u1', 'u3']),
                   'pm': (A_PM, ['u1', 'u3']),
                   'dev': (A_DEV, ['u1', 'u3'])},
        'deps': [('u2', 'u1', EXTEND)],
        'note': ('Extends authentication: on a first login the server confines '
                 'the session to this single case.', 'u2'),
    },
    'uc_sprint2': {
        'title': 'Sprint 2 — Dynamic access control, users and resources',
        'order': ['u1', 'u2', 'u3'],
        'auth': 'auth',
        'uc': {'u1': 'Assign permissions to a role',
               'u2': 'Manage user accounts',
               'u3': 'Record a resource and its yearly cost rate',
               'auth': 'Authenticate'},
        'actors': {'admin': (A_ADMIN, ['u1', 'u2', 'u3'])},
        'deps': [('u1', 'auth', INCLUDE), ('u2', 'auth', INCLUDE),
                 ('u3', 'auth', INCLUDE)],
        'note': ('The role-permission matrix is data, not code: changing it '
                 'takes effect without redeployment.', 'u1'),
    },
    'uc_sprint3': {
        'title': 'Sprint 3 — Project lifecycle',
        'order': ['u1', 'u2', 'u3', 'u4'],
        'auth': 'auth',
        'uc': {'u1': 'Create and configure a project',
               'u2': 'Change the project status',
               'u3': 'Assign the project manager',
               'u4': 'Archive a completed project',
               'auth': 'Authenticate'},
        'actors': {'dir': (A_DIR, ['u1', 'u2', 'u3', 'u4']),
                   'pm': (A_PM, ['u4'])},
        'deps': [('u%d' % i, 'auth', INCLUDE) for i in (1, 2, 3, 4)],
        'note': ('Only a completed project may be archived, and archiving is '
                 'reversible.', 'u4'),
    },
    'uc_sprint4': {
        'title': 'Sprint 4 — Team and workload',
        'order': ['u1', 'u2', 'u4', 'u3'],
        'auth': 'auth',
        'uc': {'u1': 'Build the project team',
               'u2': 'Plan the monthly workload',
               'u4': 'Validate the team workload',
               'u3': 'Submit own consumed workload',
               'auth': 'Authenticate'},
        'actors': {'dir': (A_DIR, ['u1']),
                   'pm': (A_PM, ['u1', 'u2', 'u4']),
                   'dev': (A_DEV, ['u3'])},
        'deps': [('u%d' % i, 'auth', INCLUDE) for i in (1, 2, 3, 4)],
        'note': ('The only write operation the developer role performs.', 'u3'),
    },
    'uc_sprint5': {
        'title': 'Sprint 5 — Agile planning',
        'order': ['u1', 'u2', 'u3', 'u4'],
        'auth': 'auth',
        'uc': {'u1': 'Create an iteration',
               'u2': 'Maintain a prioritised product backlog',
               'u3': 'Commit an item to an iteration',
               'u4': 'Follow progress on the board',
               'auth': 'Authenticate'},
        'actors': {'pm': (A_PM, ['u1', 'u2', 'u3', 'u4']),
                   'dir': (A_DIR, ['u4']),
                   'dev': (A_DEV, ['u4'])},
        'deps': [('u%d' % i, 'auth', INCLUDE) for i in (1, 2, 3, 4)],
        'note': ('An item with no iteration stays in the product backlog: not '
                 'committed is a normal state.', 'u2'),
    },
    'uc_sprint6': {
        'title': 'Sprint 6 — Billing and missions',
        'order': ['u1', 'u2', 'u3', 'u5', 'u4'],
        'auth': 'auth',
        'uc': {'u1': 'Define a billing milestone',
               'u2': 'Invoice a milestone',
               'u3': 'Record a payment',
               'u5': 'Record a mission and its expenses',
               'u4': 'Record a contractual amendment',
               'auth': 'Authenticate'},
        'actors': {'pm': (A_PM, ['u1', 'u2', 'u3', 'u5']),
                   'dir': (A_DIR, ['u4'])},
        'deps': [('u%d' % i, 'auth', INCLUDE) for i in (1, 2, 3, 4, 5)],
        'note': ('Reserved to the director: an amendment changes the contract, '
                 'not its execution.', 'u4'),
    },
    'uc_sprint7': {
        'title': 'Sprint 7 — Project governance',
        'order': ['u1', 'u2', 'u3', 'u4', 'u5'],
        'auth': 'auth',
        'uc': {'u1': 'Maintain the risk register',
               'u2': 'Track the deliverables',
               'u3': 'Document the stakeholders',
               'u4': 'Handle change requests',
               'u5': 'Consult the registers',
               'auth': 'Authenticate'},
        'actors': {'pm': (A_PM, ['u1', 'u2', 'u3', 'u4']),
                   'dir': (A_DIR, ['u5'])},
        'deps': [('u%d' % i, 'auth', INCLUDE) for i in (1, 2, 3, 4, 5)],
        'note': ('The deliverable status is controlled, not free text: the '
                 'delivery rate of Sprint 8 is derived from it.', 'u2'),
    },
    'uc_sprint8': {
        'title': 'Sprint 8 — Internal quote and indicator engine',
        'order': ['u4', 'u1', 'u2', 'u3'],
        'auth': 'auth',
        'uc': {'u4': 'Consult the portfolio dashboard',
               'u1': 'Define the internal quote',
               'u2': 'Consult the computed indicators',
               'u3': 'Freeze the monthly review',
               'auth': 'Authenticate'},
        'actors': {'dir': (A_DIR, ['u4', 'u1', 'u2']),
                   'pm': (A_PM, ['u2', 'u3'])},
        'deps': [('u3', 'u2', EXTEND), ('u4', 'auth', INCLUDE),
                 ('u1', 'auth', INCLUDE), ('u2', 'auth', INCLUDE)],
        'note': ('Restricted access: unit costs and internal margins are the '
                 'most commercially sensitive data held.', 'u1'),
    },
}


def main():
    for name in (sys.argv[1:] or sorted(SPECS)):
        path = os.path.join(OUT_DIR, name + '.drawio')
        io.open(path, 'w', encoding='utf-8', newline=NL).write(build(SPECS[name]))
        print('wrote ' + os.path.basename(path))


if __name__ == '__main__':
    main()
