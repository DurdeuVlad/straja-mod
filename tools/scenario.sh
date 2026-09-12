#!/usr/bin/env bash
# End-to-end duty scenario against the dev server via RCON.
# Requires: server running, testing.enableTestCommands=true, environment=local.
set -u
cd "$(dirname "$0")/.."

rcon() { python tools/rcon.py "$1"; }

step() { echo; echo "=== $1 ==="; }

# Advance virtual time while keeping the player AFK-safe (move every <90s).
active_wait() {
    local id="$1" secs="$2" base_x="$3" y="$4" z="$5"
    local i=0
    while [ "$secs" -gt 0 ]; do
        i=$((i + 1))
        local s=70
        [ "$secs" -lt 70 ] && s="$secs"
        rcon "straja test move $id $((base_x + i)) $y $z" > /dev/null
        rcon "straja test advance-time $s" > /dev/null
        secs=$((secs - s))
    done
}

step "setup: commissioner + checkpoints"
rcon "straja test create-player com"
rcon "straja test set-commissioner com"
i=0
for pos in "0 0 0" "10 64 10" "20 64 20" "30 64 30"; do
    i=$((i + 1))
    rcon "straja test move com $pos" > /dev/null
    rcon "straja test set-checkpoint com checkpoint_$i"
    rcon "straja test set-mission-time com checkpoint_$i 5"
done
rcon "straja test tell-log com" | tail -4

step "guard on duty"
rcon "straja test create-player g1"
rcon "straja test set-rank g1 2"
rcon "straja test start-duty g1"
rcon "straja test tell-log g1" | head -3
rcon "straja test assert g1 duty true"

step "patrol: cp1 -> cp4"
positions=("0 0 0" "10 64 10" "20 64 20" "30 64 30")
for n in 1 2 3 4; do
    read -r x y z <<< "${positions[$((n - 1))]}"
    rcon "straja test teleport g1 $x $y $z" > /dev/null
    rcon "straja test checkpoint g1 checkpoint_$n"
    if [ "$n" -lt 4 ]; then
        # wait out the 10-minute unlock while staying AFK-safe
        read -r nx ny nz <<< "${positions[$n]}"
        active_wait g1 660 "$nx" "$ny" "$nz"
    fi
done
rcon "straja test tell-log g1" | head -6
rcon "straja test assert g1 duty false"

step "salary payout (real adys_decorations coins)"
rcon "straja test dump-state g1"
rcon "straja test salary g1"
rcon "straja test inventory g1"
