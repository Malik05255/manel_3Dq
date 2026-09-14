from pathlib import Path

path = Path("modal_reader/app.py")
text = path.read_text()

replacements = {
    'import modal\n': (
        'import modal\n'
        'from fastapi import Header, HTTPException\n'
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
        '"cryptography==45.0.7"'
        ')'
    ),
}

for old, new in replacements.items():
    if old not in text:
        raise SystemExit(f"expected Modal reader build command not found: {old}")
    text = text.replace(old, new, 1)

marker = "@app.function(\n    image=reader_image,\n    gpu=\"T4\","
security = '''_GATEWAY_KEY_URL = "https://manzili-hai-deep-parser.onrender.com/v1/public/gateway-key"
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


'''
if marker not in text:
    raise SystemExit("Modal function marker not found")
text = text.replace(marker, security + marker, 1)

old_endpoint = '''@modal.fastapi_endpoint(method="POST", requires_proxy_auth=True)
def parse_floorplan(payload: dict[str, Any]) -> dict[str, Any]:
    image_bytes = _decode_image(payload)
'''
new_endpoint = '''@modal.fastapi_endpoint(method="POST", requires_proxy_auth=False)
def parse_floorplan(
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
    image_bytes = _decode_image(payload)
'''
if old_endpoint not in text:
    raise SystemExit("Modal endpoint signature not found")
text = text.replace(old_endpoint, new_endpoint, 1)

path.write_text(text)
