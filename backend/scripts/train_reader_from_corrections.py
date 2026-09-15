#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import random

import cv2
import numpy as np
import torch
import torch.nn.functional as F
import segmentation_models_pytorch as smp
from safetensors.torch import load_file, save_file
from torch.utils.data import DataLoader, Dataset

MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
CLASSES = 4


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def load_rows(path: pathlib.Path) -> list[dict]:
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    return [row for row in rows if row.get("split") == "train"]


def validation_id(case_id: str) -> bool:
    bucket = int(hashlib.sha256(case_id.encode()).hexdigest()[:8], 16) / 0xFFFFFFFF
    return bucket < 0.20


class CorrectionDataset(Dataset):
    def __init__(self, root: pathlib.Path, rows: list[dict], size: int, augment: bool) -> None:
        self.root, self.rows, self.size, self.augment = root, rows, size, augment

    def __len__(self) -> int:
        return len(self.rows)

    def __getitem__(self, index: int):
        row = self.rows[index]
        image = cv2.imread(str(self.root / row["image"]), cv2.IMREAD_COLOR)
        mask = cv2.imread(str(self.root / row["mask"]), cv2.IMREAD_GRAYSCALE)
        if image is None or mask is None:
            raise RuntimeError(f"cannot decode case {row['id']}")
        if self.augment and random.random() < 0.5:
            image, mask = np.fliplr(image).copy(), np.fliplr(mask).copy()
        if self.augment and random.random() < 0.25:
            image, mask = np.flipud(image).copy(), np.flipud(mask).copy()
        h, w = image.shape[:2]
        scale = min(self.size / max(w, 1), self.size / max(h, 1))
        iw, ih = max(1, round(w * scale)), max(1, round(h * scale))
        image = cv2.resize(cv2.cvtColor(image, cv2.COLOR_BGR2RGB), (iw, ih), interpolation=cv2.INTER_AREA)
        mask = cv2.resize(mask, (iw, ih), interpolation=cv2.INTER_NEAREST)
        norm = (image.astype(np.float32) / 255.0 - MEAN) / STD
        canvas = np.zeros((self.size, self.size, 3), dtype=np.float32)
        target = np.zeros((self.size, self.size), dtype=np.int64)
        top, left = (self.size - ih) // 2, (self.size - iw) // 2
        canvas[top:top + ih, left:left + iw] = norm
        target[top:top + ih, left:left + iw] = mask
        tensor = torch.from_numpy(np.transpose(canvas, (2, 0, 1)))
        return tensor, torch.from_numpy(target)


def loss_fn(logits: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    weights = torch.tensor([0.12, 1.0, 4.0, 4.0], device=logits.device)
    ce = F.cross_entropy(logits, target, weight=weights)
    probs = logits.softmax(dim=1)
    truth = F.one_hot(target, CLASSES).permute(0, 3, 1, 2).float()
    intersection = (probs[:, 1:] * truth[:, 1:]).sum(dim=(0, 2, 3))
    denominator = (probs[:, 1:] + truth[:, 1:]).sum(dim=(0, 2, 3)).clamp_min(1e-6)
    dice = 1.0 - ((2.0 * intersection + 1.0) / (denominator + 1.0)).mean()
    return ce + 0.6 * dice


def epoch(model, loader, device, optimizer=None) -> float:
    training = optimizer is not None
    model.train(training)
    if training:
        # Encoder weights and BatchNorm running statistics stay unchanged on small correction sets.
        model.encoder.eval()
    total = 0.0
    for image, target in loader:
        image, target = image.to(device), target.to(device)
        with torch.set_grad_enabled(training):
            loss = loss_fn(model(image), target)
        if training:
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
        total += float(loss.detach().cpu())
    return total / max(len(loader), 1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=pathlib.Path, required=True)
    parser.add_argument("--base-model", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--epochs", type=int, default=6)
    parser.add_argument("--image-size", type=int, default=352)
    parser.add_argument("--min-cases", type=int, default=8)
    args = parser.parse_args()

    random.seed(2026)
    np.random.seed(2026)
    torch.manual_seed(2026)
    manifest = args.dataset / "dataset.jsonl"
    rows = load_rows(manifest)
    if len(rows) < args.min_cases:
        print(json.dumps({"status": "INSUFFICIENT_CASES", "cases": len(rows), "required": args.min_cases}))
        return 3
    train_rows = [row for row in rows if not validation_id(str(row["id"]))]
    val_rows = [row for row in rows if validation_id(str(row["id"]))]
    if not val_rows:
        val_rows = [train_rows.pop()]
    if not train_rows:
        raise RuntimeError("no training rows after deterministic validation split")

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = smp.Unet(encoder_name="resnet34", encoder_weights=None, in_channels=3, classes=CLASSES)
    model.load_state_dict(load_file(str(args.base_model), device="cpu"), strict=True)
    for parameter in model.encoder.parameters():
        parameter.requires_grad = False
    model.to(device)

    train = DataLoader(CorrectionDataset(args.dataset, train_rows, args.image_size, True), batch_size=2, shuffle=True, num_workers=0)
    val = DataLoader(CorrectionDataset(args.dataset, val_rows, args.image_size, False), batch_size=2, shuffle=False, num_workers=0)
    optimizer = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=1e-4, weight_decay=1e-4)
    history = []
    best = None
    best_loss = float("inf")
    for number in range(1, max(1, args.epochs) + 1):
        train_loss = epoch(model, train, device, optimizer)
        val_loss = epoch(model, val, device)
        history.append({"epoch": number, "train_loss": round(train_loss, 6), "val_loss": round(val_loss, 6)})
        if val_loss < best_loss:
            best_loss = val_loss
            best = {key: value.detach().cpu().contiguous().clone() for key, value in model.state_dict().items()}
    if best is None:
        raise RuntimeError("training produced no candidate weights")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    save_file(best, str(args.output))
    report = {
        "status": "CANDIDATE_READY",
        "base_model_sha256": sha256(args.base_model),
        "candidate_sha256": sha256(args.output),
        "train_cases": len(train_rows),
        "validation_cases": len(val_rows),
        "encoder_frozen": True,
        "encoder_batchnorm_frozen": True,
        "history": history,
        "automatic_deploy": False,
    }
    args.output.with_suffix(".training.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
