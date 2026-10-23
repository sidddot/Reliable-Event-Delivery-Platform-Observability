import hmac
import hashlib
import os
import time
import random
from typing import Optional, Dict, Any, List
from fastapi import FastAPI, Request, HTTPException, Response, status
from pydantic import BaseModel

app = FastAPI(title="Webhook Mock Receiver", version="1.0.0")

# Runtime Configuration
RECEIVER_MODE = os.getenv("RECEIVER_MODE", "healthy").lower()
RECEIVER_SECRET = os.getenv("RECEIVER_SECRET", "whsec_test_secret")
SLOW_DELAY_SECONDS = float(os.getenv("SLOW_DELAY_SECONDS", "2.0"))
FLAKY_FAILURE_RATE = float(os.getenv("FLAKY_FAILURE_RATE", "0.5"))
MAX_AGE_SECONDS = int(os.getenv("MAX_AGE_SECONDS", "300")) # 5 minutes

# In-memory Store
received_events: Dict[str, Dict[str, Any]] = {}
stats = {
    "total_requests": 0,
    "successful_deliveries": 0,
    "signature_failures": 0,
    "timestamp_failures": 0,
    "simulated_failures": 0,
}

class ModeUpdateRequest(BaseModel):
    mode: str
    delay: Optional[float] = None
    failure_rate: Optional[float] = None
    secret: Optional[str] = None

def verify_hmac_signature(secret: str, event_id: str, timestamp_str: str, raw_body: str, signature_header: str) -> bool:
    try:
        timestamp = int(timestamp_str)
    except (ValueError, TypeError):
        return False

    current_time = int(time.time())
    if abs(current_time - timestamp) > MAX_AGE_SECONDS:
        return False

    message = f"{event_id}.{timestamp_str}.{raw_body}".encode("utf-8")
    expected_hex = hmac.new(secret.encode("utf-8"), message, hashlib.sha256).hexdigest()
    expected_v1 = f"v1={expected_hex}"

    # Signatures can be comma or space separated: "v1=xxx v1=yyy" or "v1=xxx,v1=yyy"
    sigs = [s.strip() for s in signature_header.replace(",", " ").split() if s.strip()]
    return any(hmac.compare_digest(sig, expected_v1) for sig in sigs)

@app.post("/webhook")
async def receive_webhook(request: Request):
    global stats
    stats["total_requests"] += 1

    event_id = request.headers.get("X-Webhook-Id")
    timestamp_str = request.headers.get("X-Webhook-Timestamp")
    signature_header = request.headers.get("X-Webhook-Signature")

    raw_body_bytes = await request.body()
    raw_body = raw_body_bytes.decode("utf-8", errors="replace")

    # 1. Validate required security headers
    if not event_id or not timestamp_str or not signature_header:
        stats["signature_failures"] += 1
        raise HTTPException(status_code=400, detail="Missing required webhook security headers")

    # 2. Validate timestamp freshness (max 5 minutes age)
    try:
        ts = int(timestamp_str)
        if abs(int(time.time()) - ts) > MAX_AGE_SECONDS:
            stats["timestamp_failures"] += 1
            raise HTTPException(status_code=400, detail="Timestamp too old or skew exceeds 5 minutes")
    except ValueError:
        stats["timestamp_failures"] += 1
        raise HTTPException(status_code=400, detail="Invalid timestamp format")

    # 3. Verify HMAC signature
    if not verify_hmac_signature(RECEIVER_SECRET, event_id, timestamp_str, raw_body, signature_header):
        stats["signature_failures"] += 1
        raise HTTPException(status_code=401, detail="Invalid HMAC-SHA256 signature")

    # 4. Handle Mock Modes
    if RECEIVER_MODE == "down":
        stats["simulated_failures"] += 1
        return Response(content='{"error": "Receiver down"}', status_code=status.HTTP_503_SERVICE_UNAVAILABLE, media_type="application/json")

    elif RECEIVER_MODE == "hang":
        time.sleep(300) # Block indefinitely
        return Response(content='{"error": "Hanged"}', status_code=status.HTTP_504_GATEWAY_TIMEOUT, media_type="application/json")

    elif RECEIVER_MODE == "slow":
        time.sleep(SLOW_DELAY_SECONDS)

    elif RECEIVER_MODE == "flaky":
        if random.random() < FLAKY_FAILURE_RATE:
            stats["simulated_failures"] += 1
            return Response(content='{"error": "Simulated flaky receiver error"}', status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, media_type="application/json")

    # Record successfully received event
    stats["successful_deliveries"] += 1
    if event_id in received_events:
        received_events[event_id]["count"] += 1
        received_events[event_id]["last_received_at"] = time.time()
    else:
        received_events[event_id] = {
            "count": 1,
            "first_received_at": time.time(),
            "last_received_at": time.time(),
            "raw_body": raw_body
        }

    return {"status": "success", "id": event_id, "mode": RECEIVER_MODE}

@app.get("/received")
def get_received():
    return received_events

@app.get("/stats")
def get_stats():
    return {
        "mode": RECEIVER_MODE,
        "secret": RECEIVER_SECRET,
        "stats": stats,
        "unique_events_received": len(received_events)
    }

@app.post("/admin/mode")
def update_mode(req: ModeUpdateRequest):
    global RECEIVER_MODE, SLOW_DELAY_SECONDS, FLAKY_FAILURE_RATE, RECEIVER_SECRET
    allowed_modes = {"healthy", "slow", "flaky", "down", "hang"}
    new_mode = req.mode.lower()
    if new_mode not in allowed_modes:
        raise HTTPException(status_code=400, detail=f"Mode must be one of {allowed_modes}")

    RECEIVER_MODE = new_mode
    if req.delay is not None:
        SLOW_DELAY_SECONDS = req.delay
    if req.failure_rate is not None:
        FLAKY_FAILURE_RATE = req.failure_rate
    if req.secret is not None:
        RECEIVER_SECRET = req.secret

    return {
        "status": "updated",
        "mode": RECEIVER_MODE,
        "delay": SLOW_DELAY_SECONDS,
        "failure_rate": FLAKY_FAILURE_RATE,
        "secret": RECEIVER_SECRET
    }

@app.post("/admin/reset")
def reset_state():
    global received_events, stats
    received_events.clear()
    stats = {
        "total_requests": 0,
        "successful_deliveries": 0,
        "signature_failures": 0,
        "timestamp_failures": 0,
        "simulated_failures": 0,
    }
    return {"status": "reset", "unique_events": 0}

@app.get("/health")
def health():
    return {"status": "UP", "mode": RECEIVER_MODE}
