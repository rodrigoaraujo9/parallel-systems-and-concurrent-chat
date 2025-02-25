#!/bin/bash
# This script discovers cache sizes on a Linux system using sysfs.
# It works on most modern Linux distributions that expose cache information
# under /sys/devices/system/cpu/cpu0/cache/

echo "Discovering cache sizes on Linux (CPU 0):"

for cache_dir in /sys/devices/system/cpu/cpu0/cache/index*; do
    level=$(cat "$cache_dir/level")
    type=$(cat "$cache_dir/type")
    size=$(cat "$cache_dir/size")
    # Some systems may also include "shared_cpu_list" for shared caches.
    shared=$(cat "$cache_dir/shared_cpu_list" 2>/dev/null)

    echo "L${level} ${type} Cache: ${size} (shared on CPU(s): ${shared})"
done
