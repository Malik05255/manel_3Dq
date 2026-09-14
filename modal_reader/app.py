from __future__ import annotations

import base64
import io
import os
from typing import Any

import modal

app = modal.App("manzili-hai-floorplan-reader")

image = (
    modal.Image.debian_slim(python_version="3.12")
    .pip_install(
        "fastapi==0.115.12",
        "pydantic==2.11.3",
        "pillow==11.1.0",
        "transformers==4.51.3",
        "torch==2.6.0",
        "torchvision==0.21.0",
        "opencv-python-headless==4.11.0.86",
        "numpy==2.2.4",
    )
)


@app.function(
    image=image,
    gpu="T4",
    timeout=300,
    scaledown_window=60,
    secrets=[modal.Secret.from_name("manzili-reader")],
)
@modal.fastapi_endpoint(method="POST")
def parse_floorplan(payload: dict[str, Any], authorization: str | None = None) -> dict[str, Any]:
    """Single cloud inference entrypoint.

    The Android app performs no OCR/vision inference. This container is the only
    floor-plan reader. Replace `_run_model` with the selected trained checkpoint
    without changing the mobile contract.
    """
    expected = os.getenv("MODAL_READER_TOKEN", "").strip()
    supplied = ""
    if authorization and authorization.startswith("Bearer "):
        supplied = authorization[7:].strip()
    if not expected or supplied != expected:
        from fastapi import HTTPException

        raise HTTPException(401, "invalid reader token")

    image_base64 = str(payload.get("image_base64") or "")
    page_index = int(payload.get("page_index") or 0)
    raw = image_base64.split(",", 1)[-1]
    try:
        image_bytes = base64.b64decode(raw, validate=True)
    except Exception as exc:
        from fastapi import HTTPException

        raise HTTPException(400, "invalid image") from exc

    result = _run_model(image_bytes)
    result["page_index"] = page_index
    result["model_used"] = result.get("model_used") or "modal-cloud-reader"
    result["reader_path"] = "modal-cloud-only"
    result["local_inference"] = False
    return result


def _run_model(image_bytes: bytes) -> dict[str, Any]:
    """Cloud-only model boundary.

    This initial deployment contract intentionally fails closed until a selected
    floor-plan checkpoint is configured. There is no local/device fallback and no
    fabricated geometry. Configure READER_MODEL_ID in the Modal secret/environment
    after choosing the model to activate inference.
    """
    model_id = os.getenv("READER_MODEL_ID", "").strip()
    if not model_id:
        from fastapi import HTTPException

        raise HTTPException(503, "READER_MODEL_ID is not configured")

    # Keep decoding here in the cloud so Android remains transport/display only.
    from PIL import Image

    plan_image = Image.open(io.BytesIO(image_bytes)).convert("RGB")

    # The production model adapter is intentionally isolated here. A checkpoint
    # that returns structural graph JSON can be swapped in without any mobile change.
    adapter = _load_adapter(model_id)
    result = adapter(plan_image)
    if not isinstance(result, dict):
        from fastapi import HTTPException

        raise HTTPException(502, "reader model returned invalid output")
    return result


def _load_adapter(model_id: str):
    """Load the configured cloud reader.

    Supported production adapters are added explicitly rather than silently
    falling back to on-device or heuristic readers.
    """
    if model_id == "stub":
        def _stub(_image):
            from fastapi import HTTPException

            raise HTTPException(503, "stub reader is disabled in production")
        return _stub

    raise RuntimeError(f"unsupported READER_MODEL_ID: {model_id}")
