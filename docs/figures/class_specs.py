# -*- coding: utf-8 -*-
"""The seven class diagrams of the report, laid out by hand.

They used to be split between draw.io (the global and governance ones) and
PlantUML (the other five), which meant two visual languages for the same kind of
figure and two different notations for private visibility. They are all draw.io
now, all built from this file, so a rule applied here applies to every one.

Three rules govern the relations, and they are the supervisor's:

  * A composition or an aggregation carries no verb. The diamond already says
    "is composed of" / "has" / "owns" / "contains"; writing the verb beside it
    says the same thing twice. The multiplicities stay, because they are not
    always "one to many": a project starts with no sprints and an empty
    backlog, so the end that matters reads 0..*, and the zero is a fact.
  * A plain association carries a verb, never the name of a role. "directed by",
    not "director"; "assigns", not "member".
  * A composition means the part cannot exist without the whole and dies with it.
    A project is not composed of its stakeholders: they are people and companies
    that outlive it, so that one is an association.

Attributes are written bare here. The minus sign for private visibility is added
by the renderer, so no diagram can be written without it.
"""

# ============================================================== global model ==
# Everything the platform manages, in one figure. Project sits in the middle
# because every other business class reaches it.
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
        # associations between classes that exist independently: a verb each
        ('User', 1, .45, 'Role', 0, .45, 'holds', '1..*', '1', None),
        ('Role', 1, .45, 'Permission', 0, .45, 'grants', '1..*', '1', None),
        ('Resource', 1, .40, 'User', 0, .30, 'prices', '0..1', '1', None),
        ('Project', .30, 0, 'User', .30, 1, 'directed by', '1..*', '1', None),
        ('Project', .70, 0, 'User', .70, 1, 'managed by', '1..*', '0..1', None),
        ('TeamAssignment', 1, .25, 'User', 0, .75, 'assigns', '1..*', '1', None),
        # everything a project owns and takes with it when it goes: no verb
        ('Project', 0, .25, 'TeamAssignment', 1, .70, '', '1', '0..*', 'comp'),
        ('Project', 0, .50, 'WorkloadPlan', 1, .45, '', '1', '0..*', 'comp'),
        ('Project', 0, .78, 'BillingMilestone', 1, .30, '', '1', '0..*', 'comp'),
        ('Project', 1, .25, 'ActualWorkload', 0, .70, '', '1', '0..*', 'comp'),
        ('Project', 1, .50, 'KpiSnapshot', 0, .45, '', '1', '0..*', 'comp'),
        ('Project', 1, .78, 'BacklogItem', 0, .30, '', '1', '0..*', 'comp'),
        ('Project', .25, 1, 'Mission', .55, 0, '', '1', '0..*', 'comp'),
        ('Project', .50, 1, 'Deliverable', .50, 0, '', '1', '0..*', 'comp'),
        ('Project', .75, 1, 'Risk', .40, 0, '', '1', '0..*', 'comp'),
        ('Project', .90, 1, 'Sprint', 0, .30, '', '1', '0..*', 'comp'),
        # the project owns the item; the sprint only commits it for a period, so
        # this is an association and not a second ownership of the same object
        ('Sprint', .50, 0, 'BacklogItem', .50, 1, 'commits', '0..1', '0..*', None),
    ],
}

# ================================================= dynamic authorization model ==
# The chain the whole platform rests on. A user holds one role, a role grants a
# set of permissions, a permission belongs to a functional module. None of the
# four classes owns another, so all three relations are plain associations.
CLASS_RBAC = {
    'title': 'Class diagram — dynamic authorization model',
    'classes': {
        'User':       ['id : int', 'lastName : string', 'firstName : string',
                       'email : string', 'passwordHash : string', 'active : boolean',
                       'firstLogin : boolean', 'tokenVersion : int'],
        'Role':       ['id : int', 'name : string', 'description : string',
                       'system : boolean'],
        'Permission': ['id : int', 'code : string', 'description : string'],
        'Module':     ['id : int', 'name : string'],
    },
    'pos': {'User': (40, 150), 'Role': (420, 198),
            'Permission': (800, 210), 'Module': (1180, 222)},
    'rel': [
        ('User', 1, .5, 'Role', 0, .5, 'holds', '1..*', '1', None),
        ('Role', 1, .5, 'Permission', 0, .5, 'grants', '1..*', '1..*', None),
        ('Permission', 1, .5, 'Module', 0, .5, 'belongs to', '1..*', '1', None),
    ],
    'notes': [
        ('ADMIN, DIRECTEUR, CHEF_PROJET and DEVELOPPEUR are rows, not subclasses. '
         'No role name appears anywhere in the code.', 330, 520, 420, 100, 'Role'),
        ('Each endpoint is annotated with a permission code, so the policy is '
         'edited in the database instead of being compiled in.',
         820, 520, 440, 100, 'Permission'),
    ],
}

# ================================================== projects, teams, lifecycle ==
CLASS_PROJECT_TEAM = {
    'title': 'Classes — project, team and lifecycle',
    'enums': ('ProjectStatus',),
    'classes': {
        'Project':        ['code : string', 'name : string', 'client : string',
                           'status : ProjectStatus', 'initialBudget : decimal',
                           'revisedBudget : decimal', 'currency : string',
                           'soldWorkloadDays : decimal', 'archived : boolean'],
        'Resource':       ['dailyRate : decimal', 'tccRate : decimal'],
        'User':           ['firstName : string', 'lastName : string', 'email : string'],
        'TeamAssignment': ['roleInTeam : string', 'startDate : date', 'endDate : date'],
        'ProjectStatus':  ['DRAFT', 'ACTIVE', 'ON_HOLD', 'COMPLETED', 'CANCELLED'],
    },
    'pos': {'Project': (60, 300), 'Resource': (700, 60), 'User': (700, 280),
            'TeamAssignment': (700, 520), 'ProjectStatus': (60, 700)},
    'rel': [
        ('Resource', .5, 1, 'User', .5, 0, 'prices', '0..1', '1', None),
        ('Project', 1, .2, 'User', 0, .35, 'directed by', '1..*', '1', None),
        ('Project', 1, .45, 'User', 0, .75, 'managed by', '1..*', '0..1', None),
        ('Project', 1, .8, 'TeamAssignment', 0, .4, '', '1', '0..*', 'comp'),
        ('TeamAssignment', .5, 0, 'User', .5, 1, 'assigns', '1..*', '1', None),
        ('Project', .5, 1, 'ProjectStatus', .5, 0, '', '', '', 'dep'),
    ],
    'notes': [('Effective budget = revised budget when an amendment has set one, '
               'otherwise the initial budget. Any transition the lifecycle does not '
               'allow is refused.', 400, 760, 520, 110, 'ProjectStatus')],
}

# ============================================================= agile planning ==
# The composition and the aggregation carry neither verb nor multiplicity: a
# project always holds many sprints and many items, and the diamonds say the rest.
CLASS_AGILE = {
    'title': 'Classes — product backlog and sprints',
    'enums': ('SprintStatus', 'BacklogPriority', 'BacklogItemStatus'),
    'classes': {
        'Project':           ['code : string', 'name : string'],
        'Sprint':            ['name : string', 'goal : string', 'startDate : date',
                              'endDate : date', 'status : SprintStatus'],
        'BacklogItem':       ['title : string', 'description : string',
                              'priority : BacklogPriority', 'estimateDays : decimal',
                              'status : BacklogItemStatus'],
        'User':              ['lastName : string', 'firstName : string', 'email : string'],
        'SprintStatus':      ['PLANNED', 'ACTIVE', 'CLOSED'],
        'BacklogItemStatus': ['TODO', 'IN_PROGRESS', 'DONE'],
        'BacklogPriority':   ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'],
    },
    'pos': {'Project': (300, 60), 'Sprint': (60, 420), 'BacklogItem': (540, 420),
            'User': (960, 60), 'SprintStatus': (60, 780),
            'BacklogItemStatus': (540, 780), 'BacklogPriority': (900, 780)},
    'rel': [
        # The defect was never the triangle. It was that a backlog item was drawn as
        # a part of two wholes at once: composed by the project and aggregated by the
        # sprint. UML does not allow that -- an object is a part in one composition
        # or aggregation, not two -- and it is wrong here anyway, because deleting a
        # sprint does not delete its items, it returns them to the backlog.
        #
        # So the project composes both registers, and the sprint merely commits items
        # for its period: a plain association, which keeps its multiplicities because
        # the 0..1 is the whole point of a product backlog.
        ('Project', 0, .6, 'Sprint', .5, 0, '', '1', '0..*', 'comp'),
        ('Project', 1, .6, 'BacklogItem', .5, 0, '', '1', '0..*', 'comp'),
        ('Sprint', 1, .5, 'BacklogItem', 0, .5, 'commits', '0..1', '0..*', None),
        ('BacklogItem', 1, .2, 'User', .5, 1, 'assigned to', '0..*', '0..1', None),
        ('Sprint', .5, 1, 'SprintStatus', .5, 0, '', '', '', 'dep'),
        ('BacklogItem', .5, 1, 'BacklogItemStatus', .5, 0, '', '', '', 'dep'),
        ('BacklogItem', 1, .8, 'BacklogPriority', .5, 0, '', '', '', 'dep'),
    ],
    'notes': [('An item with no sprint is still in the product backlog, which is a '
               'normal state. Deleting a sprint returns its items there rather than '
               'destroying them, so the sprint commits its items and does not own '
               'them. The project owns both registers.',
               960, 420, 460, 150, 'BacklogItem')],
}

# ========================================================= billing and missions ==
CLASS_FINANCIAL = {
    'title': 'Classes — billing, amendments and missions',
    'enums': ('MilestoneStatus', 'QuoteSection'),
    'classes': {
        'Project':          ['code : string', 'currency : string',
                             'exchangeRateToTnd : decimal'],
        'BillingMilestone': ['label : string', 'percentage : decimal',
                             'amount : decimal', 'dueDate : date',
                             'status : MilestoneStatus'],
        'Payment':          ['amountReceived : decimal', 'paymentDate : date',
                             'reference : string'],
        'Amendment':        ['number : string', 'subject : string',
                             'amount : decimal', 'workloadDays : decimal'],
        'QuoteLine':        ['section : QuoteSection', 'contractProfile : string',
                             'soldWorkloadDays : decimal', 'unitSellingPrice : decimal',
                             'internalWorkloadDays : decimal', 'unitTccCost : decimal',
                             'percentageRate : decimal'],
        'Mission':          ['purpose : string', 'location : string',
                             'startDate : date', 'endDate : date'],
        'MilestoneStatus':  ['PLANNED', 'INVOICED', 'PAID'],
        'QuoteSection':     ['FEES', 'EXPENSES', 'OTHER_EXPENSES'],
    },
    'pos': {'Project': (60, 420), 'BillingMilestone': (560, 60), 'Payment': (980, 60),
            'Amendment': (560, 340), 'QuoteLine': (560, 600), 'Mission': (560, 940),
            'MilestoneStatus': (980, 300), 'QuoteSection': (980, 620)},
    'rel': [
        ('Project', 1, .15, 'BillingMilestone', 0, .5, '', '1', '0..*', 'comp'),
        ('BillingMilestone', 1, .5, 'Payment', 0, .5, '', '1', '0..*', 'comp'),
        ('Project', 1, .35, 'Amendment', 0, .5, '', '1', '0..*', 'comp'),
        ('Project', 1, .6, 'QuoteLine', 0, .3, '', '1', '0..*', 'comp'),
        ('Project', 1, .85, 'Mission', 0, .4, '', '1', '0..*', 'comp'),
        ('BillingMilestone', 1, .85, 'MilestoneStatus', 0, .3, '', '', '', 'dep'),
        ('QuoteLine', 1, .2, 'QuoteSection', 0, .5, '', '', '', 'dep'),
    ],
    'notes': [('The percentages of the milestones never add up to more than 100 of '
               'the contract. Amounts and margins are computed when they are read '
               'and never stored.', 60, 660, 440, 120, 'Project')],
}

# ================================================= workload and indicator engine ==
# KpiSnapshot is kept: a frozen monthly review is something the project manager
# creates, consults and compares, not a technical artefact of the code.
CLASS_KPI = {
    'title': 'Classes — workload and indicator engine',
    'classes': {
        'Project':        ['code : string', 'soldWorkloadDays : decimal'],
        'WorkloadPlan':   ['period : date', 'plannedDays : decimal'],
        'ActualWorkload': ['period : date', 'actualDays : decimal',
                           'submittedAt : datetime', 'validatedAt : datetime'],
        'KpiSnapshot':    ['snapshotDate : date', 'evPct : decimal',
                           'deliveryPct : decimal', 'driftDays : decimal',
                           'productionRevenue : decimal', 'unbilledRevenue : decimal',
                           'eac : decimal', 'margin : decimal', 'highlights : string'],
        'User':           ['email : string'],
        'Resource':       ['dailyRate : decimal', 'tccRate : decimal'],
        'AnnualTcc':      ['year : int', 'dailyRate : decimal', 'tccRate : decimal'],
    },
    'pos': {'Project': (480, 60), 'WorkloadPlan': (60, 320), 'ActualWorkload': (400, 320),
            'KpiSnapshot': (800, 320), 'User': (240, 720), 'Resource': (620, 720),
            'AnnualTcc': (620, 940)},
    'rel': [
        ('Project', 0, .6, 'WorkloadPlan', .5, 0, '', '1', '0..*', 'comp'),
        ('Project', .5, 1, 'ActualWorkload', .5, 0, '', '1', '0..*', 'comp'),
        ('Project', 1, .6, 'KpiSnapshot', .5, 0, '', '1', '0..*', 'comp'),
        ('WorkloadPlan', .5, 1, 'User', .25, 0, 'plans for', '0..*', '1', None),
        ('ActualWorkload', .5, 1, 'User', .75, 0, 'reported by', '0..*', '1', None),
        ('Resource', 0, .5, 'User', 1, .5, 'prices', '0..1', '1', None),
        ('Resource', .5, 1, 'AnnualTcc', .5, 0, '', '1', '0..*', 'comp'),
    ],
    'notes': [('A day of effort is valued at the cost rate of the year it was booked '
               'in. The snapshot freezes a monthly review: the indicators computed '
               'plus the earned value the project manager entered.',
               1060, 700, 460, 130, 'KpiSnapshot')],
}

# ========================================================== project governance ==
# Risks, deliverables and change requests are created by the project and die with
# it. Stakeholders do not: they are people and companies that exist before the
# project and outlive it, so the project involves them rather than owning them.
CLASS_GOVERNANCE = {
    'title': 'Classes — project governance',
    'enums': ('DeliverableStatus', 'RiskStatus', 'RiskLevel',
              'ChangePriority', 'ChangeStatus'),
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
        ('Project', .12, 1, 'Deliverable', .55, 0, '', '1', '0..*', 'comp'),
        ('Project', .37, 1, 'Risk', .55, 0, '', '1', '0..*', 'comp'),
        ('Project', .88, 1, 'ChangeRequest', .45, 0, '', '1', '0..*', 'comp'),
        # a stakeholder is a person or a company, not a part of the project
        ('Project', .63, 1, 'Stakeholder', .45, 0, 'involves', '1', '0..*', None),
        ('Deliverable', .50, 1, 'DeliverableStatus', .50, 0, '', '', '', 'dep'),
        ('Risk', .30, 1, 'RiskStatus', .60, 0, '', '', '', 'dep'),
        ('Risk', .70, 1, 'RiskLevel', .25, 0, 'probability, impact', '', '', 'dep'),
        ('Stakeholder', .35, 1, 'RiskLevel', .75, 0, 'influence', '', '', 'dep'),
        ('ChangeRequest', .30, 1, 'ChangePriority', .70, 0, '', '', '', 'dep'),
        ('ChangeRequest', .70, 1, 'ChangeStatus', .40, 0, '', '', '', 'dep'),
    ],
    'notes': [('EVM Delivery % = delivered or validated deliverables '
               '÷ planned deliverables × 100.', 40, 180, 420, 80, 'Deliverable')],
}

SPECS = {
    'class_global': CLASS_GLOBAL,
    'class_rbac': CLASS_RBAC,
    'class_project_team': CLASS_PROJECT_TEAM,
    'class_agile': CLASS_AGILE,
    'class_financial': CLASS_FINANCIAL,
    'class_kpi': CLASS_KPI,
    'class_governance': CLASS_GOVERNANCE,
}
