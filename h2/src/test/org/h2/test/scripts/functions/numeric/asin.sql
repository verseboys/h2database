-- Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
-- and the EPL 1.0 (https://h2database.com/html/license.html).
-- Initial Developer: H2 Group
--

select asin(null) vn, asin(-1) r1;
> VN   R1
> ---- -------------------
> null -1.5707963267948966
> rows: 1

SELECT ASIN(-1.1);
> exception INVALID_VALUE_2

SELECT ASIN(1.1);
> exception INVALID_VALUE_2

SELECT ASIN(CAST('Infinity' AS DOUBLE PRECISION));
> exception INVALID_VALUE_2
