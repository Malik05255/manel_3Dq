# Manzili HAI cloud reader

Production floor-plan inference is cloud-only.

Architecture: Android -> Render gateway -> Modal GPU reader -> JSON -> Android. The Android app does not run OCR, wall detection, room detection, CubiCasa, Roboflow, OpenCV heuristics, or other floor-plan inference locally.

The Modal service uses `@modal.fastapi_endpoint(..., requires_proxy_auth=True)`. Create a Modal Workspace proxy token and store the single Bearer form (`wk-....ws-...`) in Render as `MODAL_READER_TOKEN`. Store the deployed Modal HTTPS function URL in Render as `MODAL_READER_URL`.

Deploy with:

`modal deploy modal_reader/app.py`

The current reader uses the MIT Raster2Seq Raster2Graph-512 checkpoint for room polygons and cloud EasyOCR for Arabic/English text. Door/window extraction is intentionally not claimed yet; the response returns no openings until a cloud model for those elements is enabled and validated.
