# Demo accounts — for the defence video

Every account below already exists: they are created by the seeders in
`backend/src/main/java/com/pms/shared/config/`. None of them needs to be created
by hand, and none is forced through a password change on first login
(`firstLogin = false`), so you go straight from the login screen to the app.

All data is fictitious. No real client, contract or salary appears in it.

---

## ⚠ Read this before you record

**Do not demo with `chef@pms.local` or `dev@pms.local`.** They exist and they log
in, but their screens will be **empty**.

The reason is the thing your platform is built to do. A Chef de Projet only sees
projects they manage, and a Developpeur only sees projects they are assigned to.
Those two accounts own nothing: every seeded project belongs to a generated user
in the `ent.st2i.tn` domain. So the scope filter correctly returns nothing, and
your video would show a blank workspace.

Use the accounts in the next section instead. They hold the data.

---

## The four accounts to record with

| Role | Email | Password | What it shows |
|---|---|---|---|
| **Administrator** | `admin@pms.local` | `Admin1234!` | Users, roles, the permission catalogue, resources and yearly cost rates |
| **Director** | `directeur@pms.local` | `Directeur1234!` | **The whole portfolio — 30 projects.** Holds `VIEW_ALL_PROJECTS`, so no scope filter applies |
| **Project manager** | `wafa.cherif@ent.st2i.tn` | `Enterprise@ST2I2026!` | Three real projects, with team, workload, billing and governance |
| **Developer** | `yassine.hamrouni@ent.st2i.tn` | `Enterprise@ST2I2026!` | Only assigned projects, and **no** cost rates, margins or indicators |

That last row is the pair that makes your best point on camera: same build, same
database, two accounts, and the developer simply has no financial menu. Not
hidden by an `if` — the permission rows are not there.

### Wafa Cherif's projects

| Code | Project |
|---|---|
| `ENT-CRM-2026` | Plateforme CRM Bancaire Nouvelle Génération — 1 800 000 EUR, FORFAIT, active |
| `ENT-BSS-2024` | Refonte Billing System Support Télécom |
| `ENT-EXPER-2025` | Expérimentation IA Générative — POC Chatbot |

`ENT-CRM-2026` is the richest one: a real contract reference, a budget in euros
with an exchange rate, sold workload and warranty workload. Open that one.

---

## Suggested order for the video

1. **Log in as the developer** first, not last. Show a workspace with no money in
   it. Say: *this developer works on the project and cannot see its margin.*
2. **Log out, log in as the project manager.** Same application. Open
   `ENT-CRM-2026`: team, monthly workload, billing milestones, risks and
   deliverables.
3. **Log out, log in as the director.** The whole portfolio, thirty projects, and
   the indicators computed across them.
4. **Log out, log in as the administrator.** Open the permission catalogue. Remove
   one permission from a role, log back in as that role, show the menu entry gone,
   grant it back, show it return. **Nothing was recompiled.**

Step 4 is the demonstration your whole report is built around. If the video is
short, keep step 4 and cut something else.

---

## Every seeded account

### Core accounts — `DataInitializer`

| Email | Password | Role | Owns data? |
|---|---|---|---|
| `admin@pms.local` | `Admin1234!` | ADMIN | administrative screens only |
| `admin2@pms.local` | `Admin2@2026!` | ADMIN | — |
| `admin3@pms.local` | `Admin3@2026!` | ADMIN | — |
| `directeur@pms.local` | `Directeur1234!` | DIRECTEUR | **yes — sees everything** |
| `chef@pms.local` | `Chef1234!` | CHEF_PROJET | **no — empty** |
| `dev@pms.local` | `Dev1234!` | DEVELOPPEUR | **no — empty** |
| `momo-directeur@pms.local` | `Momo123456` | DIRECTEUR | sees everything |
| `momo-chef@pms.local` | `Momo123456` | CHEF_PROJET | **no — empty** |
| `momo-dev@pms.local` | `Momo123456` | DEVELOPPEUR | **no — empty** |

### Enterprise accounts — password `Enterprise@ST2I2026!`, domain `@ent.st2i.tn`

These own the thirty `ENT-*` projects.

**Directors** — `habib.zouari`, `mouna.gueddiche`, `rafik.chaabane`,
`sonia.benamor`, `nabil.baccouche`, `lamia.abdelkefi`, `taoufik.ghazali`,
`chiraz.mbarki`

**Project managers** — `wafa.cherif`, `saber.hidouri`, `nadia.zribi`,
`issam.ouerghi`, `hanen.jouini`, `mounir.driss`, `radhia.kchaou`,
`fathi.elleuch`, `sirine.zoghlami`, `adnen.benjemaa`, `meriem.karoui`,
`riadh.belhaj`

**Developers** — `yassine.hamrouni`, `sabrine.bouchrika`, `mohamed.ayachi`,
`olfa.essid`, `adem.khlif`, `ines.touil`, `salim.meddeb`, `amina.haddad`,
`tarek.laabidi`, `sana.trabelsi`, `bilel.ferjani`, `chaima.nasraoui`,
`zoubeir.mabrouk`, `nesrine.sassi`, `khaled.bouguerba`, `rim.hammami`,
`lotfi.saadaoui`, `dorra.bouzid`, `hatem.zidi`, `kawther.lahmar`,
`nizar.benfredj`, `houda.gafsi`

### Demo accounts — password `Demo@2026!`, domain `@demo.pms`

A smaller, lighter dataset from `DemoDataSeeder` (`sami.mansouri`,
`nadia.belhadj`, `karim.trabelsi`, and others). The enterprise set above is
fuller; use that one for the video.

---

## Which chef owns which project

| Chef | Projects |
|---|---|
| Wafa Cherif | ENT-CRM-2026, ENT-BSS-2024, ENT-EXPER-2025 |
| Saber Hidouri | ENT-ERP-DIST-2026, ENT-GRC-2025, ENT-NLP-2027 |
| Nadia Zribi | ENT-SIH-2026, ENT-PORTAL-2025, ENT-OCR-2027 |
| Issam Ouerghi | ENT-FINTECH-2026, ENT-DCMIG-2024, ENT-MICROSERV-2027 |
| Hanen Jouini | ENT-SCM-2026, ENT-RPA-2024, ENT-DATALAKE-2027 |
| Mounir Driss | ENT-ELEC-2026, ENT-ARCH-2025, ENT-IDSNUM-2027 |
| Radhia Kchaou | ENT-INFRA-2026, ENT-AI-2026 |
| Fathi Elleuch | ENT-TRACE-2026, ENT-BLOCKCHAIN-2026 |
| Sirine Zoghlami | ENT-SOC-2026, ENT-SMARTCITY-2026 |
| Adnen Ben Jemaa | ENT-EDUC-2026, ENT-PEDAG-2026 |
| Meriem Karoui | ENT-TELEMEDE-2026, ENT-LEGACY-2024 |
| Riadh Belhaj | ENT-DEVOPS-2026, ENT-AUDIT-2024 |

---

## If the data is missing

The seeders run at startup and are idempotent — `EnterpriseDataSeeder` skips
itself entirely once `ENT-CRM-2026` exists. To rebuild the dataset from nothing:

```bash
docker compose down -v      # -v drops the database volume
docker compose up -d --build
```

Give the backend a minute; `docker compose ps` should report all three services
healthy before you open the app.

---

## One thing to remember afterwards

These passwords are hardcoded in the seeders, and `DataInitializer` resets them on
every startup. That is right for a demo and wrong for anything reachable from
outside. If PMS is ever deployed for real use at ST2i, set
`pms.demo.seed-users=false` and delete these accounts before it is exposed.
