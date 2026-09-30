#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
java -cp out render.software.SoftwareRenderer proof/world_proof.png