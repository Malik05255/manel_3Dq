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

The model is treated as evidence, not as ground truth. Results still pass through Manzili HAI verification/fusion before becoming canonical geometry.

The model card notes that its training distribution is dominated by CubiCasa5K residential CAD-style plans. Accuracy on hand drawings, commercial plans, or substantially different drafting conventions must be evaluated separately.
