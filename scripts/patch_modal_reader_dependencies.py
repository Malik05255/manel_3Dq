from pathlib import Path

path = Path("modal_reader/app.py")
text = path.read_text()

replacements = {
    '.run_commands("pip install -r requirements.txt")': (
        '.run_commands("sed -i \'s/^numpy==1.26.4$/numpy==1.24.4/; '
        's/^opencv-python$/opencv-python==4.8.1.78/\' requirements.txt '
        '&& pip install -r requirements.txt")'
    ),
    '.run_commands("cd models/ops && sh make.sh")': (
        '.run_commands("cd models/ops && sh make.sh", gpu="T4")'
    ),
    '.run_commands("cd diff_ras && python setup.py build develop")': (
        '.run_commands("cd diff_ras && python setup.py build develop", gpu="T4")'
    ),
}

for old, new in replacements.items():
    if old not in text:
        raise SystemExit(f"expected Modal reader build command not found: {old}")
    text = text.replace(old, new, 1)

path.write_text(text)
