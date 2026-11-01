#!/usr/bin/env python3
"""Assert the server stored a truncated text: the plugin's note is there and it fits the limit the API enforced."""
import json
import sys

state, limit = sys.argv[1], int(sys.argv[2])
run = list(json.load(open(state, encoding="utf-8"))["runs"].values())[0]
text = run["output"]["text"] or ""
assert "Output truncated; original size was" in text, text[-160:]
assert len(text) <= limit, (len(text), limit)
print("stored text: %d chars, truncated note present" % len(text))
