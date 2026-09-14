from pathlib import Path

path = Path("modal_reader/app.py")
text = path.read_text()

replacements = {
    'import modal\n': (
        'import modal\n'
        'from fastapi import Header, HTTPException\n'
    ),
    'from modal_reader.metric_evidence import metric_evidence\n': (
        'from modal_reader.metric_evidence import metric_evidence\n'
        'from modal_reader.polygon_sanitize import R2G_EMPTY_CLASS, sanitize_polygon, wall_edges_from_polygon\n'
    ),
    '.run_commands("pip install -r requirements.txt")': (
        '.run_commands("sed -i \'s/^numpy==1.26.4$/numpy==1.24.4/; '
        's/^opencv-python$/opencv-python-headless==4.8.1.78/\' requirements.txt '
        '&& pip install -r requirements.txt")'
    ),
    '.run_commands("cd models/ops && sh make.sh")': (
        '.run_commands("cd models/ops && sh make.sh", gpu="T4")'
    ),
    '.run_commands("cd diff_ras && python setup.py build develop")': (
        '.run_commands("cd diff_ras && python setup.py build develop", gpu="T4")'
    ),
    '.pip_install("fastapi==0.115.12", "pydantic==2.11.3", "easyocr==1.7.2")': (
        '.pip_install('
        '"fastapi==0.115.12", '
        '"pydantic==2.11.3", '
        '"easyocr==1.7.2", '
        '"opencv-python-headless==4.8.1.78", '
        '"numpy==1.24.4", '
        '"scipy==1.8.1", '
        '"cryptography==45.0.7", '
        '"plotly==5.24.1"'
        ')'
    ),
    '        source_x = (padded_x - left) / max(scale, 1e-6)\n'
    '        source_y = (padded_y - top) / max(scale, 1e-6)\n'
    '        points.append(\n': (
        '        tolerance = max(2.0, model_size * 0.015)\n'
        '        if (\n'
        '            padded_x < left - tolerance\n'
        '            or padded_x > left + resized_w + tolerance\n'
        '            or padded_y < top - tolerance\n'
        '            or padded_y > top + resized_h + tolerance\n'
        '        ):\n'
        '            continue\n'
        '        source_x = (padded_x - left) / max(scale, 1e-6)\n'
        '        source_y = (padded_y - top) / max(scale, 1e-6)\n'
        '        points.append(\n'
    ),
    '    for index, item in enumerate(predictions):\n'
    '        polygon = _normalize_polygon(item.get("segmentation"), source_w, source_h, _R2G_SIZE)\n'
    '        if len(polygon) < 3:\n'
    '            continue\n'
    '        cls = int(item.get("category_id", 0) or 0)\n'
    '        room_type, name = _R2G_LABELS.get(cls, ("unknown", "مساحة"))\n'
    '        if room_type == "outside":\n'
    '            continue\n': (
        '    for index, item in enumerate(predictions):\n'
        '        cls = int(item.get("category_id", 0) or 0)\n'
        '        if cls == R2G_EMPTY_CLASS:\n'
        '            continue\n'
        '        room_type, name = _R2G_LABELS.get(cls, ("unknown", "مساحة"))\n'
        '        if room_type == "outside":\n'
        '            continue\n'
        '        raw_polygon = _normalize_polygon(item.get("segmentation"), source_w, source_h, _R2G_SIZE)\n'
        '        polygon, geometry_status = sanitize_polygon(raw_polygon)\n'
        '        if len(polygon) < 3:\n'
        '            continue\n'
    ),
    '                "confidence": 0,\n'
    '                "polygon": polygon,\n': (
        '                "confidence": 0,\n'
        '                "polygon": polygon,\n'
        '                "geometry_status": geometry_status,\n'
    ),
    '        for i, start in enumerate(polygon):\n'
    '            end = polygon[(i + 1) % len(polygon)]\n': (
        '        for start, end in wall_edges_from_polygon(polygon):\n'
    ),
    '    h, w = image.shape[:2]\n'
    '    reader = easyocr.Reader(["ar", "en"], gpu=True, verbose=False)\n': (
        '    h, w = image.shape[:2]\n'
        '    max_side = max(h, w)\n'
        '    if max_side > 2800:\n'
        '        scale = 2800.0 / float(max_side)\n'
        '        image = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)\n'
        '        h, w = image.shape[:2]\n'
        '    reader = easyocr.Reader(["ar", "en"], gpu=True, verbose=False)\n'
    ),
    '    ocr_lines = _cloud_ocr(image_bytes)\n'
    '    metric = metric_evidence(ocr_lines)\n': (
        '    ocr_error: str | None = None\n'
        '    try:\n'
        '        ocr_lines = _cloud_ocr(image_bytes)\n'
        '    except Exception as exc:\n'
        '        ocr_lines = []\n'
        '        ocr_error = " ".join(str(exc).split())[:500]\n'
        '    metric = metric_evidence(ocr_lines)\n'
    ),
    '    if opening_error:\n'
    '        warnings.append("Door/window cloud pass failed: " + opening_error)\n': (
        '    repaired_count = sum(1 for room in rooms if room.get("geometry_status") == "repaired")\n'
        '    fallback_count = sum(1 for room in rooms if room.get("geometry_status") == "bbox-fallback")\n'
        '    if repaired_count:\n'
        '        warnings.append(f"Repaired {repaired_count} self-crossing room polygon(s) before wall extraction.")\n'
        '    if fallback_count:\n'
        '        warnings.append(f"Replaced {fallback_count} unusable room polygon(s) with conservative bounding boxes.")\n'
        '    if opening_error:\n'
        '        warnings.append("Door/window cloud pass failed: " + opening_error)\n'
        '    if ocr_error:\n'
        '        warnings.append("Cloud OCR failed without blocking geometry: " + ocr_error)\n'
    ),
}

for old, new in replacements.items():
    if old not in text:
        raise SystemExit(f"expected Modal reader build/source fragment not found: {old}")
    text = text.replace(old, new, 1)

old_endpoint = '''@app.function(
    image=reader_image,
    gpu="T4",
    timeout=300,
    scaledown_window=60,
)
@modal.fastapi_endpoint(method="POST", requires_proxy_auth=True)
def parse_floorplan(payload: dict[str, Any]) -> dict[str, Any]:
'''
new_gpu_function = '''@app.function(
    image=reader_image,
    gpu="T4",
    timeout=600,
    scaledown_window=120,
)
def _parse_floorplan_gpu(payload: dict[str, Any]) -> dict[str, Any]:
'''
if old_endpoint not in text:
    raise SystemExit("Modal GPU endpoint signature not found")
text = text.replace(old_endpoint, new_gpu_function, 1)

security_and_web = r'''

_GATEWAY_KEY_URL = "https://manzili-hai-deep-parser.onrender.com/v1/public/gateway-key"
_GATEWAY_PUBLIC_KEY_B64: str | None = None


def _gateway_public_key_b64() -> str:
    global _GATEWAY_PUBLIC_KEY_B64
    if _GATEWAY_PUBLIC_KEY_B64:
        return _GATEWAY_PUBLIC_KEY_B64
    import urllib.request

    try:
        with urllib.request.urlopen(_GATEWAY_KEY_URL, timeout=12) as response:
            body = json.loads(response.read().decode("utf-8"))
        value = str(body.get("public_key") or "").strip()
        if body.get("algorithm") != "Ed25519" or not value:
            raise ValueError("invalid gateway key response")
    except Exception as exc:
        raise HTTPException(503, "gateway verification key unavailable") from exc
    _GATEWAY_PUBLIC_KEY_B64 = value
    return value


def _verify_gateway_signature(
    payload: dict[str, Any],
    timestamp: str | None,
    nonce: str | None,
    signature: str | None,
) -> None:
    import time
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

    if not timestamp or not nonce or not signature:
        raise HTTPException(401, "missing gateway signature")
    try:
        unix_time = int(timestamp)
    except ValueError as exc:
        raise HTTPException(401, "invalid gateway timestamp") from exc
    if abs(int(time.time()) - unix_time) > 120:
        raise HTTPException(401, "expired gateway signature")
    try:
        public_key_b64 = _gateway_public_key_b64()
        public_raw = base64.urlsafe_b64decode(
            public_key_b64 + "=" * (-len(public_key_b64) % 4)
        )
        signature_raw = base64.urlsafe_b64decode(signature + "=" * (-len(signature) % 4))
        canonical = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        message = timestamp.encode("ascii") + b"." + nonce.encode("ascii") + b"." + canonical
        Ed25519PublicKey.from_public_bytes(public_raw).verify(signature_raw, message)
    except (ValueError, InvalidSignature) as exc:
        raise HTTPException(401, "invalid gateway signature") from exc


web_image = (
    modal.Image.debian_slim(python_version="3.11")
    .pip_install("fastapi==0.115.12", "cryptography==45.0.7")
)


@app.function(
    image=web_image,
    timeout=620,
    cpu=1.0,
    memory=1024,
)
@modal.concurrent(max_inputs=8)
@modal.fastapi_endpoint(method="POST", requires_proxy_auth=False)
async def parse_floorplan(
    payload: dict[str, Any],
    x_manzili_timestamp: str | None = Header(default=None),
    x_manzili_nonce: str | None = Header(default=None),
    x_manzili_signature: str | None = Header(default=None),
) -> dict[str, Any]:
    _verify_gateway_signature(
        payload,
        x_manzili_timestamp,
        x_manzili_nonce,
        x_manzili_signature,
    )
    if not str(payload.get("image_base64") or "").strip():
        raise HTTPException(422, "image_base64 is required")
    try:
        return await _parse_floorplan_gpu.remote.aio(payload)
    except HTTPException:
        raise
    except Exception as exc:
        detail = " ".join(str(exc).split())
        if not detail:
            detail = repr(exc)
        detail = detail[-1800:]
        print(f"GPU reader failed: {type(exc).__name__}: {detail}", flush=True)
        raise HTTPException(
            502,
            f"modal-gpu-inference:{type(exc).__name__}:{detail}",
        ) from exc
'''

text = text.rstrip() + security_and_web + "\n"
path.write_text(text)
