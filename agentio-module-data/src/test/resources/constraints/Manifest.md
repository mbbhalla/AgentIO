# Test Fixture Parquet Files

Generated via `duckdb_cli`. All employee files share the schema: `employee_id VARCHAR, email VARCHAR, department_id VARCHAR, name VARCHAR`.

## departments.parquet

Reference table for FK validation.

| department_id | department_name |
|---------------|-----------------|
| D001          | Engineering     |
| D002          | Marketing       |
| D003          | Finance         |

## employees_all_constraints_satisfied.parquet

Valid data — passes PK, UNIQUE, NOT NULL, and FK constraints.

| employee_id | email         | department_id | name  |
|-------------|---------------|---------------|-------|
| E001        | alice@co.com  | D001          | Alice |
| E002        | bob@co.com    | D002          | Bob   |
| E003        | carol@co.com  | D003          | Carol |

## employees_with_primary_key_constraint_violated.parquet

Duplicate `employee_id` (E001 appears twice).

| employee_id | email         | department_id | name  |
|-------------|---------------|---------------|-------|
| **E001**    | alice@co.com  | D001          | Alice |
| **E001**    | bob@co.com    | D002          | Bob   |
| E003        | carol@co.com  | D003          | Carol |

## employees_with_unique_constraint_violated.parquet

Duplicate `email` (alice@co.com appears twice).

| employee_id | email            | department_id | name  |
|-------------|------------------|---------------|-------|
| E001        | **alice@co.com** | D001          | Alice |
| E002        | **alice@co.com** | D002          | Bob   |
| E003        | carol@co.com     | D003          | Carol |

## employees_with_non_null_constraint_violated.parquet

NULL in `name` column (row 2).

| employee_id | email         | department_id | name     |
|-------------|---------------|---------------|----------|
| E001        | alice@co.com  | D001          | Alice    |
| E002        | bob@co.com    | D002          | **NULL** |
| E003        | carol@co.com  | D003          | Carol    |

## employees_with_foreign_key_constraint_violated.parquet

Orphaned `department_id` (D999 does not exist in departments).

| employee_id | email         | department_id | name  |
|-------------|---------------|---------------|-------|
| E001        | alice@co.com  | D001          | Alice |
| E002        | bob@co.com    | **D999**      | Bob   |
| E003        | carol@co.com  | D003          | Carol |

## Regeneration

```bash
cd agentio-module-data/src/test/resources/constraints
duckdb_cli -c "
CREATE TABLE departments (department_id VARCHAR, department_name VARCHAR);
INSERT INTO departments VALUES ('D001','Engineering'),('D002','Marketing'),('D003','Finance');
COPY departments TO 'departments.parquet';

CREATE TABLE e1 (employee_id VARCHAR, email VARCHAR, department_id VARCHAR, name VARCHAR);
INSERT INTO e1 VALUES ('E001','alice@co.com','D001','Alice'),('E002','bob@co.com','D002','Bob'),('E003','carol@co.com','D003','Carol');
COPY e1 TO 'employees_all_constraints_satisfied.parquet';

CREATE TABLE e2 (employee_id VARCHAR, email VARCHAR, department_id VARCHAR, name VARCHAR);
INSERT INTO e2 VALUES ('E001','alice@co.com','D001','Alice'),('E001','bob@co.com','D002','Bob'),('E003','carol@co.com','D003','Carol');
COPY e2 TO 'employees_with_primary_key_constraint_violated.parquet';

CREATE TABLE e3 (employee_id VARCHAR, email VARCHAR, department_id VARCHAR, name VARCHAR);
INSERT INTO e3 VALUES ('E001','alice@co.com','D001','Alice'),('E002','alice@co.com','D002','Bob'),('E003','carol@co.com','D003','Carol');
COPY e3 TO 'employees_with_unique_constraint_violated.parquet';

CREATE TABLE e4 (employee_id VARCHAR, email VARCHAR, department_id VARCHAR, name VARCHAR);
INSERT INTO e4 VALUES ('E001','alice@co.com','D001','Alice'),('E002','bob@co.com','D002',NULL),('E003','carol@co.com','D003','Carol');
COPY e4 TO 'employees_with_non_null_constraint_violated.parquet';

CREATE TABLE e5 (employee_id VARCHAR, email VARCHAR, department_id VARCHAR, name VARCHAR);
INSERT INTO e5 VALUES ('E001','alice@co.com','D001','Alice'),('E002','bob@co.com','D999','Bob'),('E003','carol@co.com','D003','Carol');
COPY e5 TO 'employees_with_foreign_key_constraint_violated.parquet';
"
```
