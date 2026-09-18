Video of the latest version: [using Taack PLM Workbench 2026-09-11](https://youtu.be/28JkqJMlMrM).

Demo server installation (Linux/Mac):

Install **FreeCAD** on the server, either via **flatpak**, your **distro** version, or the macOS app in `/Applications`.
Then use the corresponding `freecad-app-link-distrib.sh`, `freecad-app-link-flatpak.sh` or `freecad-app-link-mac.sh`, rename it to `freecad-app-link` into your home directory.

On Linux, **weston** can be used to get FreeCAD working with a headless display. Every tool path (`dot`, `convert`, `unzip`, `weston`, FreeCAD) can be overridden with an environment variable, see the [documentation](https://taack.org/en/app/Plm).

Download Server:
```bash
$ wget https://github.com/Taack/plm/releases/download/v2026.09.18/server-0.6.jar
```

Check Java version > 25:
```bash
$ java -fullversion
openjdk full version "25.0.1"
```

Launch it:
```bash
$ java -jar server-0.6.jar
```

You are done, access the server [http://localhost:9442/](http://localhost:9442/), connect with `admin` / `ChangeIt` credentials.


<img width="1666" height="1812" alt="plm-2026-09-18" src="https://github.com/user-attachments/assets/17f195da-930b-4549-b75d-ea993fe0cf36" />






