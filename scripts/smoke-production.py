"""Read-only production smoke check. Source .env before invoking."""

import base64
import http.cookiejar
import json
import os
from pathlib import Path
import urllib.request


origin = os.environ["APP_ORIGIN"].rstrip("/")
password = os.environ.get("ADMIN_PASSWORD")
if not password:
    access_file = Path(os.environ.get("ADMIN_ACCESS_FILE", "/root/commitnotes-admin-access.txt"))
    password = next(line.removeprefix("Senha inicial: ") for line in access_file.read_text().splitlines() if line.startswith("Senha inicial: "))
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))


def request(path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    headers = {} if data is None else {"Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest"}
    with client.open(urllib.request.Request(origin + path, data=data, headers=headers), timeout=15) as response:
        return response, response.read()


response, page = request("/")
assert response.status == 200 and b"CommitNotes" in page
response, body = request("/healthz")
assert response.status == 200 and json.loads(body)["status"] == "UP"
response, body = request("/api/auth/login", {
    "email": os.environ["ADMIN_EMAIL"],
    "password": password,
})
assert response.status == 200 and json.loads(body)["platform_admin"] is True
cookie = response.headers.get("Set-Cookie", "")
assert "Secure" in cookie and "HttpOnly" in cookie and "SameSite=Strict" in cookie
response, body = request("/api/admin/users")
assert response.status == 200 and any(user["email"] == os.environ["ADMIN_EMAIL"] for user in json.loads(body))
response, body = request("/api/push/key")
key = json.loads(body)["publicKey"]
assert len(base64.urlsafe_b64decode(key + "=" * (-len(key) % 4))) == 65
print("home=200 health=UP admin_login=200 admin_api=200 secure_cookie=yes vapid_key=valid")
