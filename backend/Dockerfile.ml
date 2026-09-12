FROM python:3.12-slim
WORKDIR /app

COPY requirements.txt requirements-ml.txt ./
RUN pip install --no-cache-dir -r requirements-ml.txt

COPY app ./app
COPY scripts ./scripts

ENV PYTHONUNBUFFERED=1 \
    FLOORPLAN_SAFETENSORS_MODEL=/opt/manzili/models/floorplan/best.safetensors \
    FLOORPLAN_TORCH_THREADS=2

RUN python scripts/fetch_floorplan_model.py

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
