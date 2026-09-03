#!/bin/bash
# Full CGM cadence analysis: real 5-minute era vs real 1-minute era, no decimation.
set -e
cd "$(dirname "$0")"
for s in 01_profile 02_variogram 03_forecast 04_events 05_reporting_delay 06_acceleration 07_meal_climbs; do
  echo "=== $s ==="
  python3 $s.py
done
echo "=== 08_report ==="
python3 08_report.py
python3 09_style_check.py
