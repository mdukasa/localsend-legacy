"""Renders art/ic_launcher.svg with headless Chrome at 512 px and downsamples it into each density bucket.

Also renders a 768 px copy for the Android 12+ splash screen (drawable-nodpi/splash_icon.png)."""
import os, subprocess, sys, tempfile
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
SIZES = {"ldpi": 36, "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

svg = open(os.path.join(HERE, "ic_launcher.svg"), encoding="utf-8").read()
with tempfile.TemporaryDirectory() as tmp:
    page = os.path.join(tmp, "icon.html")
    with open(page, "w", encoding="utf-8") as f:
        f.write('<html><body style="margin:0;background:transparent">' + svg + "</body></html>")
    master = os.path.join(tmp, "master.png")
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                    "--default-background-color=00000000", "--window-size=512,512",
                    "--screenshot=" + master, "file:///" + page.replace("\\", "/")],
                   check=True, capture_output=True)
    img = Image.open(master).convert("RGBA").crop((0, 0, 512, 512))
    img.save(os.path.join(HERE, "ic_launcher_512.png"))
    for bucket, px in SIZES.items():
        out = os.path.join(RES, "drawable-" + bucket)
        os.makedirs(out, exist_ok=True)
        img.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"), optimize=True)
        print(bucket, px)
    # Splash screen icon: larger, since Android 12+ draws it at up to 192 dp.
    big = os.path.join(tmp, "big.png")
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1.5",
                    "--default-background-color=00000000", "--window-size=512,512",
                    "--screenshot=" + big, "file:///" + page.replace("\\", "/")],
                   check=True, capture_output=True)
    out = os.path.join(RES, "drawable-nodpi")
    os.makedirs(out, exist_ok=True)
    Image.open(big).convert("RGBA").crop((0, 0, 768, 768)).save(os.path.join(out, "splash_icon.png"), optimize=True)
    print("splash", 768)
