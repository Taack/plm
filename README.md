Video of the latest version: [using Taack PLM Workbench 2026-09-11](https://youtu.be/28JkqJMlMrM).

Demo server installation (Linux/Mac):

Install **FreeCAD** on the server, either via **flatpak** or your **distro** version.
Then use the corresponding `freecad-app-link-distrib.sh` or `freecad-app-link-flatpak.sh`, rename it to `freecad-app-link` into your home directory.

Download Server:
```bash
$ wget https://github.com/Taack/plm/releases/download/v2026.09.17/server-0.6.jar
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






