-- A hand-written load of the AdventureWorks HR sources into the contract, in load order. The tests run
-- it through EtlWorkspace to prove the sources can be loaded into the contract and to pin what each
-- source means. The agents never see it. ${SOURCE} is replaced with the absolute source directory.
-- Each block is "-- table: <name>" followed by one SELECT whose column names are target columns.

-- table: department
-- DEPT rows of the cost-center hierarchy; groupname is the parent GROUP's name.
SELECT CAST(regexp_extract(d.cc_code, '(\d+)$', 1) AS INTEGER) AS departmentid,
       d.cc_name AS name,
       g.cc_name AS groupname,
       strptime(d.valid_from, '%d.%m.%Y') AS modifieddate
FROM read_csv('${SOURCE}/cost_centers.csv', skip = 2, delim = chr(59), header = true, all_varchar = true) AS d
JOIN read_csv('${SOURCE}/cost_centers.csv', skip = 2, delim = chr(59), header = true, all_varchar = true) AS g
  ON g.cc_code = d.parent_cc
WHERE d.cc_level = 'DEPT';

-- table: shift
-- 12-hour ranges become times of day; ids follow start time.
SELECT CAST(row_number() OVER (ORDER BY strptime(trim(split_part(Hours, '-', 1)), '%I:%M %p')) AS INTEGER) AS shiftid,
       Shift AS name,
       CAST(strptime(trim(split_part(Hours, '-', 1)), '%I:%M %p') AS TIME) AS starttime,
       CAST(strptime(trim(split_part(Hours, '-', 2)), '%I:%M %p') AS TIME) AS endtime
FROM read_csv('${SOURCE}/shift_patterns.csv', header = true, all_varchar = true);

-- table: phonenumbertype
-- The phone labels used in the roster's packed Contacts column.
WITH labels AS (
    SELECT DISTINCT lower(trim(split_part(u.c, ':', 1))) AS label
    FROM read_csv('${SOURCE}/hris_roster.csv', header = true, all_varchar = true) AS r,
         unnest(string_split(r.Contacts, '|')) AS u(c)
)
SELECT CAST(row_number() OVER (ORDER BY label) AS INTEGER) AS phonenumbertypeid,
       upper(label[1]) || label[2:] AS name
FROM labels
WHERE label <> 'email';

-- table: businessentity
SELECT CAST(regexp_extract("Emp ID", '(\d+)$', 1) AS INTEGER) AS businessentityid,
       strptime("Last Updated", '%Y-%m-%d %H:%M:%S') AS modifieddate
FROM read_csv('${SOURCE}/hris_roster.csv', header = true, all_varchar = true);

-- table: person
-- "Last, First Middle[, Suffix]".
WITH n AS (
    SELECT "Emp ID" AS emp,
           "Last Updated" AS updated,
           trim(split_part(Name, ',', 1)) AS last,
           trim(split_part(Name, ',', 2)) AS given,
           nullif(trim(split_part(Name, ',', 3)), '') AS suffix
    FROM read_csv('${SOURCE}/hris_roster.csv', header = true, all_varchar = true)
)
SELECT CAST(regexp_extract(emp, '(\d+)$', 1) AS INTEGER) AS businessentityid,
       'EM' AS persontype,
       false AS namestyle,
       split_part(given, ' ', 1) AS firstname,
       nullif(trim(substr(given, length(split_part(given, ' ', 1)) + 1)), '') AS middlename,
       last AS lastname,
       suffix,
       0 AS emailpromotion,
       strptime(updated, '%Y-%m-%d %H:%M:%S') AS modifieddate
FROM n;

-- table: employee
-- Codes from words, leave hours by label, hire dates in two formats, organizationnode from Reports To.
WITH RECURSIVE r AS (
    SELECT * FROM read_csv('${SOURCE}/hris_roster.csv', header = true, all_varchar = true)
),
reports AS (
    SELECT "Emp ID" AS emp,
           nullif("Reports To", '') AS manager,
           row_number() OVER (PARTITION BY nullif("Reports To", '') ORDER BY "Emp ID") AS position
    FROM r
),
tree AS (
    SELECT emp, '/' AS node FROM reports WHERE manager IS NULL
    UNION ALL
    SELECT k.emp, t.node || k.position || '/' FROM reports AS k JOIN tree AS t ON k.manager = t.emp
)
SELECT CAST(regexp_extract(r."Emp ID", '(\d+)$', 1) AS INTEGER) AS businessentityid,
       r."National ID" AS nationalidnumber,
       r.Login AS loginid,
       r."Job Title" AS jobtitle,
       CAST(strptime(r.Born, '%d-%b-%Y') AS DATE) AS birthdate,
       CASE WHEN r.Marital = 'Married' THEN 'M' ELSE 'S' END AS maritalstatus,
       upper(left(r.Sex, 1)) AS gender,
       CAST(coalesce(try_strptime(r.Hired, '%m/%d/%Y'), try_strptime(r.Hired, '%Y-%m-%d')) AS DATE) AS hiredate,
       r.FLSA = 'Exempt' AS salariedflag,
       CAST(regexp_extract(r."Leave Balance", 'VAC (\d+)h', 1) AS INTEGER) AS vacationhours,
       CAST(regexp_extract(r."Leave Balance", 'SICK (\d+)h', 1) AS INTEGER) AS sickleavehours,
       r.Status <> 'Terminated' AS currentflag,
       strptime(r."Last Updated", '%Y-%m-%d %H:%M:%S') AS modifieddate,
       t.node AS organizationnode
FROM r JOIN tree AS t ON t.emp = r."Emp ID";

-- table: emailaddress
SELECT CAST(regexp_extract("Emp ID", '(\d+)$', 1) AS INTEGER) AS businessentityid,
       CAST(row_number() OVER (ORDER BY "Emp ID") AS INTEGER) AS emailaddressid,
       trim(regexp_extract(Contacts, '(?i)email:\s*([^|]+)', 1)) AS emailaddress,
       strptime("Last Updated", '%Y-%m-%d %H:%M:%S') AS modifieddate
FROM read_csv('${SOURCE}/hris_roster.csv', header = true, all_varchar = true);

-- table: personphone
-- One row per phone in Contacts, typed through the loaded phonenumbertype table.
WITH c AS (
    SELECT CAST(regexp_extract(r."Emp ID", '(\d+)$', 1) AS INTEGER) AS businessentityid,
           lower(trim(split_part(u.c, ':', 1))) AS label,
           trim(split_part(u.c, ':', 2)) AS phonenumber,
           strptime(r."Last Updated", '%Y-%m-%d %H:%M:%S') AS modifieddate
    FROM read_csv('${SOURCE}/hris_roster.csv', header = true, all_varchar = true) AS r,
         unnest(string_split(r.Contacts, '|')) AS u(c)
)
SELECT c.businessentityid, c.phonenumber, p.phonenumbertypeid, c.modifieddate
FROM c JOIN phonenumbertype AS p ON lower(p.name) = c.label;

-- table: employeedepartmenthistory
-- Events become intervals: department and shift carry forward, the next event ends an interval the day
-- before it starts, a termination ends it on that day. Employees are found by login, departments by name.
WITH ev AS (
    SELECT strptime(event_time, '%Y-%m-%d %H:%M') AS ts,
           lower(login) AS login,
           action,
           CASE WHEN department IN ('R&D', 'Research & Development') THEN 'Research and Development'
                ELSE nullif(department, '') END AS department,
           nullif(shift, '') AS shift
    FROM read_csv('${SOURCE}/org_assignment_events.csv', header = true, all_varchar = true)
),
state AS (
    SELECT ts, login, action,
           last_value(department IGNORE NULLS) OVER w AS department,
           last_value(shift IGNORE NULLS) OVER w AS shift,
           lead(ts) OVER (PARTITION BY login ORDER BY ts) AS next_ts,
           lead(action) OVER (PARTITION BY login ORDER BY ts) AS next_action
    FROM ev
    WINDOW w AS (PARTITION BY login ORDER BY ts ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
)
SELECT e.businessentityid,
       d.departmentid,
       s.shiftid,
       CAST(st.ts AS DATE) AS startdate,
       CASE WHEN st.next_action = 'TERMINATION' THEN CAST(st.next_ts AS DATE)
            WHEN st.next_ts IS NOT NULL THEN CAST(st.next_ts AS DATE) - 1 END AS enddate
FROM state AS st
JOIN employee AS e ON lower(e.loginid) = st.login
JOIN department AS d ON d.name = st.department
JOIN shift AS s ON s.name = st.shift
WHERE st.action <> 'TERMINATION';

-- table: employeepayhistory
-- Pay periods collapse to rate changes; annual salaries become hourly rates over 2080 hours; employees
-- are found by national ID, which keeps its leading zeros only when read as text.
WITH periods AS (
    SELECT DISTINCT SSN AS ssn,
           CAST("Period Start" AS DATE) AS period_start,
           Basis AS basis,
           CAST(replace(replace(Amount, '$', ''), ',', '') AS DECIMAL(12, 2)) AS amount,
           lower(Cycle) AS cycle
    FROM read_csv('${SOURCE}/payroll_register.csv', header = true, all_varchar = true)
),
changes AS (
    SELECT *, lag(amount) OVER (PARTITION BY ssn ORDER BY period_start) AS previous_amount
    FROM periods
)
SELECT e.businessentityid,
       CAST(c.period_start AS TIMESTAMP) AS ratechangedate,
       CASE c.basis WHEN 'ANNUAL' THEN round(c.amount / 2080, 4) ELSE c.amount END AS rate,
       CASE WHEN c.cycle = 'monthly' THEN 1 ELSE 2 END AS payfrequency
FROM changes AS c JOIN employee AS e ON e.nationalidnumber = c.ssn
WHERE c.previous_amount IS NULL OR c.previous_amount <> c.amount;
