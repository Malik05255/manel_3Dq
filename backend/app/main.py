"""Compatibility entrypoint for the existing Render service.

Production plan reading is cloud-only.  The historical ``app.main:app``
entrypoint is intentionally kept so the already-provisioned Render service
can switch to the cloud gateway without retaining a second local inference
path.
"""

from .cloud_public_gateway import app

__all__ = ["app"]
