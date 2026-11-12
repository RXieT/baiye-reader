#!/usr/bin/env python3
"""Opt-in anonymous integration check. Never logs build configuration or book text."""
import base64
import hashlib
import hmac
import os
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

key = os.environ.get("WENKU_RELAY_BUILD_KEY", "")
if not key:
    raise SystemExit("WENKU_RELAY_BUILD_KEY is required")


def request(query):
    version = "1.30"
    message = f"{version}|{key}|{int(time.time()) // 60}"
    digest = hmac.new(key.encode(), message.encode(), hashlib.sha256).hexdigest()
    body = urllib.parse.urlencode({
        "appver": f"{version}-{key}-{digest[12:20]}",
        "request": base64.b64encode(query.encode()).decode(),
        "timetoken": str(int(time.time() * 1000)),
    }).encode()
    req = urllib.request.Request("https://wenku8-relay.mewx.org/", data=body,
                                 headers={"User-Agent": "Baiye/1.0 Android"})
    with urllib.request.urlopen(req, timeout=30) as response:
        assert response.status == 200
        return response.read(8 * 1024 * 1024).decode("utf-8")


for order in ("lastupdate", "allvisit", "fullflag"):
    root = ET.fromstring(request(f"action=novellist&sort={order}&page=1&t=0"))
    items = root.findall(".//item")
    assert items and all(item.get("aid", "").isdigit() for item in items)
    print(f"catalog {order}: {len(items)} entries")
    time.sleep(0.3)

metadata = ET.fromstring(request("action=book&do=meta&aid=1306&t=0"))
assert metadata.find(".//data[@name='Title']") is not None
print("metadata: OK")
toc_text = request("action=book&do=list&aid=1306&t=0")
toc = ET.fromstring(toc_text)
chapters = toc.findall(".//chapter")
assert chapters and chapters[0].get("cid", "").isdigit()
print(f"toc: {len(chapters)} chapters")
intro = request("action=book&do=intro&aid=1306&t=0")
assert intro.strip()
print("intro: OK")
chapter = request(f"action=book&do=text&aid=1306&cid={chapters[0].get('cid')}&t=0")
assert chapter.strip() and not chapter.lstrip().lower().startswith(("<!doctype", "<html"))
print(f"chapter: OK, image markers: {chapter.count('<!--image-->')}")
