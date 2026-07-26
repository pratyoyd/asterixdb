import aiohttp
import asyncio
import os
import argparse
from datetime import datetime
import time
import shutil

def _apply_sf_suffix(sql: str, sf: int) -> str:
    """
    Replace any TPC-H base table or already-suffixed table names with the desired _{sf} suffix.
    Works for: Customer, Lineitem, Nation, Orders, Part, Partsupp, Region, Supplier.
    Leaves non-TPC-H tables (e.g., SSB_*) untouched.
    """
    import re
    pattern = re.compile(r'\b(Customer|Lineitem|Nation|Orders|Part|Partsupp|Region|Supplier|MinCost|HalfSumQty)(?:_\d+)?\b')
    return pattern.sub(lambda m: f"{m.group(1)}_{sf}", sql)

def _is_ssb(base_q: str) -> bool:
    return base_q.startswith("SSB")

def resolve_queries(query: str, sf: str, strategy: str,
                    default_blocking_query, default_interactive_query):
    """
    Returns (blocking_query, interactive_query, query_label)
    Ensures dynamic queries get _apply_sf_suffix for TPC-H when SF-specific
    dynamic variants are missing.
    """
    base_q = query.split('_')[0]              # e.g., "q3" or "SSB_q21"
    query_label = f"{base_q}_{sf}"            # for filenames/logs

    if strategy == "dynamic":
        # 1) Prefer exact SF-specific dynamic variants, if they exist
        bq = globals().get(f"blocking_query_{query_label}_dynamic")
        iq = globals().get(f"interactive_query_{query_label}_dynamic")

        if bq is None or iq is None:
            # 2) Fall back to OG dynamic base (no SF) or base non-dynamic
            bq = globals().get(
                f"blocking_query_{base_q}_dynamic",
                globals().get(f"blocking_query_{base_q}", default_blocking_query)
            )
            iq = globals().get(
                f"interactive_query_{base_q}_dynamic",
                globals().get(f"interactive_query_{base_q}", default_interactive_query)
            )
            # 3) If it’s TPC-H (not SSB), apply the SF suffix to table names
            if not _is_ssb(base_q):
                bq = _apply_sf_suffix(bq, sf)
                iq = _apply_sf_suffix(iq, sf)

        return bq, iq, query_label

    # -------- non-dynamic path (your existing mapping) --------
    query_map = {
        "q1": (blocking_query_q1, interactive_query_q1),
        "q3": (blocking_query_q3, interactive_query_q3),
        "q4": (blocking_query_q4, interactive_query_q4),
        "q5": (blocking_query_q5, interactive_query_q5),
        "q8": (blocking_query_q8, interactive_query_q8),
        "q9": (blocking_query_q9, interactive_query_q9),
        "q10": (blocking_query_q10, interactive_query_q10),
        "q11": (blocking_query_q11, interactive_query_q11),
        "q12": (blocking_query_q12, interactive_query_q12),
        "q16": (blocking_query_q16, interactive_query_q16),
        "q18": (blocking_query_q18, interactive_query_q18),
        "q20": (blocking_query_q20, interactive_query_q20),
        "q21": (blocking_query_q21, interactive_query_q21),
        "q22": (blocking_query_q22, interactive_query_q22),
        "q2": (blocking_query_q2, interactive_query_q2),
        "q7": (blocking_query_q7, interactive_query_q7),

        "SSB_q21": (blocking_query_SSB_q21, interactive_query_SSB_q21),
        "SSB_q22": (blocking_query_SSB_q22, interactive_query_SSB_q22),
        "SSB_q23": (blocking_query_SSB_q23, interactive_query_SSB_q23),
        "SSB_q31": (blocking_query_SSB_q31, interactive_query_SSB_q31),
        "SSB_q32": (blocking_query_SSB_q32, interactive_query_SSB_q32),
        "SSB_q33": (blocking_query_SSB_q33, interactive_query_SSB_q33),
        "SSB_q34": (blocking_query_SSB_q34, interactive_query_SSB_q34),
        "SSB_q41": (blocking_query_SSB_q41, interactive_query_SSB_q41),
        "SSB_q42": (blocking_query_SSB_q42, interactive_query_SSB_q42),
        "SSB_q43": (blocking_query_SSB_q43, interactive_query_SSB_q43),

        # SF30
        "q3_30": (blocking_query_q3_30, interactive_query_q3_30),
        "q10_30": (blocking_query_q10_30, interactive_query_q10_30),
        "q4_30":  (blocking_query_q4_30,  interactive_query_q4_30),
        "q5_30":  (blocking_query_q5_30,  interactive_query_q5_30),
        "q8_30":  (blocking_query_q8_30,  interactive_query_q8_30),
        "q9_30":  (blocking_query_q9_30,  interactive_query_q9_30),
        "q1_30":  (blocking_query_q1_30,  interactive_query_q1_30),
        "q12_30": (blocking_query_q12_30, interactive_query_q12_30),
        "q16_30": (blocking_query_q16_30, interactive_query_q16_30),
        "q18_30": (blocking_query_q18_30, interactive_query_q18_30),

        # SF1 (new)
        "q1_1":  (blocking_query_q1_1,  interactive_query_q1_1),
        "q3_1":  (blocking_query_q3_1,  interactive_query_q3_1),
        "q4_1":  (blocking_query_q4_1,  interactive_query_q4_1),
        "q8_1":  (blocking_query_q8_1,  interactive_query_q8_1),
        "q9_1":  (blocking_query_q9_1,  interactive_query_q9_1),
        "q10_1": (blocking_query_q10_1, interactive_query_q10_1),
        "q12_1": (blocking_query_q12_1, interactive_query_q12_1),
        "q16_1": (blocking_query_q16_1, interactive_query_q16_1),
        "q18_1": (blocking_query_q18_1, interactive_query_q18_1),
    }

    bq, iq = query_map.get(query, (default_blocking_query, default_interactive_query))

    # If user asked e.g., query="q3" and sf="50", ensure TPCH tables get suffixed
    if not _is_ssb(base_q) and f"_{sf}" not in query:
        bq = _apply_sf_suffix(bq, sf)
        iq = _apply_sf_suffix(iq, sf)

    return bq, iq, query_label

# timeout configuration for aiohttp client
# total request timeout is set high due to long-running queries
# individual connection and socket timeouts are also extended

timeout = aiohttp.ClientTimeout(
    total=10000,        # total request time
    connect=600,        # max time to connect
    sock_connect=600,   # max time to establish socket
    sock_read=10000     # max time without receiving a chunk
)

# interactive_query = ("set `compiler.interactive.mode` `true`;SELECT l.l_shipdate,sum(l.l_quantity) FROM Lineitem_10 l where l.l_shipdate > \"1992-12-16\" AND l.l_shipdate <= \"1995-12-31\" GROUP BY l.l_shipdate")

# blocking_query = ("SELECT a.* FROM(SELECT l.l_shipdate,sum(l.l_quantity) FROM Lineitem_10 l where l.l_shipdate > \"1992-12-16\" AND l.l_shipdate <= \"1995-12-31\" GROUP BY l.l_shipdate)a where a.shipdate > \"1995-01-01\")

#tpch q10
interactive_query_q10 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT c.c_custkey, c.c_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue, '
    'c.c_acctbal, c.c_address, c.c_phone, c.c_comment '
    'FROM Customer_10 AS c, Orders_10 AS o, Lineitem_10 AS l '
    'WHERE c.c_custkey /*+indexnl*/ = o.o_custkey '
    'AND o.o_orderkey /*+indexnl*/ = l.l_orderkey '
    'AND o.o_orderdate >= "1993-10-01" AND o.o_orderdate < "1994-01-01" '
    'AND c.c_custkey < 100000 '
    'GROUP BY c.c_custkey, c.c_name, c.c_acctbal, c.c_phone, c.c_address, c.c_comment;'
)
interactive_query_q10_30 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT c.c_custkey, c.c_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue, '
    'c.c_acctbal, c.c_address, c.c_phone, c.c_comment '
    'FROM Customer_30 AS c, Orders_30 AS o, Lineitem_30 AS l '
    'WHERE c.c_custkey /*+indexnl*/ = o.o_custkey '
    'AND o.o_orderkey /*+indexnl*/ = l.l_orderkey '
    'AND o.o_orderdate >= "1993-10-01" AND o.o_orderdate < "1994-01-01" '
    'AND c.c_custkey < 100000 '
    'GROUP BY c.c_custkey, c.c_name, c.c_acctbal, c.c_phone, c.c_address, c.c_comment;'
)


interactive_query_q10_dynamic = (
    "SET `compiler.interactive.mode` `true`; "
    "SELECT c.c_custkey, n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Customer_10 AS c, Nation_10 AS n, Orders_10 AS o, Lineitem_10 AS l "
    "WHERE c.c_nationkey /*+indexnl*/ = n.n_nationkey "
    "AND c.c_custkey /*+indexnl*/ = o.o_custkey AND o.o_orderkey /*+indexnl*/ = l.l_orderkey "
    "AND o.o_orderdate >= \"1993-10-01\" AND o.o_orderdate < \"1994-01-01\" AND l.l_returnflag = \"R\"  "
    "GROUP BY c.c_custkey, n.n_name"
)

interactive_query_q10_30_dynamic = (
    "SET `compiler.interactive.mode` `true`; "
    "SELECT c.c_custkey, n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Customer_30 AS c, Nation_30 AS n, Orders_30 AS o, Lineitem_30 AS l "
    "WHERE c.c_nationkey /*+indexnl*/ = n.n_nationkey "
    "AND c.c_custkey /*+indexnl*/ = o.o_custkey AND o.o_orderkey /*+indexnl*/ = l.l_orderkey "
    "AND o.o_orderdate >= \"1993-10-01\" AND o.o_orderdate < \"1994-01-01\" AND l.l_returnflag = \"R\""
    "GROUP BY c.c_custkey, n.n_name"
)

# blocking_query_q10 = (
#     "SELECT c.c_custkey,  n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
#     "FROM Customer_10 AS c "
#     "JOIN Nation_10 AS n ON  c.c_nationkey = n.n_nationkey "
#     "JOIN Orders_10 AS o ON o.o_custkey = c.c_custkey "
#     "JOIN Lineitem_10 AS l ON l.l_orderkey = o.o_orderkey "
#     "WHERE o.o_orderdate >= \"1993-10-01\" AND o.o_orderdate < \"1994-01-01\" "
#     "AND l.l_returnflag = \"R\"  "
#     "GROUP BY c.c_custkey, n.n_name"
# )

blocking_query_q10 = (
    "SELECT ELEMENT {'c_custkey': c_custkey, 'c_name': c_name, 'revenue': revenue, "
    "'c_acctbal': c_acctbal, 'n_name': n_name, 'c_address': c_address, "
    "'c_phone': c_phone, 'c_comment': c_comment} "
    "FROM ( "
    "  SELECT ELEMENT {'c_custkey': ocn.c_custkey, 'c_name': ocn.c_name, 'c_acctbal': ocn.c_acctbal, "
    "  'n_name': ocn.n_name, 'c_address': ocn.c_address, 'c_phone': ocn.c_phone, "
    "  'c_comment': ocn.c_comment, 'l_extendedprice': l.l_extendedprice, 'l_discount': l.l_discount} "
    "  FROM Lineitem_10 AS l, ( "
    "    SELECT ELEMENT {'c_custkey': c.c_custkey, 'c_name': c.c_name, 'c_acctbal': c.c_acctbal, "
    "    'n_name': n.n_name, 'c_address': c.c_address, 'c_phone': c.c_phone, 'c_comment': c.c_comment, "
    "    'o_orderkey': o.o_orderkey} "
    "    FROM Orders_10 AS o, Customer_10 AS c, Nation_10 AS n "
    "    WHERE ((c.c_custkey = o.o_custkey) AND (o.o_orderdate >= '1993-10-01') "
    "    AND (o.o_orderdate < '1994-01-01') AND (c.c_nationkey = n.n_nationkey)) "
    "  ) AS ocn "
    "  WHERE ((l.l_orderkey = ocn.o_orderkey) AND (l.l_returnflag = 'R')) "
    ") AS locn "
    "GROUP BY locn.c_custkey AS c_custkey, locn.c_name AS c_name, locn.c_acctbal AS c_acctbal, "
    "locn.c_phone AS c_phone, locn.n_name AS n_name, locn.c_address AS c_address, locn.c_comment AS c_comment "
    "GROUP AS g "
    "LET revenue = strict_sum((SELECT ELEMENT (i.locn.l_extendedprice * (1 - i.locn.l_discount)) FROM g AS i));"
)

blocking_query_q10_30 = (
    "SELECT c.c_custkey,  n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Customer_30 AS c "
    "JOIN Nation_30 AS n ON  c.c_nationkey = n.n_nationkey "
    "JOIN Orders_30 AS o ON o.o_custkey = c.c_custkey "
    "JOIN Lineitem_30 AS l ON l.l_orderkey = o.o_orderkey "
    "WHERE o.o_orderdate >= \"1993-10-01\" AND o.o_orderdate < \"1994-01-01\" "
    "AND l.l_returnflag = \"R\"  "
    "GROUP BY c.c_custkey, n.n_name"
)

blocking_query_q10_dynamic = (
    "SET `compiler.blocking.mode` `true`; "
    "SELECT a.* FROM( "
    "SELECT c.c_custkey, n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Customer_10 AS c "
    "JOIN Nation_10 AS n ON  c.c_nationkey = n.n_nationkey "
    "JOIN Orders_10 AS o ON o.o_custkey = c.c_custkey "
    "JOIN Lineitem_10 AS l ON l.l_orderkey = o.o_orderkey "
    "WHERE o.o_orderdate >= \"1993-10-01\" AND o.o_orderdate < \"1994-01-01\" "
    "AND l.l_returnflag = \"R\"  "
    "GROUP BY c.c_custkey, n.n_name"
    ")a where a.c_custkey > 20000"

)

blocking_query_q10_30_dynamic = (
    "SET `compiler.blocking.mode` `true`; "
    "SELECT a.* FROM( "
    "SELECT c.c_custkey, n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Customer_30 AS c "
    "JOIN Nation_30 AS n ON  c.c_nationkey = n.n_nationkey "
    "JOIN Orders_30 AS o ON o.o_custkey = c.c_custkey "
    "JOIN Lineitem_30 AS l ON l.l_orderkey = o.o_orderkey "
    "WHERE o.o_orderdate >= \"1993-10-01\" AND o.o_orderdate < \"1994-01-01\" "
    "AND l.l_returnflag = \"R\"  "
    "GROUP BY c.c_custkey, n.n_name"
    ")a where a.c_custkey > 20000"

)

# blocking_query_q10_dynamic = (
#     "SET `compiler.blocking.mode` `true`; "
#     "SELECT a.* FROM( "
#     "SELECT c.c_custkey, c.c_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue, "
#     "c.c_acctbal, c.c_address, c.c_phone, c.c_comment "
#     "FROM Customer_10 AS c "
#     "JOIN Orders_10 AS o ON o.o_custkey = c.c_custkey "
#     "JOIN Lineitem_10 AS l ON l.l_orderkey = o.o_orderkey "
#     "WHERE o.o_orderdate >= \"1993-10-01\" AND o.o_orderdate < \"1994-01-01\" "
#     "AND l.l_returnflag = \"R\"  "
#     "GROUP BY c.c_custkey, c.c_name, c.c_acctbal, c.c_address, c.c_phone, c.c_comment"
#     ")a where a.c_custkey > 20000"
#
# )

# tpch q3
interactive_query_q3 = (
    "SET `compiler.interactive.mode` `true`; "
    "SELECT l.l_orderkey, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Lineitem_10 AS l, Orders_10 AS o, Customer_10 AS c "
    "WHERE c.c_mktsegment = \"BUILDING\" "
    "AND l.l_orderkey /*+ indexnl */ = o.o_orderkey "
    "AND o.o_custkey /*+ indexnl */ = c.c_custkey "
    "AND o.o_orderdate < \"1995-03-15\" AND l.l_shipdate > \"1995-03-15\" AND l.l_orderkey < 27000000"
    "GROUP BY l.l_orderkey "

)
interactive_query_q3_30 = (
    "SET `compiler.interactive.mode` `true`; "
    "SELECT l.l_orderkey, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Lineitem_30 AS l, Orders_30 AS o, Customer_30 AS c "
    "WHERE c.c_mktsegment = \"BUILDING\" "
    "AND l.l_orderkey /*+ indexnl */ = o.o_orderkey "
    "AND o.o_custkey /*+ indexnl */ = c.c_custkey "
    "AND o.o_orderdate < \"1995-03-15\" AND l.l_shipdate > \"1995-03-15\" AND l.l_orderkey < 27000000"
    "GROUP BY l.l_orderkey "

)
# interactive_query_q3 = (
#     "SET `compiler.interactive.mode` `true`; "
#     "SELECT l.l_orderkey, o.o_orderdate, o.o_shippriority, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
#     "FROM Lineitem_10 AS l, Orders_10 AS o, Customer_10 AS c "
#     "WHERE c.c_mktsegment = \"BUILDING\" "
#     "AND l.l_orderkey /*+ indexnl */ = o.o_orderkey "
#     "AND o.o_custkey /*+ indexnl */ = c.c_custkey "
#     "AND o.o_orderdate < \"1995-03-15\" AND l.l_shipdate > \"1995-03-15\" AND l.l_orderkey < 10000"
#     "GROUP BY l.l_orderkey, o.o_orderdate, o.o_shippriority"
# )

interactive_query_q3_dynamic = (
    "SET `compiler.interactive.mode` `true`; "
    "SELECT l.l_orderkey, o.o_orderdate, o.o_shippriority, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Lineitem_10 AS l, Orders_10 AS o, Customer_10 AS c "
    "WHERE c.c_mktsegment = \"BUILDING\" "
    "AND l.l_orderkey /*+ indexnl */ = o.o_orderkey "
    "AND o.o_custkey /*+ indexnl */ = c.c_custkey "
    "AND o.o_orderdate < \"1995-03-15\" AND l.l_shipdate > \"1995-03-15\" "
    "GROUP BY l.l_orderkey, o.o_orderdate, o.o_shippriority"
)
interactive_query_q3_30_dynamic = (
    "SET `compiler.interactive.mode` `true`; "
    "SELECT l.l_orderkey, o.o_orderdate, o.o_shippriority, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Lineitem_30 AS l, Orders_30 AS o, Customer_30 AS c "
    "WHERE c.c_mktsegment = \"BUILDING\" "
    "AND l.l_orderkey /*+ indexnl */ = o.o_orderkey "
    "AND o.o_custkey /*+ indexnl */ = c.c_custkey "
    "AND o.o_orderdate < \"1995-03-15\" AND l.l_shipdate > \"1995-03-15\" "
    "GROUP BY l.l_orderkey, o.o_orderdate, o.o_shippriority"
)

# blocking_query_q3 = (
#     "SELECT l.l_orderkey, o.o_orderdate,  o.o_shippriority, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
#     "FROM Customer_10 AS c "
#     "JOIN Orders_10 AS o ON c.c_custkey = o.o_custkey "
#     "JOIN Lineitem_10 AS l ON l.l_orderkey = o.o_orderkey "
#     "WHERE c.c_mktsegment = \"BUILDING\" "
#     "AND o.o_orderdate < \"1995-03-15\" AND l.l_shipdate > \"1995-03-15\" "
#     "GROUP BY l.l_orderkey, o.o_orderdate, o.o_shippriority"
# )
blocking_query_q3 = (
    "SELECT ELEMENT {'l_orderkey': l_orderkey, 'revenue': revenue, "
    "'o_orderdate': o_orderdate, 'o_shippriority': o_shippriority} "
    "FROM Customer_10 AS c, Orders_10 AS o, Lineitem_10 AS l "
    "WHERE (((c.c_mktsegment = 'BUILDING') AND (c.c_custkey = o.o_custkey)) "
    "AND ((l.l_orderkey = o.o_orderkey) AND (o.o_orderdate < '1995-03-15') "
    "AND (l.l_shipdate > '1995-03-15'))) "
    "/* +hash */ "
    "GROUP BY l.l_orderkey AS l_orderkey, o.o_orderdate AS o_orderdate, "
    "o.o_shippriority AS o_shippriority "
    "GROUP AS g "
    "LET revenue = STRICT_SUM(( "
    "  SELECT ELEMENT (i.l_extendedprice * (1 - i.l_discount)) "
    "  FROM (FROM g SELECT VALUE l) AS i "
    "));"
)
blocking_query_q3_30 = (
    "SELECT l.l_orderkey, o.o_orderdate,  o.o_shippriority, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Customer_30 AS c "
    "JOIN Orders_30 AS o ON c.c_custkey = o.o_custkey "
    "JOIN Lineitem_30 AS l ON l.l_orderkey = o.o_orderkey "
    "WHERE c.c_mktsegment = \"BUILDING\" "
    "AND o.o_orderdate < \"1995-03-15\" AND l.l_shipdate > \"1995-03-15\" "
    "GROUP BY l.l_orderkey, o.o_orderdate, o.o_shippriority"
)

blocking_query_q3_dynamic = (
    "SET `compiler.blocking.mode` `true`; "
    "SELECT a.* FROM( "
    "SELECT l.l_orderkey ,o.o_orderdate,  o.o_shippriority, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Customer_10 AS c, Orders_10 AS o, Lineitem_10 AS l "
    "WHERE c.c_mktsegment = \"BUILDING\" "
    "AND o.o_custkey = c.c_custkey "
    "AND l.l_orderkey = o.o_orderkey "
    "AND o.o_orderdate < \"1995-03-15\" AND l.l_shipdate > \"1995-03-15\" "
    "GROUP BY l.l_orderkey, o.o_orderdate, o.o_shippriority "
    ")a where a.l_orderkey > 10000"

)

blocking_query_q3_30_dynamic = (
    "SET `compiler.blocking.mode` `true`; "
    "SELECT a.* FROM( "
    "SELECT l.l_orderkey ,o.o_orderdate,  o.o_shippriority, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Customer_30 AS c, Orders_30 AS o, Lineitem_30 AS l "
    "WHERE c.c_mktsegment = \"BUILDING\" "
    "AND o.o_custkey = c.c_custkey "
    "AND l.l_orderkey = o.o_orderkey "
    "AND o.o_orderdate < \"1995-03-15\" AND l.l_shipdate > \"1995-03-15\" "
    "GROUP BY l.l_orderkey, o.o_orderdate, o.o_shippriority "
    ")a where a.l_orderkey > 10000"

)
blocking_query_q1 = "SELECT l_returnflag, l_linestatus, SUM(l_quantity) AS sum_qty, SUM(l_extendedprice) AS sum_base_price, SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price, SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge, AVG(l_quantity) AS avg_qty, AVG(l_extendedprice) AS avg_price, AVG(l_discount) AS avg_disc, COUNT(*) AS count_order FROM Lineitem_10 WHERE l_shipdate <= \"1998-09-01\" GROUP BY l_returnflag, l_linestatus"
interactive_query_q1 =  "SET `compiler.interactive.mode` \"true\"; EXPLAIN SELECT l_returnflag, l_linestatus, SUM(l_quantity) AS sum_qty, SUM(l_extendedprice) AS sum_base_price, SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price, SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge, AVG(l_quantity) AS avg_qty, AVG(l_extendedprice) AS avg_price, AVG(l_discount) AS avg_disc, COUNT(*) AS count_order FROM Lineitem_10 WHERE l_shipdate <= \"1998-09-01\" AND l_returnflag = \"N\" GROUP BY l_returnflag, l_linestatus"
blocking_query_q2 = "SELECT s.s_acctbal, s.s_name, n.n_name, p.p_partkey, p.p_mfgr, s.s_address, s.s_phone, s.s_comment FROM Part_10 p, Supplier_10 s, Partsupp_10 ps, Nation_10 n, Region_10 r WHERE p.p_partkey = ps.ps_partkey AND s.s_suppkey = ps.ps_suppkey AND p.p_size = 15 AND p.p_type LIKE \"%BRASS\" AND s.s_nationkey = n.n_nationkey AND n.n_regionkey = r.r_regionkey AND r.r_name = \"EUROPE\" ORDER BY s.s_acctbal DESC"
interactive_query_q2 = "SET `compiler.interactive.mode` \"true\"; SELECT s.s_acctbal, s.s_name, n.n_name, p.p_partkey,ps.ps_suppkey, p.p_mfgr, s.s_address, s.s_phone, s.s_comment FROM Supplier_10 s, Partsupp_10 ps, Part_10 p, Nation_10 n, Region_10 r WHERE s.s_suppkey /* +indexnl */= ps.ps_suppkey AND ps.ps_partkey /* +indexnl */= p.p_partkey  AND s.s_nationkey /* +indexnl*/ = n.n_nationkey AND n.n_regionkey/* +indexnl*/ = r.r_regionkey AND r.r_name = \"EUROPE\" AND p.p_size = 15 AND p.p_type LIKE \"%BRASS\" AND s.s_acctbal > -1000"
interactive_query_q4 = (
    "SET `compiler.interactive.mode` `true`; "
    "SELECT o.o_orderpriority, COUNT(DISTINCT o.o_orderkey) AS order_count "
    "FROM Orders_10 AS o JOIN Lineitem_10 AS l "
    "ON o.o_orderkey /*+ indexnl */ = l.l_orderkey "
    "WHERE o.o_orderdate >= \"1993-07-01\" AND o.o_orderdate < \"1993-10-01\" "
    "AND o.o_orderpriority < \"2-HIGH\" "
    "AND l.l_commitdate < l.l_receiptdate "
    "GROUP BY o.o_orderpriority "
)
interactive_query_q4_dynamic = (
    "SET `compiler.interactive.mode` `true`; "
    "SELECT o.o_orderpriority, COUNT(DISTINCT o.o_orderkey) AS order_count "
    "FROM Orders_10 AS o JOIN Lineitem_10 AS l "
    "ON o.o_orderkey /*+ indexnl */ = l.l_orderkey "
    "WHERE o.o_orderdate >= \"1993-07-01\" AND o.o_orderdate < \"1993-10-01\" "
    "AND o.o_orderpriority < \"2-HIGH\" "
    "AND l.l_commitdate < l.l_receiptdate "
    "GROUP BY o.o_orderpriority "
)
blocking_query_q1_dynamic = (
    'SET `compiler.blocking.mode` `true`; '
    'SELECT a.* FROM ( '
    'SELECT l_returnflag, l_linestatus, '
    'SUM(l_quantity) AS sum_qty, '
    'SUM(l_extendedprice) AS sum_base_price, '
    'SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price, '
    'SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge, '
    'AVG(l_quantity) AS avg_qty, '
    'AVG(l_extendedprice) AS avg_price, '
    'AVG(l_discount) AS avg_disc, '
    'COUNT(*) AS count_order '
    'FROM Lineitem_10 '
    'WHERE l_shipdate <= "1998-09-01" '
    'GROUP BY l_returnflag, l_linestatus '
    ') a WHERE a.l_returnflag = "N"'
)
interactive_query_q1_dynamic =  "SET `compiler.interactive.mode` \"true\"; SELECT l_returnflag, l_linestatus, SUM(l_quantity) AS sum_qty, SUM(l_extendedprice) AS sum_base_price, SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price, SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge, AVG(l_quantity) AS avg_qty, AVG(l_extendedprice) AS avg_price, AVG(l_discount) AS avg_disc, COUNT(*) AS count_order FROM Lineitem_10 WHERE l_shipdate <= \"1998-09-01\" AND l_returnflag = \"A\" GROUP BY l_returnflag, l_linestatus"


blocking_query_q4 = (

    "SELECT o.o_orderpriority, COUNT(DISTINCT o.o_orderkey) AS order_count "
    "FROM Lineitem_10 AS l, Orders_10 AS o  "
    "WHERE  l.l_orderkey = o.o_orderkey "
    "AND o.o_orderdate >= \"1993-07-01\" AND o.o_orderdate < \"1993-10-01\" "
    "AND l.l_commitdate < l.l_receiptdate "
    "GROUP BY o.o_orderpriority"
    " ORDER BY o.o_orderpriority"
)
blocking_query_q4_dynamic = (
    'SET `compiler.blocking.mode` `true`; '
    'SELECT a.* FROM ( '
    'SELECT o.o_orderpriority, COUNT(DISTINCT o.o_orderkey) AS order_count '
    'FROM Lineitem_10 AS l, Orders_10 AS o '
    'WHERE l.l_orderkey = o.o_orderkey '
    'AND o.o_orderdate >= "1993-07-01" AND o.o_orderdate < "1993-10-01" '
    'AND l.l_commitdate < l.l_receiptdate '
    'GROUP BY o.o_orderpriority '
    'ORDER BY o.o_orderpriority '
    ') a WHERE a.o_orderpriority >= "2-HIGH"'
)

# interactive_query_q5 = 'SET `compiler.interactive.mode` "true";  SELECT n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue FROM Nation_10 n, Region_10 r, Supplier_10 s, Customer_10 c, Lineitem_10 l, Orders_10 o WHERE n.n_regionkey /* +indexnl */ = r.r_regionkey AND n.n_nationkey /* +indexnl */ = s.s_nationkey AND n.n_nationkey /* +indexnl */ = c.c_nationkey AND c.custkey /* +indexnl */ = o.o_custkey AND o.o_orderkey /* +indexnl */ = l.l_orderkey AND s.s_suppkey /* +indexnl */ = l.l_suppkey AND r.r_name = "ASIA" AND o.o_orderdate >= "1994-01-01" AND o.o_orderdate < "1995-01-01" AND n.n_name = "INDIA" and o.o_orderkey = 1  GROUP BY n.n_name'
#
# blocking_query_q5 = "SELECT n.n_name AS n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue FROM Customer_10 c, Orders_10 o, Lineitem_10 l, Supplier_10 s, Nation_10 n, Region_10 r WHERE c.c_custkey = o.o_custkey AND l.l_orderkey = o.o_orderkey AND l.l_suppkey = s.s_suppkey AND c.c_nationkey = s.s_nationkey AND s.s_nationkey = n.n_nationkey AND n.n_regionkey = r.r_regionkey AND r.r_name = \"ASIA\" AND o.o_orderdate >= \"1994-01-01\" AND o.o_orderdate < \"1995-01-01\" GROUP BY n.n_name"
# #interactive_query_q9 = 'SET `compiler.interactive.mode` "true"; SELECT n.n_name AS nation, SUM(l.l_extendedprice * (1 - l.l_discount) - ps.ps_supplycost * l.l_quantity) AS sum_profit FROM Nation_10 AS n JOIN Supplier_10 AS s ON n.n_nationkey /*+ indexnl */ = s.s_nationkey JOIN Partsupp_10 AS ps ON s.s_suppkey /*+ indexnl */ = ps.ps_suppkey JOIN Part_10 AS p ON ps.ps_partkey /*+ indexnl */ = p.p_partkey JOIN Lineitem_10 AS l ON ps.ps_partkey /*+ indexnl */ = l.l_partkey AND ps.ps_suppkey /*+ indexnl */ = l.l_suppkey WHERE p.p_name LIKE "%green%" AND n.n_name <= "E" GROUP BY n.n_name'
blocking_query_q9 = 'SELECT n.n_name AS nation, SUM(l.l_extendedprice * (1 - l.l_discount) - ps.ps_supplycost * l.l_quantity) AS sum_profit FROM Part_10 AS p JOIN Lineitem_10 AS l ON l.l_partkey = p.p_partkey JOIN Partsupp_10 AS ps ON ps.ps_partkey = l.l_partkey AND ps.ps_suppkey = l.l_suppkey JOIN Supplier_10 AS s ON s.s_suppkey = l.l_suppkey JOIN Nation_10 AS n ON s.s_nationkey = n.n_nationkey WHERE p.p_name LIKE "%green%" GROUP BY n.n_name ORDER BY n.n_name'
interactive_query_q12 = 'SET `compiler.interactive.mode` "true"; SELECT l.l_shipmode AS l_shipmode, SUM(CASE WHEN o.o_orderpriority = "1-URGENT" OR o.o_orderpriority = "2-HIGH" THEN 1 ELSE 0 END) AS high_line_count, SUM(CASE WHEN o.o_orderpriority != "1-URGENT" AND o.o_orderpriority != "2-HIGH" THEN 1 ELSE 0 END) AS low_line_count FROM Lineitem_10 AS l, Orders_10 AS o  WHERE l.l_orderkey /* +indexnl */ = o.o_orderkey AND    l.l_shipmode ="MAIL" AND l.l_commitdate < l.l_receiptdate AND l.l_shipdate < l.l_commitdate AND l.l_receiptdate >= "1994-01-01" AND l.l_receiptdate < "1995-01-01" GROUP BY l.l_shipmode'
blocking_query_q12 = 'SELECT l.l_shipmode AS l_shipmode, SUM(CASE WHEN o.o_orderpriority = "1-URGENT" OR o.o_orderpriority = "2-HIGH" THEN 1 ELSE 0 END) AS high_line_count, SUM(CASE WHEN o.o_orderpriority != "1-URGENT" AND o.o_orderpriority != "2-HIGH" THEN 1 ELSE 0 END) AS low_line_count FROM Orders_10 AS o, Lineitem_10 AS l WHERE o.o_orderkey = l.l_orderkey AND l.l_shipmode in("MAIL", "SHIP") AND l.l_commitdate < l.l_receiptdate AND l.l_shipdate < l.l_commitdate AND l.l_receiptdate >= "1994-01-01" AND l.l_receiptdate < "1995-01-01" GROUP BY l.l_shipmode ORDER BY l.l_shipmode'
interactive_query_q12_30 = 'SET `compiler.interactive.mode` "true"; SELECT l.l_shipmode AS l_shipmode, SUM(CASE WHEN o.o_orderpriority = "1-URGENT" OR o.o_orderpriority = "2-HIGH" THEN 1 ELSE 0 END) AS high_line_count, SUM(CASE WHEN o.o_orderpriority != "1-URGENT" AND o.o_orderpriority != "2-HIGH" THEN 1 ELSE 0 END) AS low_line_count FROM Lineitem_30 AS l, Orders_30 AS o  WHERE l.l_orderkey /* +indexnl */ = o.o_orderkey AND    l.l_shipmode ="MAIL" AND l.l_commitdate < l.l_receiptdate AND l.l_shipdate < l.l_commitdate AND l.l_receiptdate >= "1994-01-01" AND l.l_receiptdate < "1995-01-01" GROUP BY l.l_shipmode'

blocking_query_q12_30 = 'SELECT l.l_shipmode AS l_shipmode, SUM(CASE WHEN o.o_orderpriority = "1-URGENT" OR o.o_orderpriority = "2-HIGH" THEN 1 ELSE 0 END) AS high_line_count, SUM(CASE WHEN o.o_orderpriority != "1-URGENT" AND o.o_orderpriority != "2-HIGH" THEN 1 ELSE 0 END) AS low_line_count FROM Orders_30 AS o, Lineitem_30 AS l WHERE o.o_orderkey = l.l_orderkey AND l.l_shipmode in("MAIL", "SHIP") AND l.l_commitdate < l.l_receiptdate AND l.l_shipdate < l.l_commitdate AND l.l_receiptdate >= "1994-01-01" AND l.l_receiptdate < "1995-01-01" GROUP BY l.l_shipmode ORDER BY l.l_shipmode'

interactive_query_q12_dynamic = 'SET `compiler.interactive.mode` "true"; SELECT l.l_shipmode AS l_shipmode, SUM(CASE WHEN o.o_orderpriority = "1-URGENT" OR o.o_orderpriority = "2-HIGH" THEN 1 ELSE 0 END) AS high_line_count, SUM(CASE WHEN o.o_orderpriority != "1-URGENT" AND o.o_orderpriority != "2-HIGH" THEN 1 ELSE 0 END) AS low_line_count FROM Lineitem_10 AS l, Orders_10 AS o  WHERE l.l_orderkey /* +indexnl */ = o.o_orderkey AND    l.l_shipmode ="MAIL" AND l.l_commitdate < l.l_receiptdate AND l.l_shipdate < l.l_commitdate AND l.l_receiptdate >= "1994-01-01" AND l.l_receiptdate < "1995-01-01" GROUP BY l.l_shipmode'
interactive_query_q12_30_dynamic = 'SET `compiler.interactive.mode` "true"; SELECT l.l_shipmode AS l_shipmode, SUM(CASE WHEN o.o_orderpriority = "1-URGENT" OR o.o_orderpriority = "2-HIGH" THEN 1 ELSE 0 END) AS high_line_count, SUM(CASE WHEN o.o_orderpriority != "1-URGENT" AND o.o_orderpriority != "2-HIGH" THEN 1 ELSE 0 END) AS low_line_count FROM Lineitem_30 AS l, Orders_30 AS o  WHERE l.l_orderkey /* +indexnl */ = o.o_orderkey AND    l.l_shipmode ="MAIL" AND l.l_commitdate < l.l_receiptdate AND l.l_shipdate < l.l_commitdate AND l.l_receiptdate >= "1994-01-01" AND l.l_receiptdate < "1995-01-01" GROUP BY l.l_shipmode'
# SF10 dynamic
blocking_query_q12_dynamic = (
    'SET `compiler.blocking.mode` `true`; '
    'SELECT a.* FROM ( '
    'SELECT l.l_shipmode AS l_shipmode, '
    'SUM(CASE WHEN o.o_orderpriority = "1-URGENT" OR o.o_orderpriority = "2-HIGH" THEN 1 ELSE 0 END) AS high_line_count, '
    'SUM(CASE WHEN o.o_orderpriority != "1-URGENT" AND o.o_orderpriority != "2-HIGH" THEN 1 ELSE 0 END) AS low_line_count '
    'FROM Orders_10 AS o, Lineitem_10 AS l '
    'WHERE o.o_orderkey = l.l_orderkey '
    'AND l.l_shipmode IN ("MAIL","SHIP") '
    'AND l.l_commitdate < l.l_receiptdate '
    'AND l.l_shipdate < l.l_commitdate '
    'AND l.l_receiptdate >= "1994-01-01" '
    'AND l.l_receiptdate < "1995-01-01" '
    'GROUP BY l.l_shipmode '
    ') a WHERE a.l_shipmode > "MAIL"'
)

# SF30 dynamic
blocking_query_q12_30_dynamic = (
    'SET `compiler.blocking.mode` `true`; '
    'SELECT a.* FROM ( '
    'SELECT l.l_shipmode AS l_shipmode, '
    'SUM(CASE WHEN o.o_orderpriority = "1-URGENT" OR o.o_orderpriority = "2-HIGH" THEN 1 ELSE 0 END) AS high_line_count, '
    'SUM(CASE WHEN o.o_orderpriority != "1-URGENT" AND o.o_orderpriority != "2-HIGH" THEN 1 ELSE 0 END) AS low_line_count '
    'FROM Orders_30 AS o, Lineitem_30 AS l '
    'WHERE o.o_orderkey = l.l_orderkey '
    'AND l.l_shipmode IN ("MAIL","SHIP") '
    'AND l.l_commitdate < l.l_receiptdate '
    'AND l.l_shipdate < l.l_commitdate '
    'AND l.l_receiptdate >= "1994-01-01" '
    'AND l.l_receiptdate < "1995-01-01" '
    'GROUP BY l.l_shipmode '
    ') a WHERE a.l_shipmode > "MAIL"'
)

interactive_query_q9 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT o.o_orderdate, n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Orders_10 AS o '
    'JOIN Lineitem_10 AS l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Part_10 AS p ON l.l_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN Supplier_10 AS s ON l.l_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN Nation_10 AS n ON s.s_nationkey /*+ indexnl */ = n.n_nationkey '
    'WHERE p.p_name LIKE "%green%" '
    'AND o.o_orderdate >= "1993-07-01" '
    'AND o.o_orderdate < "1993-10-01" '
    'GROUP BY o.o_orderdate, n.n_name;'
)

interactive_query_q9_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT o.o_orderkey, n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Orders_10 AS o '
    'JOIN Lineitem_10 AS l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Part_10 AS p ON l.l_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN Supplier_10 AS s ON l.l_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN Nation_10 AS n ON s.s_nationkey /*+ indexnl */ = n.n_nationkey '
    'WHERE p.p_name LIKE "%green%" '
    'AND o.o_orderdate >= "1993-07-01" '
    'AND o.o_orderdate < "1993-10-01" '
    'GROUP BY o.o_orderkey, n.n_name;'
)

blocking_query_q9 = (
    "SELECT o.o_orderdate, n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Lineitem_10 AS l, Orders_10 AS o,  Part_10 AS p, Supplier_10 AS s, Nation_10 AS n "
    "WHERE l.l_orderkey = o.o_orderkey "
    "AND l.l_partkey = p.p_partkey "
    "AND l.l_suppkey = s.s_suppkey "
    "AND s.s_nationkey = n.n_nationkey "
    "AND p.p_name LIKE \"%green%\" "
    "AND o.o_orderdate >= \"1993-07-01\" "
    "AND o.o_orderdate < \"1993-10-01\" "
    "GROUP BY o.o_orderdate, n.n_name;"
)

blocking_query_q9_dynamic = (
    "SET `compiler.blocking.mode` `true`; "
        "SELECT a.* FROM( "
    "SELECT o.o_orderkey, n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Lineitem_10 AS l, Orders_10 AS o,  Part_10 AS p, Supplier_10 AS s, Nation_10 AS n "
    "WHERE l.l_orderkey = o.o_orderkey "
    "AND l.l_partkey = p.p_partkey "
    "AND l.l_suppkey = s.s_suppkey "
    "AND s.s_nationkey = n.n_nationkey "
    "AND p.p_name LIKE \"%green%\" "
    "AND o.o_orderdate >= \"1993-07-01\" "
    "AND o.o_orderdate < \"1993-10-01\" "
    "GROUP BY o.o_orderkey, n.n_name "
     ")a where a.o_orderkey > 10000 "
 )

interactive_query_q18 = (
     "SET `compiler.interactive.mode` \"true\"; "
     "SELECT o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate, "
     "SUM(l.l_quantity) AS total_quantity "
     "FROM Orders_10 o "
     "JOIN Lineitem_10 l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey "
     "JOIN Customer_10 c ON o.o_custkey /*+ indexnl */ = c.c_custkey "
     "WHERE o.o_totalprice > 450000 AND l.l_quantity > 45 "
     "GROUP BY o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate;"
 )

interactive_query_q18_dynamic = (
  "SET `compiler.interactive.mode` \"true\"; "
  "SELECT o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate, "
  "SUM(l.l_quantity) AS total_quantity "
  "FROM Orders_10 o "
  "JOIN Lineitem_10 l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey "
  "JOIN Customer_10 c ON o.o_custkey /*+ indexnl */ = c.c_custkey "
  "WHERE o.o_totalprice > 250000 AND l.l_quantity > 45 "
  "GROUP BY o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate;"
)

blocking_query_q1_30 = "SELECT l_returnflag, l_linestatus, SUM(l_quantity) AS sum_qty, SUM(l_extendedprice) AS sum_base_price, SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price, SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge, AVG(l_quantity) AS avg_qty, AVG(l_extendedprice) AS avg_price, AVG(l_discount) AS avg_disc, COUNT(*) AS count_order FROM Lineitem_10 WHERE l_shipdate <= \"1998-09-01\" GROUP BY l_returnflag, l_linestatus"
interactive_query_q1_30 =  "SET `compiler.interactive.mode` \"true\"; SELECT l_returnflag, l_linestatus, SUM(l_quantity) AS sum_qty, SUM(l_extendedprice) AS sum_base_price, SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price, SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge, AVG(l_quantity) AS avg_qty, AVG(l_extendedprice) AS avg_price, AVG(l_discount) AS avg_disc, COUNT(*) AS count_order FROM Lineitem_10 WHERE l_shipdate <= \"1998-09-01\" AND l_returnflag = \"N\" GROUP BY l_returnflag, l_linestatus"

# blocking_query_q18 = (
#      "SELECT o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate, "
#      "SUM(l.l_quantity) AS total_quantity "
#      "FROM Lineitem_10 l, Orders_10 o, Customer_10 c "
#      "WHERE l.l_orderkey = o.o_orderkey "
#      "AND o.o_custkey = c.c_custkey "
#      "AND o.o_totalprice > 450000 "
#      "AND l.l_quantity > 40 "
#      "GROUP BY o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate;"
#  )

blocking_query_q18 = (
     "SELECT o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate, "
     "SUM(l.l_quantity) AS total_quantity "
     "FROM Orders_10 o "
      "JOIN Lineitem_10 l ON o.o_orderkey  = l.l_orderkey "
      "JOIN Customer_10 c ON o.o_custkey = c.c_custkey "
     "WHERE l.l_quantity > 45 "
     "GROUP BY o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate;"
 )



blocking_query_q18_dynamic = (
    'SET `compiler.blocking.mode` "true"; '
    'SELECT a.* FROM( '
    'SELECT o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate, '
    'SUM(l.l_quantity) AS total_quantity '
    'FROM Orders_10 o, Lineitem_10 l, Customer_10 c '
    'WHERE o.o_orderkey = l.l_orderkey '
    'AND  o.o_custkey = c.c_custkey '
    'AND l.l_quantity > 45 '
    'GROUP BY o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate '
    ') a WHERE a.o_totalprice > 10000;'
)
 # Just the SQL string
interactive_query_q16 = (
     'SET `compiler.interactive.mode` "true"; '
     'SELECT p.p_brand, p.p_type, p.p_size, COUNT( ps.ps_suppkey) AS supplier_cnt '
     'FROM Part_10 p, Partsupp_10 ps '
     'WHERE p.p_partkey /*+ indexnl */ = ps.ps_partkey '
     'AND p.p_brand > "Brand#45" '
     'AND p.p_brand < "Brand#53" '
     'AND p.p_type NOT LIKE "MEDIUM POLISHED%" '
     'AND p.p_size = 16 '
     'GROUP BY p.p_brand, p.p_type, p.p_size;'
 )

interactive_query_q16_dynamic = (
      'SET `compiler.interactive.mode` "true"; '
      'SELECT p.p_brand, p.p_type, p.p_size, COUNT( ps.ps_suppkey) AS supplier_cnt '
      'FROM Part_10 p, Partsupp_10 ps '
      'WHERE p.p_partkey /*+ indexnl */ = ps.ps_partkey '
      'AND p.p_brand > "Brand#45" '
      'AND p.p_brand < "Brand#52" '
      'AND p.p_type NOT LIKE "MEDIUM POLISHED%" '
      'AND p.p_size = 16 '
      'GROUP BY p.p_brand, p.p_type, p.p_size;'

  )

blocking_query_q16 = (

      'SELECT p.p_brand, p.p_type, p.p_size, COUNT( ps.ps_suppkey) AS supplier_cnt '
      'FROM  Partsupp_10 ps, Part_10 p '
      'WHERE p.p_partkey /*+ indexnl */ = ps.ps_partkey '
      'AND p.p_type NOT LIKE "MEDIUM POLISHED%" '
      'AND p.p_size = 16 '
      'GROUP BY p.p_brand, p.p_type, p.p_size;'
  )

blocking_query_q16_dynamic = (
        'SET `compiler.blocking.mode` `true`; '
                'SELECT a.* FROM( '
        'SELECT p.p_brand, p.p_type, p.p_size, COUNT( ps.ps_suppkey) AS supplier_cnt '
        'FROM  Partsupp_10 ps, Part_10 p '
        'WHERE p.p_partkey /*+ indexnl */ = ps.ps_partkey '
        'AND p.p_type NOT LIKE "MEDIUM POLISHED%" '
        'AND p.p_brand > "Brand#45" '
        'AND p.p_size = 16 '
        'GROUP BY p.p_brand, p.p_type, p.p_size'
        ')a where a.p_brand > "Brand#45"'
    )

blocking_query_q8 = (
    'SELECT o.o_orderdate AS o_orderdate, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS volume_total, '
    'SUM(CASE WHEN n2.n_name = "BRAZIL" THEN l.l_extendedprice * (1 - l.l_discount) ELSE 0 END) AS volume_brazil '
    'FROM Lineitem_10 l '
    'JOIN Orders_10 o ON l.l_orderkey = o.o_orderkey '
    'JOIN Part_10 p ON l.l_partkey = p.p_partkey '
    'JOIN Supplier_10 s ON l.l_suppkey = s.s_suppkey '
    'JOIN Customer_10 c ON o.o_custkey = c.c_custkey '
    'JOIN Nation_10 n1 ON c.c_nationkey = n1.n_nationkey '
    'JOIN Region_10 r ON n1.n_regionkey = r.r_regionkey '
    'JOIN Nation_10 n2 ON s.s_nationkey /*+ indexnl */ = n2.n_nationkey '
    'WHERE r.r_name = "AMERICA" '
    'AND o.o_orderdate > "1995-01-01" '
    'AND o.o_orderdate < "1996-12-31" '
    'AND p.p_type = "ECONOMY ANODIZED STEEL" '
    'GROUP BY o.o_orderdate '
    'ORDER BY o.o_orderdate;'
)

# Just the SQL string (kept as you wrote it)
interactive_query_q8 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT o.o_orderdate AS o_orderdate, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS volume_total, '
    'SUM(CASE WHEN n2.n_name = "BRAZIL" THEN l.l_extendedprice * (1 - l.l_discount) ELSE 0 END) AS volume_brazil '
    'FROM Orders_10 o '
    'JOIN Lineitem_10 l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Part_10 p ON l.l_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN Supplier_10 s ON l.l_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN Customer_10 c ON o.o_custkey /*+ indexnl */ = c.c_custkey '
    'JOIN Nation_10 n1 ON c.c_nationkey /*+ indexnl */ = n1.n_nationkey '
    'JOIN Region_10 r ON n1.n_regionkey /*+ indexnl */ = r.r_regionkey '
    'JOIN Nation_10 n2 ON s.s_nationkey /*+ indexnl */ = n2.n_nationkey '
    'WHERE r.r_name = "AMERICA" '
    'AND o.o_orderdate > "1995-01-01" '
    'AND o.o_orderdate <= "1995-01-02" '
    'AND p.p_type = "ECONOMY ANODIZED STEEL" '
    'GROUP BY o.o_orderdate;'
)

interactive_query_q8_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT o.o_orderdate AS o_orderdate, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS volume_total, '
    'SUM(CASE WHEN n2.n_name = "BRAZIL" THEN l.l_extendedprice * (1 - l.l_discount) ELSE 0 END) AS volume_brazil '
    'FROM Orders_10 o '
    'JOIN Lineitem_10 l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Part_10 p ON l.l_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN Supplier_10 s ON l.l_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN Customer_10 c ON o.o_custkey /*+ indexnl */ = c.c_custkey '
    'JOIN Nation_10 n1 ON c.c_nationkey /*+ indexnl */ = n1.n_nationkey '
    'JOIN Region_10 r ON n1.n_regionkey /*+ indexnl */ = r.r_regionkey '
    'JOIN Nation_10 n2 ON s.s_nationkey /*+ indexnl */ = n2.n_nationkey '
    'WHERE r.r_name = "AMERICA" '
    'AND o.o_orderdate > "1995-01-01" '
    'AND o.o_orderdate < "1995-02-15" '
    'AND p.p_type = "ECONOMY ANODIZED STEEL" '
    'GROUP BY o.o_orderdate;'
)

blocking_query_q8_dynamic = (
         'SET `compiler.blocking.mode` `true`; '
                        'SELECT a.* FROM( '
       'SELECT o.o_orderdate AS o_orderdate, '
        'SUM(l.l_extendedprice * (1 - l.l_discount)) AS volume_total, '
        'SUM(CASE WHEN n2.n_name = "BRAZIL" THEN l.l_extendedprice * (1 - l.l_discount) ELSE 0 END) AS volume_brazil '
        'FROM Lineitem_10 l '
        'JOIN Orders_10 o ON l.l_orderkey = o.o_orderkey '
        'JOIN Part_10 p ON l.l_partkey = p.p_partkey '
        'JOIN Supplier_10 s ON l.l_suppkey = s.s_suppkey '
        'JOIN Customer_10 c ON o.o_custkey = c.c_custkey '
        'JOIN Nation_10 n1 ON c.c_nationkey = n1.n_nationkey '
        'JOIN Region_10 r ON n1.n_regionkey = r.r_regionkey '
        'JOIN Nation_10 n2 ON s.s_nationkey /*+ indexnl */ = n2.n_nationkey '
        'WHERE r.r_name = "AMERICA" '
        'AND o.o_orderdate > "1995-01-01" '
        'AND o.o_orderdate < "1996-12-31" '
        'AND p.p_type = "ECONOMY ANODIZED STEEL" '
        'GROUP BY o.o_orderdate '
        'ORDER BY o.o_orderdate'
        ')a where a.o_orderdate > "1995-01-01"'
            )
blocking_query_q11 = (
    'SELECT ps.ps_partkey AS ps_partkey, '
    'SUM(ps.ps_supplycost * ps.ps_availqty) AS ps_value '
    'FROM Nation_10 AS n '
    'JOIN Supplier_10 AS s ON n.n_nationkey = s.s_nationkey '
    'JOIN Partsupp_10 AS ps ON s.s_suppkey = ps.ps_suppkey '
    'WHERE n.n_name = "GERMANY" '
    'GROUP BY ps.ps_partkey '
    'HAVING SUM(ps.ps_supplycost * ps.ps_availqty) > 7874103.109405;'
)

blocking_query_q11_dynamic = (
    'SET `compiler.blocking.mode` `true`; '
    'SELECT a.* FROM ('
    'SELECT ps.ps_partkey AS ps_partkey, '
    'SUM(ps.ps_supplycost * ps.ps_availqty) AS ps_value '
    'FROM Nation_10 AS n '
    'JOIN Supplier_10 AS s ON n.n_nationkey = s.s_nationkey '
    'JOIN Partsupp_10 AS ps ON s.s_suppkey = ps.ps_suppkey '
    'WHERE n.n_name = "GERMANY" '
    'GROUP BY ps.ps_partkey '
    'HAVING SUM(ps.ps_supplycost * ps.ps_availqty) > 7874103.109405'
    ') a WHERE a.ps_partkey > 200000;'
)

interactive_query_q11 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT ps.ps_partkey AS ps_partkey, '
    'SUM(ps.ps_supplycost * ps.ps_availqty) AS ps_value '
    'FROM Partsupp_10 AS ps '
    'JOIN Supplier_10 AS s ON ps.ps_suppkey /*+ indexnl */ = s.s_suppkey '
    'WHERE s.s_nationkey = 7 '
    'AND ps.ps_partkey < 12000000 '
    'GROUP BY ps.ps_partkey '
    'HAVING SUM(ps.ps_supplycost * ps.ps_availqty) > 7874103.109405;'
)

interactive_query_q11_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT ps.ps_partkey AS ps_partkey, '
    'SUM(ps.ps_supplycost * ps.ps_availqty) AS ps_value '
    'FROM Partsupp_10 AS ps '
    'JOIN Supplier_10 AS s ON ps.ps_suppkey /*+ indexnl */ = s.s_suppkey '
    'WHERE s.s_nationkey = 7 '
    'AND ps.ps_partkey < 12000000 '
    'GROUP BY ps.ps_partkey '
    'HAVING SUM(ps.ps_supplycost * ps.ps_availqty) > 7874103.109405;'
)
# interactive_query_q20 = (
#     'SELECT s.s_name, s.s_address '
#     'FROM Supplier_10 AS s '
#     'JOIN Nation_10 AS n ON s.s_nationkey /*+ indexnl */ = n.n_nationkey '
#     'WHERE n.n_name = "CANADA" '
#     'AND s.s_name < "Supplier#000000530";'
# )
# blocking_query_q20 = (
#     'SELECT s.s_name, s.s_address '
#     'FROM Supplier_10 AS s, Nation_10 AS n '
#     'WHERE s.s_nationkey = n.n_nationkey '
#     'AND n.n_name = "CANADA" '
#     'ORDER BY s.s_name;'
# )
# interactive_query_q20_dynamic = (
#     'SELECT s.s_name, s.s_address '
#     'FROM Supplier_10 AS s '
#     'JOIN Nation_10 AS n ON s.s_nationkey /*+ indexnl */ = n.n_nationkey '
#     'WHERE s.s_name < "Supplier#003000000"'
#     'AND n.n_name = "CANADA" '
# )
blocking_query_q20 = (
    "SELECT s.s_name, s.s_address "
    "FROM Supplier_10 AS s "
    "JOIN Nation_10 AS n ON s.s_nationkey = n.n_nationkey "
    "JOIN Partsupp_10 AS ps ON s.s_suppkey = ps.ps_suppkey "
    "JOIN Part_10 AS p ON ps.ps_partkey = p.p_partkey "
    "LEFT JOIN HalfSumQty_10 AS h "
    "  ON h.l_partkey = ps.ps_partkey AND h.l_suppkey = ps.ps_suppkey "
    "WHERE n.n_name = \"CANADA\" "
    "AND p.p_name LIKE \"forest%\" "
    "AND ps.ps_availqty > COALESCE(h.half_sum_qty, 0) "
    "GROUP BY s.s_name, s.s_address "
    "ORDER BY s.s_name;"
)

blocking_query_q20_dynamic = (
    "SET `compiler.blocking.mode` \"true\"; "
    "SELECT a.* FROM ("
        "SELECT s.s_name, s.s_address "
        "FROM Supplier_10 AS s "
        "JOIN Nation_10 AS n ON s.s_nationkey = n.n_nationkey "
        "JOIN Partsupp_10 AS ps ON s.s_suppkey = ps.ps_suppkey "
        "JOIN Part_10 AS p ON ps.ps_partkey = p.p_partkey "
        "LEFT JOIN HalfSumQty_10 AS h "
        "  ON h.l_partkey = ps.ps_partkey AND h.l_suppkey = ps.ps_suppkey "
        "WHERE n.n_name = \"CANADA\" "
        "AND p.p_name LIKE \"forest%\" "
        "AND ps.ps_availqty > COALESCE(h.half_sum_qty, 0) "
        "GROUP BY s.s_name, s.s_address "
        "ORDER BY s.s_name"
     ") a WHERE a.s_name > \"\";"
)
interactive_query_q20_dynamic = (
    "SET `compiler.interactive.mode` \"true\"; "
    "SELECT s.s_name, s.s_address "
    "FROM Supplier_10 AS s "
    "JOIN Nation_10 AS n ON s.s_nationkey /*+ indexnl */ = n.n_nationkey "
    "JOIN Partsupp_10 AS ps ON s.s_suppkey /*+ indexnl */ = ps.ps_suppkey "
    "JOIN Part_10 AS p ON ps.ps_partkey /*+ indexnl */ = p.p_partkey "
    "LEFT JOIN HalfSumQty_10 AS h "
    "  ON h.l_partkey /*+ indexnl */ = ps.ps_partkey AND h.l_suppkey = ps.ps_suppkey "
    "WHERE n.n_name = \"CANADA\" "
    "AND p.p_name LIKE \"forest%\" "
    "AND ps.ps_availqty > COALESCE(h.half_sum_qty, 0) "
    "AND s.s_name < \"Supplier#003000000\" "
    "GROUP BY s.s_name, s.s_address "
)
interactive_query_q20 = (
    "SET `compiler.interactive.mode` \"true\"; "
    "SELECT s.s_name, s.s_address "
    "FROM Supplier_10 AS s "
    "JOIN Nation_10 AS n ON s.s_nationkey /*+ indexnl */ = n.n_nationkey "
    "JOIN Partsupp_10 AS ps ON s.s_suppkey /*+ indexnl */ = ps.ps_suppkey "
    "JOIN Part_10 AS p ON ps.ps_partkey /*+ indexnl */ = p.p_partkey "
    "LEFT JOIN HalfSumQty_10 AS h "
    "  ON h.l_partkey /*+ indexnl */ = ps.ps_partkey AND h.l_suppkey = ps.ps_suppkey "
    "WHERE n.n_name = \"CANADA\" "
    "AND p.p_name LIKE \"forest%\" "
    "AND ps.ps_availqty > COALESCE(h.half_sum_qty, 0) "
    "AND s.s_name < \"Supplier#000300000\" "
    "GROUP BY s.s_name, s.s_address "
)


# blocking_query_q20_dynamic = (
#     'SELECT s.s_name, s.s_address '
#     'FROM Supplier_10 AS s, Nation_10 AS n '
#     'WHERE s.s_nationkey = n.n_nationkey '
#     'AND n.n_name = "CANADA" '
#     'ORDER BY s.s_name;'
# )

interactive_query_q21 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT s.s_name, COUNT(*) AS numwait '
    'FROM Supplier_10 AS s '
    'JOIN Nation_10 AS n ON s.s_nationkey /*+ indexnl */ = n.n_nationkey '
    'JOIN Lineitem_10 AS l1 ON s.s_suppkey /*+ indexnl */ = l1.l_suppkey '
    'JOIN Orders_10 AS o ON l1.l_orderkey /*+ indexnl */ = o.o_orderkey '
    'WHERE o.o_orderstatus = "F" '
    'AND l1.l_receiptdate > l1.l_commitdate '
    'AND n.n_name = "SAUDI ARABIA" '
    'AND s.s_name < "Supplier#000000530" '
    'GROUP BY s.s_name;'
)
blocking_query_q21 = (

    'SELECT s.s_name, COUNT(*) AS numwait '
    'FROM Supplier_10 AS s '
    'JOIN Nation_10 AS n ON s.s_nationkey = n.n_nationkey '
    'JOIN Lineitem_10 AS l1 ON s.s_suppkey  = l1.l_suppkey '
    'JOIN Orders_10 AS o ON l1.l_orderkey  = o.o_orderkey '
    'WHERE o.o_orderstatus = "F" '
    'AND l1.l_receiptdate > l1.l_commitdate '
    'AND n.n_name = "SAUDI ARABIA" '
    'GROUP BY s.s_name;'
)

blocking_query_q21_dynamic = (
    'SELECT s.s_name, COUNT(*) AS numwait '
    'FROM Supplier_10 AS s '
    'JOIN Nation_10 AS n ON s.s_nationkey = n.n_nationkey '
    'JOIN Lineitem_10 AS l1 ON s.s_suppkey  = l1.l_suppkey '
    'JOIN Orders_10 AS o ON l1.l_orderkey  = o.o_orderkey '
    'WHERE o.o_orderstatus = "F" '
    'AND l1.l_receiptdate > l1.l_commitdate '
    'AND n.n_name = "SAUDI ARABIA" '
    'GROUP BY s.s_name;'
)

interactive_query_q21_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT s.s_name, COUNT(*) AS numwait '
    'FROM Supplier_10 AS s '
    'JOIN Nation_10 AS n ON s.s_nationkey /*+ indexnl */ = n.n_nationkey '
    'JOIN Lineitem_10 AS l1 ON s.s_suppkey /*+ indexnl */ = l1.l_suppkey '
    'JOIN Orders_10 AS o ON l1.l_orderkey /*+ indexnl */ = o.o_orderkey '
    'WHERE o.o_orderstatus = "F" '
    'AND l1.l_receiptdate > l1.l_commitdate '
    'AND n.n_name = "SAUDI ARABIA" '
    'AND s.s_name < "Supplier#000025530" '
    'GROUP BY s.s_name;'
)
blocking_query_q22 = (
    "SELECT substring(c.c_phone,0,2) AS cntrycode, "
    "COUNT(*) AS numcust, "
    "SUM(if_missing(if_null(c.c_acctbal,0.0),0.0)) AS totacctbal "
    "FROM Customer_10 c "
    "LEFT JOIN Orders_10 o ON c.c_custkey = o.o_custkey "
    "WHERE substring(c.c_phone,0,2) IN (\"13\",\"31\",\"23\",\"29\",\"30\",\"18\",\"17\") "
    "AND c.c_acctbal > 5000 "
    "AND o.o_orderkey IS MISSING "
    "GROUP BY substring(c.c_phone,0,2) "
    "ORDER BY cntrycode;"
)

blocking_query_q22_dynamic = (
    "SET `compiler.blocking.mode` `true`; "
    "SELECT a.* FROM ( "
    "SELECT substring(c.c_phone,0,2) AS cntrycode, "
    "COUNT(*) AS numcust, "
    "SUM(if_missing(if_null(c.c_acctbal,0.0),0.0)) AS totacctbal "
    "FROM Customer_10 c "
    "LEFT JOIN Orders_10 o ON c.c_custkey = o.o_custkey "
    "WHERE substring(c.c_phone,0,2) IN (\"13\",\"31\",\"23\",\"29\",\"30\",\"18\",\"17\") "
    "AND c.c_acctbal > 5000 "
    "AND o.o_orderkey IS MISSING "
    "GROUP BY substring(c.c_phone,0,2) "
    "ORDER BY cntrycode "
    ") a WHERE a.cntrycode > \"13\";"
)
interactive_query_q22 = (
    'SELECT COUNT(*) AS numcust, '
    'SUM(if_missing(if_null(c.c_acctbal,0.0),0.0)) AS totacctbal '
    'FROM Customer_10 AS c '
    'WHERE c.c_phone >= "13" AND c.c_phone < "14" '
    'AND c.c_acctbal > 5000 '
    'AND NOT EXISTS ( '
    'SELECT 1 FROM Orders_10 AS o '
    'WHERE c.c_custkey /*+ indexnl */ = o.o_custkey '
    ');'
)
interactive_query_q22_dynamic = (
    'SELECT COUNT(*) AS numcust, '
    'SUM(if_missing(if_null(c.c_acctbal,0.0),0.0)) AS totacctbal '
    'FROM Customer_10 AS c '
    'WHERE c.c_phone >= "13" AND c.c_phone < "14" '
    'AND c.c_acctbal > 5000 '
    'AND NOT EXISTS ( '
    'SELECT 1 FROM Orders_10 AS o '
    'WHERE c.c_custkey /*+ indexnl */ = o.o_custkey '
    ');'
)

interactive_query_q2 = (

    'SELECT s.s_acctbal, s.s_name, n.n_name, p.p_partkey, p.p_mfgr, '
    's.s_address, s.s_phone, s.s_comment '
    'FROM Supplier_10 s '
    'JOIN Partsupp_10 ps ON s.s_suppkey /*+ indexnl */ = ps.ps_suppkey '
    'JOIN Part_10 p ON ps.ps_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN Nation_10 n ON s.s_nationkey /*+ indexnl */ = n.n_nationkey '
    'JOIN Region_10 r ON n.n_regionkey /*+ indexnl */ = r.r_regionkey '
    'JOIN MinCost_10 mc ON ps.ps_partkey /*+ indexnl */ = mc.ps_partkey '
    'AND mc.min_cost = ps.ps_supplycost '
    'WHERE p.p_size = 15 '
    'AND p.p_type LIKE "%BRASS" '
    'AND r.r_name = "EUROPE" '
    'AND s.s_acctbal > 7000 '
    'ORDER BY s.s_acctbal DESC, n.n_name, s.s_name, p.p_partkey;'
)

interactive_query_q2_dynamic = (

    'SELECT s.s_acctbal, s.s_name, n.n_name, p.p_partkey, p.p_mfgr, '
    's.s_address, s.s_phone, s.s_comment '
    'FROM Supplier_10 s '
    'JOIN Partsupp_10 ps ON s.s_suppkey /*+ indexnl */ = ps.ps_suppkey '
    'JOIN Part_10 p ON ps.ps_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN Nation_10 n ON s.s_nationkey /*+ indexnl */ = n.n_nationkey '
    'JOIN Region_10 r ON n.n_regionkey /*+ indexnl */ = r.r_regionkey '
    'JOIN MinCost_10 mc ON ps.ps_partkey /*+ indexnl */ = mc.ps_partkey '
    'AND mc.min_cost = ps.ps_supplycost '
    'WHERE p.p_size = 15 '
    'AND p.p_type LIKE "%BRASS" '
    'AND r.r_name = "EUROPE" '
    'AND s.s_acctbal > 7800 '
    'ORDER BY s.s_acctbal DESC, n.n_name, s.s_name, p.p_partkey;'
)

blocking_query_q2 = (
    "SELECT s.s_acctbal, s.s_name, n.n_name, p.p_partkey, p.p_mfgr, "
    "s.s_address, s.s_phone, s.s_comment "
    "FROM Part_10 p "
    "JOIN Partsupp_10 ps ON p.p_partkey = ps.ps_partkey "
    "JOIN Supplier_10 s ON ps.ps_suppkey = s.s_suppkey "
    "JOIN Nation_10 n ON s.s_nationkey = n.n_nationkey "
    "JOIN Region_10 r ON n.n_regionkey = r.n_regionkey "
    "JOIN MinCost_10 mc ON mc.ps_partkey = ps.ps_partkey "
    "AND mc.min_cost = ps.ps_supplycost "
    "WHERE p.p_size = 15 "
    "AND p.p_type LIKE \"%BRASS\" "
    "AND r.r_name = \"EUROPE\" "
    "ORDER BY s.s_acctbal DESC, n.n_name, s.s_name, p.p_partkey;"
)

blocking_query_q2_dynamic = (
    "SELECT s.s_acctbal, s.s_name, n.n_name, p.p_partkey, p.p_mfgr, "
    "s.s_address, s.s_phone, s.s_comment "
    "FROM Part_10 p "
    "JOIN Partsupp_10 ps ON p.p_partkey = ps.ps_partkey "
    "JOIN Supplier_10 s ON ps.ps_suppkey = s.s_suppkey "
    "JOIN Nation_10 n ON s.s_nationkey = n.n_nationkey "
    "JOIN Region_10 r ON n.n_regionkey = r.n_regionkey "
    "JOIN MinCost_10 mc ON mc.ps_partkey = ps.ps_partkey "
    "AND mc.min_cost = ps.ps_supplycost "
    "WHERE p.p_size = 15 "
    "AND p.p_type LIKE \"%BRASS\" "
    "AND r.r_name = \"EUROPE\" "
    "ORDER BY s.s_acctbal DESC, n.n_name, s.s_name, p.p_partkey;"
)

blocking_query_q5 = (
    'SELECT c.c_nationkey, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Customer_10 c, Orders_10 o, Lineitem_10 l, Supplier_10 s '
    'WHERE c.c_custkey = o.o_custkey '
    'AND l.l_orderkey = o.o_orderkey '
    'AND l.l_suppkey = s.s_suppkey '
    'AND c.c_nationkey = s.s_nationkey '
    'AND c.c_nationkey IN (8, 9, 12, 18, 21) '
    'AND o.o_orderdate >= "1994-01-01" '
    'AND o.o_orderdate < "1995-01-01" '
    'GROUP BY c.c_nationkey'
)
blocking_query_q5_dynamic = (
    'SET `compiler.blocking.mode` `true`; '
    'SELECT a.* FROM ( '
    'SELECT c.c_nationkey, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Customer_10 c, Orders_10 o, Lineitem_10 l, Supplier_10 s '
    'WHERE c.c_custkey = o.o_custkey '
    'AND l.l_orderkey = o.o_orderkey '
    'AND l.l_suppkey = s.s_suppkey '
    'AND c.c_nationkey = s.s_nationkey '
    'AND c.c_nationkey IN (8, 9, 12, 18, 21) '
    'AND o.o_orderdate >= "1994-01-01" '
    'AND o.o_orderdate < "1995-01-01" '
    'GROUP BY c.c_nationkey'
    ') a WHERE a.c_nationkey > 9;'
)

interactive_query_q5 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT c.c_nationkey, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Customer_10 c '
    'JOIN Orders_10 o ON c.c_custkey /*+ indexnl */ = o.o_custkey '
    'JOIN Lineitem_10 l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Supplier_10 s ON l.l_suppkey /*+ indexnl */ = s.s_suppkey '
    'WHERE c.c_nationkey = s.s_nationkey '
    'AND c.c_nationkey = 8 '
    'AND o.o_orderdate >= "1994-01-01" '
    'AND o.o_orderdate < "1995-01-01" '
    'GROUP BY c.c_nationkey;'
)
interactive_query_q5_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT c.c_nationkey, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Customer_10 c '
    'JOIN Orders_10 o ON c.c_custkey /*+ indexnl */ = o.o_custkey '
    'JOIN Lineitem_10 l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Supplier_10 s ON l.l_suppkey /*+ indexnl */ = s.s_suppkey '
    'WHERE c.c_nationkey = s.s_nationkey '
    'AND c.c_nationkey = 8 '
    'AND o.o_orderdate >= "1994-01-01" '
    'AND o.o_orderdate < "1995-01-01" '
    'GROUP BY c.c_nationkey;'
)
blocking_query_q7 = (
    'SELECT '
    '  s.s_nationkey AS supp_nationkey, '
    '  c.c_nationkey AS cust_nationkey, '
    '  substring(l.l_shipdate,0,4) AS l_year, '
    '  SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Supplier_10 s, '
    '     Customer_10 c, '
    '     Orders_10 o, '
    '     Lineitem_10 l '
    'WHERE s.s_suppkey = l.l_suppkey '
    '  AND o.o_orderkey = l.l_orderkey '
    '  AND c.c_custkey = o.o_custkey '
    '  AND ( (s.s_nationkey = 6 AND c.c_nationkey = 7) '
    '     OR (s.s_nationkey = 7 AND c.c_nationkey = 6) ) '
    '  AND l.l_shipdate >= "1995-01-01" '
    '  AND l.l_shipdate <  "1997-01-01" '
    'GROUP BY s.s_nationkey, c.c_nationkey, substring(l.l_shipdate,0,4) '
    'ORDER BY supp_nationkey, cust_nationkey, l_year;'
)
interactive_query_q7 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT '
    '  s.s_nationkey AS supp_nationkey, '
    '  c.c_nationkey AS cust_nationkey, '
    '  substring(l.l_shipdate,0,4) AS l_year, '
    '  SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Supplier_10 s '
    'JOIN Lineitem_10 l '
    '  ON s.s_suppkey /*+ indexnl */ = l.l_suppkey '
    'JOIN Orders_10 o '
    '  ON l.l_orderkey /*+ indexnl */ = o.o_orderkey '
    'JOIN Customer_10 c '
    '  ON o.o_custkey /*+ indexnl */ = c.c_custkey '
    'WHERE s.s_nationkey = 6 '
    '  AND c.c_nationkey = 7 '
    '  AND l.l_shipdate >= "1995-01-01" '
    '  AND l.l_shipdate < "1997-01-01" '
    'GROUP BY s.s_nationkey, c.c_nationkey, substring(l.l_shipdate,0,4);'
)
interactive_query_q7_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT '
    '  s.s_nationkey AS supp_nationkey, '
    '  c.c_nationkey AS cust_nationkey, '
    '  substring(l.l_shipdate,0,4) AS l_year, '
    '  SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Supplier_10 s '
    'JOIN Lineitem_10 l '
    '  ON s.s_suppkey /*+ indexnl */ = l.l_suppkey '
    'JOIN Orders_10 o '
    '  ON l.l_orderkey /*+ indexnl */ = o.o_orderkey '
    'JOIN Customer_10 c '
    '  ON o.o_custkey /*+ indexnl */ = c.c_custkey '
    'WHERE s.s_nationkey = 6 '
    '  AND c.c_nationkey = 7 '
    '  AND l.l_shipdate >= "1995-01-01" '
    '  AND l.l_shipdate < "1997-01-01" '
    'GROUP BY s.s_nationkey, c.c_nationkey, substring(l.l_shipdate,0,4);'
)
blocking_query_q7 = (
    'SELECT '
    '  s.s_nationkey AS supp_nationkey, '
    '  c.c_nationkey AS cust_nationkey, '
    '  substring(l.l_shipdate,0,4) AS l_year, '
    '  SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Supplier_10 s '
    'JOIN Lineitem_10 l '
    '  ON s.s_suppkey /*+ indexnl */ = l.l_suppkey '
    'JOIN Orders_10 o '
    '  ON l.l_orderkey /*+ indexnl */ = o.o_orderkey '
    'JOIN Customer_10 c '
    '  ON o.o_custkey /*+ indexnl */ = c.c_custkey '
    'WHERE s.s_nationkey IN (6,7) '
    '  AND c.c_nationkey IN (6,7) '
    '  AND s.s_nationkey <> c.c_nationkey '
    '  AND l.l_shipdate >= "1995-01-01" '
    '  AND l.l_shipdate < "1997-01-01" '
    'GROUP BY s.s_nationkey, c.c_nationkey, substring(l.l_shipdate,0,4);'
)

blocking_query_q7_dynamic = (
    'SET `compiler.blocking.mode` `true`; '
    'SELECT a.* FROM ('
    'SELECT '
    '  s.s_nationkey AS supp_nationkey, '
    '  c.c_nationkey AS cust_nationkey, '
    '  substring(l.l_shipdate,0,4) AS l_year, '
    '  SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Supplier_10 s '
    'JOIN Lineitem_10 l '
    '  ON s.s_suppkey /*+ indexnl */ = l.l_suppkey '
    'JOIN Orders_10 o '
    '  ON l.l_orderkey /*+ indexnl */ = o.o_orderkey '
    'JOIN Customer_10 c '
    '  ON o.o_custkey /*+ indexnl */ = c.c_custkey '
    'WHERE s.s_nationkey IN (6,7) '
    '  AND c.c_nationkey IN (6,7) '
    '  AND s.s_nationkey <> c.c_nationkey '
    '  AND l.l_shipdate >= "1995-01-01" '
    '  AND l.l_shipdate < "1997-01-01" '
    'GROUP BY s.s_nationkey, c.c_nationkey, substring(l.l_shipdate,0,4) '
    ') a where a.supp_nationkey > 6;'
)













# interactive_query_SSB_q21 = (
#     'SET `compiler.interactive.mode` "true"; '
#     'SELECT d.d_year, p.p_brand, SUM(l.lo_revenue) AS revenue '
#     'FROM SSB_Date d '
#     'JOIN SSB_Lineorder l ON d.d_datekey /*+ indexnl */ = l.lo_orderdate '
#     'JOIN SSB_Part p ON l.lo_partkey /*+ indexnl */ = p.p_partkey '
#     'JOIN SSB_Supplier s ON l.lo_suppkey /*+ indexnl */ = s.s_suppkey '
#     'WHERE d.d_year = 1992 '
#     'AND p.p_category = "MFGR#12" '
#     'AND s.s_region = "AMERICA" '
#     'GROUP BY d.d_year, p.p_brand;'
# )

interactive_query_SSB_q21 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT d.d_year, p.p_brand, SUM(l.lo_revenue) AS revenue '
    'FROM SSB_Date d '
    'JOIN SSB_Lineorder l ON d.d_datekey /*+ indexnl */ = l.lo_orderdate '
    'JOIN SSB_Part p ON l.lo_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN SSB_Supplier s ON l.lo_suppkey /*+ indexnl */ = s.s_suppkey '
    'WHERE d.d_year <= 1995 '
    'AND p.p_category = "MFGR#12" '
    'AND s.s_region = "AMERICA" '
    'GROUP BY d.d_year, p.p_brand;'
)
blocking_query_SSB_q21 = (
    "SELECT SUM(l.lo_revenue) AS revenue, d.d_datekey, p.p_brand1 "
    "FROM SSB_Lineorder l, SSB_Date d, SSB_Part p, SSB_Supplier s "
    "WHERE l.lo_orderdate = d.d_datekey "
    "AND d.d_datekey < 19920701 "
    "AND l.lo_partkey = p.p_partkey "
    "AND l.lo_suppkey = s.s_suppkey "
    "AND p.p_category = 'MFGR#12' "
    "AND s.s_region = 'AMERICA' "
    "GROUP BY d.d_datekey, p.p_brand1 "
    "ORDER BY d.d_datekey, p.p_brand1;"
)

blocking_query_SSB_q22 = (

    "SELECT SUM(l.lo_revenue) AS revenue, d.d_datekey, p.p_brand1 "
    "FROM SSB_Lineorder l, SSB_Date d, SSB_Part p, SSB_Supplier s "
    "WHERE l.lo_orderdate = d.d_datekey "
    "AND d.d_datekey < 19920701 "
    "AND l.lo_partkey = p.p_partkey "
    "AND l.lo_suppkey = s.s_suppkey "
    "AND p.p_brand1 BETWEEN \"MFGR#2221\" AND \"MFGR#2228\" "
    "AND s.s_region = \"ASIA\" "
    "GROUP BY d.d_datekey, p.p_brand1 "
    "ORDER BY d.d_datekey, p.p_brand1;"
)
interactive_query_SSB_q22 = (
   'SET `compiler.interactive.mode` "true"; '
   'SELECT SUM(l.lo_revenue) AS revenue, d.d_datekey, p.p_brand1 '
   'FROM SSB_Date d '
   'JOIN SSB_Lineorder l ON d.d_datekey /*+ indexnl */ = l.lo_orderdate '
   'JOIN SSB_Part p ON l.lo_partkey /*+ indexnl */ = p.p_partkey '
   'JOIN SSB_Supplier s ON l.lo_suppkey /*+ indexnl */ = s.s_suppkey '
   'WHERE d.d_datekey < 19920330 '
   'AND p.p_brand1 BETWEEN "MFGR#2221" AND "MFGR#2228" '
   'AND s.s_region = "ASIA" '
   'GROUP BY d.d_datekey, p.p_brand1;'
)



blocking_query_SSB_q21_dynamic = (
    "SET `compiler.blocking.mode` `true`; "
    "SELECT a.* FROM( "
     "SELECT SUM(l.lo_revenue) AS revenue, d.d_datekey, p.p_brand1 "
        "FROM SSB_Lineorder l, SSB_Date d, SSB_Part p, SSB_Supplier s "
        "WHERE l.lo_orderdate = d.d_datekey "
        "AND l.lo_partkey = p.p_partkey "
        "AND l.lo_suppkey = s.s_suppkey "
        "AND p.p_category = 'MFGR#12' "
        "AND s.s_region = 'AMERICA' "
        "AND  d.d_yearmonthnum < 199301 "
        "GROUP BY d.d_datekey, p.p_brand1 "
        "ORDER BY d.d_datekey, p.p_brand1"
    ")a where a.d_datekey > 19920101 "

)
interactive_query_SSB_q21_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT d.d_datekey, p.p_brand, SUM(l.lo_revenue) AS revenue '
    'FROM SSB_Date d '
    'JOIN SSB_Lineorder l ON d.d_datekey /*+ indexnl */ = l.lo_orderdate '
    'JOIN SSB_Part p ON l.lo_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN SSB_Supplier s ON l.lo_suppkey /*+ indexnl */ = s.s_suppkey '
    'WHERE d.d_yearmonthnum < 199301 '
    'AND p.p_category = "MFGR#12" '
    'AND s.s_region = "AMERICA" '
    'GROUP BY d.d_datekey, p.p_brand;'
)

blocking_query_SSB_q23 = (
    "SELECT SUM(l.lo_revenue) AS revenue, d.d_datekey, p.p_brand1 "
    "FROM SSB_Lineorder l, SSB_Date d, SSB_Part p, SSB_Supplier s "
    "WHERE l.lo_orderdate = d.d_datekey "
    "AND d.d_datekey < 19920701 "
    "AND l.lo_partkey = p.p_partkey "
    "AND l.lo_suppkey = s.s_suppkey "
    "AND p.p_brand1 = \"MFGR#2221\" "
    "AND s.s_region = \"EUROPE\" "
    "GROUP BY d.d_datekey, p.p_brand1 "
    "ORDER BY d.d_datekey, p.p_brand1;"
)

interactive_query_SSB_q23 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT d.d_datekey, p.p_brand1, SUM(l.lo_revenue) AS revenue '
    'FROM SSB_Date d '
    'JOIN SSB_Lineorder l ON d.d_datekey /*+ indexnl */ = l.lo_orderdate '
    'JOIN SSB_Part p ON l.lo_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN SSB_Supplier s ON l.lo_suppkey /*+ indexnl */ = s.s_suppkey '
    'WHERE d.d_datekey < 19920315 '
    'AND p.p_brand1 = "MFGR#2221" '
    'AND s.s_region = "EUROPE" '
    'GROUP BY d.d_datekey, p.p_brand1; '
)

blocking_query_SSB_q31 = (
    "SELECT SUM(l.lo_revenue) AS revenue, d.d_datekey, c.c_nation, s.s_nation "
    "FROM SSB_Lineorder l, SSB_Date d, SSB_Part p, SSB_Supplier s, SSB_Customer c "
    "WHERE l.lo_orderdate = d.d_datekey "
    "AND d.d_datekey < 19920701 "
    "AND l.lo_partkey = p.p_partkey "
    "AND l.lo_suppkey = s.s_suppkey "
    "AND l.lo_custkey = c.c_custkey "
    "AND c.c_region = \"ASIA\" "
    "AND s.s_region = \"ASIA\" "
    "GROUP BY d.d_datekey, c.c_nation, s.s_nation "
    "ORDER BY d.d_datekey ASC;"
)

interactive_query_SSB_q31 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT c.c_nation, s.s_nation, d.d_datekey, SUM(l.lo_revenue) AS revenue '
    'FROM SSB_Date d '
    'JOIN SSB_Lineorder l ON d.d_datekey /*+ indexnl */ = l.lo_orderdate '
    'JOIN SSB_Part p ON l.lo_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN SSB_Supplier s ON l.lo_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN SSB_Customer c ON l.lo_custkey /*+ indexnl */ = c.c_custkey '
    'WHERE d.d_datekey < 19920325 '
    'AND c.c_region = "ASIA" '
    'AND s.s_region = "ASIA" '
    'GROUP BY d.d_datekey, c.c_nation, s.s_nation '
)

blocking_query_SSB_q32 = (
    "SELECT SUM(l.lo_revenue) AS revenue, d.d_datekey, c.c_city, s.s_city "
    "FROM SSB_Lineorder l, SSB_Date d, SSB_Part p, SSB_Supplier s, SSB_Customer c "
    "WHERE l.lo_orderdate = d.d_datekey "
    "AND d.d_datekey < 19920701 "
    "AND l.lo_partkey = p.p_partkey "
    "AND l.lo_suppkey = s.s_suppkey "
    "AND l.lo_custkey = c.c_custkey "
    "AND c.c_nation = \"UNITED STATES\" "
    "AND s.s_nation = \"UNITED STATES\" "
    "GROUP BY d.d_datekey, c.c_city, s.s_city "
    "ORDER BY d.d_datekey ASC"
)

interactive_query_SSB_q32 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT c.c_city, s.s_city, d.d_datekey, SUM(l.lo_revenue) AS revenue '
    'FROM SSB_Date d '
    'JOIN SSB_Lineorder l ON d.d_datekey /*+ indexnl */ = l.lo_orderdate '
    'JOIN SSB_Part p ON l.lo_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN SSB_Supplier s ON l.lo_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN SSB_Customer c ON l.lo_custkey /*+ indexnl */ = c.c_custkey '
    'WHERE d.d_datekey < 19920328 '
    'AND c.c_nation = "UNITED STATES" '
    'AND s.s_nation = "UNITED STATES" '
    'GROUP BY  d.d_datekey, c.c_city, s.s_city; '
)

blocking_query_SSB_q33 = (
    "SELECT SUM(l.lo_revenue) AS revenue, d.d_datekey, c.c_city, s.s_city "
    "FROM SSB_Lineorder l, SSB_Date d, SSB_Part p, SSB_Supplier s, SSB_Customer c "
    "WHERE l.lo_orderdate = d.d_datekey "
    "AND d.d_datekey < 19920701 "
    "AND l.lo_partkey = p.p_partkey "
    "AND l.lo_suppkey = s.s_suppkey "
    "AND l.lo_custkey = c.c_custkey "
    "AND (c.c_city = \"UNI\") "
    "AND (s.s_city = \"UNI\") "
    "GROUP BY d.d_datekey, c.c_city, s.s_city "
    "ORDER BY d.d_datekey ASC, revenue DESC;"
)

interactive_query_SSB_q33 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT c.c_city, s.s_city, d.d_datekey, SUM(l.lo_revenue) AS revenue '
    'FROM SSB_Date d '
    'JOIN SSB_Lineorder l ON d.d_datekey /*+ indexnl */ = l.lo_orderdate '
    'JOIN SSB_Part p ON l.lo_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN SSB_Supplier s ON l.lo_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN SSB_Customer c ON l.lo_custkey /*+ indexnl */ = c.c_custkey '
    'WHERE d.d_datekey < 19920328 '
    'AND c.c_city = "UNI" '
    'AND s.s_city = "UNI" '
    'GROUP BY  d.d_datekey, c.c_city, s.s_city; '
)

blocking_query_SSB_q34 = (
    "SELECT SUM(l.lo_revenue) AS revenue, d.d_datekey, c.c_city, s.s_city "
    "FROM SSB_Lineorder l, SSB_Date d, SSB_Part p, SSB_Supplier s, SSB_Customer c "
    "WHERE l.lo_orderdate = d.d_datekey "
    "AND d.d_yearmonthnum = \"Dec1997\""
    "AND l.lo_partkey = p.p_partkey "
    "AND l.lo_suppkey = s.s_suppkey "
    "AND l.lo_custkey = c.c_custkey "
    "AND c.c_city = \"UNI\" "
    "AND s.s_city = \"UNI\"  "
    "GROUP BY d.d_datekey, c.c_city, s.s_city "
    "ORDER BY d.d_datekey;"
)
interactive_query_SSB_q34 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT c.c_city, s.s_city, d.d_datekey, SUM(l.lo_revenue) AS revenue '
    'FROM SSB_Date d '
    'JOIN SSB_Lineorder l ON d.d_datekey /*+ indexnl */ = l.lo_orderdate '
    'JOIN SSB_Part p ON l.lo_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN SSB_Supplier s ON l.lo_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN SSB_Customer c ON l.lo_custkey /*+ indexnl */ = c.c_custkey '
    'WHERE d.d_datekey >= 19971201 '
    'AND d.d_yearmonth = "Dec1997"'
    'AND c.c_city = "UNI"  '
    'AND s.s_city = "UNI"  '
    'GROUP BY d.d_datekey, c.c_city, s.s_city; '
)

blocking_query_SSB_q41 = (
    "SELECT SUM(l.lo_revenue - l.lo_supplycost) AS profit, d.d_datekey, c.c_nation "
    "FROM SSB_Lineorder l, SSB_Date d, SSB_Customer c, SSB_Supplier s, SSB_Part p "
    "WHERE l.lo_orderdate = d.d_datekey "
    "AND d.d_datekey < 19920701 "
    "AND l.lo_custkey = c.c_custkey "
    "AND l.lo_suppkey = s.s_suppkey "
    "AND l.lo_partkey = p.p_partkey "
    "AND c.c_region = \"AMERICA\" "
    "AND s.s_region = \"AMERICA\" "
    "AND (p.p_mfgr = \"MFGR#1\" ) "
    "GROUP BY d.d_datekey, c.c_nation; "

)

interactive_query_SSB_q41 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT d.d_datekey, c.c_nation, SUM(l.lo_revenue - l.lo_supplycost) AS profit '
    'FROM SSB_Date d '
    'JOIN SSB_Lineorder l ON d.d_datekey /*+ indexnl */ = l.lo_orderdate '
    'JOIN SSB_Part p ON l.lo_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN SSB_Supplier s ON l.lo_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN SSB_Customer c ON l.lo_custkey /*+ indexnl */ = c.c_custkey '
    'WHERE d.d_datekey < 19920330 '
    'AND c.c_region = "AMERICA" '
    'AND s.s_region = "AMERICA" '
    'AND (p.p_mfgr = "MFGR#1" ) '
    'GROUP BY d.d_datekey, c.c_nation; '
)

blocking_query_SSB_q42 = (
    "SELECT SUM(l.lo_revenue - l.lo_supplycost) AS profit, d.d_datekey, s.s_nation, p.p_category "
    "FROM  SSB_Lineorder l,SSB_Date d, SSB_Customer c, SSB_Supplier s, SSB_Part p "
    "WHERE l.lo_orderdate = d.d_datekey "
    "AND d.d_datekey < 19920701 "
    "AND l.lo_custkey = c.c_custkey "
    "AND l.lo_suppkey = s.s_suppkey "
    "AND l.lo_partkey = p.p_partkey "
    "AND c.c_region = \"AMERICA\" "
    "AND s.s_region = \"AMERICA\" "
    "AND (p.p_mfgr = \"MFGR#1\" ) "
    "GROUP BY d.d_datekey, s.s_nation, p.p_category "
    "ORDER BY d.d_datekey, s.s_nation, p.p_category;"
)

interactive_query_SSB_q42 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT d.d_datekey, s.s_nation, p.p_category, SUM(l.lo_revenue - l.lo_supplycost) AS profit '
    'FROM SSB_Date d '
    'JOIN SSB_Lineorder l ON d.d_datekey /*+ indexnl */ = l.lo_orderdate '
    'JOIN SSB_Part p ON l.lo_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN SSB_Supplier s ON l.lo_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN SSB_Customer c ON l.lo_custkey /*+ indexnl */ = c.c_custkey '
    'WHERE d.d_datekey < 19920323 '
    'AND c.c_region = "AMERICA" '
    'AND s.s_region = "AMERICA" '
    'AND (p.p_mfgr = "MFGR#1" ) '
    'GROUP BY d.d_datekey, s.s_nation, p.p_category;'
)

blocking_query_SSB_q43 = (
    "SELECT SUM(l.lo_revenue - l.lo_supplycost) AS profit, d.d_datekey, s.s_city, p.p_brand1 "
    "FROM SSB_Lineorder l,SSB_Date d, SSB_Customer c, SSB_Supplier s, SSB_Part p "
    "WHERE l.lo_orderdate = d.d_datekey "
    "AND d.d_datekey < 19920701 "
    "AND l.lo_custkey = c.c_custkey "
    "AND l.lo_suppkey = s.s_suppkey "
    "AND l.lo_partkey = p.p_partkey "
    "AND c.c_region = \"AMERICA\" "
    "AND s.s_nation = \"UNITED STATES\" "
    "AND p.p_category = \"MFGR#14\" "
    "GROUP BY d.d_datekey, s.s_city, p.p_brand1 "
    "ORDER BY d.d_datekey, s.s_city, p.p_brand1;"
)

interactive_query_SSB_q43 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT d.d_datekey, s.s_city, p.p_brand1, SUM(l.lo_revenue - l.lo_supplycost) AS profit '
    'FROM SSB_Date d '
    'JOIN SSB_Lineorder l ON d.d_datekey /*+ indexnl */ = l.lo_orderdate '
    'JOIN SSB_Part p ON l.lo_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN SSB_Supplier s ON l.lo_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN SSB_Customer c ON l.lo_custkey /*+ indexnl */ = c.c_custkey '
    'WHERE d.d_datekey < 19920330 '
    'AND c.c_region = "AMERICA" '
    'AND s.s_nation = "UNITED STATES" '
    'AND p.p_category = "MFGR#14" '
    'GROUP BY d.d_datekey, s.s_city, p.p_brand1;'
)

# Q1
blocking_query_q1_30 = "SELECT l_returnflag, l_linestatus, SUM(l_quantity) AS sum_qty, SUM(l_extendedprice) AS sum_base_price, SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price, SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge, AVG(l_quantity) AS avg_qty, AVG(l_extendedprice) AS avg_price, AVG(l_discount) AS avg_disc, COUNT(*) AS count_order FROM Lineitem_30 WHERE l_shipdate <= \"1998-09-01\" GROUP BY l_returnflag, l_linestatus"
interactive_query_q1_30 =  "SET `compiler.interactive.mode` \"true\"; SELECT l_returnflag, l_linestatus, SUM(l_quantity) AS sum_qty, SUM(l_extendedprice) AS sum_base_price, SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price, SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge, AVG(l_quantity) AS avg_qty, AVG(l_extendedprice) AS avg_price, AVG(l_discount) AS avg_disc, COUNT(*) AS count_order FROM Lineitem_30 WHERE l_shipdate <= \"1998-09-01\" AND l_returnflag = \"A\" GROUP BY l_returnflag, l_linestatus"
blocking_query_q1_30_dynamic = (
    'SET `compiler.blocking.mode` `true`; '
    'SELECT a.* FROM ( '
    'SELECT l_returnflag, l_linestatus, '
    'SUM(l_quantity) AS sum_qty, '
    'SUM(l_extendedprice) AS sum_base_price, '
    'SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price, '
    'SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge, '
    'AVG(l_quantity) AS avg_qty, '
    'AVG(l_extendedprice) AS avg_price, '
    'AVG(l_discount) AS avg_disc, '
    'COUNT(*) AS count_order '
    'FROM Lineitem_30 '
    'WHERE l_shipdate <= "1998-09-01" '
    'GROUP BY l_returnflag, l_linestatus '
    ') a WHERE a.l_returnflag = "N"'
)
interactive_query_q1_30_dynamic =  "SET `compiler.interactive.mode` \"true\"; SELECT l_returnflag, l_linestatus, SUM(l_quantity) AS sum_qty, SUM(l_extendedprice) AS sum_base_price, SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price, SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge, AVG(l_quantity) AS avg_qty, AVG(l_extendedprice) AS avg_price, AVG(l_discount) AS avg_disc, COUNT(*) AS count_order FROM Lineitem_30 WHERE l_shipdate <= \"1998-09-01\" AND l_returnflag = \"A\" GROUP BY l_returnflag, l_linestatus"

# Q2
blocking_query_q2_30 = "SELECT s.s_acctbal, s.s_name, n.n_name, p.p_partkey, p.p_mfgr, s.s_address, s.s_phone, s.s_comment FROM Part_30 p, Supplier_30 s, Partsupp_30 ps, Nation_30 n, Region_30 r WHERE p.p_partkey = ps.ps_partkey AND s.s_suppkey = ps.ps_suppkey AND p.p_size = 15 AND p.p_type LIKE \"%BRASS\" AND s.s_nationkey = n.n_nationkey AND n.n_regionkey = r.r_regionkey AND r.r_name = \"EUROPE\" ORDER BY s.s_acctbal DESC"
interactive_query_q2_30 = "SET `compiler.interactive.mode` \"true\"; SELECT s.s_acctbal, s.s_name, n.n_name, p.p_partkey,ps.ps_suppkey, p.p_mfgr, s.s_address, s.s_phone, s.s_comment FROM Supplier_30 s, Partsupp_30 ps, Part_30 p, Nation_30 n, Region_30 r WHERE s.s_suppkey /* +indexnl */= ps.ps_suppkey AND ps.ps_partkey /* +indexnl */= p.p_partkey  AND s.s_nationkey /* +indexnl*/ = n.n_nationkey AND n.n_regionkey/* +indexnl*/ = r.r_regionkey AND r.r_name = \"EUROPE\" AND p.p_size = 15 AND p.p_type LIKE \"%BRASS\" AND s.s_acctbal > -1000"

# Q4
interactive_query_q4_30 = (
    "SET `compiler.interactive.mode` `true`; "
    "EXPLAIN SELECT o.o_orderpriority, COUNT(DISTINCT o.o_orderkey) AS order_count "
    "FROM Orders_30 AS o JOIN Lineitem_30 AS l "
    "ON o.o_orderkey /*+ indexnl */ = l.l_orderkey "
    "WHERE o.o_orderdate >= \"1993-07-01\" AND o.o_orderdate < \"1993-10-01\" "
    "AND o.o_orderpriority < \"2-HIGH\" "
    "AND l.l_commitdate < l.l_receiptdate "
    "GROUP BY o.o_orderpriority "
)
interactive_query_q4_30_dynamic = (
    "SET `compiler.interactive.mode` `true`; "
    "SELECT o.o_orderpriority, COUNT(DISTINCT o.o_orderkey) AS order_count "
    "FROM Orders_30 AS o JOIN Lineitem_30 AS l "
    "ON o.o_orderkey /*+ indexnl */ = l.l_orderkey "
    "WHERE o.o_orderdate >= \"1993-07-01\" AND o.o_orderdate < \"1993-10-01\" "
    "AND o.o_orderpriority < \"2-HIGH\" "
    "AND l.l_commitdate < l.l_receiptdate "
    "GROUP BY o.o_orderpriority "
)
blocking_query_q4_30 = (
    "SELECT o.o_orderpriority, COUNT(DISTINCT o.o_orderkey) AS order_count "
    "FROM Lineitem_30 AS l, Orders_30 AS o  "
    "WHERE  l.l_orderkey = o.o_orderkey "
    "AND o.o_orderdate >= \"1993-07-01\" AND o.o_orderdate < \"1993-10-01\" "
    "AND l.l_commitdate < l.l_receiptdate "
    "GROUP BY o.o_orderpriority "
    "ORDER BY o.o_orderpriority "
)
blocking_query_q4_30_dynamic = (
    'SET `compiler.blocking.mode` `true`; '
    'SELECT a.* FROM ( '
    'SELECT o.o_orderpriority, COUNT(DISTINCT o.o_orderkey) AS order_count '
    'FROM Lineitem_30 AS l, Orders_30 AS o '
    'WHERE l.l_orderkey = o.o_orderkey '
    'AND o.o_orderdate >= "1993-07-01" AND o.o_orderdate < "1993-10-01" '
    'AND l.l_commitdate < l.l_receiptdate '
    'GROUP BY o.o_orderpriority '
    'ORDER BY o.o_orderpriority '
    ') a WHERE a.o_orderpriority >= "2-HIGH"'
)
# Q5
interactive_query_q5_30 = 'SET `compiler.interactive.mode` "true";  SELECT n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue FROM Nation_30 n, Region_30 r, Supplier_30 s, Customer_30 c, Lineitem_30 l, Orders_30 o WHERE n.n_regionkey /* +indexnl */ = r.r_regionkey AND n.n_nationkey /* +indexnl */ = s.s_nationkey AND n.n_nationkey /* +indexnl */ = c.c_nationkey AND c.custkey /* +indexnl */ = o.o_custkey AND o.o_orderkey /* +indexnl */ = l.l_orderkey AND s.s_suppkey /* +indexnl */ = l.l_suppkey AND r.r_name = "ASIA" AND o.o_orderdate >= "1994-01-01" AND o.o_orderdate < "1995-01-01" AND n.n_name = "INDIA" and o.o_orderkey = 1  GROUP BY n.n_name'
blocking_query_q5_30 = "SELECT n.n_name AS n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue FROM Customer_30 c, Orders_30 o, Lineitem_30 l, Supplier_30 s, Nation_30 n, Region_30 r WHERE c.c_custkey = o.o_custkey AND l.l_orderkey = o.o_orderkey AND l.l_suppkey = s.s_suppkey AND c.c_nationkey = s.s_nationkey AND s.s_nationkey = n.n_nationkey AND n.n_regionkey = r.r_regionkey AND r.r_name = \"ASIA\" AND o.o_orderdate >= \"1994-01-01\" AND o.o_orderdate < \"1995-01-01\" GROUP BY n.n_name"

# Q8
blocking_query_q8_30 = (
    'SELECT o.o_orderdate AS o_orderdate, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS volume_total, '
    'SUM(CASE WHEN n2.n_name = "BRAZIL" THEN l.l_extendedprice * (1 - l.l_discount) ELSE 0 END) AS volume_brazil '
    'FROM Lineitem_30 l '
    'JOIN Orders_30 o ON l.l_orderkey = o.o_orderkey '
    'JOIN Part_30 p ON l.l_partkey = p.p_partkey '
    'JOIN Supplier_30 s ON l.l_suppkey = s.s_suppkey '
    'JOIN Customer_30 c ON o.o_custkey = c.c_custkey '
    'JOIN Nation_30 n1 ON c.c_nationkey = n1.n_nationkey '
    'JOIN Region_30 r ON n1.n_regionkey = r.r_regionkey '
    'JOIN Nation_30 n2 ON s.s_nationkey /*+ indexnl */ = n2.n_nationkey '
    'WHERE r.r_name = "AMERICA" '
    'AND o.o_orderdate > "1995-01-01" '
    'AND o.o_orderdate < "1996-12-31" '
    'AND p.p_type = "ECONOMY ANODIZED STEEL" '
    'GROUP BY o.o_orderdate '
    'ORDER BY o.o_orderdate;'
)
interactive_query_q8_30 = (
    'SET `compiler.interactive.mode` "true"; '
    'EXPLAIN SELECT o.o_orderdate AS o_orderdate, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS volume_total, '
    'SUM(CASE WHEN n2.n_name = "BRAZIL" THEN l.l_extendedprice * (1 - l.l_discount) ELSE 0 END) AS volume_brazil '
    'FROM Orders_30 o '
    'JOIN Lineitem_30 l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Part_30 p ON l.l_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN Supplier_30 s ON l.l_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN Customer_30 c ON o.o_custkey /*+ indexnl */ = c.c_custkey '
    'JOIN Nation_30 n1 ON c.c_nationkey /*+ indexnl */ = n1.n_nationkey '
    'JOIN Region_30 r ON n1.n_regionkey /*+ indexnl */ = r.r_regionkey '
    'JOIN Nation_30 n2 ON s.s_nationkey /*+ indexnl */ = n2.n_nationkey '
     'AND o.o_orderdate > "1995-01-01" '
      'AND o.o_orderdate <= "1995-01-02" '
    'AND r.r_name = "AMERICA" '
    'AND p.p_type = "ECONOMY ANODIZED STEEL" '
    'GROUP BY o.o_orderdate;'
)
interactive_query_q8_30_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT o.o_orderdate AS o_orderdate, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS volume_total, '
    'SUM(CASE WHEN n2.n_name = "BRAZIL" THEN l.l_extendedprice * (1 - l.l_discount) ELSE 0 END) AS volume_brazil '
    'FROM Orders_30 o '
    'JOIN Lineitem_30 l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Part_30 p ON l.l_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN Supplier_30 s ON l.l_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN Customer_30 c ON o.o_custkey /*+ indexnl */ = c.c_custkey '
    'JOIN Nation_30 n1 ON c.c_nationkey /*+ indexnl */ = n1.n_nationkey '
    'JOIN Region_30 r ON n1.n_regionkey /*+ indexnl */ = r.r_regionkey '
    'JOIN Nation_30 n2 ON s.s_nationkey /*+ indexnl */ = n2.n_nationkey '
    'WHERE r.r_name = "AMERICA" '
    'AND o.o_orderdate > "1995-01-01" '
    'AND o.o_orderdate < "1995-01-25" '
    'AND p.p_type = "ECONOMY ANODIZED STEEL" '
    'GROUP BY o.o_orderdate;'
)
blocking_query_q8_30_dynamic = (
    'SET `compiler.blocking.mode` `true`; '
    'SELECT a.* FROM( '
    'SELECT o.o_orderdate AS o_orderdate, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS volume_total, '
    'SUM(CASE WHEN n2.n_name = "BRAZIL" THEN l.l_extendedprice * (1 - l.l_discount) ELSE 0 END) AS volume_brazil '
    'FROM Lineitem_30 l '
    'JOIN Orders_30 o ON l.l_orderkey = o.o_orderkey '
    'JOIN Part_30 p ON l.l_partkey = p.p_partkey '
    'JOIN Supplier_30 s ON l.l_suppkey = s.s_suppkey '
    'JOIN Customer_30 c ON o.o_custkey = c.c_custkey '
    'JOIN Nation_30 n1 ON c.c_nationkey = n1.n_nationkey '
    'JOIN Region_30 r ON n1.n_regionkey = r.r_regionkey '
    'JOIN Nation_30 n2 ON s.s_nationkey /*+ indexnl */ = n2.n_nationkey '
    'WHERE r.r_name = "AMERICA" '
    'AND o.o_orderdate > "1995-01-01" '
    'AND o.o_orderdate < "1996-12-31" '
    'AND p.p_type = "ECONOMY ANODIZED STEEL" '
    'GROUP BY o.o_orderdate '
    'ORDER BY o.o_orderdate'
    ')a where a.o_orderdate > "1995-01-01"'
)

# Q9
interactive_query_q9_30 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT o.o_orderkey, n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Orders_30 AS o '
    'JOIN Lineitem_30 AS l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Part_30 AS p ON l.l_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN Supplier_30 AS s ON l.l_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN Nation_30 AS n ON s.s_nationkey /*+ indexnl */ = n.n_nationkey '
    'WHERE p.p_name LIKE "%green%" '
    'AND o.o_orderdate >= "1993-07-01" '
    'AND o.o_orderdate < "1993-10-01" '
    'AND o.o_orderkey < 6000000 '
    'GROUP BY o.o_orderkey, n.n_name;'
)
interactive_query_q9_30_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT o.o_orderkey, n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Orders_30 AS o '
    'JOIN Lineitem_30 AS l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Part_30 AS p ON l.l_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN Supplier_30 AS s ON l.l_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN Nation_30 AS n ON s.s_nationkey /*+ indexnl */ = n.n_nationkey '
    'WHERE p.p_name LIKE "%green%" '
    'AND o.o_orderdate >= "1993-07-01" '
    'AND o.o_orderdate < "1993-10-01" '
    'GROUP BY o.o_orderkey, n.n_name;'
)
blocking_query_q9_30 = (
    "SELECT o.o_orderkey, n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Lineitem_30 AS l, Orders_30 AS o,  Part_30 AS p, Supplier_30 AS s, Nation_30 AS n "
    "WHERE l.l_orderkey = o.o_orderkey "
    "AND l.l_partkey = p.p_partkey "
    "AND l.l_suppkey = s.s_suppkey "
    "AND s.s_nationkey = n.n_nationkey "
    "AND p.p_name LIKE \"%green%\" "
    "AND o.o_orderdate >= \"1993-07-01\" "
    "AND o.o_orderdate < \"1993-10-01\" "
    "GROUP BY o.o_orderkey, n.n_name "
    "ORDER BY o.o_orderkey, n.n_name"
)
blocking_query_q9_30_dynamic = (
    "SET `compiler.blocking.mode` `true`; "
    "SELECT a.* FROM( "
    "SELECT o.o_orderkey, n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue "
    "FROM Lineitem_30 AS l, Orders_30 AS o,  Part_30 AS p, Supplier_30 AS s, Nation_30 AS n "
    "WHERE l.l_orderkey = o.o_orderkey "
    "AND l.l_partkey = p.p_partkey "
    "AND l.l_suppkey = s.s_suppkey "
    "AND s.s_nationkey = n.n_nationkey "
    "AND p.p_name LIKE \"%green%\" "
    "AND o.o_orderdate >= \"1993-07-01\" "
    "AND o.o_orderdate < \"1993-10-01\" "
    "GROUP BY o.o_orderkey, n.n_name "
    ")a where a.o_orderkey > 10000"
)

# Q16
interactive_query_q16_30 = (
     'SET `compiler.interactive.mode` "true"; '
     'SELECT p.p_brand, p.p_type, p.p_size, COUNT( ps.ps_suppkey) AS supplier_cnt '
     'FROM Part_30 p, Partsupp_30 ps '
     'WHERE p.p_partkey /*+ indexnl */ = ps.ps_partkey '
     'AND p.p_brand > "Brand#45" '
     'AND p.p_type NOT LIKE "MEDIUM POLISHED%" '
     'AND p.p_size = 16 '
     'GROUP BY p.p_brand, p.p_type, p.p_size;'
)
interactive_query_q16_30_dynamic = (
      'SET `compiler.interactive.mode` "true"; '
      'SELECT p.p_brand, p.p_type, p.p_size, COUNT( ps.ps_suppkey) AS supplier_cnt '
      'FROM Part_30 p, Partsupp_30 ps '
      'WHERE p.p_partkey /*+ indexnl */ = ps.ps_partkey '
      'AND p.p_brand > "Brand#45" '
      'AND p.p_type NOT LIKE "MEDIUM POLISHED%" '
      'AND p.p_size = 16 '
      'GROUP BY p.p_brand, p.p_type, p.p_size;'
)
blocking_query_q16_30 = (
      'SELECT p.p_brand, p.p_type, p.p_size, COUNT( ps.ps_suppkey) AS supplier_cnt '
      'FROM  Partsupp_30 ps, Part_30 p '
      'WHERE  ps.ps_partkey = p.p_partkey '
      'AND p.p_type NOT LIKE "MEDIUM POLISHED%" '
      'AND p.p_size = 16 '
      'GROUP BY p.p_brand, p.p_type, p.p_size;'
)
blocking_query_q16_30_dynamic = (
        'SET `compiler.blocking.mode` `true`; '
        'SELECT a.* FROM( '
        'SELECT p.p_brand, p.p_type, p.p_size, COUNT( ps.ps_suppkey) AS supplier_cnt '
        'FROM  Partsupp_30 ps, Part_30 p '
        'WHERE  ps.ps_partkey = p.p_partkey '
        'AND p.p_type NOT LIKE "MEDIUM POLISHED%" '
        'AND p.p_brand > "Brand#45" '
        'AND p.p_size = 16 '
        'GROUP BY p.p_brand, p.p_type, p.p_size'
        ')a where a.p_brand > "Brand#45"'
)

# Q18
interactive_query_q18_30 = (
     "SET `compiler.interactive.mode` \"true\"; "
     "SELECT o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate, "
     "SUM(l.l_quantity) AS total_quantity "
     "FROM Orders_30 o "
     "JOIN Lineitem_30 l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey "
     "JOIN Customer_30 c ON o.o_custkey /*+ indexnl */ = c.c_custkey "
     "WHERE o.o_totalprice > 450000 AND l.l_quantity > 40 "
     "GROUP BY o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate;"
)
interactive_query_q18_30_dynamic = (
  "SET `compiler.interactive.mode` \"true\"; "
  "SELECT o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate, "
  "SUM(l.l_quantity) AS total_quantity "
  "FROM Orders_30 o "
  "JOIN Lineitem_30 l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey "
  "JOIN Customer_30 c ON o.o_custkey /*+ indexnl */ = c.c_custkey "
  "WHERE o.o_totalprice > 450000 AND l.l_quantity > 40 "
  "GROUP BY o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate;"
)
blocking_query_q18_30 = (
     "SELECT o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate, "
     "SUM(l.l_quantity) AS total_quantity "
     "FROM Orders_30 o, Lineitem_30 l, Customer_30 c "
     "WHERE o.o_orderkey = l.l_orderkey "
     "AND  o.o_orderkey = c.c_custkey "
     "AND o.o_totalprice > 450000 "
     "AND l.l_quantity > 40 "
     "GROUP BY o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate;"
)
blocking_query_q18_30_dynamic = (
      "SET `compiler.blocking.mode` `true`; "
      "SELECT a.* FROM( "
      "SELECT o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate, "
      "SUM(l.l_quantity) AS total_quantity "
      "FROM Orders_30 o, Lineitem_30 l, Customer_30 c "
      "WHERE o.o_orderkey = l.l_orderkey "
      "AND  o.o_custkey= c.c_custkey "
      "AND o.o_totalprice > 450000 "
      "AND l.l_quantity > 40 "
      "GROUP BY o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate"
      ")a where a.o_orderkey > 10000"
)
# ============== Q1 (four variants) ==============
interactive_query_q1_1 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT l_returnflag, l_linestatus, '
    'SUM(l_quantity) AS sum_qty, '
    'SUM(l_extendedprice) AS sum_base_price, '
    'SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price, '
    'SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge, '
    'AVG(l_quantity) AS avg_qty, '
    'AVG(l_extendedprice) AS avg_price, '
    'AVG(l_discount) AS avg_disc, '
    'COUNT(*) AS count_order '
    'FROM Lineitem_1 '
    'WHERE l_shipdate <= "1998-09-01" AND l_returnflag <= "A" '
    'GROUP BY l_returnflag, l_linestatus;'
)

blocking_query_q1_1 = (
    'SELECT l_returnflag, l_linestatus, '
    'SUM(l_quantity) AS sum_qty, '
    'SUM(l_extendedprice) AS sum_base_price, '
    'SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price, '
    'SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge, '
    'AVG(l_quantity) AS avg_qty, '
    'AVG(l_extendedprice) AS avg_price, '
    'AVG(l_discount) AS avg_disc, '
    'COUNT(*) AS count_order '
    'FROM Lineitem_1 '
    'WHERE l_shipdate <= "1998-09-01" '
    'GROUP BY l_returnflag, l_linestatus;'
)

interactive_query_q1_1_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT l_returnflag, l_linestatus, '
    'SUM(l_quantity) AS sum_qty, '
    'SUM(l_extendedprice) AS sum_base_price, '
    'SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price, '
    'SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge, '
    'AVG(l_quantity) AS avg_qty, '
    'AVG(l_extendedprice) AS avg_price, '
    'AVG(l_discount) AS avg_disc, '
    'COUNT(*) AS count_order '
    'FROM Lineitem_1 '
    'WHERE l_shipdate <= "1998-09-01" AND l_returnflag <="A" '
    'GROUP BY l_returnflag, l_linestatus;'
)

blocking_query_q1_1_dynamic = (
    'SET `compiler.blocking.mode` "true"; '
    'SELECT a.* FROM ( '
    'SELECT l_returnflag, l_linestatus, '
    'SUM(l_quantity) AS sum_qty, '
    'SUM(l_extendedprice) AS sum_base_price, '
    'SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price, '
    'SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge, '
    'AVG(l_quantity) AS avg_qty, '
    'AVG(l_extendedprice) AS avg_price, '
    'AVG(l_discount) AS avg_disc, '
    'COUNT(*) AS count_order '
    'FROM Lineitem_1 '
    'WHERE l_shipdate <= "1998-09-01" '
    'GROUP BY l_returnflag, l_linestatus '
    ') a WHERE a.l_returnflag > "N";'
)

# ============== Q3 (four variants) ==============
interactive_query_q3_1 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT l.l_orderkey, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Lineitem_1 AS l, Orders_1 AS o, Customer_1 AS c '
    'WHERE c.c_mktsegment = "BUILDING" '
    'AND l.l_orderkey /*+ indexnl */ = o.o_orderkey '
    'AND o.o_custkey /*+ indexnl */ = c.c_custkey '
    'AND o.o_orderdate < "1995-03-15" '
    'AND l.l_shipdate > "1995-03-15" '
    'GROUP BY l.l_orderkey;'
)

blocking_query_q3_1 = (
    'SELECT l.l_orderkey, o.o_orderdate, o.o_shippriority, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Customer_1 AS c '
    'JOIN Orders_1 AS o ON c.c_custkey = o.o_custkey '
    'JOIN Lineitem_1 AS l ON l.l_orderkey = o.o_orderkey '
    'WHERE c.c_mktsegment = "BUILDING" '
    'AND o.o_orderdate < "1995-03-15" '
    'AND l.l_shipdate > "1995-03-15" '
    'GROUP BY l.l_orderkey, o.o_orderdate, o.o_shippriority;'
)

interactive_query_q3_1_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT l.l_orderkey, o.o_orderdate, o.o_shippriority, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Lineitem_1 AS l, Orders_1 AS o, Customer_1 AS c '
    'WHERE c.c_mktsegment = "BUILDING" '
    'AND l.l_orderkey /*+ indexnl */ = o.o_orderkey '
    'AND o.o_custkey /*+ indexnl */ = c.c_custkey '
    'AND o.o_orderdate < "1995-03-15" '
    'AND l.l_shipdate > "1995-03-15" '
    'GROUP BY l.l_orderkey, o.o_orderdate, o.o_shippriority;'
)

blocking_query_q3_1_dynamic = (
    'SET `compiler.blocking.mode` "true"; '
    'SELECT a.* FROM ( '
    'SELECT l.l_orderkey, o.o_orderdate, o.o_shippriority, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Customer_1 AS c, Orders_1 AS o, Lineitem_1 AS l '
    'WHERE c.c_mktsegment = "BUILDING" '
    'AND o.o_custkey = c.c_custkey '
    'AND l.l_orderkey = o.o_orderkey '
    'AND o.o_orderdate < "1995-03-15" '
    'AND l.l_shipdate > "1995-03-15" '
    'GROUP BY l.l_orderkey, o.o_orderdate, o.o_shippriority '
    ') a WHERE a.l_orderkey > 10000;'
)

# ============== Q4 (four variants) ==============
interactive_query_q4_1 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT o.o_orderpriority, COUNT(DISTINCT o.o_orderkey) AS order_count '
    'FROM Orders_1 AS o JOIN Lineitem_1 AS l '
    'ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'WHERE o.o_orderdate >= "1993-07-01" AND o.o_orderdate < "1993-10-01" '
    'AND o.o_orderpriority < "2-HIGH" '
    'AND l.l_commitdate < l.l_receiptdate '
    'GROUP BY o.o_orderpriority;'
)

blocking_query_q4_1 = (
    'SELECT o.o_orderpriority, COUNT(DISTINCT o.o_orderkey) AS order_count '
    'FROM Lineitem_1 AS l, Orders_1 AS o '
    'WHERE l.l_orderkey = o.o_orderkey '
    'AND o.o_orderdate >= "1993-07-01" AND o.o_orderdate < "1993-10-01" '
    'AND l.l_commitdate < l.l_receiptdate '
    'GROUP BY o.o_orderpriority '
    'ORDER BY o.o_orderpriority;'
)

interactive_query_q4_1_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT o.o_orderpriority, COUNT(DISTINCT o.o_orderkey) AS order_count '
    'FROM Orders_1 AS o JOIN Lineitem_1 AS l '
    'ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'WHERE o.o_orderdate >= "1993-07-01" AND o.o_orderdate < "1993-10-01" '
    'AND o.o_orderpriority <= "2-HIGH" '
    'AND l.l_commitdate < l.l_receiptdate '
    'GROUP BY o.o_orderpriority;'
)

blocking_query_q4_1_dynamic = (
    'SET `compiler.blocking.mode` "true"; '
    'SELECT a.* FROM ( '
    'SELECT o.o_orderpriority, COUNT(DISTINCT o.o_orderkey) AS order_count '
    'FROM Lineitem_1 AS l, Orders_1 AS o '
    'WHERE l.l_orderkey = o.o_orderkey '
    'AND o.o_orderdate >= "1993-07-01" AND o.o_orderdate < "1993-10-01" '
    'AND l.l_commitdate < l.l_receiptdate '
    'GROUP BY o.o_orderpriority '
    'ORDER BY o.o_orderpriority '
    ') a WHERE a.o_orderpriority >= "2-HIGH";'
)

# ============== Q8 (four variants) ==============
interactive_query_q8_1 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT o.o_orderdate AS o_orderdate, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS volume_total, '
    'SUM(CASE WHEN n2.n_name = "BRAZIL" THEN l.l_extendedprice * (1 - l.l_discount) ELSE 0 END) AS volume_brazil '
    'FROM Orders_1 o '
    'JOIN Lineitem_1 l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Part_1 p ON l.l_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN Supplier_1 s ON l.l_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN Customer_1 c ON o.o_custkey /*+ indexnl */ = c.c_custkey '
    'JOIN Nation_1 n1 ON c.c_nationkey /*+ indexnl */ = n1.n_nationkey '
    'JOIN Region_1 r ON n1.n_regionkey /*+ indexnl */ = r.r_regionkey '
    'JOIN Nation_1 n2 ON s.s_nationkey /*+ indexnl */ = n2.n_nationkey '
    'WHERE r.r_name = "AMERICA" '
    'AND o.o_orderdate > "1995-01-01" '
    'AND o.o_orderdate < "1995-01-31" '
    'AND p.p_type = "ECONOMY ANODIZED STEEL" '
    'GROUP BY o.o_orderdate;'
)

blocking_query_q8_1 = (
    'SELECT o.o_orderdate AS o_orderdate, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS volume_total, '
    'SUM(CASE WHEN n2.n_name = "BRAZIL" THEN l.l_extendedprice * (1 - l.l_discount) ELSE 0 END) AS volume_brazil '
    'FROM Lineitem_1 l '
    'JOIN Orders_1 o ON l.l_orderkey = o.o_orderkey '
    'JOIN Part_1 p ON l.l_partkey = p.p_partkey '
    'JOIN Supplier_1 s ON l.l_suppkey = s.s_suppkey '
    'JOIN Customer_1 c ON o.o_custkey = c.c_custkey '
    'JOIN Nation_1 n1 ON c.c_nationkey = n1.n_nationkey '
    'JOIN Region_1 r ON n1.n_regionkey = r.r_regionkey '
    'JOIN Nation_1 n2 ON s.s_nationkey /*+ indexnl */ = n2.n_nationkey '
    'WHERE r.r_name = "AMERICA" '
    'AND o.o_orderdate > "1995-01-01" '
    'AND o.o_orderdate < "1996-12-31" '
    'AND p.p_type = "ECONOMY ANODIZED STEEL" '
    'GROUP BY o.o_orderdate '
    'ORDER BY o.o_orderdate;'
)

interactive_query_q8_1_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT o.o_orderdate AS o_orderdate, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS volume_total, '
    'SUM(CASE WHEN n2.n_name = "BRAZIL" THEN l.l_extendedprice * (1 - l.l_discount) ELSE 0 END) AS volume_brazil '
    'FROM Orders_1 o '
    'JOIN Lineitem_1 l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Part_1 p ON l.l_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN Supplier_1 s ON l.l_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN Customer_1 c ON o.o_custkey /*+ indexnl */ = c.c_custkey '
    'JOIN Nation_1 n1 ON c.c_nationkey /*+ indexnl */ = n1.n_nationkey '
    'JOIN Region_1 r ON n1.n_regionkey /*+ indexnl */ = r.r_regionkey '
    'JOIN Nation_1 n2 ON s.s_nationkey /*+ indexnl */ = n2.n_nationkey '
    'WHERE r.r_name = "AMERICA" '
    'AND o.o_orderdate > "1995-01-01" '
    'AND o.o_orderdate < "1995-03-30" '
    'AND p.p_type = "ECONOMY ANODIZED STEEL" '
    'GROUP BY o.o_orderdate;'
)

blocking_query_q8_1_dynamic = (
    'SET `compiler.blocking.mode` "true"; '
    'SELECT a.* FROM ( '
    'SELECT o.o_orderdate AS o_orderdate, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS volume_total, '
    'SUM(CASE WHEN n2.n_name = "BRAZIL" THEN l.l_extendedprice * (1 - l.l_discount) ELSE 0 END) AS volume_brazil '
    'FROM Lineitem_1 l '
    'JOIN Orders_1 o ON l.l_orderkey = o.o_orderkey '
    'JOIN Part_1 p ON l.l_partkey = p.p_partkey '
    'JOIN Supplier_1 s ON l.l_suppkey = s.s_suppkey '
    'JOIN Customer_1 c ON o.o_custkey = c.c_custkey '
    'JOIN Nation_1 n1 ON c.c_nationkey = n1.n_nationkey '
    'JOIN Region_1 r ON n1.n_regionkey = r.r_regionkey '
    'JOIN Nation_1 n2 ON s.s_nationkey /*+ indexnl */ = n2.n_nationkey '
    'WHERE r.r_name = "AMERICA" '
    'AND o.o_orderdate > "1995-01-01" '
    'AND o.o_orderdate < "1996-12-31" '
    'AND p.p_type = "ECONOMY ANODIZED STEEL" '
    'GROUP BY o.o_orderdate '
    'ORDER BY o.o_orderdate '
    ') a WHERE a.o_orderdate > "1995-01-01";'
)

# ============== Q9 (four variants) ==============
interactive_query_q9_1 = (
    'SET `compiler.interactive.mode` "true"; '
    'EXPLAIN SELECT o.o_orderkey, n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Orders_1 AS o '
    'JOIN Lineitem_1 AS l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Part_1 AS p ON l.l_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN Supplier_1 AS s ON l.l_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN Nation_1 AS n ON s.s_nationkey /*+ indexnl */ = n.n_nationkey '
    'WHERE p.p_name LIKE "%green%" '
    'AND o.o_orderdate >= "1993-07-01" '
    'AND o.o_orderdate < "1993-10-01" '
    'GROUP BY o.o_orderkey, n.n_name;'
)

blocking_query_q9_1 = (
    'SELECT o.o_orderkey, n.n_name, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Lineitem_1 AS l, Orders_1 AS o, Part_1 AS p, Supplier_1 AS s, Nation_1 AS n '
    'WHERE l.l_orderkey = o.o_orderkey '
    'AND l.l_partkey = p.p_partkey '
    'AND l.l_suppkey = s.s_suppkey '
    'AND s.s_nationkey = n.n_nationkey '
    'AND p.p_name LIKE "%green%" '
    'AND o.o_orderdate >= "1993-07-01" '
    'AND o.o_orderdate < "1993-10-01" '
    'GROUP BY o.o_orderkey, n.n_name '
    'ORDER BY o.o_orderkey, n.n_name;'
)

interactive_query_q9_1_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT o.o_orderkey, n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Orders_1 AS o '
    'JOIN Lineitem_1 AS l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Part_1 AS p ON l.l_partkey /*+ indexnl */ = p.p_partkey '
    'JOIN Supplier_1 AS s ON l.l_suppkey /*+ indexnl */ = s.s_suppkey '
    'JOIN Nation_1 AS n ON s.s_nationkey /*+ indexnl */ = n.n_nationkey '
    'WHERE p.p_name LIKE "%green%" '
    'AND o.o_orderdate >= "1993-07-01" '
    'AND o.o_orderdate < "1993-10-01" '
    'GROUP BY o.o_orderkey, n.n_name;'
)

blocking_query_q9_1_dynamic = (
    'SET `compiler.blocking.mode` "true"; '
    'SELECT a.* FROM ( '
    'SELECT o.o_orderkey, n.n_name, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Lineitem_1 AS l, Orders_1 AS o, Part_1 AS p, Supplier_1 AS s, Nation_1 AS n '
    'WHERE l.l_orderkey = o.o_orderkey '
    'AND l.l_partkey = p.p_partkey '
    'AND l.l_suppkey = s.s_suppkey '
    'AND s.s_nationkey = n.n_nationkey '
    'AND p.p_name LIKE "%green%" '
    'AND o.o_orderdate >= "1993-07-01" '
    'AND o.o_orderdate < "1993-10-01" '
    'GROUP BY o.o_orderkey, n.n_name '
    ') a WHERE a.o_orderkey > 10000;'
)

# ============== Q10 (four variants) ==============
interactive_query_q10_1 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT c.c_custkey, c.c_name, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue, '
    'c.c_acctbal, c.c_address, c.c_phone, c.c_comment '
    'FROM Customer_1 AS c, Orders_1 AS o, Lineitem_1 AS l '
    'WHERE c.c_custkey /*+indexnl*/ = o.o_custkey '
    'AND o.o_orderkey /*+indexnl*/ = l.l_orderkey '
    'AND o.o_orderdate >= "1993-10-01" AND o.o_orderdate < "1994-01-01" '
    'AND c.c_custkey < 100000 '
    'GROUP BY c.c_custkey, c.c_name, c.c_acctbal, c.c_phone, c.c_address, c.c_comment;'
)

blocking_query_q10_1 = (
    'SELECT c.c_custkey, n.n_name, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Customer_1 AS c '
    'JOIN Nation_1 AS n ON c.c_nationkey = n.n_nationkey '
    'JOIN Orders_1 AS o ON o.o_custkey = c.c_custkey '
    'JOIN Lineitem_1 AS l ON l.l_orderkey = o.o_orderkey '
    'WHERE o.o_orderdate >= "1993-10-01" AND o.o_orderdate < "1994-01-01" '
    'AND l.l_returnflag = "R" '
    'GROUP BY c.c_custkey, n.n_name;'
)

interactive_query_q10_1_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT c.c_custkey, n.n_name, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Customer_1 AS c, Nation_1 AS n, Orders_1 AS o, Lineitem_1 AS l '
    'WHERE c.c_nationkey /*+indexnl*/ = n.n_nationkey '
    'AND c.c_custkey /*+indexnl*/ = o.o_custkey '
    'AND o.o_orderkey /*+indexnl*/ = l.l_orderkey '
    'AND o.o_orderdate >= "1993-10-01" AND o.o_orderdate < "1994-01-01" '
    'AND l.l_returnflag = "R" '
    'GROUP BY c.c_custkey, n.n_name;'
)

blocking_query_q10_1_dynamic = (
    'SET `compiler.blocking.mode` "true"; '
    'SELECT a.* FROM ( '
    'SELECT c.c_custkey, n.n_name, '
    'SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue '
    'FROM Customer_1 AS c '
    'JOIN Nation_1 AS n ON c.c_nationkey = n.n_nationkey '
    'JOIN Orders_1 AS o ON o.o_custkey = c.c_custkey '
    'JOIN Lineitem_1 AS l ON l.l_orderkey = o.o_orderkey '
    'WHERE o.o_orderdate >= "1993-10-01" AND o.o_orderdate < "1994-01-01" '
    'AND l.l_returnflag = "R" '
    'GROUP BY c.c_custkey, n.n_name '
    ') a WHERE a.c_custkey > 20000;'
)

# ============== Q12 (four variants) ==============
interactive_query_q12_1 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT l.l_shipmode AS l_shipmode, '
    'SUM(CASE WHEN o.o_orderpriority = "1-URGENT" OR o.o_orderpriority = "2-HIGH" THEN 1 ELSE 0 END) AS high_line_count, '
    'SUM(CASE WHEN o.o_orderpriority != "1-URGENT" AND o.o_orderpriority != "2-HIGH" THEN 1 ELSE 0 END) AS low_line_count '
    'FROM Lineitem_1 AS l, Orders_1 AS o '
    'WHERE l.l_orderkey /*+ indexnl */ = o.o_orderkey '
    'AND l.l_shipmode = "MAIL" '
    'AND l.l_commitdate < l.l_receiptdate '
    'AND l.l_shipdate < l.l_commitdate '
    'AND l.l_receiptdate >= "1994-01-01" '
    'AND l.l_receiptdate < "1995-01-01" '
    'GROUP BY l.l_shipmode;'
)

blocking_query_q12_1 = (
    'SELECT l.l_shipmode AS l_shipmode, '
    'SUM(CASE WHEN o.o_orderpriority = "1-URGENT" OR o.o_orderpriority = "2-HIGH" THEN 1 ELSE 0 END) AS high_line_count, '
    'SUM(CASE WHEN o.o_orderpriority != "1-URGENT" AND o.o_orderpriority != "2-HIGH" THEN 1 ELSE 0 END) AS low_line_count '
    'FROM Orders_1 AS o, Lineitem_1 AS l '
    'WHERE o.o_orderkey = l.l_orderkey '
    'AND l.l_shipmode IN ("MAIL","SHIP") '
    'AND l.l_commitdate < l.l_receiptdate '
    'AND l.l_shipdate < l.l_commitdate '
    'AND l.l_receiptdate >= "1994-01-01" '
    'AND l.l_receiptdate < "1995-01-01" '
    'GROUP BY l.l_shipmode '
    'ORDER BY l.l_shipmode;'
)

interactive_query_q12_1_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT l.l_shipmode AS l_shipmode, '
    'SUM(CASE WHEN o.o_orderpriority = "1-URGENT" OR o.o_orderpriority = "2-HIGH" THEN 1 ELSE 0 END) AS high_line_count, '
    'SUM(CASE WHEN o.o_orderpriority != "1-URGENT" AND o.o_orderpriority != "2-HIGH" THEN 1 ELSE 0 END) AS low_line_count '
    'FROM Lineitem_1 AS l, Orders_1 AS o '
    'WHERE l.l_orderkey /*+ indexnl */ = o.o_orderkey '
    'AND l.l_shipmode = "MAIL" '
    'AND l.l_commitdate < l.l_receiptdate '
    'AND l.l_shipdate < l.l_commitdate '
    'AND l.l_receiptdate >= "1994-01-01" '
    'AND l.l_receiptdate < "1995-01-01" '
    'GROUP BY l.l_shipmode;'
)

blocking_query_q12_1_dynamic = (
    'SET `compiler.blocking.mode` "true"; '
    'SELECT a.* FROM ( '
    'SELECT l.l_shipmode AS l_shipmode, '
    'SUM(CASE WHEN o.o_orderpriority = "1-URGENT" OR o.o_orderpriority = "2-HIGH" THEN 1 ELSE 0 END) AS high_line_count, '
    'SUM(CASE WHEN o.o_orderpriority != "1-URGENT" AND o.o_orderpriority != "2-HIGH" THEN 1 ELSE 0 END) AS low_line_count '
    'FROM Orders_1 AS o, Lineitem_1 AS l '
    'WHERE o.o_orderkey = l.l_orderkey '
    'AND l.l_shipmode IN ("MAIL","SHIP") '
    'AND l.l_commitdate < l.l_receiptdate '
    'AND l.l_shipdate < l.l_commitdate '
    'AND l.l_receiptdate >= "1994-01-01" '
    'AND l.l_receiptdate < "1995-01-01" '
    'GROUP BY l.l_shipmode '
    ') a WHERE a.l_shipmode > "MAIL";'
)

# ============== Q16 (four variants) ==============
interactive_query_q16_1 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT p.p_brand, p.p_type, p.p_size, COUNT(ps.ps_suppkey) AS supplier_cnt '
    'FROM Part_1 p, Partsupp_1 ps '
    'WHERE p.p_partkey /*+ indexnl */ = ps.ps_partkey '
    'AND p.p_brand > "Brand#45" '
    'AND p.p_type NOT LIKE "MEDIUM POLISHED%" '
    'AND p.p_size = 16 '
    'GROUP BY p.p_brand, p.p_type, p.p_size;'
)

blocking_query_q16_1 = (
    'SELECT p.p_brand, p.p_type, p.p_size, COUNT(ps.ps_suppkey) AS supplier_cnt '
    'FROM Partsupp_1 ps, Part_1 p '
    'WHERE ps.ps_partkey = p.p_partkey '
    'AND p.p_type NOT LIKE "MEDIUM POLISHED%" '
    'AND p.p_size = 16 '
    'GROUP BY p.p_brand, p.p_type, p.p_size;'
)

interactive_query_q16_1_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT p.p_brand, p.p_type, p.p_size, COUNT(ps.ps_suppkey) AS supplier_cnt '
    'FROM Part_1 p, Partsupp_1 ps '
    'WHERE p.p_partkey /*+ indexnl */ = ps.ps_partkey '
    'AND p.p_brand > "Brand#45" '
    'AND p.p_type NOT LIKE "MEDIUM POLISHED%" '
    'AND p.p_size = 16 '
    'GROUP BY p.p_brand, p.p_type, p.p_size;'
)

blocking_query_q16_1_dynamic = (
    'SET `compiler.blocking.mode` "true"; '
    'SELECT a.* FROM ( '
    'SELECT p.p_brand, p.p_type, p.p_size, COUNT(ps.ps_suppkey) AS supplier_cnt '
    'FROM Partsupp_1 ps, Part_1 p '
    'WHERE ps.ps_partkey = p.p_partkey '
    'AND p.p_type NOT LIKE "MEDIUM POLISHED%" '
    'AND p.p_brand > "Brand#45" '
    'AND p.p_size = 16 '
    'GROUP BY p.p_brand, p.p_type, p.p_size '
    ') a WHERE a.p_brand > "Brand#45";'
)

# ============== Q18 (four variants) ==============
interactive_query_q18_1 = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate, '
    'SUM(l.l_quantity) AS total_quantity '
    'FROM Orders_1 o '
    'JOIN Lineitem_1 l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Customer_1 c ON o.o_custkey /*+ indexnl */ = c.c_custkey '
    'WHERE o.o_totalprice > 450000 AND l.l_quantity > 40 '
    'GROUP BY o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate;'
)

blocking_query_q18_1 = (
    'SELECT o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate, '
    'SUM(l.l_quantity) AS total_quantity '
    'FROM Orders_1 o, Lineitem_1 l, Customer_1 c '
    'WHERE o.o_orderkey = l.l_orderkey '
    'AND  o.o_orderkey = c.c_custkey '
    'AND o.o_totalprice > 450000 '
    'AND l.l_quantity > 40 '
    'GROUP BY o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate;'
)

interactive_query_q18_1_dynamic = (
    'SET `compiler.interactive.mode` "true"; '
    'SELECT o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate, '
    'SUM(l.l_quantity) AS total_quantity '
    'FROM Orders_1 o '
    'JOIN Lineitem_1 l ON o.o_orderkey /*+ indexnl */ = l.l_orderkey '
    'JOIN Customer_1 c ON o.o_custkey /*+ indexnl */ = c.c_custkey '
    'WHERE o.o_totalprice > 450000 AND l.l_quantity > 40 '
    'GROUP BY o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate;'
)

blocking_query_q18_1_dynamic = (
    'SET `compiler.blocking.mode` "true"; '
    'SELECT a.* FROM( '
    'SELECT o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate, '
    'SUM(l.l_quantity) AS total_quantity '
    'FROM Orders_1 o, Lineitem_1 l, Customer_1 c '
    'WHERE o.o_orderkey /*+ indexnl */= l.l_orderkey '
    'AND  o.o_orderkey /*+ indexnl */= c.c_custkey '
    'AND o.o_totalprice > 450000 '
    'AND l.l_quantity > 40 '
    'GROUP BY o.o_totalprice, o.o_orderkey, c.c_name, c.c_custkey, o.o_orderdate '
    ') a WHERE a.o_orderkey > 10000;'
)




headers = {
    "Content-Type": "application/json"
}

url = "http://localhost:19002/query/service"

def build_payload(statement, strategy, isInteractive):
    payload = {
        "statement": statement
       #,"optimized-logical-plan": "true"
       ,"profile": "timings",
    }
    if not (strategy == "dynamic" and isInteractive):
            payload["mode"] = "deferred"
    return payload

import os

def make_filename(base_dir, mode, part, deployment, timestamp, query, sf, iteration, strategy, number_of_nodes, create_dirs=True):
    # Directory hierarchy: <base>/<number_of_nodes>N/sf-<sf>/<query>/
    dir_path = os.path.join(base_dir, f"{number_of_nodes}N", f"sf-{sf}", query)
    if create_dirs:
        os.makedirs(dir_path, exist_ok=True)

    # Put the remaining identifiers into the filename
    filename = f"output_{deployment}_{mode}-{strategy}_part-{part}_{timestamp}_run{iteration + 1}.json"
    return os.path.join(dir_path, filename)

def metric_dest_name(metric: str, query_label: str, timestamp: str) -> str:
    # Keep extension if present (e.g., .log); otherwise follow your existing naming
    root, ext = os.path.splitext(metric)
    if ext:
        return f"{root}_{query_label}_{timestamp}{ext}"
    return f"{metric}_{query_label}_{timestamp}"

# runs a single query and saves result to filename
async def run_query(session, payload, filename, delay=0):
    try:
        if delay > 0:
            print(f"Delaying query for {filename} by {delay} seconds...")
            await asyncio.sleep(delay)
        async with session.post(url, headers=headers, json=payload) as response:
            output = await response.text()
            with open(filename, "w") as file:
                file.write(output)
            print(f"Run completed. Output saved to {filename}")
    except asyncio.TimeoutError:
        print(f" Timeout error on query for {filename}")
    except Exception as e:
        print(f" Other error for {filename}: {e}")

# main function: selects queries, handles filenames and execution mode
async def main(mode, deployment, query, base_dir, runs, strategy, sf, number_of_nodes):
    signal_dir = "/scratch/asterixdb_eightynode/asterixdb_eightnode/results"
    for iteration in range(int(runs)):
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        print(f"\n--- Run {iteration + 1} ---")
        time.sleep(1)

        del_dir = os.path.join(signal_dir, "HybridExecution")
        hybrid_dir = os.path.join(base_dir, "HybridExecution")
        os.makedirs(hybrid_dir, exist_ok=True)

        files_to_delete = [
            os.path.join(del_dir, "B2ISignal"),
            os.path.join(del_dir, "I2BSignal"),
            os.path.join(del_dir, "InteractiveAnswers"),
            os.path.join(del_dir, "InteractiveAnswersAll"),
            os.path.join(del_dir, "output_blocking.json"),
            os.path.join(del_dir, "output_interactive.json"),
            os.path.join(del_dir, "GroupBarriers")
        ]

        for path in files_to_delete:
            if os.path.isdir(path):
                shutil.rmtree(path, ignore_errors=True)  # deletes directory and contents
            elif os.path.isfile(path):
                os.remove(path)



        # Base fallback queries (default to q3)
        default_blocking_query = blocking_query_q3
        default_interactive_query = interactive_query_q3


        blocking_query, interactive_query, query_label = resolve_queries(
            query=query,
            sf=str(sf),
            strategy=strategy,
            default_blocking_query=default_blocking_query,
            default_interactive_query=default_interactive_query
        )




        def move_if_exists(src_path: str, dest_path: str, label: str, iteration: int):
            if os.path.exists(src_path):
                try:
                    os.replace(src_path, dest_path)  # atomic if same filesystem
                    print(f"Moved {label}: {src_path} → {dest_path}")
                except Exception:
                    try:
                        shutil.move(src_path, dest_path)
                        print(f"Moved (fallback) {label}: {src_path} → {dest_path}")
                    except Exception as e2:
                        print(f"Failed to move {label}: {src_path} → {dest_path}: {e2}")
            else:
                print(f"No {label} file found for run {iteration + 1}")

        async with aiohttp.ClientSession(timeout=timeout) as session:
            if mode in ["blocking", "interactive"]:
                query_obj = blocking_query if mode == "blocking" else interactive_query
                payloads = [build_payload(query_obj, strategy, False)] * 2
                filenames = [
                    make_filename(base_dir, mode, "A", deployment, timestamp, query, sf, iteration, strategy, args.number_of_nodes),
                    make_filename(base_dir, mode, "B", deployment, timestamp, query, sf, iteration, strategy, args.number_of_nodes)
                ]

            elif mode in ["blocking_single", "interactive_single"]:
                query_obj = blocking_query if "blocking" in mode else interactive_query
                payloads = [build_payload(query_obj, strategy, False)]
                filenames = [
                    make_filename(base_dir, mode, "single", deployment, timestamp, query, sf, iteration, strategy, args.number_of_nodes)
                ]

            else:  # hybrid
                payloads = [
                    build_payload(interactive_query, strategy, True),
                    build_payload(blocking_query, strategy, False)
                ]
                filenames = [
                    make_filename(base_dir, mode, "interactive", deployment, timestamp, query, sf, iteration, strategy, args.number_of_nodes),
                    make_filename(base_dir, mode, "blocking", deployment, timestamp, query, sf, iteration, strategy, args.number_of_nodes)
                ]

            # Run both queries concurrently
            tasks = [run_query(session, payloads[i], filenames[i], delay=0) for i in range(len(payloads))]
            await asyncio.gather(*tasks)

            # --- Post-run file renaming and displacement ---
            interactive_answers_path = os.path.join(signal_dir, "HybridExecution", "InteractiveAnswers")
            if os.path.exists(interactive_answers_path):
                new_name = f"InteractiveAnswers_{query_label}_{timestamp}"
                new_path = os.path.join(signal_dir, "HybridExecution", new_name)
                try:
                    os.rename(interactive_answers_path, new_path)
                    print(f"Renamed {interactive_answers_path} → {new_path}")
                except Exception as e:
                    print(f"Failed to rename {interactive_answers_path}: {e}")
            else:
                print(f"No InteractiveAnswers file found for run {iteration + 1}")

            # --- Move metrics to destination ---
            dest_dir = os.path.join(
                "/scratch/SmartRabbitProfilerOutputFourNode",
                f"{number_of_nodes}N",
                str(sf),
                query_label,
            )
            os.makedirs(dest_dir, exist_ok=True)

            # All metrics to relocate
            metrics = [
                "InteractiveAnswerCount",
                "InteractiveAnswerRate",
                "BlockingAnswerRate",
                "B2I_stdout.log"
            ]
            for metric in metrics:
                src_path = os.path.join(signal_dir, "HybridExecution", metric)
                dest_path = os.path.join(dest_dir, metric_dest_name(metric, query_label, timestamp))
                move_if_exists(src_path, dest_path, metric, iteration)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run AsterixDB queries")
    parser.add_argument("--mode", choices=["hybrid", "blocking", "blocking_single", "interactive", "interactive_single"], default="hybrid")
    parser.add_argument("--deployment", choices=["single", "multi"], default="multi")
    parser.add_argument("--query",
      choices = [
          "q10", "q3", "q1", "q4", "q9", "q12", "q5", "q16", "q8", "q18","q11","q20","q21", "q22","q7","q2",
          "SSB_q21", "SSB_q22", "SSB_q23", "SSB_q31", "SSB_q32", "SSB_q33", "SSB_q34", "SSB_q41", "SSB_q42", "SSB_q43",
          "q1_30", "q3_30", "q4_30", "q5_30", "q8_30", "q9_30", "q10_30", "q12_30", "q16_30", "q18_30",
          "q1_1", "q3_1", "q4_1", "q8_1", "q9_1", "q10_1", "q12_1", "q16_1", "q18_1"
      ],

       default = "q3")

    parser.add_argument("--base-dir", default="/scratch/SmartRabbitProfilerOutputFourNode", help="Base directory for output files and signals")
    parser.add_argument("--runs", default = 3, help = "No of runs you want to run")
    parser.add_argument("--strategy", default = "static", choices= ["dynamic", "static"])
    parser.add_argument("--number-of-nodes", type=int, required=True, help="Number of nodes to prefix in filenames")


    parser.add_argument("--sf", type=int, choices=[1,5,10,30,50,100,300], default=10, help="Scale factor to suffix TPCH table names (default=10)")
args = parser.parse_args()

asyncio.run(main(args.mode, args.deployment, args.query, args.base_dir,
                 args.runs, args.strategy, args.sf, args.number_of_nodes))

