#!/usr/bin/env python3
"""Applies the Hourglass Touch changes to a melonDS Android checkout. Fails loudly if anything doesn't match."""
import pathlib, shutil, sys

root = pathlib.Path(sys.argv[1])
ours = pathlib.Path(__file__).resolve().parent

def edit(path, old, new):
    p = root / path
    s = p.read_bytes().decode("utf-8")
    n = s.count(old)
    if n != 1:
        sys.exit(f"apply.py: expected exactly 1 match in {path}, found {n}:\n{old}")
    p.write_bytes(s.replace(old, new).encode("utf-8"))
    print(f"patched {path}")

# 1. our touch overlay
dst = root / "app/src/main/java/me/magnum/melonds/ui/emulator/hourglass"
dst.mkdir(parents=True, exist_ok=True)
shutil.copy(ours / "src/HourglassOverlayView.kt", dst)
print("added HourglassOverlayView.kt")

# 2. put the overlay on top of the normal on-screen controls
edit("app/src/main/res/layout/activity_emulator.xml",
'''    <TextView
            android:id="@+id/textFps"''',
'''    <me.magnum.melonds.ui.emulator.hourglass.HourglassOverlayView
            android:id="@+id/view_hourglass"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />

    <TextView
            android:id="@+id/textFps"''')

A = "app/src/main/java/me/magnum/melonds/ui/emulator/EmulatorActivity.kt"
# 3. hook the overlay up: it decides where the two DS screens are drawn, and hides the stock controls
edit(A,
'''        val layoutChangeListener = View.OnLayoutChangeListener''',
'''        binding.viewLayoutControls.alpha = 0f
        binding.viewHourglass.host = object : me.magnum.melonds.ui.emulator.hourglass.HourglassOverlayView.Host {
            override fun onScreenAreasChanged(top: Rect, bottom: Rect, topOnTop: Boolean, bottomOnTop: Boolean) {
                mainScreenRenderer.updateScreenAreas(top, bottom, 1f, 1f, topOnTop, bottomOnTop)
            }
            override fun onMenuRequested() {
                viewModel.pauseEmulator(true)
            }
        }

        val layoutChangeListener = View.OnLayoutChangeListener''')

edit(A,
'''    private fun updateRendererScreenAreas() {
''',
'''    private fun updateRendererScreenAreas() {
        if (binding.viewHourglass.isActive) {
            binding.viewHourglass.pushScreenAreas()
            return
        }
''')

# 4. always landscape
edit(A,
'''            setLayoutOrientation(layoutConfiguration.layoutOrientation)''',
'''            setLayoutOrientation(me.magnum.melonds.domain.model.layout.LayoutConfiguration.LayoutOrientation.LANDSCAPE)''')

# 5. its own app name and ID, so it installs next to normal melonDS
edit("app/build.gradle.kts", 'applicationId = "me.magnum.melonds"', 'applicationId = "app.hourglasstouch"')
for f in (root / "app/src/main/res").glob("values*/strings.xml"):
    s = f.read_bytes().decode("utf-8")
    if '<string name="app_name">melonDS</string>' in s:
        f.write_bytes(s.replace('<string name="app_name">melonDS</string>', '<string name="app_name">Hourglass Touch</string>').encode("utf-8"))
print("renamed app")
