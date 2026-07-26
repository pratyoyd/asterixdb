#!/usr/bin/env python3
import subprocess
import argparse
import json
import urllib.parse

SCALE_FACTORS = [1, 5, 10, 30, 50, 100, 300]
QUERY_URL = "http://localhost:19002/query/service"

def run_curl_json(statement: str):
    payload = json.dumps({"statement": statement})
    cmd = [
        "curl", "-s", "-X", "POST", QUERY_URL,
        "-H", "Content-Type: application/json",
        "-d", payload
    ]
    subprocess.run(cmd, check=False)

def run_curl_form(statement: str):
    body = urllib.parse.urlencode({"statement": statement})
    cmd = [
        "curl", "-s", "-X", "POST", QUERY_URL,
        "-H", "Content-Type: application/x-www-form-urlencoded",
        "-d", body
    ]
    subprocess.run(cmd, check=False)

def ds_name(base: str, sf: int, cs: bool):
    return f"{base}{'_column' if cs else ''}_{sf}"

def type_name(base_type: str, sf: int, cs: bool):
    # e.g., CustomerType_30 vs CustomerType_column_30
    return f"{base_type}{'_column' if cs else ''}_{sf}"

def create_dataset_stmt(dataset_name: str, type_name_str: str, pk: str, cs: bool):
    if cs:
        return (
            f'CREATE DATASET {dataset_name} ({type_name_str}) PRIMARY KEY {pk} WITH '
            f'{{ "storage-format": {{ "format": "column" }} }};'
        )
    else:
        return f"CREATE DATASET {dataset_name} ({type_name_str}) PRIMARY KEY {pk};"

def make_statements(sf: int, cs: bool):
    stmts = []

    # Customer
    t = type_name("CustomerType", sf, cs)
    d = ds_name("Customer", sf, cs)
    stmts.append(
        f"CREATE TYPE {t} AS OPEN {{ c_custkey: int64, c_name: string, c_address: string, "
        f"c_nationkey: int64, c_phone: string, c_acctbal: double, c_mktsegment: string, c_comment: string }};"
    )
    stmts.append(create_dataset_stmt(d, t, "c_custkey", cs))

    # Lineitem
    t = type_name("LineitemType", sf, cs)
    d = ds_name("Lineitem", sf, cs)
    stmts.append(
        f"CREATE TYPE {t} AS OPEN{{ l_orderkey: int64, l_partkey: int64, l_suppkey: int64, "
        f"l_linenumber: int64, l_quantity: double, l_extendedprice: double, l_discount: double, "
        f"l_tax: double, l_returnflag: string, l_linestatus: string, l_shipdate: string, "
        f"l_commitdate: string, l_receiptdate: string, l_shipinstruct: string, l_shipmode: string, "
        f"l_comment: string }};"
    )
    stmts.append(create_dataset_stmt(d, t, "l_orderkey, l_linenumber", cs))

    # Nation
    t = type_name("NationType", sf, cs)
    d = ds_name("Nation", sf, cs)
    stmts.append(f"CREATE TYPE {t} AS OPEN{{ n_nationkey: int64, n_name: string, n_regionkey: int64, n_comment: string }};")
    stmts.append(create_dataset_stmt(d, t, "n_nationkey", cs))

    # Orders
    t = type_name("OrdersType", sf, cs)
    d = ds_name("Orders", sf, cs)
    stmts.append(
        f"CREATE TYPE {t} AS OPEN{{ o_orderkey: int64, o_custkey: int64, o_orderstatus: string, "
        f"o_totalprice: double, o_orderdate: string, o_orderpriority: string, o_clerk: string, "
        f"o_shippriority: int64, o_comment: string }};"
    )
    stmts.append(create_dataset_stmt(d, t, "o_orderkey", cs))

    # Part
    t = type_name("PartType", sf, cs)
    d = ds_name("Part", sf, cs)
    stmts.append(
        f"CREATE TYPE {t} AS OPEN{{ p_partkey: int64, p_name: string, p_mfgr: string, p_brand: string, "
        f"p_type: string, p_size: int64, p_container: string, p_retailprice: double, p_comment: string }};"
    )
    stmts.append(create_dataset_stmt(d, t, "p_partkey", cs))

    # Partsupp
    t = type_name("PartsuppType", sf, cs)
    d = ds_name("Partsupp", sf, cs)
    stmts.append(
        f"CREATE TYPE {t} AS OPEN{{ ps_partkey: int64, ps_suppkey: int64, ps_availqty: int64, "
        f"ps_supplycost: double, ps_comment: string }};"
    )
    stmts.append(create_dataset_stmt(d, t, "ps_partkey, ps_suppkey", cs))

    # Region
    t = type_name("RegionType", sf, cs)
    d = ds_name("Region", sf, cs)
    stmts.append(f"CREATE TYPE {t} AS OPEN{{ r_regionkey: int64, r_name: string, r_comment: string }};")
    stmts.append(create_dataset_stmt(d, t, "r_regionkey", cs))

    # Supplier
    t = type_name("SupplierType", sf, cs)
    d = ds_name("Supplier", sf, cs)
    stmts.append(
        f"CREATE TYPE {t} AS OPEN{{ s_suppkey: int64, s_name: string, s_address: string, "
        f"s_nationkey: int64, s_phone: string, s_acctbal: double, s_comment: string }};"
    )
    stmts.append(create_dataset_stmt(d, t, "s_suppkey", cs))

    return stmts

def make_indexes(sf: int, cs: bool):
    orders_ds = ds_name("Orders", sf, cs)
    return [
        f"DROP INDEX {orders_ds}.idx_o_custkey IF EXISTS;",
        f"CREATE INDEX idx_o_custkey ON {orders_ds}(o_custkey) TYPE BTREE;"
    ]

def load_one(sf: int, base_path: str, cs: bool):
    files = {
        "Customer": "customer.tbl",
        "Lineitem": "lineitem.tbl",
        "Nation": "nation.tbl",
        "Orders": "orders.tbl",
        "Part": "part.tbl",
        "Partsupp": "partsupp.tbl",
        "Region": "region.tbl",
        "Supplier": "supplier.tbl"
    }

    mode = "COLUMN" if cs else "ROW"
    print(f"\n=== Loading SF={sf} ({mode}-STORE) from {base_path} ===")

    # Drop ONLY the target mode datasets + types
    for base in files.keys():
        run_curl_json(f"DROP DATASET {ds_name(base, sf, cs)} IF EXISTS;")

    for base_type in ["CustomerType", "LineitemType", "NationType", "OrdersType",
                      "PartType", "PartsuppType", "RegionType", "SupplierType"]:
        run_curl_json(f"DROP TYPE {type_name(base_type, sf, cs)} IF EXISTS;")

    # Create (types + datasets for this mode)
    for stmt in make_statements(sf, cs):
        run_curl_json(stmt)

    # Load into this mode datasets
    for base, filename in files.items():
        dataset = ds_name(base, sf, cs)
        full_path = f"asterix_nc1://{base_path}/{filename}"
        load_stmt = (
            f'LOAD DATASET {dataset} USING localfs (("path"="{full_path}"), '
            f'("format"="delimited-text"), ("delimiter"="|"));'
        )
        run_curl_form(load_stmt)

    # Indexes for this mode
    for stmt in make_indexes(sf, cs):
        run_curl_json(stmt)

def main(sfs, base_path, all_flag, cs: bool):
    if all_flag:
        sfs = SCALE_FACTORS
    for sf in sfs:
        bp = base_path or f"/scratch/tpch_data/sf{sf}"
        load_one(sf, bp, cs)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Load TPC-H datasets into AsterixDB")
    parser.add_argument("--sf", type=int, nargs="+", default=[10], choices=SCALE_FACTORS)
    parser.add_argument("--base-path", help="Override dataset base path")
    parser.add_argument("--all", action="store_true", help="Load data for all scale factors")
    parser.add_argument("--cs", action="store_true", help="Create column-store datasets (names like Orders_column_<sf>)")
    args = parser.parse_args()
    main(args.sf, args.base_path, args.all, args.cs)
