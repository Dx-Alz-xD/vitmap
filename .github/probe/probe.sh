#!/usr/bin/env bash
# Checks the ML Kit subject segmentation artifact for the Photo Editor.
set -u
for a in play-services-mlkit-subject-segmentation; do
  echo "=== $a"
  curl -sS --max-time 30 "https://dl.google.com/dl/android/maven2/com/google/android/gms/$a/maven-metadata.xml" | grep -o "<version>[^<]*</version>" | tail -5
done
v=$(curl -sS --max-time 30 "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-mlkit-subject-segmentation/maven-metadata.xml" | grep -o "<latest>[^<]*</latest>" | sed 's/<[^>]*>//g')
echo "latest=$v"
curl -sS --max-time 30 "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-mlkit-subject-segmentation/$v/play-services-mlkit-subject-segmentation-$v.pom" | grep -A3 "<dependency>" | grep -E "artifactId|version" | paste - - | head -20
