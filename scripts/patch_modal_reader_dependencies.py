from pathlib import Path

path = Path("modal_reader/app.py")
text = path.read_text()
old = '.run_commands("pip install -r requirements.txt")'
new = (
    '.run_commands("sed -i \'s/^numpy==1.26.4$/numpy==1.24.4/; '
    's/^opencv-python$/opencv-python==4.8.1.78/\' requirements.txt '
    '&& pip install -r requirements.txt")'
)
if old not in text:
    raise SystemExit("expected Raster2Seq install command not found")
path.write_text(text.replace(old, new, 1))
