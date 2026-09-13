# Third-party model notice

## Yytsi/floorplan-to-3d-walls

Manzili HAI can optionally run the `Yytsi/floorplan-to-3d-walls` segmentation weights on the backend.

- Architecture: UNet with ResNet-34 encoder
- Classes: floor, wall, door, window
- Training dataset: CubiCasa5K
- Upstream repository: https://github.com/Yytsi/floorplan-to-3d
- Model distribution: https://huggingface.co/Yytsi/floorplan-to-3d-walls
- License: MIT
- Expected `best.safetensors` SHA-256: `d7f6a0fd06e2931aecfc8c4849192c5e153701578026efc78d9a6246731a8d6c`

The model is treated as verifier/fallback evidence, not as ground truth. Results still pass through Manzili HAI verification/fusion before becoming canonical geometry.

The model card notes that its training distribution is dominated by CubiCasa5K residential CAD-style plans. Accuracy on hand drawings, commercial plans, or substantially different drafting conventions must be evaluated separately.

## Roboflow Universe floor-plan models

When a server-side Roboflow credential is configured, Manzili HAI uses Roboflow Universe as the primary floor-plan detector and keeps the local model/OpenCV path as verification and fallback evidence.

Default public model candidates:

- `floor-plan-detector/19` — WALL / ROOM instance segmentation, project by harsh-bagadiya, CC BY 4.0 dataset/model page.
- `floor-plan-nnoub-ngvnw/1` — door / window / wall instance segmentation, project by 2D Drawing to 3D model, CC BY 4.0 dataset/model page.

Roboflow credentials are backend-only and must never be embedded in the Android APK. Model outputs remain probabilistic evidence and are subject to Manzili HAI consistency gates before approval or 3D generation.
