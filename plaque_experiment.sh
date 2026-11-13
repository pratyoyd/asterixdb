#!/bin/bash
# PLAQUE M2 Experiment: SF × Propagation Interval
# Query: SELECT MAX(l.l_extendedprice) FROM Lineitem_SF l, Orders_SF o
#        WHERE l.l_orderkey = o.o_orderkey AND o.o_orderdate < '1995-01-01'

URL="http://localhost:19002/query/service"
RUNS=5
SFS="10 30 100"
# Intervals: 1 (eager), 100, 5000 (default), 2147483647 (finish-only), and no-plaque baseline
INTERVALS="off 1 100 5000 2147483647"

echo "SF,mode,interval,run,executionTime_s,result"

for sf in $SFS; do
  for interval in $INTERVALS; do
    for run in $(seq 1 $RUNS); do
      if [ "$interval" = "off" ]; then
        # Baseline: no PLAQUE
        STMT="SELECT MAX(l.l_extendedprice) FROM Lineitem_${sf} l, Orders_${sf} o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderdate < '1995-01-01';"
        mode="baseline"
        iv="N/A"
      else
        STMT="SET \`compiler.plaque.enabled\` \"true\"; SET \`compiler.plaque.propagation.interval\` \"${interval}\"; SELECT MAX(l.l_extendedprice) FROM Lineitem_${sf} l, Orders_${sf} o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderdate < '1995-01-01';"
        mode="plaque"
        iv="$interval"
      fi

      resp=$(curl -s "$URL" --data-urlencode "statement=${STMT}")
      time_s=$(echo "$resp" | grep -oP '"executionTime"\s*:\s*"\K[^"]+' | sed 's/s$//')
      result=$(echo "$resp" | grep -oP '"results".*?\[\s*\{[^}]*\}' | grep -oP ':\K[0-9.]+' | head -1)

      echo "${sf},${mode},${iv},${run},${time_s},${result}"
    done
  done
done
