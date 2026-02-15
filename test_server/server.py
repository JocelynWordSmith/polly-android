#!/usr/bin/env python3
"""
Polly Robot Test Server

Connects to the Android phone's WebSocket bridge and serves a local
web dashboard with real-time data, camera streams, and Xbox controller
support for driving the robot.

Usage:
    python server.py [phone_ip]

    phone_ip defaults to the POLLY_PHONE_IP env var, or 192.168.1.100
"""

import asyncio
import json
import logging
import os
import struct
import sys
from base64 import b64encode
from pathlib import Path

import websockets
from aiohttp import web, WSMsgType

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("polly-server")

PHONE_IP = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("POLLY_PHONE_IP", "192.168.1.100")
PHONE_PORT = 8080
LOCAL_PORT = 8000

# Endpoints we connect to on the phone
ENDPOINTS = ["arduino", "lidar", "camera", "flir", "imu"]

# ── Shared state ──────────────────────────────────────────────────────────────

browser_clients: set[web.WebSocketResponse] = set()
phone_connected: dict[str, bool] = {}        # endpoint name → bool
control_ws = None  # websockets connection to phone /control endpoint


# ── Phone WebSocket clients (using websockets library) ────────────────────────

async def phone_connect_opts(**overrides):
    """Common websockets.connect kwargs for NanoHTTPD compatibility."""
    defaults = dict(
        ping_interval=3,        # Must be < NanoHTTPD's 5s SOCKET_READ_TIMEOUT
        ping_timeout=None,      # Don't close on missed pong (phone may be slow)
        compression=None,       # NanoHTTPD doesn't support permessage-deflate
        close_timeout=2,        # Don't wait long for close frame from NanoHTTPD
        max_size=2**20,         # 1 MB max frame for camera JPEGs
    )
    defaults.update(overrides)
    return defaults


async def connect_phone_endpoint(endpoint: str, initial_delay: float = 0):
    """Connect to one phone WebSocket endpoint and relay data."""
    url = f"ws://{PHONE_IP}:{PHONE_PORT}/{endpoint}"
    backoff = 5

    if initial_delay > 0:
        await asyncio.sleep(initial_delay)

    while True:
        try:
            log.info(f"Connecting to {url} ...")
            opts = await phone_connect_opts()
            async with websockets.connect(url, **opts) as ws:
                phone_connected[endpoint] = True
                backoff = 5  # reset on success
                log.info(f"Connected to /{endpoint}")
                await broadcast_status()

                async for message in ws:
                    if isinstance(message, str):
                        await handle_phone_text(endpoint, message)
                    elif isinstance(message, bytes):
                        await handle_phone_binary(endpoint, message)

        except (websockets.ConnectionClosed, OSError) as e:
            log.warning(f"/{endpoint} disconnected: {e}")
        except Exception as e:
            log.warning(f"/{endpoint} connection failed: {e}")

        phone_connected[endpoint] = False
        await broadcast_status()
        await asyncio.sleep(backoff)
        backoff = min(backoff * 1.5, 30)  # exponential backoff, cap at 30s


async def connect_phone_control(initial_delay: float = 0):
    """Maintain connection to /control endpoint for sending commands."""
    global control_ws
    url = f"ws://{PHONE_IP}:{PHONE_PORT}/control"
    backoff = 5

    if initial_delay > 0:
        await asyncio.sleep(initial_delay)

    while True:
        try:
            log.info(f"Connecting to {url} ...")
            opts = await phone_connect_opts()
            async with websockets.connect(url, **opts) as ws:
                control_ws = ws
                phone_connected["control"] = True
                backoff = 5
                log.info("Connected to /control")
                await broadcast_status()

                async for message in ws:
                    if isinstance(message, str):
                        log.debug(f"[control] response: {message}")

        except (websockets.ConnectionClosed, OSError) as e:
            log.warning(f"/control disconnected: {e}")
        except Exception as e:
            log.warning(f"/control connection failed: {e}")

        control_ws = None
        phone_connected["control"] = False
        await broadcast_status()
        await asyncio.sleep(backoff)
        backoff = min(backoff * 1.5, 30)


async def handle_phone_text(endpoint: str, data: str):
    """Handle text messages from phone (arduino sensors, IMU)."""
    if endpoint in ("arduino", "imu"):
        try:
            parsed = json.loads(data)
        except json.JSONDecodeError:
            return  # Arduino sometimes sends partial/concatenated JSON
        await broadcast_to_browsers(json.dumps({"type": endpoint, "data": parsed}))


async def handle_phone_binary(endpoint: str, data: bytes):
    """Handle binary messages from phone (camera, FLIR, LIDAR)."""
    if endpoint == "camera":
        b64 = b64encode(data).decode("ascii")
        await broadcast_to_browsers(json.dumps({"type": "camera", "jpeg": b64}))

    elif endpoint == "flir":
        if len(data) >= 12:
            width, height, min_val, max_val = struct.unpack_from("<HHII", data, 0)
            pixel_data = data[12:]
            val_range = max(max_val - min_val, 1)
            pixels = []
            for i in range(0, len(pixel_data) - 1, 2):
                raw = struct.unpack_from("<H", pixel_data, i)[0]
                normalized = int((raw - min_val) * 255 / val_range)
                pixels.append(max(0, min(255, normalized)))

            await broadcast_to_browsers(json.dumps({
                "type": "flir",
                "width": width,
                "height": height,
                "minVal": min_val,
                "maxVal": max_val,
                "pixels": pixels,
            }))

    elif endpoint == "lidar":
        points = []
        for i in range(0, len(data) - 4, 5):
            b0, b1, b2, b3, b4 = data[i:i+5]
            quality = b0 >> 2
            if quality == 0:
                continue
            angle = ((b1 >> 1) | (b2 << 7)) / 64.0
            distance = (b3 | (b4 << 8)) / 4.0
            if distance > 0:
                points.append({"a": round(angle, 1), "d": round(distance, 1), "q": quality})

        if points:
            await broadcast_to_browsers(json.dumps({"type": "lidar", "points": points}))


# ── Browser WebSocket relay (aiohttp server side) ─────────────────────────────

async def broadcast_to_browsers(message: str):
    """Send a message to all connected browser clients."""
    if not browser_clients:
        return
    stale = set()
    for ws in browser_clients:
        try:
            await ws.send_str(message)
        except (ConnectionError, RuntimeError):
            stale.add(ws)
    browser_clients.difference_update(stale)


async def broadcast_status():
    """Send connection status to all browser clients."""
    status = {ep: phone_connected.get(ep, False) for ep in ENDPOINTS + ["control"]}
    await broadcast_to_browsers(json.dumps({"type": "status", "connections": status}))


async def ws_handler(request):
    """Handle incoming browser WebSocket connections."""
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    browser_clients.add(ws)
    log.info(f"Browser client connected ({len(browser_clients)} total)")

    # Send current status immediately
    status = {ep: phone_connected.get(ep, False) for ep in ENDPOINTS + ["control"]}
    await ws.send_str(json.dumps({"type": "status", "connections": status}))

    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                await handle_browser_message(msg.data)
            elif msg.type in (WSMsgType.CLOSED, WSMsgType.ERROR):
                break
    finally:
        browser_clients.discard(ws)
        log.info(f"Browser client disconnected ({len(browser_clients)} total)")

    return ws


async def handle_browser_message(data: str):
    """Handle messages from browser (motor commands, config)."""
    try:
        msg = json.loads(data)
    except json.JSONDecodeError:
        return

    if msg.get("type") == "motor":
        left = msg.get("left", 0)
        right = msg.get("right", 0)
        cmd = json.dumps({"N": 7, "D1": left, "D2": right})
        if not hasattr(handle_browser_message, '_mc'):
            handle_browser_message._mc = 0
        handle_browser_message._mc += 1
        if handle_browser_message._mc % 20 == 1:
            log.info("Motor: L=%d R=%d (msg #%d)", left, right, handle_browser_message._mc)
        await send_control({"target": "arduino", "cmd": cmd})

    elif msg.get("type") == "stop":
        cmd = json.dumps({"N": 6})
        await send_control({"target": "arduino", "cmd": cmd})

    elif msg.get("type") == "control":
        await send_control(msg.get("payload", {}))


async def send_control(payload: dict):
    """Send a command to the phone's /control endpoint."""
    if control_ws is None:
        log.warning("Control command dropped (no phone connection): %s", payload)
        return
    try:
        await control_ws.send(json.dumps(payload))
    except Exception:
        log.warning("Failed to send control command")


# ── HTTP routes ───────────────────────────────────────────────────────────────

async def index_handler(request):
    """Serve the dashboard HTML."""
    html_path = Path(__file__).parent / "index.html"
    return web.FileResponse(html_path)


# ── Application lifecycle ─────────────────────────────────────────────────────

async def start_background_tasks(app):
    """Start phone WebSocket connections with slight stagger."""
    for i, endpoint in enumerate(ENDPOINTS):
        delay = i * 0.5
        app[f"phone_{endpoint}"] = asyncio.create_task(
            connect_phone_endpoint(endpoint, delay)
        )
    control_delay = len(ENDPOINTS) * 0.5
    app["phone_control"] = asyncio.create_task(connect_phone_control(control_delay))


async def cleanup_background_tasks(app):
    """Cancel phone connections on shutdown."""
    for endpoint in ENDPOINTS:
        task = app.get(f"phone_{endpoint}")
        if task:
            task.cancel()
    control_task = app.get("phone_control")
    if control_task:
        control_task.cancel()


def main():
    app = web.Application()
    app.router.add_get("/", index_handler)
    app.router.add_get("/ws", ws_handler)

    app.on_startup.append(start_background_tasks)
    app.on_cleanup.append(cleanup_background_tasks)

    log.info(f"Phone target: {PHONE_IP}:{PHONE_PORT}")
    log.info(f"Dashboard: http://localhost:{LOCAL_PORT}")
    web.run_app(app, host="0.0.0.0", port=LOCAL_PORT, print=None)


if __name__ == "__main__":
    main()
