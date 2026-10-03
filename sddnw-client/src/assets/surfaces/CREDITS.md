# Снимки поверхности — откуда они взяты

Земля на экране колонии (`ColonySurface`) — настоящая фотография, обрезанная по горизонту:
выше горизонта остаётся нарисованное звёздное небо с грядой гор, ниже лежит снимок. Так же,
как планеты на схеме системы (`../planets/CREDITS.md`), и по тем же правилам.

**Лицензия проверяется ДО скачивания** — по метаданным Commons (`LicenseShortName`), а не по
тому, что на снимке национальный парк: четыре отобранных было кандидата оказались `CC BY-SA`
и `FAL` и отброшены проверкой, не дойдя до диска. Берётся не оригинал, а эскиз нужной ширины:
у панорамы Марса исходник в тринадцать тысяч точек.

Подписи здесь потому, что ведомства США её не требуют, но просят.

## Как получены файлы

`tools/surface_strips.py` (в истории сессии; правила ниже — то, что он делает):

1. **Обрезка по горизонту.** Небо снимка отбрасывается целиком: оно дневное и спорило бы со
   звёздным небом сцены. Остаётся полоса земли пропорцией 4 : 1 — ровно та, что лежит под
   горизонтом сцены (1000 × 250 в её единицах).
2. **Зеркало, если солнце справа.** Свет в сцене ОДИН — слева сверху, от него считаются тени
   и блики зданий, — поэтому снимок, у которого тени идут влево, разворачивается. Таких два:
   лунная панорама Аполлона-15 и марсианская Rocknest.
3. **Приглушение по самому снимку**, а не общим множителем: лунный реголит снят вдвое ярче
   марсианского грунта, и одно число развело бы полосы по яркости сильнее, чем это оправдано
   климатом. Яркость приводится к одной средней.
4. **Цвет климата** (`climateColor`, тот же, которым игра красит пояс астероидов и поле боя)
   подмешивается на треть и по яркости точки: тёмные места не выцветают.
5. **Дымка у верхнего края** — воздушная перспектива: дальний план бледнее ближнего, иначе
   снимок читается наклейкой, а не далью.

Цвета правились, и это отличие от снимков планет, где они не правились вовсе: там снимок
показывает тело, а здесь — служит фоном, на котором должны читаться постройки.

## Источники

| Файл | Климат | Автор | Лицензия | Источник | Зеркало |
|---|---|---|---|---|---|
| `toxic-travertine-terraces.webp` | Toxic | Yellowstone National Park | Public domain | [Travertine terraces of Canary Spring (47953929088).jpg](https://commons.wikimedia.org/wiki/File%3ATravertine_terraces_of_Canary_Spring_%2847953929088%29.jpg) | нет |
| `radiated-lava-plain.webp` | Radiated | NPS staff (NPGallery) | Public domain | [Craters of the Moon Monument and Preserve, Idaho (c62f799f-…).jpg](https://commons.wikimedia.org/wiki/File%3ACraters_of_the_Moon_Monument_and_Preserve%2C_Idaho_%28c62f799f-c398-4a38-ab79-4277e771b7ea%29.jpg) | нет |
| `barren-moon-hadley.webp` | Barren | NASA / Dave Scott / Jim Irwin; панораму собрал Kev Holmes | Public domain | [Apollo 15 - EVA 3 - AS15-82-11055 to 11057.jpg](https://commons.wikimedia.org/wiki/File%3AApollo_15_-_EVA_3_-_AS15-82-11055_to_11057.jpg) | да |
| `desert-mars-rocknest.webp` | Desert | NASA/JPL-Caltech/Malin Space Science Systems | Public domain | [PIA16453-MarsCuriosityRover-RocknestPanorama-20121126.jpg](https://commons.wikimedia.org/wiki/File%3APIA16453-MarsCuriosityRover-RocknestPanorama-20121126.jpg) | да |
| `tundra-soda-butte-valley.webp` | Tundra | YellowstoneNPS | Public domain | [Soda Butte Creek and Abiathar Mountain (49705457733).jpg](https://commons.wikimedia.org/wiki/File%3ASoda_Butte_Creek_and_Abiathar_Mountain_%2849705457733%29.jpg) | нет |
| `arid-great-sand-dunes.webp` | Arid | NPS staff (NPGallery) | Public domain | [Views at Great Sand Dunes National Monument and Preserve, Colorado (0225cfa4-…).jpg](https://commons.wikimedia.org/wiki/File%3AViews_at_Great_Sand_Dunes_National_Monument_and_Preserve%2C_Colorado_%280225cfa4-0f71-444d-8d50-bb79fc4257cb%29.jpg) | нет |
| `swamp-bald-cypress.webp` | Swamp | Ryan Hagerty (U.S. Fish and Wildlife Service) | Public domain | [Bald Cypress swamp.jpg](https://commons.wikimedia.org/wiki/File%3ABald_Cypress_swamp.jpg) | нет |
| `ocean-cape-hatteras.webp` | Ocean | National Park Service (NPGallery) | Public domain | [Beach and ocean view at Cape Hatteras National Seashore. (5cfede95-…).jpg](https://commons.wikimedia.org/wiki/File%3ABeach_and_ocean_view_at_Cape_Hatteras_National_Seashore._%285cfede95-1dd8-b71c-0721-a5797fe4bc2e%29.jpg) | нет |
| `terran-comanche-prairie.webp` | Terran | National Trails Office, U.S. National Park Service | Public domain | [A prairie horizon at Comanche National Grassland (a559f15e…).JPG](https://commons.wikimedia.org/wiki/File%3AA_prairie_horizon_at_Comanche_National_Grassland_%28a559f15eee7648ff813fa174c8e8c751%29.JPG) | нет |
| `gaia-alpine-meadow.webp` | Gaia | NPS photo (NPGallery) | Public domain | [A meadow full of wildflowers, with snowy mountain ridges beyond. (f24ce01e-…).jpg](https://commons.wikimedia.org/wiki/File%3AA_meadow_full_of_wildflowers%2C_with_snowy_mountain_ridges_beyond._%28f24ce01e-155d-4519-3e32-326e70f1c078%29.jpg) | нет |

## Где сходство приблизительное

Снятой поверхности у человечества всего три: Луна, Марс и несколько астероидов. Поэтому
чужими мирами по-настоящему заняты только Barren и Desert, а остальные климаты получили
самые непохожие на обжитую Землю места самой Земли — минеральные террасы Мамонтова источника
под ядовитый мир, лавовую равнину Кратеров Луны под облучённый, дюны, снежную долину,
кипарисовое болото, берег океана, степь и альпийский луг. Выбор тот же, что и у снимков
планет, где обитаемые климаты — это разные полушария Земли: другого обитаемого мира никто не
снимал.

**Пояс астероидов и газовый гигант поверхности не получили:** колоний там не бывает
(`PlanetClimate.ASTEROID_BELT` и `GAS_GIANT` непригодны), и экран колонии для них не
открывается. Мир без снимка рисуется по-старому — землёй цвета климата.
