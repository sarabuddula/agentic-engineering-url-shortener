#!/usr/bin/env python3
"""Reconstruct a demonstration run from its committed export alone (T092; US-005, SC-006).

Usage: python3 scripts/reconstruct-run.py docs/evidence/runs/<run-id>.json

Needs only the repository: no service, no database, no third-party packages. It
  1. recomputes the run's SHA-256 audit hash chain from the exported history, with the same canonical form as
     AuditChain.hash (each field length-prefixed, "~" for an absent field), and compares the head with the exported
     /verify result;
  2. rebuilds the run from the history: final node states, revisions, approvals and who decided them, decisions with
     their rationale, retries, failures, recoveries, replanning and the terminal outcome.
The history endpoint returns an absent affected/reason as "", which the chain hashed as absent ("~").
"""
import hashlib
import json
import sys

GENESIS = "0" * 64


def canonical(run_id, seq, prev, e, affected, reason):
    out = []
    for field in (run_id, str(seq), prev, e["actor_type"], e["action"], e["occurred_at"], affected, e["result"], reason):
        out.append("~;" if field is None else f"{len(field)}:{field};")
    return hashlib.sha256("".join(out).encode("utf-8")).hexdigest()


def recompute(run_id, history):
    prev = GENESIS
    for e in history:
        # the history endpoint returns an absent affected/reason as ""; the chain hashed it as absent
        prev = canonical(run_id, e["seq"], prev, e, e.get("affected") or None, e.get("reason") or None)
    return prev


def main(path):
    export = json.load(open(path))
    run, history, verify = export["run"], export["history"], export["verify"]
    run_id = run["run_id"]
    seqs = [e["seq"] for e in history]
    print(f"run {run_id}: {len(history)} events, sequence 1..{seqs[-1]} contiguous={seqs == list(range(1, len(seqs) + 1))}")
    head = recompute(run_id, history)
    print(f"recomputed head {head[:16]}...  exported /verify head {verify.get('head_hash', '')[:16]}...  "
          f"match={head == verify.get('head_hash')}  exported verified={verify.get('verified')} event_count={verify.get('event_count')}")

    nodes, revisions, approvals, decisions, retries, failures, recovered, replans = {}, [], [], [], [], [], [], []
    for e in history:
        a, res, reason = e["action"], e["result"], e.get("reason", "")
        if a == "NODE_TRANSITION":
            nodes[e["affected"]] = res.split("->")[-1]
            if res.endswith("->RETRY_WAITING"):
                retries.append(f"{e['affected']}: {reason[:80]}")
            if res.endswith("->FAILED"):
                failures.append(f"{e['affected']}: {reason[:80]}")
        elif a == "REVISION_ACTIVATED":
            revisions.append(res)
            nodes = {}  # node states of the new revision follow; carried-over nodes are restated below
        elif a in ("APPROVAL_DECIDED", "APPROVAL_EXPIRED", "APPROVAL_ESCALATED"):
            approvals.append(f"{a} {res}: {reason[:110]}")
        elif a == "DECISION_RECORDED":
            decisions.append(f"{res}: {reason[:110]}")
        elif a.startswith("REPLAN_"):
            replans.append(f"{a}: {reason[:110]}")
        elif a in ("RUN_RESUMED", "BOOT_RECOVERY", "OPERATION_RECONCILED"):
            recovered.append(f"{a} {res}: {reason[:100]}")
    terminal = [e for e in history if e["action"] == "RUN_TRANSITION" and e["result"].split("->")[-1] in ("COMPLETED", "TERMINATED")]
    print(f"revisions activated: {revisions or 'none'}")
    print("approvals:"), [print("  " + x) for x in approvals]
    print("decisions:"), [print("  " + x) for x in decisions]
    print("replanning:"), [print("  " + x) for x in replans or ["none"]]
    print("interruptions and recovery:"), [print("  " + x) for x in recovered or ["none"]]
    print(f"retries: {retries or 'none'}")
    print(f"failures: {failures or 'none'}")
    print(f"node states after the last revision: {dict(sorted(nodes.items()))}")
    print(f"terminal transition: {terminal[-1]['result'] if terminal else 'none'}; exported status {run['status']}, "
          f"terminal outcome {run.get('terminal_outcome')}, execution class {run['execution_class']}")
    print(f"last event: {history[-1]['action']} {history[-1]['result']}")


if __name__ == "__main__":
    main(sys.argv[1])
