#!/usr/bin/env python3
"""Write N annotations to a file, for the "more than 50 per request" case."""
import json
import sys

path, count = sys.argv[1], int(sys.argv[2])
levels = ["notice", "warning", "failure"]
items = [{"path": "src/f%03d.c" % i, "startLine": i + 1, "endLine": i + 1,
          "level": levels[i % 3], "message": "finding %d" % i} for i in range(count)]
json.dump(items, open(path, "w", encoding="utf-8"), indent=1)
print("%d annotations -> %s" % (count, path))
