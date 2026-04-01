# AutismTV

AutismTV is now a Fabric 1.21.11 client mod that adds a local command for spawning a black 16:9 panel in front of the player.

What it does:
- Adds the client-only command `/localpanel spawn`.
- Spawns a solid black 16:9 slab a few blocks in front of the player.
- Keeps the panel local to the client, with no server-side registration or networking.
- Adds `/localpanel clear` to remove the current panel.

What it does not do:
- It does not create a real server entity.
- Other players will not see the panel.
- The panel stays fixed where it was spawned until you clear it and spawn a new one.

Build:

```powershell
.\gradlew.bat build
```

Run a development client:

```powershell
.\gradlew.bat runClient
```

Recommended companion mods for larger gains:
- None required.
