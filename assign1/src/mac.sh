#!/bin/bash
# This script discovers cache sizes on macOS using sysctl.

echo "Discovering cache sizes on macOS..."

# Query L1 Data Cache size
L1D=$(sysctl -n hw.l1dcachesize 2>/dev/null)
if [ -z "$L1D" ]; then
  echo "L1 Data Cache size not available."
else
  echo "L1 Data Cache: $L1D bytes"
fi

# Query L1 Instruction Cache size
L1I=$(sysctl -n hw.l1icachesize 2>/dev/null)
if [ -z "$L1I" ]; then
  echo "L1 Instruction Cache size not available."
else
  echo "L1 Instruction Cache: $L1I bytes"
fi

# Query L2 Cache size
L2=$(sysctl -n hw.l2cachesize 2>/dev/null)
if [ -z "$L2" ]; then
  echo "L2 Cache size not available."
else
  echo "L2 Cache: $L2 bytes"
fi

# Query L3 Cache size, if available
L3=$(sysctl -n hw.l3cachesize 2>/dev/null)
if [ -z "$L3" ]; then
  echo "L3 Cache not available."
else
  echo "L3 Cache: $L3 bytes"
fi
