Drop your infected villager skin here as:

    infected_villager.png

Requirements
------------
- 64 x 64 pixels, matching the vanilla villager UV layout
- Base it on the vanilla texture so the UVs line up:
      .minecraft/versions/1.21.4/1.21.4.jar
      -> assets/minecraft/textures/entity/villager/villager.png

Notes
-----
- This replaces the villager BASE skin only. The profession and biome clothing
  overlays are separate feature renderers and still draw on top, so an infected
  farmer still reads as a farmer with a diseased face and hands.
- It is applied only while a villager is INFECTIOUS with Scarlet Blight.
  Incubating villagers keep the normal skin — they are not contagious yet and
  must not be identifiable on sight.
- Once this file exists the swap works with no code change; the path is
  referenced by InfectedVillagerRenderer.INFECTED_TEXTURE.
