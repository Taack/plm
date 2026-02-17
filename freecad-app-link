echo $1
env WAYLAND_DISPLAY=wl-freecad QT_QPA_PLATFORM=wayland COIN_GL_NO_CURRENT_CONTEXT_CHECK=1 flatpak --filesystem=/tmp run --socket=wayland --env=LIBGL_ALWAYS_SOFTWARE=1 --env=QT_QPA_PLATFORM=wayland --env=WAYLAND_DISPLAY=wl-freecad org.freecad.FreeCAD $1
