#!/usr/bin/env python3
"""Real Core HTTP acceptance against explicitly enabled loopback test services only."""
from __future__ import annotations

import argparse
import http.client
import json
import os
import platform
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def instant(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


class Api:
    def __init__(self, base: str, timeout: float):
        self.base, self.timeout = base.rstrip("/"), timeout

    def call(self, method: str, path: str, body=None, token: str | None = None,
             key: str | None = None, headers: dict | None = None) -> dict:
        request_headers = {"Content-Type": "application/json"}
        if token:
            request_headers["Authorization"] = token
        if key:
            request_headers["Idempotency-Key"] = key
        request_headers.update(headers or {})
        request = urllib.request.Request(self.base + path, method=method, headers=request_headers,
                                        data=None if body is None else json.dumps(body).encode())
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                status, data = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, data = error.code, error.read()
        except (urllib.error.URLError, OSError, http.client.HTTPException) as error:
            return {"status": 0, "body": {"transportError": type(error).__name__},
                    "latency_ms": (time.perf_counter() - started) * 1000}
        return {"status": status, "body": json.loads(data) if data else None,
                "latency_ms": (time.perf_counter() - started) * 1000}


def test_login(api: Api, phone: str, redis_port: int) -> str:
    issued = api.call("POST", "/user/code?" + urllib.parse.urlencode({"phone": phone}))
    require(issued["status"] == 200 and issued["body"].get("success"), "Test login code was not issued")
    key = ("login:code:" + phone).encode()
    with socket.create_connection(("127.0.0.1", redis_port), timeout=5) as connection:
        connection.sendall(b"*2\r\n$3\r\nGET\r\n$" + str(len(key)).encode() + b"\r\n" + key + b"\r\n")
        with connection.makefile("rb") as stream:
            line = stream.readline()
            require(line.startswith(b"$") and int(line[1:]) > 0, "Test Redis contains no login code")
            code = stream.read(int(line[1:])).decode()
    result = api.call("POST", "/user/login", {"phone": phone, "code": code})
    require(result["status"] == 200 and result["body"].get("success"), "Test login failed")
    return result["body"]["data"]


def identity(api: Api, token: str) -> dict:
    result = api.call("GET", "/user/me", token=token)
    require(result["status"] == 200 and result["body"].get("data"), "Test token has no authenticated identity")
    return result["body"]["data"]


class DropCommittedResponse:
    """Forward one HOLD, observe Core's completed response, then send the caller zero response bytes."""
    def __init__(self, upstream: str, timeout: float):
        target = urllib.parse.urlsplit(upstream)
        self.observed = None
        self.failure = None
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_POST(self):
                try:
                    require(self.path == "/bookings/holds", "Drop proxy only forwards a test HOLD")
                    body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                    headers = {name: self.headers[name] for name in ("Authorization", "Idempotency-Key", "Content-Type") if name in self.headers}
                    connection = http.client.HTTPConnection(target.hostname, target.port, timeout=timeout)
                    try:
                        connection.request("POST", target.path.rstrip("/") + self.path, body, headers)
                        response = connection.getresponse()
                        data = response.read()
                        owner.observed = {"status": response.status, "body": json.loads(data)}
                    finally:
                        connection.close()
                except Exception as error:
                    owner.failure = type(error).__name__ + ": " + str(error)
                finally:
                    # No send_response/write occurs, after the upstream transaction has already returned.
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.server.timeout = timeout + 5
        self.thread = threading.Thread(target=self.server.handle_request, daemon=True)
        self.api = Api("http://127.0.0.1:" + str(self.server.server_port), timeout + 5)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_args):
        self.thread.join(self.server.timeout + 1)
        self.server.server_close()


def slot_state(api: Api, token: str, resource: dict, slot: dict) -> dict:
    query = urllib.parse.urlencode({"dateFrom": slot["startAt"], "dateTo": slot["endAt"]})
    result = api.call("GET", f'/booking-resources/{resource["id"]}/slots?{query}', token=token)
    require(result["status"] == 200, "Could not read real slot capacity")
    return next(value for value in result["body"] if value["id"] == slot["id"])


def summarize_response(result: dict) -> dict:
    body = result.get("body") or {}
    return {"httpStatus": result["status"], "code": body.get("code"), "latencyMs": round(result["latency_ms"], 3)}


def run(args) -> dict:
    require(args.test_only, "This runner requires --test-only and isolated loopback services")
    address = urllib.parse.urlsplit(args.base_url)
    require(address.scheme == "http" and address.hostname in ("127.0.0.1", "localhost", "::1")
            and not address.username and not address.password, "Only loopback HTTP test services are allowed")
    fixtures = json.loads(Path(args.fixture).read_text(encoding="utf-8"))
    fixture = next(item for item in fixtures if item["resource"]["id"] == args.resource_id)
    resource = fixture["resource"]
    require(resource["kind"] == "DINING_POOL" and fixture["offer"], "Use a dining fixture with a published fixed package")
    slot = fixture["slots"][0]
    require(instant(slot["startAt"]) > datetime.now(timezone.utc), "The selected test slot must be in the future")
    api = Api(args.base_url, args.timeout)
    token = os.environ.get(args.token_env) or test_login(api, args.test_phone, args.redis_code_port)
    current = identity(api, token)
    require(str(current["id"]) == args.expected_user_id and current.get("role") == "user", "Primary identity must be the expected ordinary test user")
    other_token = os.environ.get(args.other_token_env) or test_login(api, args.other_test_phone, args.redis_code_port)
    other = identity(api, other_token)
    require(other["id"] != current["id"] and other.get("role") == "user", "The isolation probe needs a different ordinary test user")
    run_id = "api-acceptance-" + uuid.uuid4().hex
    report = {"testOnly": True, "runId": run_id, "observedAt": datetime.now(timezone.utc).isoformat(),
              "environment": args.environment, "baseUrl": api.base, "python": platform.python_version(),
              "fixture": {"userId": str(current["id"]), "otherUserId": str(other["id"]), "shopId": resource["shopId"],
                          "resourceId": resource["id"], "slotId": slot["id"], "startAt": slot["startAt"]},
              "security": {}, "lostResponse": {}, "cleanup": {"completed": False},
              "limitations": ["Local isolated test services; no production throughput claim."]}
    hold_key = run_id + ":hold"
    possible_keys = [run_id + ":anonymous", hold_key]
    booking_id = None
    baseline = slot_state(api, token, resource, slot)
    report["capacityBefore"] = baseline["occupied"]
    try:
        offers = api.call("GET", f'/booking-resources/{resource["id"]}/offers', token=token)
        require(offers["status"] == 200, "Published package query failed")
        offer = next(value for value in offers["body"] if value["id"] == fixture["offer"]["id"] and value["status"] == "OPEN")
        quote_request = {"resourceId": resource["id"], "slotId": slot["id"], "partySize": 2, "offerId": offer["id"],
                         "couponMode": "AUTO", "couponIds": [], "purpose": "CREATE", "source": "API_ACCEPTANCE", "requestId": run_id}
        quote_result = api.call("POST", "/booking-quotes", quote_request, token)
        require(quote_result["status"] == 200, "Actual personal quote did not succeed")
        quote = quote_result["body"]
        require(quote["baseCents"] == offer["unitPriceCents"] * 2 and 0 <= quote["totalCents"] <= quote["baseCents"], "Personal package price contract failed")
        report["personalQuote"] = {"baseCents": quote["baseCents"], "totalCents": quote["totalCents"], "partySize": 2}
        hold = {"resourceId": resource["id"], "slotId": slot["id"], "partySize": 2, "quoteId": quote["quoteId"],
                "source": quote["source"], "requestId": quote["requestId"]}
        forged = {"X-CityCopilot-User-Id": str(current["id"]), "X-CityCopilot-Admin": "true", "X-CityCopilot-Platform-Admin": "true"}
        anonymous = api.call("POST", "/bookings/holds", hold, key=possible_keys[0], headers=forged)
        report["security"]["anonymousForgedIdentity"] = summarize_response(anonymous)
        require(anonymous["status"] == 401, "Anonymous forged identity headers were not rejected with 401")
        require(slot_state(api, token, resource, slot)["occupied"] == baseline["occupied"], "Anonymous attempt changed capacity")

        with DropCommittedResponse(api.base, args.timeout) as proxy:
            lost = proxy.api.call("POST", "/bookings/holds", hold, token, hold_key)
        require(proxy.failure is None, "Drop proxy failed before observing the upstream result")
        upstream = proxy.observed
        require(upstream and 200 <= upstream["status"] < 300 and upstream["body"].get("booking", {}).get("status") == "HELD",
                "Cannot claim a committed response loss without observing Core HOLD success")
        booking_id = upstream["body"]["booking"]["id"]
        require(lost["status"] == 0, "The caller unexpectedly received a proxy response")
        command = api.call("GET", "/booking-commands/" + hold_key, token=token)
        require(command["status"] == 200 and command["body"]["bookingId"] == booking_id, "Command recovery did not return the committed booking")
        replay = api.call("POST", "/bookings/holds", hold, token, hold_key)
        require(200 <= replay["status"] < 300 and replay["body"]["booking"]["id"] == booking_id, "HOLD retry did not replay the same booking")
        require(slot_state(api, token, resource, slot)["occupied"] == baseline["occupied"] + 2, "A repeated HOLD consumed capacity more than once")
        report["lostResponse"] = {"upstreamHttpStatus": upstream["status"], "upstreamBookingStatus": "HELD",
                                  "callerHttpStatus": lost["status"], "callerTransportError": lost["body"].get("transportError"),
                                  "observedCommittedResponseBeforeDisconnect": True, "bookingId": booking_id,
                                  "commandReturnedSameBooking": True, "retryReturnedSameBooking": True,
                                  "capacityDeltaAfterReplay": 2}

        held = replay["body"]["booking"]
        own = api.call("GET", "/bookings/" + booking_id, token=token, headers={"X-CityCopilot-User-Id": str(other["id"])})
        cross = api.call("GET", "/bookings/" + booking_id, token=other_token, headers=forged)
        cross_command = api.call("GET", "/booking-commands/" + hold_key, token=other_token)
        merchant = api.call("POST", f"/admin/bookings/{booking_id}/cancel", {"expectedVersion": held["version"], "reason": "unauthorized acceptance probe"},
                            token, run_id + ":forbidden-merchant", forged)
        report["security"].update(ownRead=summarize_response(own), otherRead=summarize_response(cross),
                                  otherCommandRead=summarize_response(cross_command), ordinaryUserMerchantWrite=summarize_response(merchant))
        require(own["status"] == 200 and own["body"]["userId"] == str(current["id"]), "Authenticated identity was replaced by a caller supplied header")
        require(cross["status"] in (403, 404) and cross_command["status"] in (403, 404), "Another user could retrieve a booking or command")
        require(merchant["status"] == 403, "Ordinary user was not rejected at the merchant write boundary")
        confirmed = api.call("POST", f"/bookings/{booking_id}/confirm", {"expectedVersion": held["version"]}, token, run_id + ":confirm")
        require(confirmed["status"] == 200 and confirmed["body"]["booking"]["status"] == "CONFIRMED", "Recovered booking did not confirm")
        cancelled = api.call("POST", f"/bookings/{booking_id}/cancel", {"expectedVersion": confirmed["body"]["booking"]["version"], "reason": "API acceptance completed"}, token, run_id + ":cancel")
        require(cancelled["status"] == 200 and cancelled["body"]["booking"]["status"] == "CANCELLED", "Confirmed acceptance booking did not cancel")
        require(slot_state(api, token, resource, slot)["occupied"] == baseline["occupied"], "Cancellation did not restore capacity")
        report["lostResponse"].update(confirmed=True, cancelled=True)

        report["passed"] = True
    except Exception as error:
        report["passed"] = False
        report["failure"] = {"type": type(error).__name__, "message": str(error)}
    finally:
        recovered = {booking_id} if booking_id else set()
        for key in possible_keys:
            command = api.call("GET", "/booking-commands/" + key, token=token)
            if command["status"] == 200 and command["body"].get("bookingId"):
                recovered.add(command["body"]["bookingId"])
        cleanup_failures = []
        for created_id in recovered:
            current_booking = api.call("GET", "/bookings/" + created_id, token=token)
            if current_booking["status"] != 200:
                cleanup_failures.append(created_id)
                continue
            view = current_booking["body"]
            if view["status"] in ("HELD", "CONFIRMED"):
                cancellation = api.call("POST", f"/bookings/{created_id}/cancel", {"expectedVersion": view["version"], "reason": "API acceptance cleanup"}, token, run_id + ":cleanup:" + created_id)
                if cancellation["status"] != 200:
                    cleanup_failures.append(created_id)
        after = slot_state(api, token, resource, slot)["occupied"]
        report["cleanup"] = {"completed": not cleanup_failures and after == baseline["occupied"], "unreleasedBookingIds": cleanup_failures,
                             "capacityBefore": baseline["occupied"], "capacityAfter": after, "createdBookingIds": sorted(recovered)}
        if not report["cleanup"]["completed"]:
            report["passed"] = False
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test-only", action="store_true")
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--environment", required=True, help="Label the isolated test database/service environment")
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--resource-id", default="3")
    parser.add_argument("--test-phone", default="19900100298")
    parser.add_argument("--expected-user-id", default="100298")
    parser.add_argument("--other-test-phone", default="19900100000")
    parser.add_argument("--redis-code-port", type=int, default=61380, help="Test-only loopback Redis containing login verification codes")
    parser.add_argument("--token-env", default="CITYCOPILOT_BOOKING_API_TOKEN")
    parser.add_argument("--other-token-env", default="CITYCOPILOT_BOOKING_API_OTHER_TOKEN")
    parser.add_argument("--timeout", type=float, default=20)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    report = run(args)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"passed": report["passed"], "output": str(output), "cleanupCompleted": report["cleanup"]["completed"]}))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
