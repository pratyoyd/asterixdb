#!/bin/bash
URL="http://localhost:19002/query/service"
SF=50
RUNS=5
OUTFILE="/scratch/asterixdb_eightynode/asterixdb_eightnode/plaque/plaque_results_sf50.csv"

echo "=== SF${SF} warmup ==="
curl -s "$URL" --data-urlencode "statement=SELECT MAX(l.l_extendedprice) FROM Lineitem_${SF} l, Orders_${SF} o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderdate < '1995-01-01';" | grep -oP '"executionTime"\s*:\s*"\K[^"]+'

echo "SF,mode,interval,run,executionTime_s,result" > "$OUTFILE"

for interval in off 1 100 5000 2147483647; do
  for run in $(seq 1 $RUNS); do
    if [ "$interval" = "off" ]; then
      STMT="SELECT MAX(l.l_extendedprice) FROM Lineitem_${SF} l, Orders_${SF} o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderdate < '1995-01-01';"
      mode="baseline"
      iv="N/A"
    else
      STMT="SET \`compiler.plaque.enabled\` \"true\"; SET \`compiler.plaque.propagation.interval\` \"${interval}\"; SELECT MAX(l.l_extendedprice) FROM Lineitem_${SF} l, Orders_${SF} o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderdate < '1995-01-01';"
      mode="plaque"
      iv="$interval"
    fi

    resp=$(curl -s "$URL" --data-urlencode "statement=${STMT}")
    time_s=$(echo "$resp" | grep -oP '"executionTime"\s*:\s*"\K[^"]+' | sed 's/s$//')
    result=$(echo "$resp" | grep -oP '\$1[^0-9]*\K[0-9.]+' | head -1)
    line="${SF},${mode},${iv},${run},${time_s},${result}"
    echo "$line"
    echo "$line" >> "$OUTFILE"
  done
done

echo "=== Done ==="
