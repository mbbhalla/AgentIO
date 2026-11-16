# Test Fixture: a University Domain Model as an Open Data Contract

`university.odcs.yaml` is one [Open Data Contract Standard](https://bitol-io.github.io/open-data-contract-standard/)
v3.2 document describing a small university: campuses, buildings and rooms; departments, programs,
courses, terms and sections; faculty and students; enrollments, tuition, scholarships and payments.
It is a *domain model*: it says what data must look like and carries no data.
`DuckDbDatabaseEnvironment.fromOdcs(<this directory>)` turns it into an empty DuckDB database that enforces
every rule in it. `fromOdcs` reads every `.yml` and `.yaml` file in the directory, so this is the only YAML
file here.

The file is hand-maintained and is the source of truth; there is no generator.

## What ODCS says and what the database enforces

Only the `schema` section describes the model. Everything else in the contract (`description`,
`team`, `support`, `tags`, ...) is metadata that documents the contract and is ignored by the builder.

| ODCS, on a schema object or property | Model / DuckDB |
|---|---|
| object `name` (or `physicalName`), lower-cased | table |
| property `name` (or `physicalName`), lower-cased | column |
| `description` | `COMMENT ON TABLE` / `COMMENT ON COLUMN` |
| `physicalType` (`VARCHAR`, `BIGINT`, `varchar(18)`, ...) | column type, when present |
| `logicalType` `string` / `integer` / `number` / `boolean` / `date` / `timestamp` | `VARCHAR` / `INTEGER` / `DOUBLE` / `BOOLEAN` / `DATE` / `TIMESTAMP`, when `physicalType` is absent; `integer` with `logicalTypeOptions.format` `i64` or `u32` is `BIGINT` |
| `required: true`, or a primary key property | `NOT NULL` |
| `primaryKey: true` + `primaryKeyPosition` | `PRIMARY KEY`, columns in position order |
| `unique: true` | single-column `UNIQUE`; ignored on a single-column primary key, which it only repeats |
| property `relationships: [{to: object.property}]` | single-column `FOREIGN KEY` |
| object `relationships: [{type: foreignKey, from: [...], to: [...]}]` | composite `FOREIGN KEY` |
| `enum` (entries `{value: ...}`, optionally with `label`, `description`, `tags`) | `CHECK (col IN (...))` |
| `logicalTypeOptions.minimum` / `maximum` / `exclusiveMinimum` / `exclusiveMaximum` | `CHECK (col >= v)`, `> v`, `<= v`, `< v`; numbers, dates and timestamps |
| `logicalTypeOptions.minLength` / `maxLength` | `CHECK (length(col) >= n)` / `<= n` |
| `logicalTypeOptions.pattern` | `CHECK (regexp_matches(col, 'pattern'))`, RE2 syntax |

Every property carries both `logicalType` and `physicalType`, as the standard's own examples do, so a
reader that understands only one of them still gets the right type. Foreign keys are collected
property-level first, in property order, then object-level in declared order.

Accepted and documented but **not enforced**: `logicalTypeOptions.format` (describes the value's
shape, e.g. `email`), `multipleOf`, `timezone`, `defaultTimezone`, and the `quality` rules. Also
accepted, as pure metadata: `businessName`, `classification`, `criticalDataElement`, `examples`,
`tags`, `authoritativeDefinitions`, `customProperties`, `dataGranularityDescription`.

ODCS has no composite unique key, so a model that needs one cannot be written in it; this fixture has
only single-column unique keys.

### Who validates what

The `DomainModel` checks every rule DuckDB applies to the DDL plus what a database cannot see, so a
model that exists is accepted by DuckDB; `fromDomainModel` cannot fail on a valid model:

- every key names columns the object has, none twice; primary key columns are not nullable;
- every relationship references a schema object in the model and properties it has; the referenced
  columns are that object's primary key or one of its unique keys, in the same order (DuckDB requires
  an exact match); each foreign key column has the same type as the column it references (DuckDB does
  not widen, not even `INTEGER` to `BIGINT`);
- foreign keys form no cycle (DuckDB declares foreign keys only inside `CREATE TABLE`, so tables are
  created in dependency order and a cycle has no valid order);
- every key is meaningful: a unique key is not implied by the primary key or another unique key, no
  foreign key is declared twice, and none references its own columns;
- every value constraint fits its column type, a column has at most one of each kind, a minimum does
  not exceed its maximum and exclusive bounds on a discrete type leave a value between them, every
  enumerated value satisfies the column's other constraints, and a `pattern` compiles under RE2, the
  engine behind DuckDB's `regexp_matches`.

DuckDB then rejects every `INSERT` or `UPDATE` that breaks a key or a `CHECK`. `NULL` passes every
`CHECK`, as in SQL; `required` decides whether a column may be null.

### Relationships that are not keys

A model often has a relationship the database cannot enforce: a column that refers to a non-key
column of another table. The convention here is to say so in the property's `description` so an agent
still sees it through `COMMENT ON COLUMN`; `student.home_campus_code` refers to `campus.code` this way.

## What the fixture exercises

| Property | Where |
|---|---|
| Foreign-key chain four deep, so creation order matters | `campus` → `building` → `room` → `section` → `enrollment` |
| Alphabetical order is wrong | `academic_calendar` sorts first but references `term` |
| Composite primary keys, 2 to 4 columns | `room`, `academic_calendar`, `prerequisite` (2); `section`, `tuition_rate`, `award` (3); `enrollment` (4) |
| Composite foreign keys (object-level relationships) | `section (building_id, room_number)` → `room`; `enrollment (course_code, term_code, section_number)` → `section` |
| Self-referencing foreign key | `faculty.mentor_faculty_id` → `faculty` |
| Two foreign keys to the same table | `prerequisite.course_code` and `prerequisite.prerequisite_code` → `course` |
| Tables with no foreign keys | `campus`, `scholarship`, `term` |
| Unique keys | `faculty.email` (nullable), `student.student_number`, `student.email` |
| String enums, with and without labels | `academic_calendar.day_type`, `enrollment.status`, `enrollment.letter_grade`, `program.degree_level`, `prerequisite.minimum_grade`; `payment.method` |
| Integer enum | `course.level` (100, 200, 300, 400) |
| Inclusive and exclusive numeric bounds, on INTEGER and DOUBLE | `course.credits` 1..30, `building.year_built` 1800..2100, `student.gpa` 0..4, `payment.amount` > 0, `program.credits_required` > 0 |
| Date and timestamp bounds | `term.starts_on` ≥ 1990-01-01, `campus.opened_on` ≥ 1800-01-01, `tuition_rate.eff_start_date` / `eff_end_date` |
| Length bounds and patterns | `campus.code` 2..4 chars `^[A-Z]+$`, `tuition_rate.currency` exactly 3 `^[A-Z]{3}$`, `term.term_code` `^[0-9]{4}(SP|SU|FA)$`, `course.course_code`, emails, `room.room_number` ≤ 10 |
| Doc-only relationship to a non-key column | `student.home_campus_code` → `campus.code` |
| A column named like a SQL keyword | `academic_calendar.date`; also `course.level` and `payment.method`, which DuckDB quotes in its messages |
| Every supported type | `string`, `integer` (INTEGER and BIGINT), `number`, `boolean`, `date`, `timestamp` |
| Metadata the builder ignores | `businessName`, `classification`, `criticalDataElement`, `examples`, `tags`, `authoritativeDefinitions`, `customProperties`, `dataGranularityDescription`, `quality`, `format`, and the contract's `team` and `support` |
| Docs with apostrophes, for `COMMENT ON` escaping | `campus`, `student`, `scholarship`, `department.annual_budget`, `payment.reference` |

Totals the tests assert: 17 tables, 103 columns, 17 primary keys, 24 foreign keys (2 composite),
3 unique keys, 73 `NOT NULL` columns, 42 `CHECK` constraints, 17 table comments, 42 column comments.

## Verified against DuckDB 2.0.0.1 (JDBC), kaml 0.104.0 and re2j 1.7

- `duckdb_constraints()` on the built environment reports exactly the keys and checks above, and
  rebuilding every table from the catalog (`duckdb_tables`, `duckdb_columns`, `duckdb_constraints`,
  including the `CHECK` expressions) gives back the parsed model, table by table.
- `INSERT` is rejected for: a duplicate primary key, a duplicate unique key, a foreign key to a
  missing row (single, composite and self-referencing), `NULL` in a `NOT NULL` column, and a value
  outside an enum, a bound, a length or a pattern. `UPDATE` is checked the same way. A value that
  cannot be cast to the column type fails earlier, when the statement is bound.
- DuckDB reports **one** violation per statement, the first row that fails, not a full report. When a
  value breaks several `CHECK`s, DuckDB names the one it evaluates first.
- A self-referencing foreign key is checked per statement: a parent and its child must arrive in
  separate `INSERT`s, even inside one transaction.
